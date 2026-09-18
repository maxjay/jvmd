package dev.jvmd.index.rocks;

import dev.jvmd.index.ArtifactIndexFormat;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.rocksdb.*;

/**
 * Immutable, content-addressed artifact generations backed by externally built RocksDB SST files.
 *
 * Different cache keys build SSTs concurrently. Ingestion is serialized, and publication of the
 * manifest plus all artifact-local indexes occurs in the same SST.
 */
public final class RocksArtifactRepository implements AutoCloseable {
    public record Publication(String cacheKey,boolean reused,long symbols,long relationships,long classReferences,long storageBytes) { }

    private static final byte[] EMPTY=new byte[0];
    static {RocksDB.loadLibrary();}

    private final Path root;
    private final Path dbPath;
    private final Path staging;
    private final Options options;
    private final RocksDB db;
    private final ConcurrentHashMap<String,Object> artifactLocks=new ConcurrentHashMap<>();
    private final Object ingestLock=new Object();
    private final AtomicLong published=new AtomicLong(),reused=new AtomicLong();
    private final AtomicInteger buildsInFlight=new AtomicInteger(),peakBuilds=new AtomicInteger();

    public RocksArtifactRepository(Path root)throws Exception{
        this.root=root.toAbsolutePath().normalize();
        this.dbPath=this.root.resolve("db");
        this.staging=this.root.resolve("staging");
        Files.createDirectories(dbPath);Files.createDirectories(staging);
        cleanupStaging();
        this.options=new Options().setCreateIfMissing(true).setMaxOpenFiles(128);
        this.db=RocksDB.open(options,dbPath.toString());
    }

    public Publication publish(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception{
        String cacheKey=facts.key().cacheKey();
        Object artifactLock=artifactLocks.computeIfAbsent(cacheKey,ignored->new Object());
        try{
            synchronized(artifactLock){
                if(contains(cacheKey)){reused.incrementAndGet();return result(cacheKey,true,facts,classReferences);}
                Path sst=staging.resolve(cacheKey+"-"+UUID.randomUUID()+".sst.tmp");
                int active=buildsInFlight.incrementAndGet();peakBuilds.accumulateAndGet(active,Math::max);
                try{
                    writeSst(sst,cacheKey,facts,classReferences);
                    try(var file=FileChannel.open(sst,StandardOpenOption.WRITE)){file.force(true);}
                    synchronized(ingestLock){
                        if(contains(cacheKey)){reused.incrementAndGet();return result(cacheKey,true,facts,classReferences);}
                        try(var ingest=new IngestExternalFileOptions().setMoveFiles(true)){
                            db.ingestExternalFile(List.of(sst.toString()),ingest);
                        }
                        if(!verify(cacheKey))throw new IOException("RocksDB publication verification failed: "+cacheKey);
                        published.incrementAndGet();
                    }
                    return result(cacheKey,false,facts,classReferences);
                }finally{
                    buildsInFlight.decrementAndGet();
                    Files.deleteIfExists(sst);
                }
            }
        }finally{
            artifactLocks.remove(cacheKey,artifactLock);
        }
    }

    private Publication result(String cacheKey,boolean wasReused,ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception{
        return new Publication(cacheKey,wasReused,facts.symbols().size(),facts.relationships().size(),classReferences.size(),storageBytes());
    }

    public boolean contains(String cacheKey)throws Exception{return db.get(key(cacheKey,"0|manifest"))!=null;}

    public boolean verify(String cacheKey)throws Exception{
        byte[] manifestBytes=db.get(key(cacheKey,"0|manifest")),artifactBytes=db.get(key(cacheKey,"1|artifact"));
        if(manifestBytes==null||artifactBytes==null)return false;
        var manifest=parseManifest(manifestBytes);
        var data=ArtifactIndexFormat.decode(artifactBytes);
        if(!cacheKey.equals(data.key().cacheKey()))return false;
        if(!Objects.equals(manifest.get("format"),Integer.toString(data.key().formatVersion())))return false;
        if(!Objects.equals(manifest.get("indexer"),data.key().indexerVersion()))return false;
        if(!Objects.equals(manifest.get("runtime"),Integer.toString(data.key().runtimeFeature())))return false;
        if(!Objects.equals(manifest.get("mode"),data.key().mode()))return false;
        if(!Objects.equals(manifest.get("symbols"),Integer.toString(data.symbols().size())))return false;
        if(!Objects.equals(manifest.get("relationships"),Integer.toString(data.relationships().size())))return false;
        long classReferences=countPrefix(key(cacheKey,"6|class|"));
        return Objects.equals(manifest.get("class_references"),Long.toString(classReferences));
    }

    public ArtifactIndexFormat.ArtifactData artifact(String cacheKey)throws Exception{
        byte[] encoded=db.get(key(cacheKey,"1|artifact"));
        return encoded==null?null:ArtifactIndexFormat.decode(encoded);
    }

    public Integer binaryId(String cacheKey,String binaryKey)throws Exception{
        byte[] value=db.get(key(cacheKey,"2|binary|"+binaryKey));
        return value==null?null:ByteBuffer.wrap(value).getInt();
    }

    public List<Integer> nameIds(String cacheKey,String namePrefix,int limit){return idsByPrefix(cacheKey,"3|name|"+namePrefix,limit);}
    public List<Integer> reverseSources(String cacheKey,String target,String kind,int limit){return idsByPrefix(cacheKey,"5|reverse|"+target+"|"+kind+"|",limit);}

    public List<ArtifactIndexFormat.Relationship> outgoing(String cacheKey,int sourceId,Set<String> kinds,int limit){
        byte[] prefix=key(cacheKey,"4|out|"+hex8(sourceId)+"|");var result=new ArrayList<ArtifactIndexFormat.Relationship>();
        try(var read=new ReadOptions();var iterator=db.newIterator(read)){
            for(iterator.seek(prefix);iterator.isValid()&&result.size()<limit;iterator.next()){
                byte[] current=iterator.key();if(!startsWith(current,prefix))break;
                String suffix=new String(current,StandardCharsets.UTF_8).substring(cacheKey.length()+1);
                int afterSource=suffix.indexOf('|',suffix.indexOf('|',suffix.indexOf('|')+1)+1)+1;
                int last=suffix.lastIndexOf('|');
                if(afterSource<=0||last<=afterSource)continue;
                String target=suffix.substring(afterSource,last),kind=suffix.substring(last+1);
                if(kinds.isEmpty()||kinds.contains(kind))result.add(new ArtifactIndexFormat.Relationship(sourceId,target,kind));
            }
        }
        return List.copyOf(result);
    }

    public Map<String,Object> status()throws Exception{
        return Map.of(
                "published",published.get(),"reused",reused.get(),
                "builds_in_flight",buildsInFlight.get(),"peak_parallel_builds",peakBuilds.get(),
                "artifact_locks",artifactLocks.size(),"storage_bytes",storageBytes());
    }

    public long storageBytes()throws Exception{
        try(var files=Files.walk(root)){
            return files.filter(Files::isRegularFile).mapToLong(path->{try{return Files.size(path);}catch(IOException e){return 0L;}}).sum();
        }
    }

    private List<Integer> idsByPrefix(String cacheKey,String suffix,int limit){
        byte[] prefix=key(cacheKey,suffix);var result=new ArrayList<Integer>();
        try(var read=new ReadOptions();var iterator=db.newIterator(read)){
            for(iterator.seek(prefix);iterator.isValid()&&result.size()<limit;iterator.next()){
                byte[] current=iterator.key();if(!startsWith(current,prefix))break;
                String text=new String(current,StandardCharsets.UTF_8);
                int split=text.lastIndexOf('|');if(split>=0)result.add((int)Long.parseLong(text.substring(split+1),16));
            }
        }
        return List.copyOf(result);
    }

    private void writeSst(Path path,String cacheKey,ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception{
        try(var env=new EnvOptions();var writer=new SstFileWriter(env,options)){
            writer.open(path.toString());
            put(writer,key(cacheKey,"0|manifest"),manifest(facts,classReferences));
            put(writer,key(cacheKey,"1|artifact"),ArtifactIndexFormat.encode(facts));

            var byBinary=new ArrayList<>(facts.symbols());
            byBinary.sort(Comparator.comparing(ArtifactIndexFormat.SymbolRecord::key).thenComparingInt(ArtifactIndexFormat.SymbolRecord::id));
            for(var symbol:byBinary)put(writer,key(cacheKey,"2|binary|"+symbol.key()),intBytes(symbol.id()));

            var byName=new ArrayList<>(facts.symbols());
            byName.sort(Comparator.comparing(ArtifactIndexFormat.SymbolRecord::name).thenComparingInt(ArtifactIndexFormat.SymbolRecord::id));
            for(var symbol:byName)put(writer,key(cacheKey,"3|name|"+symbol.name()+"|"+hex8(symbol.id())),EMPTY);

            var outgoing=new ArrayList<>(facts.relationships());
            outgoing.sort(Comparator.comparingInt(ArtifactIndexFormat.Relationship::sourceId)
                    .thenComparing(ArtifactIndexFormat.Relationship::target).thenComparing(ArtifactIndexFormat.Relationship::kind));
            for(var edge:outgoing)put(writer,key(cacheKey,"4|out|"+hex8(edge.sourceId())+"|"+edge.target()+"|"+edge.kind()),EMPTY);

            var reverse=new ArrayList<>(facts.relationships());
            reverse.sort(Comparator.comparing(ArtifactIndexFormat.Relationship::target)
                    .thenComparing(ArtifactIndexFormat.Relationship::kind).thenComparingInt(ArtifactIndexFormat.Relationship::sourceId));
            for(var edge:reverse)put(writer,key(cacheKey,"5|reverse|"+edge.target()+"|"+edge.kind()+"|"+hex8(edge.sourceId())),EMPTY);

            var refs=new ArrayList<>(classReferences);Collections.sort(refs);
            for(String reference:refs)put(writer,key(cacheKey,"6|class|"+reference),EMPTY);
            writer.finish();
        }
    }

    private long countPrefix(byte[] prefix){
        long count=0;
        try(var read=new ReadOptions();var iterator=db.newIterator(read)){
            for(iterator.seek(prefix);iterator.isValid();iterator.next()){
                if(!startsWith(iterator.key(),prefix))break;
                count++;
            }
        }
        return count;
    }

    private static Map<String,String> parseManifest(byte[] bytes)throws IOException{
        var result=new LinkedHashMap<String,String>();
        for(String line:new String(bytes,StandardCharsets.UTF_8).split("\\R")){
            if(line.isBlank())continue;
            int split=line.indexOf('=');if(split<=0)throw new IOException("Invalid artifact manifest");
            if(result.put(line.substring(0,split),line.substring(split+1))!=null)throw new IOException("Duplicate artifact manifest field");
        }
        return Map.copyOf(result);
    }

    private static byte[] manifest(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences){
        String value="format="+facts.key().formatVersion()+"\nindexer="+facts.key().indexerVersion()+
                "\nruntime="+facts.key().runtimeFeature()+"\nmode="+facts.key().mode()+
                "\nsymbols="+facts.symbols().size()+"\nrelationships="+facts.relationships().size()+
                "\nclass_references="+classReferences.size()+"\n";
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] key(String cacheKey,String suffix){return (cacheKey+"|"+suffix).getBytes(StandardCharsets.UTF_8);}
    private static byte[] intBytes(int value){return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();}
    private static String hex8(int value){String raw=Integer.toHexString(value);return "0".repeat(8-raw.length())+raw;}
    private static void put(SstFileWriter writer,byte[] key,byte[] value)throws RocksDBException{writer.put(key,value);}
    private static boolean startsWith(byte[] value,byte[] prefix){
        if(value.length<prefix.length)return false;
        for(int i=0;i<prefix.length;i++)if(value[i]!=prefix[i])return false;
        return true;
    }

    private void cleanupStaging()throws IOException{
        try(var files=Files.list(staging)){
            for(Path file:files.filter(Files::isRegularFile).toList())Files.deleteIfExists(file);
        }
    }

    @Override public void close(){db.close();options.close();}
}
