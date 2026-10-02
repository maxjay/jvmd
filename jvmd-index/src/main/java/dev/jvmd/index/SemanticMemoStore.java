package dev.jvmd.index;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;

/**
 * LOCAL semantic memo store (architecture §68–81, §106–107).
 *
 * Each record is an independently valid memoised computation:
 * <pre>
 *   static semantic context (function id + version + static inputs)
 *   + dynamic restart-stable dependency certificate
 *   + coverage / completeness
 *   + semantic result (PRESENT bytes or established ABSENT)
 *   + physical integrity (SHA-256 over the canonical record bytes)
 * </pre>
 * There is no transactional generation: every lookup validates its own record. The directory
 * layout is management metadata only. A missing, stale, unvalidated or corrupt record is a miss,
 * which is UNKNOWN — never ABSENT. A persisted ABSENT result is an explicit negative record.
 */
public final class SemanticMemoStore {
    /** Physical record format version; distinct from any function's semantic version (§81). */
    public static final int FORMAT_VERSION=1;
    private static final byte[] MAGIC="JVMDMEMO".getBytes(StandardCharsets.US_ASCII);

    /** Semantic computation identity. Bump {@code version} when extraction, canonicalisation or dependency recording changes. */
    public record Function(String name,int version) {
        public Function {
            Objects.requireNonNull(name);if(name.isBlank()||name.contains("/"))throw new IllegalArgumentException("function name");
            if(version<1)throw new IllegalArgumentException("function version");
        }
    }

    /**
     * Static memo key {@code K_s = H(FunctionId, FunctionVersion, StaticInputs)} (§70). Static inputs
     * must bind everything the in-process owner holds fixed (§72): language mode, platform,
     * compile context, classpath context, processors. Callers pass canonical values only.
     */
    public record StaticKey(Function function,Hash256 staticInputs) {
        public StaticKey { Objects.requireNonNull(function);Objects.requireNonNull(staticInputs); }
        public static StaticKey of(Function function,Object... staticInputs){
            return new StaticKey(function,CanonicalDigestWriter.digest("semantic-memo-static-inputs-v1",staticInputs));
        }
        public Hash256 identity(){
            return CanonicalDigestWriter.digest("semantic-memo-static-key-v1",function.name(),function.version(),staticInputs);
        }
    }

    /** How the certificate covers what the computation read (§73–75). */
    public enum Coverage {
        /** Every dynamic input is a precise dependency in the certificate. Coarse certificates are not representable. */
        PRECISE
    }

    /**
     * Dynamic dependency certificate {@code D = {(k_i, I_i)}}. Every identity is established evidence;
     * UNKNOWN cannot be represented. Keys must pass {@link PersistableProofKeys#requirePersistable}.
     */
    public record Certificate(QueryProof dependencies) {
        public Certificate {
            Objects.requireNonNull(dependencies);
            for(var dependency:dependencies.dependencies())PersistableProofKeys.requirePersistable(dependency.key());
        }
        public static Certificate empty(){return new Certificate(QueryProof.empty());}
        public Hash256 identity(){return dependencies.identity();}
    }

    /** A reusable memoised result: PRESENT(bytes) or an established ABSENT. */
    public sealed interface Result {
        record Present(byte[] value) implements Result {
            public Present { value=Objects.requireNonNull(value).clone(); }
            @Override public byte[] value(){return value.clone();}
            @Override public boolean equals(Object other){return other instanceof Present present&&Arrays.equals(value,present.value);}
            @Override public int hashCode(){return Arrays.hashCode(value);}
        }
        record Absent() implements Result { }
        static Result present(byte[] value){return new Present(value);}
        static Result absent(){return new Absent();}
    }

    public record MemoRecord(StaticKey key,Certificate certificate,Coverage coverage,SemanticCompleteness completeness,Result result) {
        public MemoRecord {
            Objects.requireNonNull(key);Objects.requireNonNull(certificate);Objects.requireNonNull(coverage);
            Objects.requireNonNull(completeness);Objects.requireNonNull(result);
            // UNKNOWN semantic results are never memoised (§8).
            if(completeness==SemanticCompleteness.UNKNOWN)throw new IllegalArgumentException("UNKNOWN results are not memo state");
        }
    }

    /** Current identity of a dependency key; empty is UNKNOWN and invalidates reuse. */
    @FunctionalInterface
    public interface CurrentIdentities {
        Optional<Hash256> current(QueryProof.Key key)throws Exception;
    }

    public sealed interface Lookup {
        record Hit(MemoRecord record) implements Lookup { }
        /** No reusable record; this is UNKNOWN, never a negative semantic answer. */
        record Miss(String reason) implements Lookup { }
    }

    private final Path root;
    private final int maxVariants;
    private long hits,misses,staleCertificates,unknownDependencies,corrupt,writes,bytesWritten,bytesRead,evictions;

    public SemanticMemoStore(Path root){this(root,4);}
    /** {@code maxVariants} bounds the certificates retained per static key (§77). */
    public SemanticMemoStore(Path root,int maxVariants){
        this.root=Objects.requireNonNull(root).toAbsolutePath().normalize();
        if(maxVariants<1)throw new IllegalArgumentException("maxVariants");this.maxVariants=maxVariants;
    }
    public Path root(){return root;}

    /**
     * Find a record whose static key matches and whose every certificate dependency is currently
     * established equal. Completeness is returned exactly as persisted.
     *
     * The store's monitor guards only file access and counters, never {@code current}: resolving a
     * dependency may take other locks or do real work (restoring or attributing a dependency), and
     * a writer on another thread must be able to publish meanwhile.
     */
    public Lookup lookup(StaticKey key,CurrentIdentities current)throws Exception{
        Objects.requireNonNull(key);Objects.requireNonNull(current);
        Path directory=directory(key);
        List<Path> variants;
        synchronized(this){
            try(var stream=Files.list(directory)){variants=stream.filter(path->path.getFileName().toString().endsWith(".memo")).sorted().toList();}
            catch(NoSuchFileException|NotDirectoryException missing){misses++;return new Lookup.Miss("no-record");}
        }
        String reason="no-valid-variant";
        variant:
        for(Path path:variants){
            MemoRecord record;
            synchronized(this){
                try{
                    byte[] bytes=Files.readAllBytes(path);bytesRead+=bytes.length;
                    record=decode(bytes);
                }catch(NoSuchFileException evicted){continue;}
                catch(IOException|RuntimeException invalid){
                    corrupt++;reason="corrupt";deleteQuietly(path);continue;
                }
                if(!record.key().equals(key)){corrupt++;reason="foreign-key";deleteQuietly(path);continue;}
            }
            for(var dependency:record.certificate().dependencies().dependencies()){
                var now=current.current(dependency.key());
                if(now==null||now.isEmpty()){synchronized(this){unknownDependencies++;}reason="unknown-dependency:"+dependency.key().domain();continue variant;}
                if(!now.get().equals(dependency.identity())){synchronized(this){staleCertificates++;}reason="stale-dependency:"+dependency.key().domain()+":"+dependency.key().value();continue variant;}
            }
            synchronized(this){
                try{Files.setLastModifiedTime(path,FileTime.fromMillis(System.currentTimeMillis()));}catch(IOException ignored){}
                hits++;
            }
            return new Lookup.Hit(record);
        }
        synchronized(this){misses++;}
        return new Lookup.Miss(reason);
    }

    /** Publish one record atomically. Loss or failure only causes a later miss (§80). */
    public synchronized void put(MemoRecord record)throws IOException{
        Objects.requireNonNull(record);
        Path directory=Files.createDirectories(directory(record.key()));
        byte[] bytes=encode(record);
        Path target=directory.resolve(record.certificate().identity().hex()+".memo");
        Path temporary=directory.resolve(target.getFileName()+".tmp-"+ProcessHandle.current().pid()+"-"+System.nanoTime());
        Files.write(temporary,bytes);
        try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        writes++;bytesWritten+=bytes.length;
        boundVariants(directory);
    }

    /** Remove every variant of one static key (management only). */
    public synchronized void invalidate(StaticKey key)throws IOException{
        Path directory=directory(key);if(!Files.isDirectory(directory))return;
        try(var stream=Files.list(directory)){for(Path path:stream.toList())deleteQuietly(path);}
        deleteQuietly(directory);
    }

    /** LRU disk budgeting over record access times; management metadata, not semantic authority (§79). */
    public synchronized long garbageCollect(long maxBytes)throws IOException{
        if(!Files.isDirectory(root))return 0;
        record Entry(Path path,long size,long accessed){}
        var entries=new ArrayList<Entry>();long total=0;
        try(var walk=Files.walk(root)){
            for(Path path:walk.filter(Files::isRegularFile).toList()){
                long size=Files.size(path);total+=size;
                entries.add(new Entry(path,size,Files.getLastModifiedTime(path).toMillis()));
            }
        }
        entries.sort(Comparator.comparingLong(Entry::accessed).thenComparing(entry->entry.path().toString()));
        long removed=0;
        for(var entry:entries){
            if(total<=maxBytes)break;
            deleteQuietly(entry.path());total-=entry.size();removed++;evictions++;
        }
        return removed;
    }

    public synchronized Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        result.put("root",root.toString());result.put("format_version",FORMAT_VERSION);
        result.put("memo_hits",hits);result.put("memo_misses",misses);
        result.put("memo_validation_failures",staleCertificates+unknownDependencies+corrupt);
        result.put("memo_stale_certificates",staleCertificates);result.put("memo_unknown_dependencies",unknownDependencies);
        result.put("memo_corrupt_records",corrupt);result.put("memo_writes",writes);
        result.put("bytes_persisted",bytesWritten);result.put("bytes_read",bytesRead);result.put("memo_evictions",evictions);
        return Collections.unmodifiableMap(result);
    }

    private Path directory(StaticKey key){
        String hex=key.identity().hex();
        return root.resolve(key.function().name()).resolve(hex.substring(0,2)).resolve(hex);
    }
    private void boundVariants(Path directory)throws IOException{
        List<Path> variants;
        try(var stream=Files.list(directory)){variants=new ArrayList<>(stream.filter(path->path.getFileName().toString().endsWith(".memo")).toList());}
        if(variants.size()<=maxVariants)return;
        variants.sort(Comparator.comparingLong((Path path)->{
            try{return Files.getLastModifiedTime(path).toMillis();}catch(IOException missing){return Long.MIN_VALUE;}
        }).thenComparing(Path::toString));
        for(int i=0;i<variants.size()-maxVariants;i++){deleteQuietly(variants.get(i));evictions++;}
    }
    private static void deleteQuietly(Path path){try{Files.deleteIfExists(path);}catch(IOException ignored){}}

    // ---- canonical encoding: big-endian DataOutput, explicit widths, SHA-256 trailer ----

    static byte[] encode(MemoRecord record)throws IOException{
        var body=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(body)){
            out.write(MAGIC);out.writeInt(FORMAT_VERSION);
            out.writeUTF(record.key().function().name());out.writeInt(record.key().function().version());
            out.write(record.key().staticInputs().bytes());
            out.writeByte(record.coverage().ordinal());out.writeByte(record.completeness().ordinal());
            var dependencies=record.certificate().dependencies().dependencies();
            out.writeInt(dependencies.size());
            for(var dependency:dependencies){
                out.writeUTF(dependency.key().domain().name());writeString(out,dependency.key().value());
                out.write(dependency.identity().bytes());
            }
            switch(record.result()){
                case Result.Absent ignored -> out.writeByte(0);
                case Result.Present present -> {byte[] value=present.value();out.writeByte(1);out.writeInt(value.length);out.write(value);}
            }
        }
        byte[] payload=body.toByteArray();
        byte[] digest=sha256(payload);
        byte[] result=Arrays.copyOf(payload,payload.length+digest.length);
        System.arraycopy(digest,0,result,payload.length,digest.length);
        return result;
    }

    static MemoRecord decode(byte[] bytes)throws IOException{
        if(bytes.length<MAGIC.length+4+32)throw new IOException("truncated memo");
        byte[] payload=Arrays.copyOf(bytes,bytes.length-32);
        byte[] expected=Arrays.copyOfRange(bytes,bytes.length-32,bytes.length);
        if(!MessageDigest.isEqual(sha256(payload),expected))throw new IOException("memo integrity check failed");
        try(var in=new DataInputStream(new ByteArrayInputStream(payload))){
            byte[] magic=in.readNBytes(MAGIC.length);
            if(!Arrays.equals(magic,MAGIC))throw new IOException("foreign memo");
            if(in.readInt()!=FORMAT_VERSION)throw new IOException("unsupported memo format");
            var function=new Function(in.readUTF(),in.readInt());
            var key=new StaticKey(function,new Hash256(in.readNBytes(32)));
            var coverage=Coverage.values()[in.readUnsignedByte()];
            var completeness=SemanticCompleteness.values()[in.readUnsignedByte()];
            int count=in.readInt();if(count<0||count>1_000_000)throw new IOException("dependency count");
            var dependencies=new ArrayList<QueryProof.Dependency>(count);
            for(int i=0;i<count;i++){
                var domain=QueryProof.Domain.valueOf(in.readUTF());String value=readString(in);
                dependencies.add(new QueryProof.Dependency(domain,value,new Hash256(in.readNBytes(32))));
            }
            Result result=switch(in.readUnsignedByte()){
                case 0 -> Result.absent();
                case 1 -> {int length=in.readInt();if(length<0||length>in.available())throw new IOException("result length");yield Result.present(in.readNBytes(length));}
                default -> throw new IOException("result tag");
            };
            if(in.available()!=0)throw new IOException("trailing memo bytes");
            return new MemoRecord(key,new Certificate(new QueryProof(dependencies)),coverage,completeness,result);
        }
    }
    private static void writeString(DataOutputStream out,String value)throws IOException{
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
    }
    private static String readString(DataInputStream in)throws IOException{
        int length=in.readInt();if(length<0||length>in.available())throw new IOException("string length");
        return new String(in.readNBytes(length),StandardCharsets.UTF_8);
    }
    private static byte[] sha256(byte[] value){
        try{return MessageDigest.getInstance("SHA-256").digest(value);}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
