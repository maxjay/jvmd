package dev.jvmd.analyzer;

import dev.jvmd.index.IndexService;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import javax.tools.*;

/** Implements 4.2: indexed classpath paths, multi-release bytes, bounded LRU and source overlays. */
public final class IndexedFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
    private record Stamp(long size,long modified,Object identity) { }
    private record Entry(String binary,String path) { }
    private record Catalog(Path path,Stamp stamp,Map<String,List<Entry>> packages,Map<String,Entry> classes) { }
    private record ByteKey(Path path,Stamp stamp,String entry) { }
    private record SourceEntry(String binary,Path file) { }
    private final List<Path> classpath;
    private final List<Path> directories;
    private final List<Path> sourceRoots;
    private final boolean preciseSourceRoots;
    private final Map<Location,List<Path>> modulePaths=new HashMap<>();
    private final Map<Location,List<Path>> moduleSources=new HashMap<>();
    private record ModuleLocation(Location delegate) implements Location {
        @Override public String getName(){return "jvmd:"+delegate.getName();}
        @Override public boolean isOutputLocation(){return delegate.isOutputLocation();}
        @Override public boolean isModuleOrientedLocation(){return delegate.isModuleOrientedLocation();}
    }
    private final Map<Location,ModuleLocation> wrappedLocations=new HashMap<>();
    private Location wrap(Location location){return location==null||location instanceof ModuleLocation?location:wrappedLocations.computeIfAbsent(location,ModuleLocation::new);}
    private static Location delegate(Location location){return location instanceof ModuleLocation module?module.delegate():location;}
    private Map<Path,String> moduleDescriptors;
    private long sourceModuleGeneration;
    long sourceModuleGeneration(){return sourceModuleGeneration;}
    private Path moduleOutput;
    private Map<Path,String> documents=Map.of();
    public void documents(Map<Path,String> values){documents=Map.copyOf(values);try{configureModules();}catch(IOException e){throw new UncheckedIOException(e);}}
    private final Map<Path,Catalog> catalogs=new HashMap<>();
    private final Map<Path,Stamp> classFiles=new HashMap<>();
    private final WatchService classWatcher;
    private final Map<WatchKey,Path> watchDirectories=new HashMap<>();
    private final Set<Path> watchedPaths=new HashSet<>();
    private boolean watcherReliable=true;
    private long watchEvents,fullClassScans;
    private final WatchService sourceWatcher;
    private final Map<WatchKey,Path> sourceWatchDirectories=new HashMap<>();
    private final Set<Path> watchedSourcePaths=new HashSet<>();
    private boolean sourceWatcherReliable=true;
    private long sourceWatchEvents,sourceStateGeneration;
    private Map<String,List<SourceEntry>> sourcePackages=Map.of();
    private boolean sourceCatalogDirty=true;
    private long sourceCatalogBuilds,sourceCatalogFiles,sourceListCalls,sourceListEntries;
    private final LinkedHashMap<ByteKey,byte[]> bytes=new LinkedHashMap<>(64,.75f,true);
    private final long byteLimit;
    private long byteSize,hits,loads;
    private Set<Path> binarySources=Set.of();
    public void binarySources(Set<Path> sources){binarySources=Set.copyOf(sources);}
    private boolean preferBinary(JavaFileObject file){
        return !binarySources.isEmpty()&&file.getKind()==JavaFileObject.Kind.SOURCE&&file.toUri().getScheme().equals("file")&&binarySources.contains(Path.of(file.toUri()).toAbsolutePath().normalize())&&!documents.containsKey(Path.of(file.toUri()).toAbsolutePath().normalize());
    }
    public IndexedFileManager(StandardJavaFileManager delegate,List<Path> classpath,List<Path> sources,
                              IndexService index,long byteLimit)throws Exception {
        this(delegate,classpath,sources,index,byteLimit,true);
    }
    public IndexedFileManager(StandardJavaFileManager delegate,List<Path> classpath,List<Path> sources,
                              IndexService index,long byteLimit,boolean preciseSourceRoots)throws Exception {
        super(delegate);this.byteLimit=byteLimit;this.preciseSourceRoots=preciseSourceRoots;this.sourceRoots=sources.stream().map(p->p.toAbsolutePath().normalize()).sorted(Comparator.comparingInt((Path p)->p.getNameCount()).reversed()).toList();
        var paths=new ArrayList<Path>();
        for(var path:classpath){path=path.toAbsolutePath().normalize();var artifact=index==null?null:index.artifact(path);paths.add(artifact==null?path:Path.of(artifact.path()));}
        this.classpath=List.copyOf(paths);
        directories=paths.stream().filter(p->!p.toString().endsWith(".jar")).toList();
        WatchService watcher=null;try{watcher=FileSystems.getDefault().newWatchService();}catch(IOException|UnsupportedOperationException ignored){watcherReliable=false;}
        classWatcher=watcher;
        if(classWatcher!=null)for(Path directory:directories)if(Files.isDirectory(directory))registerTree(directory);
        // Source-root precision controls package-catalog use, not change detection. Even a
        // coarse/plain workspace root can be watched recursively and provide a cheap invalidation
        // epoch; javac listing still falls back to the standard file manager unless roots are precise.
        WatchService sourcesWatcher=null;
        try{sourcesWatcher=FileSystems.getDefault().newWatchService();}catch(IOException|UnsupportedOperationException ignored){sourceWatcherReliable=false;}
        sourceWatcher=sourcesWatcher;
        if(sourceWatcher!=null)for(Path root:sourceRoots)if(Files.isDirectory(root))registerSourceTree(root);
        // Directory inputs and source roots keep javac's own file-manager behavior.
        delegate.setLocationFromPaths(StandardLocation.CLASS_PATH,directories.stream().filter(Files::isDirectory).toList());
        delegate.setLocationFromPaths(StandardLocation.SOURCE_PATH,sources.stream().filter(Files::isDirectory).toList());
        configureModules();
    }
    private void configureModules()throws IOException{
        var descriptors=new LinkedHashMap<Path,String>();
        for(Path root:sourceRoots){Path file=root.resolve("module-info.java");String text=documents.get(file);if(text==null&&Files.isRegularFile(file))text=Files.readString(file);if(text!=null)descriptors.put(root,text);}
        if(descriptors.equals(moduleDescriptors))return;
        if(!moduleSources.isEmpty())fileManager.setLocationFromPaths(StandardLocation.MODULE_SOURCE_PATH,List.of());
        moduleSources.clear();modulePaths.clear();wrappedLocations.clear();
        fileManager.setLocationFromPaths(StandardLocation.SOURCE_PATH,sourceRoots.stream().filter(Files::isDirectory).toList());
        fileManager.setLocationFromPaths(StandardLocation.MODULE_PATH,descriptors.isEmpty()?List.of():classpath.stream().filter(Files::exists).toList());
        for(var group:fileManager.listLocationsForModules(StandardLocation.MODULE_PATH))for(var location:group){
            var entries=new ArrayList<Path>();fileManager.getLocationAsPaths(location).forEach(entries::add);modulePaths.put(wrap(location),List.copyOf(entries));
        }
        fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH,classInputs().stream().filter(Files::isDirectory).toList());
        if(descriptors.size()>1){
            var inputs=new ArrayList<JavaFileObject>();descriptors.forEach((root,text)->inputs.add(source(root.resolve("module-info.java"),text)));
            // Parse descriptors with javac; do not infer module names from paths or source text patterns.
            var task=(com.sun.source.util.JavacTask)ToolProvider.getSystemJavaCompiler().getTask(new StringWriter(),fileManager,new DiagnosticCollector<JavaFileObject>(),List.of("-proc:none"),null,inputs);
            var names=new LinkedHashMap<Path,String>();
            for(var unit:task.parse())if(unit.getModule()!=null)names.put(Path.of(unit.getSourceFile().toUri()).getParent(),unit.getModule().getName().toString());
            if(names.size()>1){
                var roots=new LinkedHashMap<String,List<Path>>();names.forEach((root,name)->roots.computeIfAbsent(name,k->new ArrayList<>()).add(root));
                for(Path root:sourceRoots)if(!names.containsKey(root)){
                    Path nearest=names.keySet().stream().max(Comparator.comparingInt(candidate->commonPrefix(candidate,root))).orElseThrow();
                    roots.get(names.get(nearest)).add(root);
                }
                fileManager.setLocation(StandardLocation.SOURCE_PATH,null);
                // Javac requires an output location in multi-module mode even for analyze().
                // No generate() call is made, so this private directory remains empty.
                if(moduleOutput==null)moduleOutput=Files.createTempDirectory("jvmd-module-analysis-");
                fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT,List.of(moduleOutput));
                for(var entry:roots.entrySet())fileManager.setLocationForModule(StandardLocation.MODULE_SOURCE_PATH,entry.getKey(),entry.getValue());
                for(var group:fileManager.listLocationsForModules(StandardLocation.MODULE_SOURCE_PATH))for(var location:group){
                    var entries=new ArrayList<Path>();fileManager.getLocationAsPaths(location).forEach(entries::add);moduleSources.put(wrap(location),List.copyOf(entries));
                }
            }
        }
        moduleDescriptors=Map.copyOf(descriptors);
        sourceModuleGeneration++;
    }
    private static int commonPrefix(Path first,Path second){
        int length=0;while(length<Math.min(first.getNameCount(),second.getNameCount())&&first.getName(length).equals(second.getName(length)))length++;return length;
    }
    private List<Path> classInputs(){return modulePaths.isEmpty()?classpath:classpath.stream().filter(path->modulePaths.values().stream().noneMatch(paths->paths.contains(path))).toList();}
    public void validateClasspath(){
        try {
            for(var path:classpath)if(path.toString().endsWith(".jar")){
                var current=stamp(path);var catalog=catalogs.get(path);
                if(catalog!=null&&!current.equals(catalog.stamp()))throw new IOException("classpath changed during analysis: "+path);
            }
            Path changed=watchedClasspathChange();
            if(changed!=null)throw new IOException("classpath changed during analysis: "+changed);
            if(!watcherReliable){
                fullClassScans++;
                for(var file:classFiles.entrySet())if(!stamp(file.getKey()).equals(file.getValue()))throw new IOException("class file changed during analysis: "+file.getKey());
            }
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    private Path watchedClasspathChange(){
        if(classWatcher==null)return null;
        Path changed=null;WatchKey key;
        while((key=classWatcher.poll())!=null){
            Path directory=watchDirectories.get(key);
            for(var event:key.pollEvents()){
                watchEvents++;
                if(event.kind()==StandardWatchEventKinds.OVERFLOW){
                    watcherReliable=false;if(changed==null)changed=directory;continue;
                }
                if(directory==null||!(event.context() instanceof Path relative))continue;
                Path candidate=directory.resolve(relative).toAbsolutePath().normalize();
                if(event.kind()==StandardWatchEventKinds.ENTRY_CREATE&&Files.isDirectory(candidate))registerTree(candidate);
                if(changed==null)changed=candidate;
            }
            if(!key.reset()){watchDirectories.remove(key);if(directory!=null)watchedPaths.remove(directory);watcherReliable=false;if(changed==null)changed=directory;}
        }
        return changed;
    }
    private void registerTree(Path root){
        if(classWatcher==null)return;
        try(var paths=Files.walk(root)){
            for(Path directory:paths.filter(Files::isDirectory).toList()){
                directory=directory.toAbsolutePath().normalize();if(!watchedPaths.add(directory))continue;
                var key=directory.register(classWatcher,StandardWatchEventKinds.ENTRY_CREATE,StandardWatchEventKinds.ENTRY_MODIFY,StandardWatchEventKinds.ENTRY_DELETE);
                watchDirectories.put(key,directory);
            }
        }catch(IOException|UnsupportedOperationException error){watcherReliable=false;}
    }
    long sourceStateGeneration(){
        pollSourceChanges();return sourceWatcherReliable?sourceStateGeneration:-1L;
    }
    private void pollSourceChanges(){
        if(sourceWatcher==null)return;
        WatchKey key;
        while((key=sourceWatcher.poll())!=null){
            Path directory=sourceWatchDirectories.get(key);
            for(var event:key.pollEvents()){
                sourceWatchEvents++;
                if(event.kind()==StandardWatchEventKinds.OVERFLOW){sourceWatcherReliable=false;sourceCatalogDirty=true;sourceStateGeneration++;continue;}
                if(directory==null||!(event.context() instanceof Path relative))continue;
                Path candidate=directory.resolve(relative).toAbsolutePath().normalize();
                boolean directoryCreate=event.kind()==StandardWatchEventKinds.ENTRY_CREATE&&Files.isDirectory(candidate);
                if(directoryCreate){
                    registerSourceTree(candidate);sourceCatalogDirty=true;
                    try{if(!dev.jvmd.core.FileInventory.matching(candidate,".java",1).isEmpty())sourceStateGeneration++;}
                    catch(IOException error){sourceWatcherReliable=false;sourceStateGeneration++;}
                }else if(candidate.toString().endsWith(".java")){
                    if(event.kind()!=StandardWatchEventKinds.ENTRY_MODIFY)sourceCatalogDirty=true;
                    sourceStateGeneration++;
                }else if(event.kind()==StandardWatchEventKinds.ENTRY_DELETE&&watchedSourcePaths.contains(candidate)){
                    sourceCatalogDirty=true;sourceStateGeneration++;
                }
            }
            if(!key.reset()){
                sourceWatchDirectories.remove(key);if(directory!=null)watchedSourcePaths.remove(directory);
                if(directory!=null&&Files.exists(directory))sourceWatcherReliable=false;
                sourceCatalogDirty=true;sourceStateGeneration++;
            }
        }
    }
    private void registerSourceTree(Path root){
        if(sourceWatcher==null)return;
        try{
            Files.walkFileTree(root,new SimpleFileVisitor<>(){
                @Override public FileVisitResult preVisitDirectory(Path directory,java.nio.file.attribute.BasicFileAttributes attributes)throws IOException{
                    directory=directory.toAbsolutePath().normalize();if(!watchedSourcePaths.add(directory))return FileVisitResult.CONTINUE;
                    try{
                        var key=directory.register(sourceWatcher,StandardWatchEventKinds.ENTRY_CREATE,StandardWatchEventKinds.ENTRY_MODIFY,StandardWatchEventKinds.ENTRY_DELETE);
                        sourceWatchDirectories.put(key,directory);
                    }catch(NoSuchFileException removed){watchedSourcePaths.remove(directory);}
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path path,IOException error)throws IOException{
                    if(error instanceof NoSuchFileException)return FileVisitResult.CONTINUE;
                    throw error;
                }
                @Override public FileVisitResult postVisitDirectory(Path path,IOException error)throws IOException{
                    if(error!=null&&!(error instanceof NoSuchFileException))throw error;
                    return FileVisitResult.CONTINUE;
                }
            });
        }catch(IOException|UnsupportedOperationException error){sourceWatcherReliable=false;}
    }
    private boolean sourceCatalogUsable(){
        pollSourceChanges();return preciseSourceRoots&&sourceWatcherReliable;
    }
    private void ensureSourceCatalog()throws IOException{
        if(!sourceCatalogUsable()||!sourceCatalogDirty)return;
        var packages=new TreeMap<String,List<SourceEntry>>();var files=new LinkedHashSet<Path>();
        for(Path root:sourceRoots)if(Files.isDirectory(root))files.addAll(dev.jvmd.core.FileInventory.matching(root,".java"));
        for(Path file:files){
            file=file.toAbsolutePath().normalize();String binary=sourceName(file,sourceRoots);if(binary==null)continue;
            int dot=binary.lastIndexOf('.');String pkg=dot<0?"":binary.substring(0,dot);
            packages.computeIfAbsent(pkg,ignored->new ArrayList<>()).add(new SourceEntry(binary,file));
        }
        var frozen=new TreeMap<String,List<SourceEntry>>();
        packages.forEach((pkg,entries)->frozen.put(pkg,entries.stream().sorted(Comparator.comparing(SourceEntry::binary)).toList()));
        sourcePackages=Map.copyOf(frozen);sourceCatalogDirty=false;sourceCatalogBuilds++;sourceCatalogFiles=files.size();
    }
    private List<SourceEntry> sourceEntries(List<Path> roots,String packageName,boolean recurse)throws IOException{
        ensureSourceCatalog();if(!sourceCatalogUsable())return null;
        var result=new ArrayList<SourceEntry>();
        if(recurse){
            for(var entry:sourcePackages.entrySet())if(packageName.isEmpty()||entry.getKey().equals(packageName)||entry.getKey().startsWith(packageName+"."))
                for(var source:entry.getValue())if(roots.stream().anyMatch(source.file()::startsWith))result.add(source);
        }else for(var source:sourcePackages.getOrDefault(packageName,List.of()))if(roots.stream().anyMatch(source.file()::startsWith))result.add(source);
        return result;
    }
    private void track(JavaFileObject file)throws IOException {
        if(file!=null && file.getKind()==JavaFileObject.Kind.CLASS && file.toUri().getScheme().equals("file")){
            Path path=Path.of(file.toUri());classFiles.putIfAbsent(path,stamp(path));
        }
    }
    public Map<String,Long> status(){return Map.ofEntries(
            Map.entry("class_bytes",byteSize),Map.entry("class_byte_hits",hits),Map.entry("class_byte_loads",loads),
            Map.entry("classpath_watch_events",watchEvents),Map.entry("classpath_full_scans",fullClassScans),Map.entry("classpath_watch_directories",(long)watchDirectories.size()),
            Map.entry("source_watch_events",sourceWatchEvents),Map.entry("source_watch_directories",(long)sourceWatchDirectories.size()),
            Map.entry("source_watch_reliable",sourceWatcherReliable?1L:0L),Map.entry("source_state_generation",sourceStateGeneration),
            Map.entry("source_catalog_builds",sourceCatalogBuilds),Map.entry("source_catalog_files",sourceCatalogFiles),
            Map.entry("source_list_calls",sourceListCalls),Map.entry("source_list_entries",sourceListEntries));}
    private static Stamp stamp(Path path)throws IOException {
        try{var attrs=Files.readAttributes(path,"unix:size,lastModifiedTime,ctime,ino");return new Stamp(((Number)attrs.get("size")).longValue(),((java.nio.file.attribute.FileTime)attrs.get("lastModifiedTime")).to(java.util.concurrent.TimeUnit.NANOSECONDS),List.of(attrs.get("ctime"),attrs.get("ino")));}
        catch(UnsupportedOperationException|IllegalArgumentException ignored){var attrs=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);return new Stamp(attrs.size(),attrs.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS),dev.jvmd.core.Hashing.sha256(path));}
    }
    private Catalog catalog(Path path)throws IOException {
        Stamp stamp=stamp(path);var old=catalogs.get(path);if(old!=null&&old.stamp().equals(stamp))return old;
        if(old!=null)throw new UncheckedIOException(new IOException("classpath changed during analysis: "+path));
        var packages=new HashMap<String,List<Entry>>();var classes=new HashMap<String,Entry>();
        // Jar entries include package-private classes needed to complete public signatures.
        try(var jar=new JarFile(path.toFile(),false,JarFile.OPEN_READ,Runtime.version())){
            for(var item:jar.versionedStream().filter(e->e.getName().endsWith(".class")&&!e.getName().equals("module-info.class")).toList()){
                String binary=item.getName().substring(0,item.getName().length()-6).replace('/','.');var entry=new Entry(binary,item.getRealName());
                classes.put(binary,entry);String pkg=binary.contains(".")?binary.substring(0,binary.lastIndexOf('.')):"";packages.computeIfAbsent(pkg,k->new ArrayList<>()).add(entry);
            }
        }
        var value=new Catalog(path,stamp,packages,classes);catalogs.put(path,value);return value;
    }
    private byte[] bytes(Catalog catalog,Entry entry)throws IOException {
        // Stat before touching the LRU: deletion must never be hidden by cached class bytes.
        Stamp current;
        try{current=stamp(catalog.path());}catch(IOException e){throw new UncheckedIOException(e);}
        if(!current.equals(catalog.stamp()))throw new UncheckedIOException(new IOException("classpath changed during analysis: "+catalog.path()));
        var key=new ByteKey(catalog.path(),current,entry.path());var cached=bytes.get(key);if(cached!=null){hits++;return cached;}
        byte[] value;try(var jar=new JarFile(catalog.path().toFile(),false,JarFile.OPEN_READ,Runtime.version())){var item=jar.getJarEntry(entry.path());if(item==null)throw new FileNotFoundException(entry.path());try(var stream=jar.getInputStream(item)){value=stream.readAllBytes();}}
        loads++;if(value.length<=byteLimit){bytes.put(key,value);byteSize+=value.length;while(byteSize>byteLimit){var first=bytes.entrySet().iterator();var evicted=first.next();byteSize-=evicted.getValue().length;first.remove();}}
        return value;
    }
    private final class BinaryFile extends SimpleJavaFileObject {
        final Catalog catalog;final Entry entry;
        BinaryFile(Catalog catalog,Entry entry){super(URI.create("jvmd-class:///"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(catalog.path().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))+"/"+entry.path()),Kind.CLASS);this.catalog=catalog;this.entry=entry;}
        @Override public InputStream openInputStream()throws IOException{return new ByteArrayInputStream(bytes(catalog,entry));}
        @Override public long getLastModified(){return catalog.stamp().modified()/1_000_000;}
        @Override public String getName(){return catalog.path()+"!/"+entry.path();}
    }
    private final class SourceFile extends SimpleJavaFileObject {
        final String binary,text;final Path file;final long generation;
        SourceFile(Path file,String binary,String text){super(file.toUri(),Kind.SOURCE);this.file=file;this.binary=binary;this.text=text;this.generation=sourceStateGeneration;}
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors)throws IOException{return text==null?Files.readString(file):text;}
        @Override public InputStream openInputStream()throws IOException{return text==null?Files.newInputStream(file):new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        @Override public long getLastModified(){
            if(text!=null)return Long.MAX_VALUE;
            try{long modified=Files.getLastModifiedTime(file).toMillis();return modified>Long.MAX_VALUE-generation?Long.MAX_VALUE:modified+generation;}
            catch(IOException ignored){return generation;}
        }
    }
    public JavaFileObject source(Path file,String text){
        file=file.toAbsolutePath().normalize();String binary=sourceName(file);
        return new SourceFile(file,binary==null?file.getFileName().toString().replaceFirst("\\.java$", ""):binary,text);
    }
    private String sourceName(Path file){
        return sourceName(file,sourceRoots);
    }
    private static String sourceName(Path file,List<Path> roots){
        for(Path root:roots)if(file.startsWith(root)){String relative=root.relativize(file).toString();if(relative.endsWith(".java"))return relative.substring(0,relative.length()-5).replace(java.io.File.separatorChar,'.');}
        return null;
    }
    @Override public Iterable<JavaFileObject> list(Location location,String packageName,Set<JavaFileObject.Kind> kinds,boolean recurse)throws IOException {
        List<Path> sourceInputs=location==StandardLocation.SOURCE_PATH?sourceRoots:moduleSources.get(location);
        if(sourceInputs!=null){
            sourceListCalls++;var sources=new LinkedHashMap<String,JavaFileObject>();
            List<SourceEntry> indexed=kinds.contains(JavaFileObject.Kind.SOURCE)?sourceEntries(sourceInputs,packageName,recurse):List.of();
            if(indexed==null){
                for(var file:super.list(delegate(location),packageName,kinds,recurse))if(!preferBinary(file))sources.put(super.inferBinaryName(delegate(location),file),file);
            }else{
                if(kinds.size()>1){
                    var delegated=EnumSet.copyOf(kinds);delegated.remove(JavaFileObject.Kind.SOURCE);
                    if(!delegated.isEmpty())for(var file:super.list(delegate(location),packageName,delegated,recurse))sources.put(super.inferBinaryName(delegate(location),file),file);
                }
                for(var entry:indexed)if(!binarySources.contains(entry.file())||documents.containsKey(entry.file()))
                    sources.put(entry.binary(),new SourceFile(entry.file(),entry.binary(),documents.get(entry.file())));
            }
            // Open buffers must overlay both the watcher-backed catalog and the conservative
            // standard-file-manager path. Coarse roots intentionally disable the catalog.
            if(kinds.contains(JavaFileObject.Kind.SOURCE))for(var entry:documents.entrySet()){
                String binary=sourceName(entry.getKey(),sourceInputs);if(binary==null)continue;int dot=binary.lastIndexOf('.');String pkg=dot<0?"":binary.substring(0,dot);
                if(pkg.equals(packageName)||recurse&&(packageName.isEmpty()||pkg.startsWith(packageName+".")))sources.put(binary,new SourceFile(entry.getKey(),binary,entry.getValue()));
            }
            sourceListEntries+=sources.size();return sources.values();
        }
        List<Path> inputs=location==StandardLocation.CLASS_PATH?classInputs():modulePaths.get(location);
        if(inputs==null||!kinds.contains(JavaFileObject.Kind.CLASS))return super.list(delegate(location),packageName,kinds,recurse);
        var result=new LinkedHashMap<String,JavaFileObject>();
        if(inputs.stream().filter(path->!path.toString().endsWith(".jar")).anyMatch(Files::isDirectory))for(var file:super.list(delegate(location),packageName,kinds,recurse)){track(file);result.put(super.inferBinaryName(delegate(location),file),file);}
        for(var path:inputs)if(path.toString().endsWith(".jar")){
            Catalog catalog;
            try{catalog=catalog(path);}catch(IOException e){throw new UncheckedIOException(e);}
            if(recurse){for(var item:catalog.packages().entrySet())if(packageName.isEmpty()||item.getKey().equals(packageName)||item.getKey().startsWith(packageName+"."))for(var entry:item.getValue())result.putIfAbsent(entry.binary(),new BinaryFile(catalog,entry));}
            else for(var entry:catalog.packages().getOrDefault(packageName,List.of()))result.putIfAbsent(entry.binary(),new BinaryFile(catalog,entry));
        }
        return result.values();
    }
    @Override public JavaFileObject getJavaFileForInput(Location location,String className,JavaFileObject.Kind kind)throws IOException {
        List<Path> sourceInputs=location==StandardLocation.SOURCE_PATH?sourceRoots:moduleSources.get(location);
        if(sourceInputs!=null&&kind==JavaFileObject.Kind.SOURCE)for(var entry:documents.entrySet())if(className.equals(sourceName(entry.getKey(),sourceInputs)))return new SourceFile(entry.getKey(),className,entry.getValue());
        List<Path> inputs=location==StandardLocation.CLASS_PATH?classInputs():modulePaths.get(location);
        if(inputs!=null&&kind==JavaFileObject.Kind.CLASS&&!className.equals("module-info")){
            if(inputs.stream().filter(path->!path.toString().endsWith(".jar")).anyMatch(Files::isDirectory)){var directory=super.getJavaFileForInput(delegate(location),className,kind);if(directory!=null){track(directory);return directory;}}
            for(var path:inputs)if(path.toString().endsWith(".jar")){Catalog catalog;try{catalog=catalog(path);}catch(IOException e){throw new UncheckedIOException(e);}var entry=catalog.classes().get(className);if(entry!=null)return new BinaryFile(catalog,entry);}
            return null;
        }var file=super.getJavaFileForInput(delegate(location),className,kind);return file!=null&&sourceInputs!=null&&preferBinary(file)?null:file;
    }
    @Override public String inferBinaryName(Location location,JavaFileObject file){return file instanceof SourceFile source?source.binary:file instanceof IndexedFileManager.BinaryFile binary?binary.entry.binary():super.inferBinaryName(delegate(location),file);}
    @Override public boolean isSameFile(FileObject a,FileObject b){if(a instanceof SourceFile||b instanceof SourceFile||a instanceof IndexedFileManager.BinaryFile||b instanceof IndexedFileManager.BinaryFile)return a.toUri().equals(b.toUri());return super.isSameFile(a,b);}
    @Override public boolean contains(Location location,FileObject file)throws IOException{
        if(file instanceof SourceFile){
            Path path=Path.of(file.toUri()).toAbsolutePath().normalize();
            if(location==StandardLocation.SOURCE_PATH)return moduleSources.isEmpty()&&sourceRoots.stream().anyMatch(path::startsWith);
            if(moduleSources.containsKey(location))return moduleSources.get(location).stream().anyMatch(path::startsWith);
            return false;
        }
        if(file instanceof IndexedFileManager.BinaryFile binary)return location==StandardLocation.CLASS_PATH?classInputs().contains(binary.catalog.path()):modulePaths.getOrDefault(location,List.of()).contains(binary.catalog.path());
        return super.contains(delegate(location),file);
    }
    @Override public Location getLocationForModule(Location location,JavaFileObject file)throws IOException{
        if(file instanceof SourceFile)file=fileManager.getJavaFileObjectsFromPaths(List.of(Path.of(file.toUri()))).iterator().next();
        if(file instanceof IndexedFileManager.BinaryFile binary&&location==StandardLocation.MODULE_PATH){for(var entry:modulePaths.entrySet())if(entry.getValue().contains(binary.catalog.path()))return entry.getKey();return null;}
        return wrap(super.getLocationForModule(delegate(location),file));
    }
    @Override public Location getLocationForModule(Location location,String name)throws IOException{return wrap(super.getLocationForModule(delegate(location),name));}
    @Override public Iterable<Set<Location>> listLocationsForModules(Location location)throws IOException{
        var result=new ArrayList<Set<Location>>();for(var group:super.listLocationsForModules(delegate(location))){var entries=new LinkedHashSet<Location>();for(var entry:group)entries.add(wrap(entry));result.add(entries);}return result;
    }
    @Override public String inferModuleName(Location location)throws IOException{return super.inferModuleName(delegate(location));}
    @Override public FileObject getFileForInput(Location location,String packageName,String relativeName)throws IOException{return super.getFileForInput(delegate(location),packageName,relativeName);}
    @Override public FileObject getFileForOutput(Location location,String packageName,String relativeName,FileObject sibling)throws IOException{return super.getFileForOutput(delegate(location),packageName,relativeName,sibling);}
    @Override public JavaFileObject getJavaFileForOutput(Location location,String className,JavaFileObject.Kind kind,FileObject sibling)throws IOException{return super.getJavaFileForOutput(delegate(location),className,kind,sibling);}
    @Override public ClassLoader getClassLoader(Location location){return super.getClassLoader(delegate(location));}
    @Override public <S> ServiceLoader<S> getServiceLoader(Location location,Class<S> service)throws IOException{return super.getServiceLoader(delegate(location),service);}
    @Override public boolean hasLocation(Location location){if(location==StandardLocation.MODULE_SOURCE_PATH)return !moduleSources.isEmpty();return location==StandardLocation.CLASS_PATH||location==StandardLocation.SOURCE_PATH&&moduleSources.isEmpty()&&!documents.isEmpty()||super.hasLocation(delegate(location));}
    public void sourcesChanged(){try{fileManager.flush();configureModules();}catch(IOException e){throw new UncheckedIOException(e);}}
    public void invalidate(){catalogs.clear();classFiles.clear();bytes.clear();byteSize=0;sourcesChanged();}
    @Override public void close()throws IOException{
        try{super.close();}finally{
            catalogs.clear();classFiles.clear();bytes.clear();byteSize=0;
            if(classWatcher!=null)classWatcher.close();
            if(sourceWatcher!=null)sourceWatcher.close();
            watchDirectories.clear();watchedPaths.clear();sourceWatchDirectories.clear();watchedSourcePaths.clear();sourcePackages=Map.of();sourceCatalogDirty=true;
            if(moduleOutput!=null)try(var files=Files.walk(moduleOutput)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}
        }
    }
}
