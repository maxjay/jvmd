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
    private final Map<Path,Catalog> catalogs=new HashMap<>();
    private final LinkedHashMap<ByteKey,byte[]> bytes=new LinkedHashMap<>(64,.75f,true);
    private final long byteLimit;
    private long byteSize,hits,loads;
    public IndexedFileManager(StandardJavaFileManager delegate,List<Path> classpath,List<Path> sources,
                              IndexService index,long byteLimit)throws Exception {
        super(delegate);this.byteLimit=byteLimit;
        var paths=new ArrayList<Path>();
        for(var path:classpath){path=path.toAbsolutePath().normalize();var artifact=index==null?null:index.artifact(path);paths.add(artifact==null?path:Path.of(artifact.path()));}
        this.classpath=List.copyOf(paths);
        directories=paths.stream().filter(p->!p.toString().endsWith(".jar")).toList();
        // Directory inputs and source roots keep javac's own file-manager behavior.
        delegate.setLocationFromPaths(StandardLocation.CLASS_PATH,directories.stream().filter(Files::isDirectory).toList());
        delegate.setLocationFromPaths(StandardLocation.SOURCE_PATH,sources.stream().filter(Files::isDirectory).toList());
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
    @Override public Iterable<JavaFileObject> list(Location location,String packageName,Set<JavaFileObject.Kind> kinds,boolean recurse)throws IOException {
        if(location!=StandardLocation.CLASS_PATH||!kinds.contains(JavaFileObject.Kind.CLASS))return super.list(location,packageName,kinds,recurse);
        var result=new LinkedHashMap<String,JavaFileObject>();
        for(var file:super.list(location,packageName,kinds,recurse))result.put(super.inferBinaryName(location,file),file);
        for(var path:classpath)if(path.toString().endsWith(".jar")){
            Catalog catalog;
            try{catalog=catalog(path);}catch(IOException e){throw new UncheckedIOException(e);}
            if(recurse){for(var item:catalog.packages().entrySet())if(packageName.isEmpty()||item.getKey().equals(packageName)||item.getKey().startsWith(packageName+"."))for(var entry:item.getValue())result.putIfAbsent(entry.binary(),new BinaryFile(catalog,entry));}
            else for(var entry:catalog.packages().getOrDefault(packageName,List.of()))result.putIfAbsent(entry.binary(),new BinaryFile(catalog,entry));
        }
        return result.values();
    }
    @Override public JavaFileObject getJavaFileForInput(Location location,String className,JavaFileObject.Kind kind)throws IOException {
        if(location==StandardLocation.CLASS_PATH&&kind==JavaFileObject.Kind.CLASS){
            var directory=super.getJavaFileForInput(location,className,kind);if(directory!=null)return directory;
            for(var path:classpath)if(path.toString().endsWith(".jar")){Catalog catalog;try{catalog=catalog(path);}catch(IOException e){throw new UncheckedIOException(e);}var entry=catalog.classes().get(className);if(entry!=null)return new BinaryFile(catalog,entry);}
            return null;
        }return super.getJavaFileForInput(location,className,kind);
    }
    @Override public String inferBinaryName(Location location,JavaFileObject file){return file instanceof IndexedFileManager.BinaryFile binary?binary.entry.binary():super.inferBinaryName(location,file);}
    @Override public boolean isSameFile(FileObject a,FileObject b){if(a instanceof IndexedFileManager.BinaryFile||b instanceof IndexedFileManager.BinaryFile)return a.toUri().equals(b.toUri());return super.isSameFile(a,b);}
    @Override public boolean contains(Location location,FileObject file)throws IOException{if(file instanceof IndexedFileManager.BinaryFile)return location==StandardLocation.CLASS_PATH;return super.contains(location,file);}
    @Override public boolean hasLocation(Location location){return location==StandardLocation.CLASS_PATH||super.hasLocation(location);}
    public void invalidate(){catalogs.clear();bytes.clear();byteSize=0;}
    @Override public void close()throws IOException{invalidate();super.close();}
}
