package dev.jvmd.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
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
    public record Snapshot(LiveStateTree.State state,long inputEpoch,boolean trusted,long events,long reconciliations,long overflows,String uncertainty) {
        public Snapshot { Objects.requireNonNull(state);if(inputEpoch<0)throw new IllegalArgumentException("inputEpoch");uncertainty=uncertainty==null?"":uncertainty; }
    }
    /** Path-derived source lookup retained at mutation time for javac package queries. */
    public record Source(Path file,String binary) {
        public Source { file=normalize(file);Objects.requireNonNull(binary); }
    }
    private record Change(long epoch,Path file) { }

    private final FileStateRegistry files;
    private final Documents documents;
    private final List<Path> roots;
    private final LiveStateTree tree;
    private final Map<Path,Source> sourceByPath=new HashMap<>();
    private final NavigableMap<String,NavigableMap<String,Source>> sourcesByPackage=new TreeMap<>();
    private final Map<Path,Object> packageEvidence=new HashMap<>();
    private final ArrayDeque<Change> sourceChanges=new ArrayDeque<>();
    private long sourceHistoryFloor,inputEpoch;
    private static final int MAX_SOURCE_CHANGES=32768;
    private final Map<WatchKey,Path> watchKeys=new HashMap<>();
    private final Object watchProcessing=new Object();
    private final Set<Path> watchedDirectories=new HashSet<>();
    private WatchService watcher;
    private Thread watchThread;
    private boolean trusted,closed;
    private String uncertainty="";
    private long events,reconciliations,targetedReconciliations,overflows,semanticUpdates,staleSemanticUpdates;

    private volatile boolean verificationOnly;
    LiveSourceState(FileStateRegistry files,Documents documents,Collection<Path> sourceRoots){this(files,documents,sourceRoots,true);}
    LiveSourceState(FileStateRegistry files,Documents documents,Collection<Path> sourceRoots,boolean watchEnabled) {
        this.files=Objects.requireNonNull(files);this.documents=Objects.requireNonNull(documents);
        roots=sourceRoots.stream().map(LiveSourceState::normalize).distinct().sorted(Comparator.comparing(Path::toString)).toList();
        tree=new LiveStateTree(roots);
        boolean fallback=!watchEnabled;String fallbackReason=watchEnabled?"":"watch disabled";
        if(watchEnabled)try {
            watcher=FileSystems.getDefault().newWatchService();
            registerRoots();
        } catch(IOException|UnsupportedOperationException unavailable) {
            fallback=true;fallbackReason="filesystem watch unavailable: "+unavailable.getClass().getSimpleName();closeWatcher();
        }
        verificationOnly=fallback;
        try{
            reconcileContents();trusted=true;uncertainty="";
        }catch(IOException initial){
            trusted=false;uncertainty="initial source verification failed: "+initial.getClass().getSimpleName();
        }
        if(!fallback&&trusted)watchThread=Thread.ofPlatform().daemon(true).name("jvmd-source-state-"+Integer.toHexString(roots.hashCode())).start(this::watchLoop);
        else if(fallback&&trusted)uncertainty=fallbackReason;
    }

    public List<Path> roots(){return roots;}
    public boolean accepts(Path path){path=normalize(path);for(Path root:roots)if(path.startsWith(root))return true;return false;}
    public synchronized Snapshot snapshot(){return new Snapshot(tree.state(),inputEpoch,trusted,events,reconciliations,overflows,uncertainty);}
    public synchronized Optional<LiveStateTree.Leaf> leaf(Path path){return tree.leaf(path);}
    public synchronized String contentHash(Path path){var leaf=tree.leaf(path).orElse(null);return leaf==null?null:leaf.content().value();}
    /** Materialized only at reconciliation/persistence/full-build boundaries; request identity must use snapshot(). */
    public synchronized Set<Path> paths(){return tree.paths();}
    /** Changed source paths since a captured epoch; empty Optional means the bounded journal cannot prove the delta. */
    public synchronized Optional<Set<Path>> changedPathsSince(long epoch){
        long current=inputEpoch;
        if(epoch==current)return Optional.of(Set.of());
        if(epoch<sourceHistoryFloor)return Optional.empty();
        var changed=new LinkedHashSet<Path>();
        for(var item:sourceChanges)if(item.epoch()>epoch)changed.add(item.file());
        return Optional.of(Set.copyOf(changed));
    }
    /** Path-derived binary lookup from maintained package membership; does not inventory the workspace. */
    public synchronized Optional<Source> source(String binary){
        Objects.requireNonNull(binary);int split=binary.lastIndexOf('.');String pkg=split<0?"":binary.substring(0,split);
        var values=sourcesByPackage.get(pkg);return Optional.ofNullable(values==null?null:values.get(binary));
    }
    /** Result-size package lookup; membership is maintained on mutations rather than rebuilt on requests. */
    public synchronized List<Source> sources(Collection<Path> requestedRoots,String packageName,boolean recurse){
        var selected=requestedRoots.stream().map(LiveSourceState::normalize).toList();
        var result=new ArrayList<Source>();
        if(recurse){
            for(var entry:sourcesByPackage.tailMap(packageName,true).entrySet()){
                String pkg=entry.getKey();
                if(!packageName.isEmpty()&&!pkg.equals(packageName)&&!pkg.startsWith(packageName+"."))break;
                for(var source:entry.getValue().values())if(selected.stream().anyMatch(source.file()::startsWith))result.add(source);
            }
        }else{
            for(var source:sourcesByPackage.getOrDefault(packageName,new TreeMap<>()).values())
                if(selected.stream().anyMatch(source.file()::startsWith))result.add(source);
        }
        return List.copyOf(result);
    }

    /**
     * Synchronously observe a bounded request-relevant source set.
     * This is proportional to the requested/known dependency set, never the workspace.
     */
    public void observe(Collection<Path> paths){for(Path path:paths)refresh(path);}
    public void observe(Path path){refresh(path);}
    /**
     * Delivery fence for graph-wide validation. Drain WatchService keys directly instead of
     * sleeping for the background watcher; cost is proportional to queued mutations, never owners.
     */
    public void settleWatchEvents()throws IOException{
        if(verificationOnly){reconcile();return;}
        WatchService current; synchronized(this){current=watcher;}
        if(current==null){reconcile();return;}
        try{RequestScope.settleFilesystemStart(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2));}
        catch(Exception failed){markUncertain("source watch settlement failed");reconcile();return;}
        // A background reader can already have dequeued an event. Its content publication
        // must finish before an empty queue is accepted as settled.
        synchronized(watchProcessing){
            for(WatchKey key;(key=current.poll())!=null;)processWatchKey(key);
        }
        synchronized(this){if(!trusted)reconcile();}
    }

    /**
     * Reconcile only exact source-package directories. This is the synchronous discovery fallback
     * for an unresolved completion; it never inventories an entire source root.
     */
    public void reconcilePackages(Collection<String> packageNames)throws IOException{
        var packages=packageNames.stream().filter(Objects::nonNull).distinct().toList();
        for(String packageName:packages){
            String relative=packageName.replace('.',java.io.File.separatorChar);
            for(Path root:roots)reconcilePackage(root,relative,packageName);
        }
        synchronized(this){targetedReconciliations++;}
    }
    private record DirectoryListing(Object evidence,List<Path> files) { }
    private void reconcilePackage(Path root,String relative,String packageName)throws IOException{
        Path directory=relative.isEmpty()?root:root.resolve(relative).normalize();
        Object currentEvidence=directoryEvidence(directory);
        synchronized(this){if(Objects.equals(packageEvidence.get(directory),currentEvidence))return;}
        var listing=directJavaFiles(directory);var effective=new TreeMap<Path,String>();
        for(Path file:listing.files())effective.put(file,files.hash(file));
        for(Path file:documents.paths()){
            Path normalized=normalize(file);
            if(accepts(normalized)&&Objects.equals(normalized.getParent(),directory)){
                String overlay=documents.hash(normalized);if(overlay!=null)effective.put(normalized,overlay);
            }
        }
        synchronized(this){
            var existing=new ArrayList<Path>();
            for(var source:sourcesByPackage.getOrDefault(packageName,new TreeMap<>()).values())
                if(Objects.equals(source.file().getParent(),directory))existing.add(source.file());
            for(Path file:existing)if(!effective.containsKey(file))applyContent(file,"missing");
            effective.forEach(this::applyContent);packageEvidence.put(directory,listing.evidence());
        }
        if(watcher!=null&&Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))registerDirectory(directory);
    }
    private DirectoryListing directJavaFiles(Path directory)throws IOException{
        for(int attempt=0;attempt<4;attempt++){
            Object before=directoryEvidence(directory);List<Path> files;
            try(var stream=Files.list(directory)){
                files=stream.filter(path->path.getFileName().toString().endsWith(".java"))
                        .filter(path->Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))
                        .map(LiveSourceState::normalize).sorted().toList();
            }catch(NoSuchFileException missing){files=List.of();}
            Object after=directoryEvidence(directory);
            if(Objects.equals(before,after))return new DirectoryListing(after,files);
        }
        throw new CompilerInputs.Superseded("Source package changed repeatedly during reconciliation: "+directory);
    }
    private static Object directoryEvidence(Path directory)throws IOException{
        try{
            var attrs=Files.readAttributes(directory,"unix:size,lastModifiedTime,ctime,ino,isDirectory",LinkOption.NOFOLLOW_LINKS);
            return Map.copyOf(attrs);
        }catch(NoSuchFileException missing){return "missing";}
        catch(UnsupportedOperationException|IllegalArgumentException unsupported){
            try{
                var attrs=Files.readAttributes(directory,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                return List.of(attrs.isDirectory(),attrs.size(),attrs.lastModifiedTime().toMillis(),Objects.toString(attrs.fileKey(),""));
            }catch(NoSuchFileException missing){return "missing";}
        }
    }

    /** Feed the canonical API/export identities extracted by the existing semantic contribution path. */
    public synchronized boolean semantic(Path path,String sourceHash,String apiFingerprint,Collection<String> exportedNames){
        path=normalize(path);if(!accepts(path))return false;
        var current=tree.leaf(path).orElse(null);
        if(current==null||!current.content().value().equals(sourceHash)){staleSemanticUpdates++;return false;}
        tree.put(new LiveStateTree.Leaf(path,current.content(),new LiveStateTree.Fingerprint(apiFingerprint),LiveStateTree.namespace(exportedNames),current.content()));
        semanticUpdates++;return true;
    }

    /**
     * Prepare/verify a compiler transaction. Watch-backed states usually do no work here; a
     * verification-only state performs the slower full check at both transaction boundaries.
     */
    public void verifyTransactionBoundary()throws IOException{
        boolean verify; synchronized(this){verify=verificationOnly||!trusted;}
        if(verify)reconcile();
    }
    /** Explicit correctness boundary used after startup uncertainty, watcher overflow, or verification-only fallback. */
    public void reconcile() throws IOException {
        reconcileContents();
        synchronized(this){
            reconciliations++;
            if(!trusted){tree.uncertainTransition();inputEpoch++;}
            trusted=true;uncertainty=verificationOnly?"watch unavailable: verification-only":"";
        }
    }

    public synchronized void markUncertain(String reason){
        if(closed)return;
        if(trusted){tree.uncertainTransition();inputEpoch++;trusted=false;}
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
        var previous=tree.leaf(file).orElse(null);
        if("missing".equals(hash)){
            if(previous!=null){tree.remove(file);removeSource(file);recordSourceChange(file);}
            return;
        }
        if(previous==null){
            tree.put(new LiveStateTree.Leaf(file,new LiveStateTree.Fingerprint(hash),LiveStateTree.UNKNOWN,LiveStateTree.UNKNOWN,LiveStateTree.UNATTRIBUTED_CONTENT));
            addSource(file);recordSourceChange(file);
        }else if(!previous.content().value().equals(hash)){
            tree.put(new LiveStateTree.Leaf(file,new LiveStateTree.Fingerprint(hash),previous.api(),previous.namespace(),previous.semanticContent()));
            recordSourceChange(file);
        }
    }
    private void recordSourceChange(Path file){
        long epoch=++inputEpoch;sourceChanges.addLast(new Change(epoch,normalize(file)));
        while(sourceChanges.size()>MAX_SOURCE_CHANGES){var removed=sourceChanges.removeFirst();sourceHistoryFloor=Math.max(sourceHistoryFloor,removed.epoch());}
    }
    private void addSource(Path file){
        file=normalize(file);String binary=sourceName(file);if(binary==null)return;
        var source=new Source(file,binary);sourceByPath.put(file,source);
        int split=binary.lastIndexOf('.');String pkg=split<0?"":binary.substring(0,split);
        sourcesByPackage.computeIfAbsent(pkg,_ -> new TreeMap<>()).put(binary,source);
    }
    private void removeSource(Path file){
        file=normalize(file);var source=sourceByPath.remove(file);if(source==null)return;
        int split=source.binary().lastIndexOf('.');String pkg=split<0?"":source.binary().substring(0,split);
        var values=sourcesByPackage.get(pkg);if(values!=null){values.remove(source.binary());if(values.isEmpty())sourcesByPackage.remove(pkg);}
    }
    private String sourceName(Path file){
        Path root=roots.stream().filter(file::startsWith).max(Comparator.comparingInt(Path::getNameCount)).orElse(null);
        if(root==null)return null;String relative=root.relativize(file).toString();
        if(!relative.endsWith(".java"))return null;
        return relative.substring(0,relative.length()-5).replace(java.io.File.separatorChar,'.');
    }

    private void reconcileContents() throws IOException {
        var effective=new TreeMap<Path,String>();
        for(Path root:roots)for(Path file:files.inventory(root,".java"))effective.put(normalize(file),files.hash(file));
        for(Path file:documents.paths())if(accepts(file)){
            String overlay=documents.hash(file);if(overlay!=null)effective.put(normalize(file),overlay);
        }
        synchronized(this){
            for(Path existing:new ArrayList<>(tree.paths()))if(!effective.containsKey(existing))applyContent(existing,"missing");
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
        Files.walkFileTree(directory,new SimpleFileVisitor<>(){
            @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attributes)throws IOException{
                try{registerDirectory(path);}
                catch(NoSuchFileException|NotDirectoryException vanished){return FileVisitResult.SKIP_SUBTREE;}
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path path,IOException failure)throws IOException{
                if(failure instanceof NoSuchFileException||failure instanceof NotDirectoryException)return FileVisitResult.CONTINUE;
                throw failure;
            }
        });
    }
    private synchronized void registerDirectory(Path directory) throws IOException {
        if(watcher==null)return;
        directory=normalize(directory);if(watchedDirectories.contains(directory))return;
        WatchKey key=directory.register(watcher,StandardWatchEventKinds.ENTRY_CREATE,StandardWatchEventKinds.ENTRY_MODIFY,StandardWatchEventKinds.ENTRY_DELETE);
        watchedDirectories.add(directory);watchKeys.put(key,directory);
    }

    private void watchLoop(){
        while(!closed&&watcher!=null){
            WatchKey key;
            try{key=watcher.take();}
            catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}
            catch(ClosedWatchServiceException stopped){return;}
            try{processWatchKey(key);}catch(IOException failed){markUncertain("filesystem watch processing failed: "+failed.getClass().getSimpleName());}
        }
    }
    private void processWatchKey(WatchKey key)throws IOException{
        synchronized(watchProcessing){processWatchKeyLocked(key);}
    }
    private void processWatchKeyLocked(WatchKey key)throws IOException{
        Path directory; synchronized(this){directory=watchKeys.get(key);}
        boolean reconcile=false;
        if(directory==null){key.reset();return;}
        for(WatchEvent<?> event:key.pollEvents()){
            if(event.kind()==StandardWatchEventKinds.OVERFLOW){
                synchronized(this){overflows++;}markUncertain("filesystem watch overflow");reconcile=true;continue;
            }
            if(!(event.context() instanceof Path relative))continue;
            Path changed=normalize(directory.resolve(relative));
            try{
                if(event.kind()==StandardWatchEventKinds.ENTRY_CREATE&&Files.isDirectory(changed,LinkOption.NOFOLLOW_LINKS)){
                    registerTree(changed);reconcile=true;
                }else{
                    boolean watched; synchronized(this){watched=watchedDirectories.contains(changed);}
                    if(event.kind()==StandardWatchEventKinds.ENTRY_DELETE&&watched)reconcile=true;
                    else if(changed.toString().endsWith(".java"))refresh(changed);
                    else if(relevantBoundary(changed))reconcile=true;
                }
            }catch(IOException observation){
                enterVerificationOnly("filesystem watch registration failed: "+changed);reconcile=true;
            }
        }
        if(!key.reset()){
            synchronized(this){watchKeys.remove(key);watchedDirectories.remove(directory);}
            enterVerificationOnly("filesystem watch key invalid: "+directory);reconcile=true;
        }
        if(reconcile)reconcile();
    }
    private void enterVerificationOnly(String reason){
        verificationOnly=true;
        markUncertain(reason);
    }
    private boolean relevantBoundary(Path path){
        for(Path root:roots)if(path.startsWith(root)||root.startsWith(path))return true;return false;
    }

    public synchronized Map<String,Object> status(){
        var state=tree.state();
        return Map.ofEntries(
                Map.entry("trusted",trusted),Map.entry("verification_only",verificationOnly),Map.entry("epoch",state.epoch()),Map.entry("input_epoch",inputEpoch),Map.entry("files",state.files()),
                Map.entry("merkle",state.merkle().value()),Map.entry("semantic_current",state.semanticsCurrent()),Map.entry("pending_semantic_files",state.pendingSemanticFiles()),
                Map.entry("events",events),Map.entry("reconciliations",reconciliations),
                Map.entry("targeted_reconciliations",targetedReconciliations),Map.entry("overflows",overflows),Map.entry("semantic_updates",semanticUpdates),
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
