package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import dev.jvmd.core.Json;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
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
    private static final Set<String> TYPES=Set.of("class","interface","enum","record","annotation");
    static {RocksDB.loadLibrary();}

    private final Path root;
    private final Path dbPath;
    private final Path staging;
    private final Options options;
    private final RocksDB db;
    private final ConcurrentHashMap<String,Object> artifactLocks=new ConcurrentHashMap<>();
    private final Set<String> verifiedPublications=ConcurrentHashMap.newKeySet();
    private final AtomicLong verificationPasses=new AtomicLong(),nativePublicationVerifications=new AtomicLong(),activationVerificationReuses=new AtomicLong(),oracleMaterializations=new AtomicLong();
    private final Object ingestLock=new Object();
    private final AtomicLong published=new AtomicLong(),reused=new AtomicLong();
    private final AtomicLong sortPeakBytes=new AtomicLong(),sortSpillBytes=new AtomicLong();
    private final AtomicLong prepareNanos=new AtomicLong(),spillNanos=new AtomicLong(),sstNanos=new AtomicLong(),
            syncNanos=new AtomicLong(),ingestNanos=new AtomicLong(),verifyNanos=new AtomicLong(),
            sortInputRecords=new AtomicLong(),sortRunRecords=new AtomicLong(),gramOccurrences=new AtomicLong(),gramBlocks=new AtomicLong();
    private final long sortBufferBytes=Long.getLong("jvmd.index.sort_buffer_bytes",4L*1024*1024);
    private final AtomicInteger buildsInFlight=new AtomicInteger(),peakBuilds=new AtomicInteger();

    public RocksArtifactRepository(Path root)throws Exception{this(root,null);}

    RocksArtifactRepository(Path root,RocksMemory memory)throws Exception{
        this.root=root.toAbsolutePath().normalize();
        this.dbPath=this.root.resolve("db");
        this.staging=this.root.resolve("staging");
        Files.createDirectories(dbPath);Files.createDirectories(staging);
        this.options=memory==null?new Options().setCreateIfMissing(true).setMaxOpenFiles(128):memory.options(128);
        this.db=RocksDB.open(options,dbPath.toString());
        try{cleanupStaging();}catch(IOException error){db.close();options.close();throw error;}
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
                    long expectedEntries=writeSst(sst,cacheKey,facts,classReferences);
                    long started=System.nanoTime();
                    try(var file=FileChannel.open(sst,StandardOpenOption.WRITE)){file.force(true);}
                    syncNanos.addAndGet(System.nanoTime()-started);
                    // Validate the finished file while it is still invisible. The builder checked
                    // record schemas and hashed every sorted row; native verification reads every
                    // SST block and compares its checksum without returning all rows through JNI.
                    started=System.nanoTime();verifyStagedSst(sst,expectedEntries,options);
                    nativePublicationVerifications.incrementAndGet();verifyNanos.addAndGet(System.nanoTime()-started);
                    synchronized(ingestLock){
                        if(contains(cacheKey)){reused.incrementAndGet();return result(cacheKey,true,facts,classReferences);}
                        try(var ingest=new IngestExternalFileOptions().setMoveFiles(true)){
                            started=System.nanoTime();
                            db.ingestExternalFile(List.of(sst.toString()),ingest);
                            ingestNanos.addAndGet(System.nanoTime()-started);
                        }
                        verifiedPublications.add(cacheKey);
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

    static void verifyStagedSst(Path path,long expectedEntries,Options options)throws Exception{
        try(var reader=new SstFileReader(options)){
            reader.open(path.toString());reader.verifyChecksum();var properties=reader.getTableProperties();
            if(properties.getNumEntries()!=expectedEntries||properties.getNumDeletions()!=0||properties.getNumRangeDeletions()!=0)
                throw new IOException("Invalid staged SST record counts");
        }
    }

    private Publication result(String cacheKey,boolean wasReused,ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception{
        return new Publication(cacheKey,wasReused,facts.symbols().size(),facts.relationships().size(),classReferences.size(),db.getLongProperty("rocksdb.total-sst-files-size"));
    }


    public String publishDocumentation(String binaryCacheKey,ArtifactIndexFormat.Key sourceKey,
                                       Map<String,Map<String,Object>> members,int unmatchedMembers)throws Exception{
        var binary=artifactKey(binaryCacheKey);if(binary==null)throw new IOException("Missing binary generation for documentation: "+binaryCacheKey);
        String docsKey=ArtifactIndexFormat.documentationKey(binary,sourceKey.binarySha256());
        Object artifactLock=artifactLocks.computeIfAbsent(docsKey,ignored->new Object());
        try{
            synchronized(artifactLock){
                if(verifyDocumentation(docsKey,binaryCacheKey,sourceKey.binarySha256()))return docsKey;
                Path sst=staging.resolve(docsKey+"-"+UUID.randomUUID()+".docs.sst.tmp");
                try{
                    try(var entries=new SstSorter(staging,sortBufferBytes,docsKey)){
                    String manifest="kind=documentation\nbinary="+binaryCacheKey+"\nsource_sha="+sourceKey.binarySha256()+
                            "\nmembers="+members.size()+"\nunmatched="+unmatchedMembers+"\n";
                    for(var entry:new TreeMap<>(members).entrySet())
                        entries.add(relativeKey("9|member|"+entry.getKey()),Json.MAPPER.writeValueAsBytes(entry.getValue()));
                    try(var env=new EnvOptions();var writer=new SstFileWriter(env,options)){
                        writer.open(sst.toString());String checksum=entries.writeTo(writer);
                        writer.put(key(docsKey,"z|manifest"),(manifest+"sha256="+checksum+"\n").getBytes(StandardCharsets.UTF_8));writer.finish();
                    }
                    sortPeakBytes.accumulateAndGet(entries.peakBytes(),Math::max);sortSpillBytes.addAndGet(entries.spillBytes());
                    }
                    try(var file=FileChannel.open(sst,StandardOpenOption.WRITE)){file.force(true);}
                    synchronized(ingestLock){
                        if(!verifyDocumentation(docsKey,binaryCacheKey,sourceKey.binarySha256())){
                            try(var ingest=new IngestExternalFileOptions().setMoveFiles(true)){db.ingestExternalFile(List.of(sst.toString()),ingest);}
                        }
                    }
                    if(!verifyDocumentation(docsKey,binaryCacheKey,sourceKey.binarySha256()))
                        throw new IOException("Documentation publication verification failed: "+docsKey);
                    return docsKey;
                }finally{Files.deleteIfExists(sst);}
            }
        }finally{artifactLocks.remove(docsKey,artifactLock);}
    }

    public boolean verifyDocumentation(String docsKey,String binaryCacheKey,String sourceSha)throws Exception{
        byte[] value=db.get(key(docsKey,"z|manifest"));if(value==null)return false;
        var manifest=parseManifest(value);
        if(!"documentation".equals(manifest.get("kind"))||!binaryCacheKey.equals(manifest.get("binary"))||!sourceSha.equals(manifest.get("source_sha")))return false;
        var digest=java.security.MessageDigest.getInstance("SHA-256");byte[] prefix=key(docsKey,"9|member|");long count=0;
        try(var read=new ReadOptions().setFillCache(false);var iterator=db.newIterator(read)){
            for(iterator.seek(prefix);iterator.isValid()&&startsWith(iterator.key(),prefix);iterator.next()){
                SstSorter.hash(digest,iterator.key(),iterator.value());count++;
            }
            iterator.status();
        }
        return Objects.equals(manifest.get("members"),Long.toString(count))&&Objects.equals(manifest.get("sha256"),HexFormat.of().formatHex(digest.digest()));
    }

    public Map<String,Object> documentation(String docsKey,String binaryKey)throws Exception{
        if(docsKey==null||docsKey.isBlank())return Map.of();
        byte[] value=db.get(key(docsKey,"9|member|"+binaryKey));if(value==null)return Map.of();
        return Json.MAPPER.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
    }

    public boolean contains(String cacheKey)throws Exception{return db.get(key(cacheKey,"z|manifest"))!=null;}

    /** Immutable SSTs verified by this owner need no second scan during candidate activation. */
    boolean verifyForActivation(String cacheKey)throws Exception{
        if(verifiedPublications.contains(cacheKey)){activationVerificationReuses.incrementAndGet();return true;}
        return verify(cacheKey);
    }

    public boolean verify(String cacheKey)throws Exception{
        verificationPasses.incrementAndGet();
        byte[] manifestBytes=db.get(key(cacheKey,"z|manifest"));if(manifestBytes==null)return false;
        var manifest=parseManifest(manifestBytes);var identity=artifactKey(cacheKey);
        if(identity==null||!identity.cacheKey().equals(cacheKey))return false;
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        long symbols=0,relationships=0,references=0;byte[] prefix=key(cacheKey,"");
        long expectedSymbols=Long.parseLong(manifest.get("symbols"));
        byte[] manifestKey=key(cacheKey,"z|manifest");
        try(var read=new ReadOptions().setFillCache(false);var iterator=db.newIterator(read)){
            for(iterator.seek(prefix);iterator.isValid();iterator.next()){
                byte[] current=iterator.key();if(!startsWith(current,prefix))break;
                if(Arrays.equals(current,manifestKey))continue;
                byte[] value=iterator.value();SstSorter.hash(digest,current,value);
                if(suffixStartsWith(current,prefix.length,"1|symbol|")){
                    if(PostingCodec.lastId(current)!=symbols||!ArtifactIndexFormat.validateSymbol(value,symbols,expectedSymbols))return false;
                    symbols++;
                }
                else if(suffixStartsWith(current,prefix.length,"4|out|"))relationships++;
                else if(suffixStartsWith(current,prefix.length,"6|class|"))references++;
            }
            iterator.status();
        }
        return Objects.equals(manifest.get("sha256"),HexFormat.of().formatHex(digest.digest()))
                &&Objects.equals(manifest.get("symbols"),Long.toString(symbols))
                &&Objects.equals(manifest.get("relationships"),Long.toString(relationships))
                &&Objects.equals(manifest.get("class_references"),Long.toString(references));
    }
    private static boolean suffixStartsWith(byte[] key,int offset,String ascii){
        if(key.length-offset<ascii.length())return false;
        for(int i=0;i<ascii.length();i++)if(key[offset+i]!=ascii.charAt(i))return false;return true;
    }

    /** Materialization is reserved for the correctness oracle; queries read individual records. */
    public ArtifactIndexFormat.ArtifactData artifact(String cacheKey)throws Exception{
        oracleMaterializations.incrementAndGet();
        var identity=artifactKey(cacheKey);if(identity==null)return null;
        var symbols=new ArrayList<ArtifactIndexFormat.SymbolRecord>();var relationships=new ArrayList<ArtifactIndexFormat.Relationship>();
        byte[] prefix=key(cacheKey,"1|symbol|");
        try(var iterator=db.newIterator()){
            for(iterator.seek(prefix);iterator.isValid()&&startsWith(iterator.key(),prefix);iterator.next()){
                var symbol=ArtifactIndexFormat.decodeSymbol(iterator.value());
                if(symbol.id()!=symbols.size())throw new IOException("Noncanonical local symbol id");symbols.add(symbol);
            }
            iterator.status();
        }
        var ordered=new TreeMap<Integer,ArtifactIndexFormat.Relationship>();
        for(var symbol:symbols)for(var edge:outgoing(cacheKey,symbol.id(),Set.of(),Integer.MAX_VALUE)){
            byte[] ordinal=db.get(key(cacheKey,"4|out|"+hex8(edge.sourceId())+"|"+edge.target()+"|"+edge.kind()));
            ordered.put(ByteBuffer.wrap(ordinal).getInt(),edge);
        }
        relationships.addAll(ordered.values());
        return new ArtifactIndexFormat.ArtifactData(identity,symbols,relationships);
    }

    public ArtifactIndexFormat.Key artifactKey(String cacheKey)throws Exception{
        byte[] value=db.get(key(cacheKey,"z|manifest"));if(value==null)return null;
        var fields=parseManifest(value);
        return new ArtifactIndexFormat.Key(fields.get("binary_sha"),Integer.parseInt(fields.get("format")),
                fields.get("indexer"),Integer.parseInt(fields.get("runtime")),fields.get("mode"));
    }

    public ArtifactIndexFormat.SymbolRecord symbol(String cacheKey,int id)throws Exception{
        byte[] value=db.get(key(cacheKey,"1|symbol|"+hex8(id)));
        if(value==null)return null;
        var result=ArtifactIndexFormat.decodeSymbol(value);
        if(result.id()!=id)throw new IOException("Symbol id mismatch");
        return result;
    }

    /** Compare candidate counts without loading symbols; stop as soon as the alternative wins. */
    boolean postingCountExceeds(String cacheKey,String postingPrefix,long maximum)throws Exception{
        byte[] prefix=key(cacheKey,postingPrefix);long count=0;
        try(var iterator=db.newIterator()){
            for(iterator.seek(prefix);iterator.isValid()&&startsWith(iterator.key(),prefix);iterator.next()){
                count+=postingIds(iterator.key(),iterator.value()).length;
                if(count>maximum)return true;
            }
            iterator.status();
        }
        return false;
    }

    /** Filter before pagination, with bounded top-k memory even for large prefix postings. */
    public List<ArtifactIndexFormat.SymbolRecord> select(String cacheKey,String postingPrefix,int after,int limit,
                                                        Predicate<ArtifactIndexFormat.SymbolRecord> filter)throws Exception{
        return selectRanked(cacheKey,postingPrefix,after,limit,filter,symbol->symbol.id());
    }
    public List<ArtifactIndexFormat.SymbolRecord> selectRanked(String cacheKey,String postingPrefix,long after,int limit,
            Predicate<ArtifactIndexFormat.SymbolRecord> filter,java.util.function.ToLongFunction<ArtifactIndexFormat.SymbolRecord> rank)throws Exception{
        if(limit<=0)return List.of();
        var selected=new TreeMap<Long,ArtifactIndexFormat.SymbolRecord>();
        byte[] prefix=key(cacheKey,postingPrefix);
        try(var iterator=db.newIterator()){
            for(iterator.seek(prefix);iterator.isValid()&&startsWith(iterator.key(),prefix);iterator.next()){
                for(int id:postingIds(iterator.key(),iterator.value())){
                var symbol=symbol(cacheKey,id);
                if(symbol==null)throw new IOException("Posting references missing symbol: "+id);
                long order=rank.applyAsLong(symbol);
                if(order<=after||selected.containsKey(order)||(selected.size()==limit&&order>=selected.lastKey()))continue;
                if(!filter.test(symbol))continue;
                selected.put(order,symbol);if(selected.size()>limit)selected.pollLastEntry();
                }
            }
            iterator.status();
        }
        return List.copyOf(selected.values());
    }

    public static String scipSuffix(ArtifactIndexFormat.SymbolRecord symbol){
        return new ArtifactContext("index:artifact:0","jar","index").scip(symbol).substring("maven index/artifact 0 ".length());
    }

    public Integer binaryId(String cacheKey,String binaryKey)throws Exception{
        byte[] value=db.get(key(cacheKey,"2|binary|"+binaryKey));
        return value==null?null:ByteBuffer.wrap(value).getInt();
    }
    public boolean referencesClass(String cacheKey,String fqn)throws Exception{return db.get(key(cacheKey,"6|class|"+fqn))!=null;}

    public List<Integer> nameIds(String cacheKey,String namePrefix,int limit){return idsByPrefix(cacheKey,"3|name|"+namePrefix,limit);}
    public List<Integer> reverseSources(String cacheKey,String target,String kind,int limit){return idsByPrefix(cacheKey,"5|reverse|"+target+"|"+kind+"|",limit);}
    public List<Integer> pathIds(String cacheKey,String pathPrefix,int limit){return idsByPrefix(cacheKey,"7|path|"+pathPrefix,limit);}
    public List<Integer> substringIds(String cacheKey,String query,int limit){
        String normalized=query.toLowerCase(Locale.ROOT);if(normalized.isBlank())return List.of();
        String gram=normalized.substring(0,Math.min(3,normalized.length()));
        return idsByPrefix(cacheKey,"8|gram|"+gram+"|",limit);
    }


    public List<ArtifactIndexFormat.Relationship> incoming(String cacheKey,String target,Set<String> kinds,int limit){
        byte[] prefix=key(cacheKey,"5|reverse|"+target+"|");var result=new ArrayList<ArtifactIndexFormat.Relationship>();
        try(var read=new ReadOptions();var iterator=db.newIterator(read)){
            for(iterator.seek(prefix);iterator.isValid()&&result.size()<limit;iterator.next()){
                byte[] current=iterator.key();if(!startsWith(current,prefix))break;
                String text=new String(current,StandardCharsets.UTF_8);
                int last=text.lastIndexOf('|');if(last<=0)continue;
                int previous=text.lastIndexOf('|',last-1);if(previous<=0)continue;
                String kind=text.substring(previous+1,last);
                if(!kinds.isEmpty()&&!kinds.contains(kind))continue;
                for(int source:postingIds(current,iterator.value())){
                    result.add(new ArtifactIndexFormat.Relationship(source,target,kind));if(result.size()==limit)break;
                }
            }
        }
        return List.copyOf(result);
    }

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
        var result=new LinkedHashMap<String,Object>();
        result.put("published",published.get());result.put("reused",reused.get());
        result.put("builds_in_flight",buildsInFlight.get());result.put("peak_parallel_builds",peakBuilds.get());
        result.put("artifact_locks",artifactLocks.size());result.put("storage_bytes",db.getLongProperty("rocksdb.total-sst-files-size"));
        result.put("sort_buffer_bytes",sortBufferBytes);result.put("sort_peak_bytes",sortPeakBytes.get());result.put("sort_spill_bytes",sortSpillBytes.get());
        result.put("record_prepare_ms",prepareNanos.get()/1e6);result.put("sort_spill_ms",spillNanos.get()/1e6);
        result.put("sort_merge_and_sst_ms",sstNanos.get()/1e6);result.put("file_sync_ms",syncNanos.get()/1e6);
        result.put("sst_ingest_ms",ingestNanos.get()/1e6);result.put("publication_verify_ms",verifyNanos.get()/1e6);
        result.put("sort_input_records",sortInputRecords.get());result.put("sort_run_records",sortRunRecords.get());
        result.put("gram_occurrences",gramOccurrences.get());result.put("gram_posting_blocks",gramBlocks.get());
        result.put("verification_passes",verificationPasses.get());result.put("native_publication_verifications",nativePublicationVerifications.get());result.put("activation_verification_reuses",activationVerificationReuses.get());result.put("oracle_materializations",oracleMaterializations.get());
        for(String property:List.of("estimate-pending-compaction-bytes","num-running-compactions","num-running-flushes",
                "actual-delayed-write-rate","is-write-stopped","estimate-table-readers-mem","cur-size-all-mem-tables"))
            result.put(property.replace('-','_'),db.getLongProperty("rocksdb."+property));
        return Map.copyOf(result);
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
                for(int id:postingIds(current,iterator.value())){result.add(id);if(result.size()==limit)break;}
            }
        }
        return List.copyOf(result);
    }

    private static int[] postingIds(byte[] key,byte[] value){
        return key[65]=='1'?new int[]{PostingCodec.lastId(key)}:PostingCodec.decode(key,value);
    }

    private long writeSst(Path path,String cacheKey,ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception{
        long preparationStarted=System.nanoTime();
        long gramBudget=sortBufferBytes>=262144?Math.min(1024*1024,sortBufferBytes/4):0;
        try(var entries=new SstSorter(staging,sortBufferBytes-gramBudget,cacheKey)){
        var gramsIndex=gramBudget==0?null:new GramPostings(entries,gramBudget);
        List<ArtifactIndexFormat.SymbolRecord> symbols=facts.symbols();
        for(int i=1;i<symbols.size();i++)if(symbols.get(i-1).id()>=symbols.get(i).id()){
            symbols=new ArrayList<>(symbols);symbols.sort(Comparator.comparingInt(ArtifactIndexFormat.SymbolRecord::id));break;
        }
        int previousId=-1;
        String lastPrefix="";var prefixGrams=new HashSet<String>();var grams=new HashSet<String>();

        for(var symbol:symbols){
            if(symbol.id()!=previousId+1||symbol.ownerId()>=symbols.size()||symbol.ownerId()<-1)throw new IOException("Invalid artifact symbol ID or owner");previousId=symbol.id();
            String symbolId=hex8(symbol.id());
            if(TYPES.contains(symbol.kind()))entries.add(relativeKey("0|type|"+symbol.fqn()+"|"+symbolId),EMPTY);
            byte[] encoded=ArtifactIndexFormat.encodeSymbol(symbol);
            if(!ArtifactIndexFormat.validateSymbol(encoded,symbol.id(),symbols.size()))throw new IOException("Invalid artifact symbol");
            entries.add(relativeKey("1|symbol|"+symbolId),encoded);
            entries.add(relativeKey("2|binary|"+symbol.key()),intBytes(symbol.id()));
            entries.add(relativeKey("2|scip|"+scipSuffix(symbol)+"|"+symbolId),EMPTY);
            entries.add(relativeKey("3|name|"+symbol.name()+"|"+symbolId),EMPTY);
            String namePath=ArtifactContext.namePath(symbol);
            entries.add(relativeKey("7|path|"+namePath+"|"+symbolId),EMPTY);

            grams.clear();
            String name=symbol.name().toLowerCase(Locale.ROOT);
            String pathValue=namePath.toLowerCase(Locale.ROOT);
            int boundary=pathValue.lastIndexOf('/')+1;String prefix=pathValue.substring(0,boundary);
            // Retain only the current owner prefix, not a growing per-artifact cache. Include
            // two preceding characters in the tail so every boundary-crossing trigram survives.
            if(!prefix.equals(lastPrefix)){prefixGrams.clear();addGrams(prefixGrams,prefix);lastPrefix=prefix;}
            addGrams(grams,pathValue.substring(Math.max(0,boundary-2)));if(!pathValue.contains(name))addGrams(grams,name);
            for(String gram:prefixGrams)addGram(entries,gramsIndex,gram,symbol.id());
            for(String gram:grams)if(!prefixGrams.contains(gram))addGram(entries,gramsIndex,gram,symbol.id());
        }
        if(gramsIndex!=null)gramsIndex.finish();

        for(int ordinal=0;ordinal<facts.relationships().size();ordinal++){
            var edge=facts.relationships().get(ordinal);
            if(edge.sourceId()<0||edge.sourceId()>=symbols.size()||edge.target()==null||edge.kind()==null)throw new IOException("Invalid artifact relationship");
            entries.add(relativeKey("4|out|"+hex8(edge.sourceId())+"|"+edge.target()+"|"+edge.kind()),intBytes(ordinal));
            entries.add(relativeKey("5|reverse|"+edge.target()+"|"+edge.kind()+"|"+hex8(edge.sourceId())),EMPTY);
        }
        for(String reference:classReferences)entries.add(relativeKey("6|class|"+reference),EMPTY);

        prepareNanos.addAndGet(System.nanoTime()-preparationStarted-entries.spillNanos());
        long writeStarted=System.nanoTime(),previousSpill=entries.spillNanos();
        try(var env=new EnvOptions();var writer=new SstFileWriter(env,options)){
            writer.open(path.toString());String checksum=entries.writeTo(writer);
            writer.put(key(cacheKey,"z|manifest"),manifest(facts,classReferences,checksum));writer.finish();
        }
        sstNanos.addAndGet(System.nanoTime()-writeStarted-(entries.spillNanos()-previousSpill));
        spillNanos.addAndGet(entries.spillNanos());sortInputRecords.addAndGet(entries.inputRecords());sortRunRecords.addAndGet(entries.runRecords());
        sortPeakBytes.accumulateAndGet(entries.peakBytes()+(gramsIndex==null?0:gramsIndex.peakBytes()),Math::max);sortSpillBytes.addAndGet(entries.spillBytes());
        if(gramsIndex!=null){gramOccurrences.addAndGet(gramsIndex.occurrences());gramBlocks.addAndGet(gramsIndex.flushed());}
        return entries.writtenRecords()+1; // Includes the manifest in the same atomic ingestion.
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

    private static void addGrams(Set<String> output,String value){
        for(int length=1;length<=3;length++){
            if(value.length()<length)break;
            for(int i=0;i<=value.length()-length;i++)output.add(value.substring(i,i+length));
        }
    }

    private static void addGram(SstSorter entries,GramPostings index,String gram,int id)throws Exception{
        if(index!=null)index.add(gram,id);else entries.add(relativeKey("8|gram|"+gram+"|"+hex8(id)),EMPTY);
    }

    private static byte[] manifest(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences,String checksum){
        String value="format="+facts.key().formatVersion()+"\nindexer="+facts.key().indexerVersion()+
                "\nruntime="+facts.key().runtimeFeature()+"\nmode="+facts.key().mode()+"\nbinary_sha="+facts.key().binarySha256()+
                "\nsymbols="+facts.symbols().size()+"\nrelationships="+facts.relationships().size()+
                "\nclass_references="+classReferences.size()+"\nsha256="+checksum+"\n";
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] relativeKey(String suffix){return suffix.getBytes(StandardCharsets.UTF_8);}
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
