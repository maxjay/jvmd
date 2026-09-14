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
    private record Stamp(long size,long modified) { }
    private record Entry(String binary,String path) { }
    private record Catalog(Path path,Stamp stamp,Map<String,List<Entry>> packages,Map<String,Entry> classes) { }
    private record ByteKey(Path path,Stamp stamp,String entry) { }
    private final List<Path> classpath;
    private final List<Path> directories;
    private final List<Path> sourceRoots;
    private final Map<Location,List<Path>> modulePaths=new HashMap<>();
    private Map<Path,String> documents=Map.of();
    public void documents(Map<Path,String> values){documents=Map.copyOf(values);}
    private final Map<Path,Catalog> catalogs=new HashMap<>();
    private final Map<Path,Stamp> classFiles=new HashMap<>();
    private final LinkedHashMap<ByteKey,byte[]> bytes=new LinkedHashMap<>(64,.75f,true);
    private final long byteLimit;
    private long byteSize,hits,loads;
    private Set<Path> binarySources=Set.of();
    public void binarySources(Set<Path> sources){binarySources=Set.copyOf(sources);}
    private boolean preferBinary(JavaFileObject file){
        return file.getKind()==JavaFileObject.Kind.SOURCE&&file.toUri().getScheme().equals("file")&&binarySources.contains(Path.of(file.toUri()).toAbsolutePath().normalize())&&!documents.containsKey(Path.of(file.toUri()).toAbsolutePath().normalize());
    }
    public IndexedFileManager(StandardJavaFileManager delegate,List<Path> classpath,List<Path> sources,
                              IndexService index,long byteLimit)throws Exception {
        super(delegate);this.byteLimit=byteLimit;this.sourceRoots=sources.stream().map(p->p.toAbsolutePath().normalize()).sorted(Comparator.comparingInt((Path p)->p.getNameCount()).reversed()).toList();
        var paths=new ArrayList<Path>();
        for(var path:classpath){path=path.toAbsolutePath().normalize();var artifact=index==null?null:index.artifact(path);paths.add(artifact==null?path:Path.of(artifact.path()));}
        this.classpath=List.copyOf(paths);
        directories=paths.stream().filter(p->!p.toString().endsWith(".jar")).toList();
        // Directory inputs and source roots keep javac's own file-manager behavior.
        delegate.setLocationFromPaths(StandardLocation.CLASS_PATH,directories.stream().filter(Files::isDirectory).toList());
        delegate.setLocationFromPaths(StandardLocation.SOURCE_PATH,sources.stream().filter(Files::isDirectory).toList());
        if(sources.stream().anyMatch(root->Files.isRegularFile(root.resolve("module-info.java")))){
            delegate.setLocationFromPaths(StandardLocation.MODULE_PATH,paths.stream().filter(Files::exists).toList());
            for(var group:delegate.listLocationsForModules(StandardLocation.MODULE_PATH))for(var location:group){
                var entries=new ArrayList<Path>();delegate.getLocationAsPaths(location).forEach(entries::add);modulePaths.put(location,List.copyOf(entries));
            }
        }
    }
    public void validateClasspath(){
        try {
            for(var path:classpath)if(path.toString().endsWith(".jar")){
                var current=stamp(path);var catalog=catalogs.get(path);
                if(catalog!=null&&!current.equals(catalog.stamp()))throw new IOException("classpath changed during analysis: "+path);
            }
            for(var file:classFiles.entrySet())if(!stamp(file.getKey()).equals(file.getValue()))throw new IOException("class file changed during analysis: "+file.getKey());
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    private void track(JavaFileObject file)throws IOException {
        if(file!=null && file.getKind()==JavaFileObject.Kind.CLASS && file.toUri().getScheme().equals("file")){
            Path path=Path.of(file.toUri());classFiles.putIfAbsent(path,stamp(path));
        }
    }
    public Map<String,Long> status(){return Map.of("class_bytes",byteSize,"class_byte_hits",hits,"class_byte_loads",loads);}
    private static Stamp stamp(Path path)throws IOException {var attrs=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);return new Stamp(attrs.size(),attrs.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS));}
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
    private static final class SourceFile extends SimpleJavaFileObject {
        final String binary,text;
        SourceFile(Path file,String binary,String text){super(file.toUri(),Kind.SOURCE);this.binary=binary;this.text=text;}
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors){return text;}
        @Override public long getLastModified(){return Long.MAX_VALUE;}
    }
    public JavaFileObject source(Path file,String text){
        file=file.toAbsolutePath().normalize();String binary=sourceName(file);
        return new SourceFile(file,binary==null?file.getFileName().toString().replaceFirst("\\.java$", ""):binary,text);
    }
    private String sourceName(Path file){
        for(Path root:sourceRoots)if(file.startsWith(root)){String relative=root.relativize(file).toString();if(relative.endsWith(".java"))return relative.substring(0,relative.length()-5).replace(java.io.File.separatorChar,'.');}
        return null;
    }
    @Override public Iterable<JavaFileObject> list(Location location,String packageName,Set<JavaFileObject.Kind> kinds,boolean recurse)throws IOException {
        if(location==StandardLocation.SOURCE_PATH){
            var sources=new LinkedHashMap<String,JavaFileObject>();for(var file:super.list(location,packageName,kinds,recurse))if(!preferBinary(file))sources.put(super.inferBinaryName(location,file),file);
            if(kinds.contains(JavaFileObject.Kind.SOURCE))for(var entry:documents.entrySet()){
                String binary=sourceName(entry.getKey());if(binary==null)continue;int dot=binary.lastIndexOf('.');String pkg=dot<0?"":binary.substring(0,dot);
                if(pkg.equals(packageName)||recurse&&(packageName.isEmpty()||pkg.startsWith(packageName+".")))sources.put(binary,new SourceFile(entry.getKey(),binary,entry.getValue()));
            }return sources.values();
        }
        List<Path> inputs=location==StandardLocation.CLASS_PATH?classpath:modulePaths.get(location);
        if(inputs==null||!kinds.contains(JavaFileObject.Kind.CLASS))return super.list(location,packageName,kinds,recurse);
        var result=new LinkedHashMap<String,JavaFileObject>();
        if(inputs.stream().anyMatch(Files::isDirectory))for(var file:super.list(location,packageName,kinds,recurse)){track(file);result.put(super.inferBinaryName(location,file),file);}
        for(var path:inputs)if(path.toString().endsWith(".jar")){
            Catalog catalog;
            try{catalog=catalog(path);}catch(IOException e){throw new UncheckedIOException(e);}
            if(recurse){for(var item:catalog.packages().entrySet())if(packageName.isEmpty()||item.getKey().equals(packageName)||item.getKey().startsWith(packageName+"."))for(var entry:item.getValue())result.putIfAbsent(entry.binary(),new BinaryFile(catalog,entry));}
            else for(var entry:catalog.packages().getOrDefault(packageName,List.of()))result.putIfAbsent(entry.binary(),new BinaryFile(catalog,entry));
        }
        return result.values();
    }
    @Override public JavaFileObject getJavaFileForInput(Location location,String className,JavaFileObject.Kind kind)throws IOException {
        if(location==StandardLocation.SOURCE_PATH&&kind==JavaFileObject.Kind.SOURCE)for(var entry:documents.entrySet())if(className.equals(sourceName(entry.getKey())))return new SourceFile(entry.getKey(),className,entry.getValue());
        List<Path> inputs=location==StandardLocation.CLASS_PATH?classpath:modulePaths.get(location);
        if(inputs!=null&&kind==JavaFileObject.Kind.CLASS&&!className.equals("module-info")){
            if(inputs.stream().anyMatch(Files::isDirectory)){var directory=super.getJavaFileForInput(location,className,kind);if(directory!=null){track(directory);return directory;}}
            for(var path:inputs)if(path.toString().endsWith(".jar")){Catalog catalog;try{catalog=catalog(path);}catch(IOException e){throw new UncheckedIOException(e);}var entry=catalog.classes().get(className);if(entry!=null)return new BinaryFile(catalog,entry);}
            return null;
        }var file=super.getJavaFileForInput(location,className,kind);return file!=null&&location==StandardLocation.SOURCE_PATH&&preferBinary(file)?null:file;
    }
    @Override public String inferBinaryName(Location location,JavaFileObject file){return file instanceof SourceFile source?source.binary:file instanceof IndexedFileManager.BinaryFile binary?binary.entry.binary():super.inferBinaryName(location,file);}
    @Override public boolean isSameFile(FileObject a,FileObject b){if(a instanceof SourceFile||b instanceof SourceFile||a instanceof IndexedFileManager.BinaryFile||b instanceof IndexedFileManager.BinaryFile)return a.toUri().equals(b.toUri());return super.isSameFile(a,b);}
    @Override public boolean contains(Location location,FileObject file)throws IOException{
        if(file instanceof SourceFile){
            Path path=Path.of(file.toUri()).toAbsolutePath().normalize();
            if(location==StandardLocation.SOURCE_PATH)return sourceRoots.stream().anyMatch(path::startsWith);
            return false;
        }
        if(file instanceof IndexedFileManager.BinaryFile binary)return location==StandardLocation.CLASS_PATH?classpath.contains(binary.catalog.path()):modulePaths.getOrDefault(location,List.of()).contains(binary.catalog.path());
        return super.contains(location,file);
    }
    @Override public Location getLocationForModule(Location location,JavaFileObject file)throws IOException{
        if(file instanceof SourceFile)file=fileManager.getJavaFileObjectsFromPaths(List.of(Path.of(file.toUri()))).iterator().next();
        if(file instanceof IndexedFileManager.BinaryFile binary&&location==StandardLocation.MODULE_PATH){for(var entry:modulePaths.entrySet())if(entry.getValue().contains(binary.catalog.path()))return entry.getKey();return null;}
        return super.getLocationForModule(location,file);
    }
    @Override public boolean hasLocation(Location location){return location==StandardLocation.CLASS_PATH||location==StandardLocation.SOURCE_PATH&&!documents.isEmpty()||super.hasLocation(location);}
    public void invalidate(){catalogs.clear();classFiles.clear();bytes.clear();byteSize=0;try{fileManager.flush();}catch(IOException e){throw new UncheckedIOException(e);}}
    @Override public void close()throws IOException{invalidate();super.close();}
}
