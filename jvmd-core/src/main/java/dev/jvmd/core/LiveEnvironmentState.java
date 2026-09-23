package dev.jvmd.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Mutation-maintained compiler environment identity.
 *
 * <p>Classpath/JDK/path-option membership is discovered once when the compiler configuration is
 * accepted. WatchService events update the resident Merkle/content identity; request reads drain
 * only queued mutations. If watching is unavailable or overflows, correctness falls back to a
 * full verification at the transaction boundary.
 */
final class LiveEnvironmentState implements AutoCloseable {
    record Snapshot(CompilerInputs.EnvironmentIdentity identity,long epoch,boolean trusted) { }
    private record Recursive(Path root,String suffix) { }
    private record Registration(Path physical,Path logical) { }

    private final FileStateRegistry files;
    private final CompilerInputs.Configuration config;
    private final List<Recursive> recursive;
    private final Set<Path> direct;
    private final LiveStateTree tree;
    private final Map<WatchKey,Registration> watches=new HashMap<>();
    private final Set<Path> watchedPhysical=new HashSet<>();
    private WatchService watcher;
    private boolean verificationOnly,trusted,closed;
    private long events,overflows,reconciliations;

    LiveEnvironmentState(FileStateRegistry files,CompilerInputs.Configuration config)throws IOException{
        this.files=Objects.requireNonNull(files);this.config=Objects.requireNonNull(config);
        var recursive=new ArrayList<Recursive>();var direct=new LinkedHashSet<Path>();
        for(Path path:config.classpath()){
            path=normalize(path);
            if(Files.isDirectory(path)){recursive.add(new Recursive(path,".class"));recursive.add(new Recursive(path,".jar"));}
            else direct.add(path);
        }
        for(Path path:optionPaths(config.options())){
            path=normalize(path);
            if(Files.isDirectory(path))recursive.add(new Recursive(path,""));
            else direct.add(path);
        }
        Path home=Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        direct.add(home.resolve("release"));direct.add(home.resolve("lib/modules"));direct.add(home.resolve("lib/ct.sym"));
        this.recursive=List.copyOf(recursive);this.direct=Set.copyOf(direct);
        var roots=new LinkedHashSet<Path>();
        for(var spec:recursive){Path root=spec.root().getRoot();if(root!=null)roots.add(root);}
        for(Path path:direct){Path root=path.getRoot();if(root!=null)roots.add(root);}
        if(roots.isEmpty())roots.add(Path.of(".").toAbsolutePath().normalize().getRoot());
        tree=new LiveStateTree(roots);

        try{
            watcher=FileSystems.getDefault().newWatchService();registerInputs();
        }catch(IOException|UnsupportedOperationException unavailable){
            verificationOnly=true;closeWatcher();
        }
        reconcileAll();
        trusted=true;
    }

    synchronized Snapshot snapshot(){var state=tree.state();return new Snapshot(identity(state),state.epoch(),trusted);}
    synchronized Map<String,Object> status(){var state=tree.state();return Map.of(
            "tracked_files",state.files(),"epoch",state.epoch(),"trusted",trusted,"verification_only",verificationOnly,
            "watch_events",events,"watch_overflows",overflows,"full_reconciliations",reconciliations);}

    Snapshot verifyBoundary()throws IOException{
        synchronized(this){if(closed)throw new IOException("Environment state is closed");}
        if(verificationOnly){reconcileAll();synchronized(this){trusted=true;}return snapshot();}
        drain(false);
        synchronized(this){if(!trusted){reconcileAll();trusted=true;return snapshot();}}
        drain(true);
        synchronized(this){if(!trusted){reconcileAll();trusted=true;}return snapshot();}
    }

    private CompilerInputs.EnvironmentIdentity identity(LiveStateTree.State state){
        return new CompilerInputs.EnvironmentIdentity(CompilerInputs.compose("environment-live-v1",
                config.generation(),config.roots(),config.options(),config.classpath().stream().map(Path::toString).toList(),config.platform(),
                state.membership().fingerprint().value(),state.content().fingerprint().value()));
    }

    private void reconcileAll()throws IOException{
        var current=new TreeMap<Path,String>();
        for(var spec:recursive)for(Path path:files.inventory(spec.root(),spec.suffix(),true)){
            String hash=files.hash(path);if(!"missing".equals(hash))current.put(normalize(path),hash);
        }
        for(Path path:direct){String hash=files.hash(path);if(!"missing".equals(hash))current.put(path,hash);}
        synchronized(this){
            for(Path existing:new ArrayList<>(tree.paths()))if(!current.containsKey(existing))tree.remove(existing);
            for(var entry:current.entrySet())put(entry.getKey(),entry.getValue());
            reconciliations++;
        }
    }
    private void put(Path path,String hash){
        path=normalize(path);var prior=tree.leaf(path).orElse(null);
        if(prior==null||!prior.content().value().equals(hash))
            tree.put(new LiveStateTree.Leaf(path,new LiveStateTree.Fingerprint(hash),LiveStateTree.UNKNOWN,LiveStateTree.UNKNOWN));
    }
    private void remove(Path path){if(tree.leaf(path).isPresent())tree.remove(path);}

    private void registerInputs()throws IOException{
        for(var spec:recursive){
            if(Files.isDirectory(spec.root()))registerTree(spec.root());
            else registerNearest(spec.root());
        }
        for(Path path:direct)registerNearest(path.getParent()==null?path:path.getParent());
    }
    private void registerNearest(Path logical)throws IOException{
        if(logical==null)return;logical=normalize(logical);Path existing=logical;
        while(existing!=null&&!Files.isDirectory(existing,LinkOption.NOFOLLOW_LINKS))existing=existing.getParent();
        if(existing==null)return;
        Path physical;try{physical=existing.toRealPath();}catch(NoSuchFileException missing){return;}
        registerDirectory(physical,existing);
    }
    private void registerTree(Path logicalRoot)throws IOException{
        logicalRoot=normalize(logicalRoot);if(!Files.isDirectory(logicalRoot))return;
        Path physicalRoot=logicalRoot.toRealPath();
        try(var paths=Files.walk(physicalRoot,FileVisitOption.FOLLOW_LINKS)){
            for(Path physical:paths.filter(Files::isDirectory).toList()){
                Path relative=physicalRoot.relativize(physical);registerDirectory(physical,logicalRoot.resolve(relative).normalize());
            }
        }
    }
    private synchronized void registerDirectory(Path physical,Path logical)throws IOException{
        if(watcher==null)return;physical=physical.toAbsolutePath().normalize();if(!watchedPhysical.add(physical))return;
        WatchKey key=physical.register(watcher,StandardWatchEventKinds.ENTRY_CREATE,StandardWatchEventKinds.ENTRY_MODIFY,StandardWatchEventKinds.ENTRY_DELETE);
        watches.put(key,new Registration(physical,logical.toAbsolutePath().normalize()));
    }

    private void drain(boolean settle)throws IOException{
        WatchService current; synchronized(this){current=watcher;if(current==null){trusted=false;return;}}
        WatchKey first=null;
        if(settle)try{first=current.poll(1,java.util.concurrent.TimeUnit.MILLISECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();synchronized(this){trusted=false;}return;}
        if(first!=null)process(first);
        for(WatchKey key;(key=current.poll())!=null;)process(key);
    }
    private void process(WatchKey key)throws IOException{
        Registration registration; synchronized(this){registration=watches.get(key);}
        if(registration==null){key.reset();return;}
        for(var event:key.pollEvents()){
            synchronized(this){events++;}
            if(event.kind()==StandardWatchEventKinds.OVERFLOW){uncertain();continue;}
            if(!(event.context() instanceof Path relative)){uncertain();continue;}
            Path logical=registration.logical().resolve(relative).toAbsolutePath().normalize();
            Path physical=registration.physical().resolve(relative);
            if(event.kind()==StandardWatchEventKinds.ENTRY_CREATE&&Files.isDirectory(physical)){
                try{registerTree(logical);}catch(FileSystemLoopException loop){uncertain();}
                reconcileCreatedDirectory(logical);continue;
            }
            if(event.kind()==StandardWatchEventKinds.ENTRY_DELETE&&isEnvironmentDirectory(logical)){
                removeSubtree(logical);continue;
            }
            if(relevant(logical)){
                synchronized(this){tree.uncertainTransition();} // observe A→B→A even if final bytes match
                if(event.kind()==StandardWatchEventKinds.ENTRY_DELETE) synchronized(this){remove(logical);}
                else{
                    String hash=files.hash(logical);
                    synchronized(this){if("missing".equals(hash))remove(logical);else put(logical,hash);}
                }
            }
        }
        if(!key.reset()){synchronized(this){watches.remove(key);watchedPhysical.remove(registration.physical());}uncertain();}
    }
    private void reconcileCreatedDirectory(Path directory)throws IOException{
        for(var spec:recursive){
            if(directory.startsWith(spec.root())){
                for(Path file:files.inventory(directory,spec.suffix(),true)){String hash=files.hash(file);synchronized(this){if(!"missing".equals(hash))put(file,hash);}}
            }else if(spec.root().startsWith(directory)&&Files.isDirectory(spec.root())){
                registerTree(spec.root());
                for(Path file:files.inventory(spec.root(),spec.suffix(),true)){String hash=files.hash(file);synchronized(this){if(!"missing".equals(hash))put(file,hash);}}
            }
        }
        for(Path path:direct)if(path.startsWith(directory)){String hash=files.hash(path);synchronized(this){if(!"missing".equals(hash))put(path,hash);}}
    }
    private synchronized void removeSubtree(Path directory){for(Path path:new ArrayList<>(tree.paths()))if(path.startsWith(directory))remove(path);}
    private boolean isEnvironmentDirectory(Path directory){
        for(var spec:recursive)if(directory.startsWith(spec.root())||spec.root().startsWith(directory))return true;
        for(Path path:direct)if(path.startsWith(directory))return true;return false;
    }
    private boolean relevant(Path file){
        if(direct.contains(file))return true;
        for(var spec:recursive)if(file.startsWith(spec.root())&&(spec.suffix().isEmpty()||file.getFileName().toString().endsWith(spec.suffix())))return true;
        return false;
    }
    private synchronized void uncertain(){tree.uncertainTransition();trusted=false;overflows++;}

    private static List<Path> optionPaths(List<String> options){
        var result=new ArrayList<Path>();
        var pathOptions=Set.of("--module-path","-p","--upgrade-module-path","--class-path","-classpath","-cp","--processor-path","-processorpath","--processor-module-path","--patch-module","--system",
                "--source-path","-sourcepath","--module-source-path","--boot-class-path","-bootclasspath",
                "-extdirs","-endorseddirs","-Djava.ext.dirs","-Djava.endorsed.dirs");
        for(int i=0;i<options.size();i++){
            String option=options.get(i),name=option.contains("=")?option.substring(0,option.indexOf('=')):option;
            if(option.startsWith("-Xbootclasspath:")||option.startsWith("-Xbootclasspath/a:")||option.startsWith("-Xbootclasspath/p:")){
                addOptionPaths(result,option.substring(option.indexOf(':')+1));continue;
            }
            if(!pathOptions.contains(name))continue;
            String value=option.contains("=")?option.substring(option.indexOf('=')+1):i+1<options.size()?options.get(++i):"";
            if((name.equals("--patch-module")||name.equals("--module-source-path"))&&value.contains("="))value=value.substring(value.indexOf('=')+1);
            addOptionPaths(result,value);
        }
        return List.copyOf(result);
    }
    private static void addOptionPaths(List<Path> entries,String value){
        for(String entry:value.split(java.util.regex.Pattern.quote(File.pathSeparator))){
            if(entry.isBlank()||entry.equals("none"))continue;
            int wildcard=entry.indexOf('*'),group=entry.indexOf('{');
            if(group>=0&&(wildcard<0||group<wildcard))wildcard=group;
            if(wildcard>=0){int separator=Math.max(entry.lastIndexOf('/',wildcard),entry.lastIndexOf('\\',wildcard));entry=separator<0?".":entry.substring(0,separator+1);}
            entries.add(Path.of(entry.isEmpty()?".":entry).toAbsolutePath().normalize());
        }
    }
    private static Path normalize(Path path){return path.toAbsolutePath().normalize();}
    private synchronized void closeWatcher(){var current=watcher;watcher=null;if(current!=null)try{current.close();}catch(IOException ignored){}watches.clear();watchedPhysical.clear();}
    @Override public synchronized void close(){if(closed)return;closed=true;closeWatcher();}
}
