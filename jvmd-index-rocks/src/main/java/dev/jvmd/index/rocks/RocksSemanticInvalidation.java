package dev.jvmd.index.rocks;

import dev.jvmd.core.Hashing;
import dev.jvmd.index.FileSemanticContribution;
import dev.jvmd.index.SemanticUpdatePolicy;
import dev.jvmd.index.SemanticUpdatePolicy.Result;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.rocksdb.*;

/**
 * Persistent semantic invalidation derived from canonical per-file semantic contributions.
 *
 * <p>Per-file observations use persisted reverse-dependency and unresolved-name postings. A
 * module-wide contribution scan is reserved for one-time postings migration and genuine compiler
 * context changes.
 */
public final class RocksSemanticInvalidation implements AutoCloseable {

    private static final byte[] EMPTY=new byte[0],POSTING_SCHEMA=bytes("2");
    static {RocksDB.loadLibrary();}
    private final Options options;
    private final RocksDB db;
    private long contributionReads,moduleScans,reversePostingReads,unresolvedPostingReads,postingWrites,migrations,migrationFiles;

    public RocksSemanticInvalidation(Path root)throws Exception{this(root,null);}

    RocksSemanticInvalidation(Path root,RocksMemory memory)throws Exception{
        Path path=root.toAbsolutePath().normalize();Files.createDirectories(path);
        options=memory==null?new Options().setCreateIfMissing(true).setMaxOpenFiles(64):memory.options(64);
        db=RocksDB.open(options,path.toString());
    }

    public synchronized Result observeFile(String moduleId,String contextFingerprint,FileSemanticContribution contribution)throws Exception{
        Objects.requireNonNull(contextFingerprint);Objects.requireNonNull(contribution);
        String moduleKey=moduleKey(moduleId);ensurePostings(moduleKey);
        FileSemanticContribution old=contribution(moduleKey,contribution.file());
        String priorContext=textOrNull(db.get(contextKey(moduleKey)));
        boolean contextChanged=priorContext!=null&&!priorContext.equals(contextFingerprint);

        var result=SemanticUpdatePolicy.decide(List.of(new SemanticUpdatePolicy.Change(old,contribution)),
                contextChanged?SemanticUpdatePolicy.EnvironmentTransition.UNKNOWN:SemanticUpdatePolicy.EnvironmentTransition.NONE,
                postings(moduleKey));

        var invalid=new LinkedHashSet<>(result.reanalyze());invalid.remove(contribution.file());
        if(!Objects.equals(old,contribution)||!Objects.equals(priorContext,contextFingerprint))
            writeContribution(moduleKey,old,contribution,contextFingerprint,invalid);
        return result;
    }

    public synchronized long revision(Path file)throws Exception{
        byte[] value=db.get(bytes("I|"+file.toAbsolutePath().normalize()));
        return value==null?0:java.nio.ByteBuffer.wrap(value).getLong();
    }

    private void invalidate(WriteBatch batch,Set<Path> files)throws Exception{
        for(Path file:files)batch.put(bytes("I|"+file.toAbsolutePath().normalize()),java.nio.ByteBuffer.allocate(8).putLong(revision(file)+1).array());
    }

    public synchronized Result removeFiles(String moduleId,Set<Path> requested)throws Exception{
        String moduleKey=moduleKey(moduleId);ensurePostings(moduleKey);
        String context=textOrNull(db.get(contextKey(moduleKey)));
        if(context==null)return new Result(Set.of(),Set.of(),Set.of(),Set.of(),false,false);

        var deleted=new LinkedHashSet<Path>();var oldValues=new LinkedHashMap<Path,FileSemanticContribution>();
        for(Path value:requested){
            Path file=value.toAbsolutePath().normalize();var old=contribution(moduleKey,file);
            if(old!=null){deleted.add(file);oldValues.put(file,old);}
        }
        if(deleted.isEmpty())return new Result(Set.of(),Set.of(),Set.of(),Set.of(),false,false);

        var result=SemanticUpdatePolicy.decide(oldValues.values().stream().map(value->new SemanticUpdatePolicy.Change(value,null)).toList(),false,postings(moduleKey));

        try(var batch=new WriteBatch();var write=new WriteOptions()){
            for(var entry:oldValues.entrySet()){
                removePostings(batch,moduleKey,entry.getValue());
                batch.delete(fileKey(moduleKey,entry.getKey()));
            }
            invalidate(batch,result.reanalyze());db.write(write,batch);
        }
        return result;
    }

    private SemanticUpdatePolicy.Postings postings(String moduleKey){
        return new SemanticUpdatePolicy.Postings(){
            public Set<Path> dependants(Path file){return postingFiles(reversePrefix(moduleKey,file),true);}
            public Set<Path> unresolved(Set<String> exports){return unresolvedWaiters(moduleKey,exports);}
            public Set<Path> files(){return moduleFiles(moduleKey);}
        };
    }

    private Set<Path> unresolvedWaiters(String moduleKey,Set<String> exports){
        if(exports.isEmpty())return Set.of();
        var result=new LinkedHashSet<Path>(postingFiles(unresolvedExactPrefix(moduleKey,"*"),false));
        for(String exported:exports){
            if(exported.isBlank())continue;
            result.addAll(postingFiles(unresolvedDescendantPrefix(moduleKey,exported),false));
            for(String prefix:dottedPrefixes(exported))
                result.addAll(postingFiles(unresolvedExactPrefix(moduleKey,prefix),false));
        }
        return Set.copyOf(result);
    }

    private void writeContribution(String moduleKey,FileSemanticContribution old,FileSemanticContribution value,String contextFingerprint,Set<Path> invalid)throws Exception{
        try(var batch=new WriteBatch();var write=new WriteOptions()){
            if(old!=null)removePostings(batch,moduleKey,old);
            addPostings(batch,moduleKey,value);
            batch.put(fileKey(moduleKey,value.file()),encode(value));
            batch.put(contextKey(moduleKey),bytes(contextFingerprint));
            invalidate(batch,invalid);db.write(write,batch);
        }
    }

    private void addPostings(WriteBatch batch,String moduleKey,FileSemanticContribution value)throws RocksDBException{
        for(Path dependency:value.dependencies()){batch.put(reverseKey(moduleKey,dependency,value.file()),EMPTY);postingWrites++;}
        for(String target:value.unresolvedTargets()){
            batch.put(unresolvedExactKey(moduleKey,target,value.file()),EMPTY);postingWrites++;
            for(String prefix:dottedPrefixes(target)){batch.put(unresolvedDescendantKey(moduleKey,prefix,value.file()),EMPTY);postingWrites++;}
        }
    }

    private void removePostings(WriteBatch batch,String moduleKey,FileSemanticContribution value)throws RocksDBException{
        for(Path dependency:value.dependencies()){batch.delete(reverseKey(moduleKey,dependency,value.file()));postingWrites++;}
        for(String target:value.unresolvedTargets()){
            batch.delete(unresolvedExactKey(moduleKey,target,value.file()));postingWrites++;
            for(String prefix:dottedPrefixes(target)){batch.delete(unresolvedDescendantKey(moduleKey,prefix,value.file()));postingWrites++;}
        }
    }

    private FileSemanticContribution contribution(String moduleKey,Path file)throws Exception{
        contributionReads++;byte[] value=db.get(fileKey(moduleKey,file));
        return value==null?null:decode(file.toAbsolutePath().normalize(),value);
    }

    /** Existing stores migrate once; ordinary observations never rebuild these postings. */
    private void ensurePostings(String moduleKey)throws Exception{
        if(Arrays.equals(db.get(schemaKey(moduleKey)),POSTING_SCHEMA))return;
        moduleScans++;migrations++;
        byte[] prefix=bytes("S|"+moduleKey+"|");
        try(var read=new ReadOptions();var iterator=db.newIterator(read)){
            for(iterator.seek(prefix);iterator.isValid();iterator.next()){
                if(!startsWith(iterator.key(),prefix))break;
                Path file=Path.of(text(iterator.key()).substring(("S|"+moduleKey+"|").length()));
                var value=decode(file,iterator.value());migrationFiles++;
                try(var batch=new WriteBatch();var write=new WriteOptions()){
                    addPostings(batch,moduleKey,value);db.write(write,batch);
                }
            }
        }
        db.put(schemaKey(moduleKey),POSTING_SCHEMA);
    }

    private Set<Path> moduleFiles(String moduleKey){
        moduleScans++;byte[] prefix=bytes("S|"+moduleKey+"|");var result=new LinkedHashSet<Path>();
        try(var read=new ReadOptions();var iterator=db.newIterator(read)){
            for(iterator.seek(prefix);iterator.isValid();iterator.next()){
                if(!startsWith(iterator.key(),prefix))break;
                result.add(Path.of(text(iterator.key()).substring(("S|"+moduleKey+"|").length())));
            }
        }
        return Set.copyOf(result);
    }

    private Set<Path> postingFiles(byte[] prefix,boolean reverse){
        if(reverse)reversePostingReads++;else unresolvedPostingReads++;
        var result=new LinkedHashSet<Path>();String textPrefix=text(prefix);
        try(var read=new ReadOptions();var iterator=db.newIterator(read)){
            for(iterator.seek(prefix);iterator.isValid();iterator.next()){
                if(!startsWith(iterator.key(),prefix))break;
                result.add(Path.of(text(iterator.key()).substring(textPrefix.length())));
            }
        }
        return Set.copyOf(result);
    }

    public synchronized Map<String,Object> status(){
        return Map.of("contribution_reads",contributionReads,"module_scans",moduleScans,
                "reverse_posting_reads",reversePostingReads,"unresolved_posting_reads",unresolvedPostingReads,
                "posting_writes",postingWrites,"migrations",migrations,"migration_files",migrationFiles);
    }

    private static List<String> dottedPrefixes(String value){
        var result=new ArrayList<String>();int from=0;
        while(true){int dot=value.indexOf('.',from);if(dot<0)break;if(dot>0)result.add(value.substring(0,dot));from=dot+1;}
        result.add(value);return result;
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
    private static String identity(String value){return Hashing.sha256(bytes(value));}
    private static byte[] fileKey(String moduleKey,Path path){return bytes("S|"+moduleKey+"|"+path.toAbsolutePath().normalize());}
    private static byte[] contextKey(String moduleKey){return bytes("C|"+moduleKey);}
    private static byte[] schemaKey(String moduleKey){return bytes("V|"+moduleKey);}
    private static byte[] reversePrefix(String moduleKey,Path dependency){return bytes("R|"+moduleKey+"|"+identity(dependency.toAbsolutePath().normalize().toString())+"|");}
    private static byte[] reverseKey(String moduleKey,Path dependency,Path dependant){return append(reversePrefix(moduleKey,dependency),dependant.toAbsolutePath().normalize().toString());}
    private static byte[] unresolvedExactPrefix(String moduleKey,String target){return bytes("U|"+moduleKey+"|"+identity(target)+"|");}
    private static byte[] unresolvedExactKey(String moduleKey,String target,Path file){return append(unresolvedExactPrefix(moduleKey,target),file.toAbsolutePath().normalize().toString());}
    private static byte[] unresolvedDescendantPrefix(String moduleKey,String prefix){return bytes("W|"+moduleKey+"|"+identity(prefix)+"|");}
    private static byte[] unresolvedDescendantKey(String moduleKey,String prefix,Path file){return append(unresolvedDescendantPrefix(moduleKey,prefix),file.toAbsolutePath().normalize().toString());}
    private static byte[] append(byte[] prefix,String suffix){
        byte[] tail=bytes(suffix),value=Arrays.copyOf(prefix,prefix.length+tail.length);System.arraycopy(tail,0,value,prefix.length,tail.length);return value;
    }
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static String text(byte[] value){return new String(value,StandardCharsets.UTF_8);}
    private static String textOrNull(byte[] value){return value==null?null:text(value);}
    private static boolean startsWith(byte[] value,byte[] prefix){
        if(value.length<prefix.length)return false;for(int i=0;i<prefix.length;i++)if(value[i]!=prefix[i])return false;return true;
    }

    @Override public void close(){db.close();options.close();}
}
