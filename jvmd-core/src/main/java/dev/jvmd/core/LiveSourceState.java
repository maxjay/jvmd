package dev.jvmd.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Mutation-maintained effective source state for one compiler source-root set.
 *
 * <p>Disk events and editor overlays update the resident {@link LiveStateTree}; reads return its
 * already-maintained identity. Filesystem uncertainty marks the state untrusted and advances the
 * transition epoch before reconciliation. Semantic API/namespace identities are accepted only for
 * the exact source content that javac analysed, so a late semantic result cannot overwrite newer
 * source state.
 */
public final class LiveSourceState implements AutoCloseable {
    public record Snapshot(LiveStateTree.State state,boolean trusted,long events,long reconciliations,long overflows,String uncertainty) {
        public Snapshot { Objects.requireNonNull(state);uncertainty=uncertainty==null?"":uncertainty; }
    }

    private final FileStateRegistry files;
    private final Documents documents;
    private final List<Path> roots;
    private final LiveStateTree tree;
    private final Map<WatchKey,Path> watchKeys=new HashMap<>();
    private final Set<Path> watchedDirectories=new HashSet<>();
    private WatchService watcher;
    private Thread watchThread;
    private boolean trusted,closed;
    private String uncertainty="";
    private long events,reconciliations,overflows,semanticUpdates,staleSemanticUpdates;

    LiveSourceState(FileStateRegistry files,Documents documents,Collection<Path> sourceRoots) {
        this.files=Objects.requireNonNull(files);this.documents=Objects.requireNonNull(documents);
        roots=sourceRoots.stream().map(LiveSourceState::normalize).distinct().sorted(Comparator.comparing(Path::toString)).toList();
        tree=new LiveStateTree(roots);
        try {
            watcher=FileSystems.getDefault().newWatchService();
            registerRoots();
            reconcileContents();
            trusted=true;
            watchThread=Thread.ofPlatform().daemon(true).name("jvmd-source-state-"+Integer.toHexString(roots.hashCode())).start(this::watchLoop);
        } catch(IOException|UnsupportedOperationException unavailable) {
            closeWatcher();
            try { reconcileContents(); } catch(IOException ignored) { }
            synchronized(this){
                trusted=false;uncertainty="filesystem watch unavailable: "+unavailable.getClass().getSimpleName();
                tree.uncertainTransition();
            }
        }
    }

    public List<Path> roots(){return roots;}
    public boolean accepts(Path path){path=normalize(path);for(Path root:roots)if(path.startsWith(root))return true;return false;}
    public synchronized Snapshot snapshot(){return new Snapshot(tree.state(),trusted,events,reconciliations,overflows,uncertainty);}
    public synchronized Optional<LiveStateTree.Leaf> leaf(Path path){return tree.leaf(path);}
    /** Materialized only at reconciliation/persistence boundaries; request identity must use snapshot(). */
    public synchronized Set<Path> paths(){return tree.paths();}

    /** Feed the canonical API/export identities extracted by the existing semantic contribution path. */
    public synchronized boolean semantic(Path path,String sourceHash,String apiFingerprint,Collection<String> exportedNames){
        path=normalize(path);if(!accepts(path))return false;
        var current=tree.leaf(path).orElse(null);
        if(current==null||!current.content().value().equals(sourceHash)){staleSemanticUpdates++;return false;}
        tree.put(new LiveStateTree.Leaf(path,current.content(),new LiveStateTree.Fingerprint(apiFingerprint),LiveStateTree.namespace(exportedNames)));
        semanticUpdates++;return true;
    }

    /** Explicit correctness boundary used after startup uncertainty or watcher overflow. */
    public void reconcile() throws IOException {
        reconcileContents();
        synchronized(this){
            reconciliations++;
            if(watcher!=null&&!trusted){tree.uncertainTransition();trusted=true;uncertainty="";}
        }
    }

    public synchronized void markUncertain(String reason){
        if(closed)return;
        if(trusted){tree.uncertainTransition();trusted=false;}
        uncertainty=reason==null?"uncertain":reason;
    }

    void documentChanged(Path file,String effectiveHash){
        file=normalize(file);if(!accepts(file))return;
        synchronized(this){applyContent(file,effectiveHash);events++;}
    }

    private void refresh(Path file){
        file=normalize(file);if(!accepts(file)||!file.toString().endsWith(".java"))return;
        try{
            for(int attempt=0;attempt<3;attempt++){
                long generation=documents.generation();
                String overlay=documents.hash(file);
                String effective=overlay==null?files.hash(file):overlay;
                if(generation==documents.generation()){
                    synchronized(this){applyContent(file,effective);events++;}
                    return;
                }
            }
            markUncertain("editor state changed repeatedly while observing "+file);reconcile();
        }catch(IOException failed){
            markUncertain("source observation failed: "+file);
            try{reconcile();}catch(IOException ignored){}
        }
    }

    private void applyContent(Path file,String hash){
        if("missing".equals(hash)){tree.remove(file);return;}
        var previous=tree.leaf(file).orElse(null);
        if(previous==null)tree.put(new LiveStateTree.Leaf(file,new LiveStateTree.Fingerprint(hash),LiveStateTree.UNKNOWN,LiveStateTree.UNKNOWN));
        else if(!previous.content().value().equals(hash))
            tree.put(new LiveStateTree.Leaf(file,new LiveStateTree.Fingerprint(hash),previous.api(),previous.namespace()));
    }

    private void reconcileContents() throws IOException {
        var effective=new TreeMap<Path,String>();
        for(Path root:roots)for(Path file:files.inventory(root,".java"))effective.put(normalize(file),files.hash(file));
        for(Path file:documents.paths())if(accepts(file)){
            String overlay=documents.hash(file);if(overlay!=null)effective.put(normalize(file),overlay);
        }
        synchronized(this){
            for(Path existing:new ArrayList<>(tree.paths()))if(!effective.containsKey(existing))tree.remove(existing);
            effective.forEach(this::applyContent);
        }
    }

    private void registerRoots() throws IOException {
        for(Path root:roots){
            if(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))registerTree(root);
            else{
                Path parent=root.getParent();
                while(parent!=null&&!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS))parent=parent.getParent();
                if(parent!=null)registerDirectory(parent);
            }
        }
    }
    private void registerTree(Path directory) throws IOException {
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))return;
        try(var paths=Files.walk(directory)){
            for(Path path:paths.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).toList())registerDirectory(path);
        }
    }
    private void registerDirectory(Path directory) throws IOException {
        directory=normalize(directory);if(!watchedDirectories.add(directory))return;
        WatchKey key=directory.register(watcher,StandardWatchEventKinds.ENTRY_CREATE,StandardWatchEventKinds.ENTRY_MODIFY,StandardWatchEventKinds.ENTRY_DELETE);
        watchKeys.put(key,directory);
    }

    private void watchLoop(){
        while(!closed&&watcher!=null){
            WatchKey key;
            try{key=watcher.take();}
            catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}
            catch(ClosedWatchServiceException stopped){return;}
            Path directory=watchKeys.get(key);boolean reconcile=false;
            if(directory==null){key.reset();continue;}
            for(WatchEvent<?> event:key.pollEvents()){
                if(event.kind()==StandardWatchEventKinds.OVERFLOW){
                    synchronized(this){overflows++;}markUncertain("filesystem watch overflow");reconcile=true;continue;
                }
                if(!(event.context() instanceof Path relative))continue;
                Path changed=normalize(directory.resolve(relative));
                try{
                    if(event.kind()==StandardWatchEventKinds.ENTRY_CREATE&&Files.isDirectory(changed,LinkOption.NOFOLLOW_LINKS)){
                        registerTree(changed);reconcile=true;
                    }else if(event.kind()==StandardWatchEventKinds.ENTRY_DELETE&&watchedDirectories.contains(changed))reconcile=true;
                    else if(changed.toString().endsWith(".java"))refresh(changed);
                    else if(relevantBoundary(changed))reconcile=true;
                }catch(IOException observation){
                    markUncertain("filesystem watch registration failed: "+changed);reconcile=true;
                }
            }
            if(!key.reset()){
                watchKeys.remove(key);watchedDirectories.remove(directory);markUncertain("filesystem watch key invalid: "+directory);reconcile=true;
            }
            if(reconcile)try{reconcile();}catch(IOException ignored){}
        }
    }
    private boolean relevantBoundary(Path path){
        for(Path root:roots)if(path.startsWith(root)||root.startsWith(path))return true;return false;
    }

    public synchronized Map<String,Object> status(){
        var state=tree.state();
        return Map.ofEntries(
                Map.entry("trusted",trusted),Map.entry("epoch",state.epoch()),Map.entry("files",state.files()),
                Map.entry("merkle",state.merkle().value()),Map.entry("events",events),Map.entry("reconciliations",reconciliations),
                Map.entry("overflows",overflows),Map.entry("semantic_updates",semanticUpdates),
                Map.entry("stale_semantic_updates",staleSemanticUpdates),Map.entry("watched_directories",watchedDirectories.size()),
                Map.entry("uncertainty",uncertainty));
    }

    private static Path normalize(Path path){return path.toAbsolutePath().normalize();}
    private void closeWatcher(){var current=watcher;watcher=null;if(current!=null)try{current.close();}catch(IOException ignored){}}
    @Override public void close(){
        Thread thread;
        synchronized(this){if(closed)return;closed=true;thread=watchThread;closeWatcher();}
        if(thread!=null&&thread!=Thread.currentThread()){
            thread.interrupt();try{thread.join(2000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        }
    }
}
