package dev.jvmd.index.rocks;

import dev.jvmd.core.Hashing;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.rocksdb.*;

/**
 * Persistent semantic invalidation state consuming detached API fingerprints from the analyzer.
 * It distinguishes content-only changes from declaration changes and propagates only through
 * recorded reverse dependencies (plus unresolved-target matches).
 */
public final class RocksSemanticInvalidation implements AutoCloseable {
    public record FileInput(String contentHash,String apiFingerprint,Set<Path> dependencies,
                            Set<String> exportedNames,Set<String> unresolvedTargets) {
        public FileInput {
            Objects.requireNonNull(contentHash);Objects.requireNonNull(apiFingerprint);
            dependencies=dependencies.stream().map(path->path.toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toUnmodifiableSet());
            exportedNames=Set.copyOf(exportedNames);unresolvedTargets=Set.copyOf(unresolvedTargets);
        }
    }
    public record Result(Set<Path> reanalyze,Set<Path> apiChanged,Set<Path> bodyOnly,Set<Path> deleted,boolean contextChanged) { }
    private record Stored(String contentHash,String apiFingerprint,Set<Path> dependencies,Set<String> exportedNames,Set<String> unresolvedTargets) { }

    static {RocksDB.loadLibrary();}
    private final Options options;
    private final RocksDB db;

    public RocksSemanticInvalidation(Path root)throws Exception{this(root,null);}

    RocksSemanticInvalidation(Path root,RocksMemory memory)throws Exception{
        Path path=root.toAbsolutePath().normalize();Files.createDirectories(path);
        options=memory==null?new Options().setCreateIfMissing(true).setMaxOpenFiles(64):memory.options(64);
        db=RocksDB.open(options,path.toString());
    }


    public synchronized Result observeFile(String moduleId,String contextFingerprint,Path file,FileInput input)throws Exception{
        Objects.requireNonNull(file);Objects.requireNonNull(input);
        String moduleKey=Hashing.sha256(moduleId.getBytes(StandardCharsets.UTF_8));
        var merged=new TreeMap<Path,FileInput>();
        for(var entry:load(moduleKey).entrySet()){
            var value=entry.getValue();
            merged.put(entry.getKey(),new FileInput(value.contentHash(),value.apiFingerprint(),value.dependencies(),
                    value.exportedNames(),value.unresolvedTargets()));
        }
        merged.put(file.toAbsolutePath().normalize(),input);
        var result=update(moduleId,contextFingerprint,merged);
        var invalid=new LinkedHashSet<>(result.reanalyze());invalid.remove(file.toAbsolutePath().normalize());
        invalidate(invalid);return result;
    }

    public synchronized long revision(Path file)throws Exception{
        byte[] value=db.get(bytes("I|"+file.toAbsolutePath().normalize()));
        return value==null?0:java.nio.ByteBuffer.wrap(value).getLong();
    }
    private void invalidate(Set<Path> files)throws Exception{
        if(files.isEmpty())return;
        try(var batch=new WriteBatch();var write=new WriteOptions()){
            for(Path file:files)batch.put(bytes("I|"+file.toAbsolutePath().normalize()),java.nio.ByteBuffer.allocate(8).putLong(revision(file)+1).array());
            db.write(write,batch);
        }
    }
    public synchronized Result removeFiles(String moduleId,Set<Path> deleted)throws Exception{
        String moduleKey=Hashing.sha256(moduleId.getBytes(StandardCharsets.UTF_8));
        var merged=new TreeMap<Path,FileInput>();var normalized=deleted.stream().map(path->path.toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toSet());
        for(var entry:load(moduleKey).entrySet()){
            if(normalized.contains(entry.getKey()))continue;var value=entry.getValue();
            merged.put(entry.getKey(),new FileInput(value.contentHash(),value.apiFingerprint(),value.dependencies(),value.exportedNames(),value.unresolvedTargets()));
        }
        String context=textOrNull(db.get(bytes("C|"+moduleKey)));
        if(context==null)return new Result(Set.of(),Set.of(),Set.of(),Set.of(),false);
        var result=update(moduleId,context,merged);invalidate(result.reanalyze());return result;
    }

    public synchronized Result update(String moduleId,String contextFingerprint,Map<Path,FileInput> input)throws Exception{
        Objects.requireNonNull(moduleId);Objects.requireNonNull(contextFingerprint);
        String moduleKey=Hashing.sha256(moduleId.getBytes(StandardCharsets.UTF_8));
        var current=new TreeMap<Path,FileInput>();
        input.forEach((path,value)->current.put(path.toAbsolutePath().normalize(),value));
        var prior=load(moduleKey);
        String priorContext=textOrNull(db.get(bytes("C|"+moduleKey)));
        boolean contextChanged=priorContext!=null&&!priorContext.equals(contextFingerprint);

        var apiChanged=new LinkedHashSet<Path>();var bodyOnly=new LinkedHashSet<Path>();var deleted=new LinkedHashSet<Path>();
        for(var entry:current.entrySet()){
            Stored old=prior.get(entry.getKey());FileInput now=entry.getValue();
            if(old==null){apiChanged.add(entry.getKey());continue;}
            if(old.contentHash().equals(now.contentHash())&&old.apiFingerprint().equals(now.apiFingerprint()))continue;
            if(old.apiFingerprint().equals(now.apiFingerprint()))bodyOnly.add(entry.getKey());else apiChanged.add(entry.getKey());
        }
        for(Path path:prior.keySet())if(!current.containsKey(path)){deleted.add(path);apiChanged.add(path);}

        var reverse=new HashMap<Path,Set<Path>>();
        for(var entry:prior.entrySet())for(Path dependency:entry.getValue().dependencies())
            reverse.computeIfAbsent(dependency,ignored->new LinkedHashSet<>()).add(entry.getKey());
        for(var entry:current.entrySet())for(Path dependency:entry.getValue().dependencies())
            reverse.computeIfAbsent(dependency,ignored->new LinkedHashSet<>()).add(entry.getKey());

        var reanalyze=new LinkedHashSet<Path>();
        if(contextChanged)reanalyze.addAll(current.keySet());
        else{
            reanalyze.addAll(bodyOnly);reanalyze.addAll(apiChanged);
            var changedExports=new LinkedHashSet<String>();
            for(Path changed:apiChanged){
                Stored old=prior.get(changed);if(old!=null)changedExports.addAll(old.exportedNames());
                FileInput now=current.get(changed);if(now!=null)changedExports.addAll(now.exportedNames());
            }
            if(!changedExports.isEmpty())for(var entry:current.entrySet()){
                if(reanalyze.contains(entry.getKey()))continue;
                if(matchesUnresolved(entry.getValue().unresolvedTargets(),changedExports))reanalyze.add(entry.getKey());
            }
            var queue=new ArrayDeque<Path>(reanalyze.stream().filter(path->!bodyOnly.contains(path)).toList());
            var visited=new HashSet<Path>();
            while(!queue.isEmpty()){
                Path changed=queue.removeFirst();if(!visited.add(changed))continue;
                for(Path dependant:reverse.getOrDefault(changed,Set.of()))if(current.containsKey(dependant)){
                    reanalyze.add(dependant);queue.addLast(dependant);
                }
            }
        }
        reanalyze.removeAll(deleted);

        try(var batch=new WriteBatch();var write=new WriteOptions()){
            boolean changed=false;
            for(var entry:current.entrySet()){
                byte[] key=fileKey(moduleKey,entry.getKey()),value=encode(entry.getValue());
                if(!Arrays.equals(db.get(key),value)){batch.put(key,value);changed=true;}
            }
            for(Path path:deleted)batch.delete(fileKey(moduleKey,path));
            if(!contextFingerprint.equals(priorContext)){batch.put(bytes("C|"+moduleKey),bytes(contextFingerprint));changed=true;}
            if(changed||!deleted.isEmpty())db.write(write,batch);
        }

        return new Result(Set.copyOf(reanalyze),Set.copyOf(apiChanged),Set.copyOf(bodyOnly),Set.copyOf(deleted),contextChanged);
    }

    private Map<Path,Stored> load(String moduleKey){
        byte[] prefix=bytes("S|"+moduleKey+"|");var result=new TreeMap<Path,Stored>();
        try(var read=new ReadOptions();var iterator=db.newIterator(read)){
            for(iterator.seek(prefix);iterator.isValid();iterator.next()){
                if(!startsWith(iterator.key(),prefix))break;
                Path path=Path.of(text(iterator.key()).substring(("S|"+moduleKey+"|").length()));
                try{result.put(path,decode(iterator.value()));}catch(IOException e){throw new UncheckedIOException(e);}
            }
        }
        return result;
    }

    private static boolean matchesUnresolved(Set<String> unresolved,Set<String> exports){
        for(String target:unresolved)for(String exported:exports)
            if(target.equals(exported)||target.startsWith(exported+".")||exported.startsWith(target+"."))return true;
        return false;
    }

    private static byte[] encode(FileInput value)throws IOException{
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            writeString(out,value.contentHash());writeString(out,value.apiFingerprint());
            writePaths(out,value.dependencies());writeStrings(out,value.exportedNames());writeStrings(out,value.unresolvedTargets());
        }
        return bytes.toByteArray();
    }
    private static Stored decode(byte[] value)throws IOException{
        try(var in=new DataInputStream(new ByteArrayInputStream(value))){
            String content=readString(in),api=readString(in);
            var dependencies=readPaths(in);var exports=readStrings(in);var unresolved=readStrings(in);
            if(in.available()!=0)throw new IOException("Trailing semantic state");
            return new Stored(content,api,dependencies,exports,unresolved);
        }
    }
    private static void writePaths(DataOutputStream out,Set<Path> values)throws IOException{
        var strings=values.stream().map(Path::toString).sorted().toList();writeStrings(out,new LinkedHashSet<>(strings));
    }
    private static Set<Path> readPaths(DataInputStream in)throws IOException{
        var result=new LinkedHashSet<Path>();for(String value:readStrings(in))result.add(Path.of(value));return Set.copyOf(result);
    }
    private static void writeStrings(DataOutputStream out,Set<String> values)throws IOException{
        var sorted=new ArrayList<>(values);Collections.sort(sorted);out.writeInt(sorted.size());for(String value:sorted)writeString(out,value);
    }
    private static Set<String> readStrings(DataInputStream in)throws IOException{
        int count=in.readInt();if(count<0||count>1_000_000)throw new IOException("Invalid semantic collection");
        var result=new LinkedHashSet<String>();for(int i=0;i<count;i++)result.add(readString(in));return Set.copyOf(result);
    }
    private static void writeString(DataOutputStream out,String value)throws IOException{
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
    }
    private static String readString(DataInputStream in)throws IOException{
        int length=in.readInt();if(length<0||length>16*1024*1024)throw new IOException("Invalid semantic string");
        byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw new EOFException();return new String(bytes,StandardCharsets.UTF_8);
    }
    private static byte[] fileKey(String moduleKey,Path path){return bytes("S|"+moduleKey+"|"+path.toAbsolutePath().normalize());}
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static String text(byte[] value){return new String(value,StandardCharsets.UTF_8);}
    private static String textOrNull(byte[] value){return value==null?null:text(value);}
    private static boolean startsWith(byte[] value,byte[] prefix){
        if(value.length<prefix.length)return false;for(int i=0;i<prefix.length;i++)if(value[i]!=prefix[i])return false;return true;
    }

    @Override public void close(){db.close();options.close();}
}
