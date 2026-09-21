package dev.jvmd.index.rocks;

import dev.jvmd.core.Hashing;
import dev.jvmd.index.FileSemanticContribution;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.rocksdb.*;

/** Persistent invalidation derived from canonical per-file semantic contributions. */
public final class RocksSemanticInvalidation implements AutoCloseable {
    public record Result(Set<Path> reanalyze,Set<Path> apiChanged,Set<Path> bodyOnly,Set<Path> deleted,boolean contextChanged) { }

    static {RocksDB.loadLibrary();}
    private final Options options;
    private final RocksDB db;

    public RocksSemanticInvalidation(Path root)throws Exception{this(root,null);}

    RocksSemanticInvalidation(Path root,RocksMemory memory)throws Exception{
        Path path=root.toAbsolutePath().normalize();Files.createDirectories(path);
        options=memory==null?new Options().setCreateIfMissing(true).setMaxOpenFiles(64):memory.options(64);
        db=RocksDB.open(options,path.toString());
    }

    public synchronized Result observeFile(String moduleId,String contextFingerprint,FileSemanticContribution contribution)throws Exception{
        Objects.requireNonNull(contribution);
        String moduleKey=moduleKey(moduleId);
        var prior=load(moduleKey);
        var current=new TreeMap<>(prior);current.put(contribution.file(),contribution);
        var result=update(moduleKey,contextFingerprint,prior,current);
        var invalid=new LinkedHashSet<>(result.reanalyze());invalid.remove(contribution.file());
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
        String moduleKey=moduleKey(moduleId);
        var prior=load(moduleKey);var current=new TreeMap<>(prior);
        var normalized=deleted.stream().map(path->path.toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toSet());
        current.keySet().removeAll(normalized);
        String context=textOrNull(db.get(bytes("C|"+moduleKey)));
        if(context==null)return new Result(Set.of(),Set.of(),Set.of(),Set.of(),false);
        var result=update(moduleKey,context,prior,current);invalidate(result.reanalyze());return result;
    }

    public synchronized Result update(String moduleId,String contextFingerprint,Collection<FileSemanticContribution> input)throws Exception{
        Objects.requireNonNull(moduleId);Objects.requireNonNull(contextFingerprint);
        var current=new TreeMap<Path,FileSemanticContribution>();
        for(var contribution:input){
            var previous=current.put(contribution.file(),contribution);
            if(previous!=null)throw new IllegalArgumentException("Duplicate semantic contribution: "+contribution.file());
        }
        String moduleKey=moduleKey(moduleId);
        return update(moduleKey,contextFingerprint,load(moduleKey),current);
    }

    private Result update(String moduleKey,String contextFingerprint,Map<Path,FileSemanticContribution> prior,
                          Map<Path,FileSemanticContribution> current)throws Exception{
        String priorContext=textOrNull(db.get(bytes("C|"+moduleKey)));
        boolean contextChanged=priorContext!=null&&!priorContext.equals(contextFingerprint);

        var apiChanged=new LinkedHashSet<Path>();var bodyOnly=new LinkedHashSet<Path>();var deleted=new LinkedHashSet<Path>();
        for(var entry:current.entrySet()){
            var old=prior.get(entry.getKey());var now=entry.getValue();
            if(old==null){apiChanged.add(entry.getKey());continue;}
            if(old.sourceHash().equals(now.sourceHash())&&old.apiFingerprint().equals(now.apiFingerprint()))continue;
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
                var old=prior.get(changed);if(old!=null)changedExports.addAll(old.exportedNames());
                var now=current.get(changed);if(now!=null)changedExports.addAll(now.exportedNames());
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

    private Map<Path,FileSemanticContribution> load(String moduleKey){
        byte[] prefix=bytes("S|"+moduleKey+"|");var result=new TreeMap<Path,FileSemanticContribution>();
        try(var read=new ReadOptions();var iterator=db.newIterator(read)){
            for(iterator.seek(prefix);iterator.isValid();iterator.next()){
                if(!startsWith(iterator.key(),prefix))break;
                Path path=Path.of(text(iterator.key()).substring(("S|"+moduleKey+"|").length()));
                try{result.put(path,decode(path,iterator.value()));}catch(IOException e){throw new UncheckedIOException(e);}
            }
        }
        return result;
    }

    private static boolean matchesUnresolved(Set<String> unresolved,Set<String> exports){
        for(String target:unresolved)for(String exported:exports)
            if(target.equals(exported)||target.startsWith(exported+".")||exported.startsWith(target+"."))return true;
        return false;
    }

    private static byte[] encode(FileSemanticContribution value)throws IOException{
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            writeString(out,value.sourceHash());writeString(out,value.apiFingerprint());
            writePaths(out,value.dependencies());writeStrings(out,value.exportedNames());writeStrings(out,value.unresolvedTargets());
        }
        return bytes.toByteArray();
    }

    private static FileSemanticContribution decode(Path file,byte[] value)throws IOException{
        try(var in=new DataInputStream(new ByteArrayInputStream(value))){
            String source=readString(in),api=readString(in);
            var dependencies=readPaths(in);var exports=readStrings(in);var unresolved=readStrings(in);
            if(in.available()!=0)throw new IOException("Trailing semantic state");
            return new FileSemanticContribution(file,source,api,dependencies,exports,unresolved);
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
    private static String moduleKey(String moduleId){return Hashing.sha256(Objects.requireNonNull(moduleId).getBytes(StandardCharsets.UTF_8));}
    private static byte[] fileKey(String moduleKey,Path path){return bytes("S|"+moduleKey+"|"+path.toAbsolutePath().normalize());}
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static String text(byte[] value){return new String(value,StandardCharsets.UTF_8);}
    private static String textOrNull(byte[] value){return value==null?null:text(value);}
    private static boolean startsWith(byte[] value,byte[] prefix){
        if(value.length<prefix.length)return false;for(int i=0;i<prefix.length;i++)if(value[i]!=prefix[i])return false;return true;
    }

    @Override public void close(){db.close();options.close();}
}
