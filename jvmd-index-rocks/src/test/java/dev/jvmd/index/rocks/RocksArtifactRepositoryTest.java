package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RocksArtifactRepositoryTest {
    @TempDir Path temp;

    @Test void boundedPagesRejectUnusableIdsBeforeDecodingButPreserveCustomRanks()throws Exception{
        var data=facts(5000,0);String key=data.key().cacheKey();
        try(var store=new RocksArtifactRepository(temp.resolve("bounded-page"))){
            store.publish(data,Set.of());
            for(String prefix:List.of("8|gram|met|","1|symbol|")){
                long before=((Number)store.status().get("query_symbol_reads")).longValue();
                long candidates=((Number)store.status().get("query_posting_candidates")).longValue();
                assertThat(store.select(key,prefix,4979,20,s->true)).extracting(ArtifactIndexFormat.SymbolRecord::id)
                        .containsExactlyElementsOf(java.util.stream.IntStream.range(4980,5000).boxed().toList());
                assertThat(((Number)store.status().get("query_symbol_reads")).longValue()-before).isEqualTo(20);
                if(prefix.equals("1|symbol|"))assertThat(((Number)store.status().get("query_posting_candidates")).longValue()-candidates).isEqualTo(20);
            }
            assertThat(store.select(key,"1|symbol|",Integer.MAX_VALUE,20,s->true)).isEmpty();
            assertThat(store.select(key,"1|symbol|",-1,20,s->s.id()%2==0)).extracting(ArtifactIndexFormat.SymbolRecord::id)
                    .containsExactlyElementsOf(java.util.stream.IntStream.range(0,20).map(i->i*2).boxed().toList());
            assertThat(store.selectRanked(key,"1|symbol|",0,20,s->true,s->5000-s.id()))
                    .extracting(ArtifactIndexFormat.SymbolRecord::id)
                    .containsExactlyElementsOf(java.util.stream.IntStream.range(0,20).map(i->4999-i).boxed().toList());
        }
    }

    @Test void postingCountsChooseSelectiveListsWithoutMaterializingSymbols()throws Exception{
        var data=facts(1100,0);String key=data.key().cacheKey();
        try(var store=new RocksArtifactRepository(temp.resolve("posting-counts"))){
            store.publish(data,Set.of());
            assertThat(store.postingCountExceeds(key,"8|gram|met|",1099)).isTrue();
            assertThat(store.postingCountExceeds(key,"8|gram|met|",1100)).isFalse();
            assertThat(store.postingCountExceeds(key,"3|name|method1099|",0)).isTrue();
            assertThat(store.postingCountExceeds(key,"3|name|method1099|",1)).isFalse();
            assertThat(store.postingCountExceeds(key,"8|gram|xyz|",0)).isFalse();
            assertThat(store.status()).containsEntry("oracle_materializations",0L);
        }
    }

    @Test void compactPostingDecoderPreservesVariableWidthsAndRejectsMalformedBlocks(){
        byte[] zero="00000000".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] wide="00000080".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(PostingCodec.decode(zero,new byte[]{0x7f,0})).containsExactly(0);
        assertThat(PostingCodec.decode(wide,new byte[]{0x7f,(byte)0x80,1})).containsExactly(128);
        assertThat(PostingCodec.decode(wide,new byte[]{0x7f,0,(byte)0x80,1})).containsExactly(0,128);
        byte[] full=new byte[257];Arrays.fill(full,(byte)1);full[0]=0x7f;full[1]=0;
        assertThat(PostingCodec.decode("000000ff".getBytes(java.nio.charset.StandardCharsets.UTF_8),full))
                .containsExactly(java.util.stream.IntStream.range(0,256).toArray());
        for(byte[] malformed:List.of(new byte[]{0x7f},new byte[]{0x7f,(byte)0x80},new byte[]{0x7f,0,0},
                new byte[]{0x7f,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,0x10},new byte[]{0x7f,1}))
            assertThatThrownBy(()->PostingCodec.decode(zero,malformed)).isInstanceOf(IllegalStateException.class);
        byte[] tooMany=Arrays.copyOf(full,258);tooMany[257]=1;
        assertThatThrownBy(()->PostingCodec.decode("00000100".getBytes(java.nio.charset.StandardCharsets.UTF_8),tooMany))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void admittedPublisherDoesNotQueueBehindAWorkerWaitingForItsCapacity()throws Exception{
        var acquired=new java.util.concurrent.CountDownLatch(1);var publish=new java.util.concurrent.CountDownLatch(1);
        try(var storage=new RocksIndexStorage(temp.resolve("fair-admission"),1024*1024)){
            var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
            try{
                var owner=executor.submit(()->{
                    try(var permit=storage.admission().acquireArtifact(temp.resolve("owner.jar"))){acquired.countDown();publish.await();publish(storage,facts(10,0),Set.of());}
                    return null;
                });
                assertThat(acquired.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var waiter=executor.submit(()->{try(var permit=storage.admission().acquireArtifact(temp.resolve("waiter.jar"))){return true;}});
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(((Number)storage.status().get("admission_waiters")).intValue()==0&&System.nanoTime()<deadline)Thread.sleep(1);
                assertThat(storage.status()).containsEntry("admission_waiters",1);
                publish.countDown();owner.get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(waiter.get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(storage.status()).containsEntry("estimated_bytes_in_flight",0L);
                assertThat(((Map<?,?>)storage.status().get("repository")).get("published")).isEqualTo(1L);
            }finally{publish.countDown();executor.shutdownNow();assertThat(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
        }
    }

    @Test void prefixGramReusePreservesEverySubstringIncludingOwnerBoundariesAndUnicode()throws Exception{
        var symbols=new ArrayList<ArtifactIndexFormat.SymbolRecord>();
        for(String owner:List.of("ABab.Owner","ABab.Owner","other.İType$Nested","Plain")){
            int id=symbols.size();String name=id==3?"Plain":"fieldİ"+id;
            symbols.add(new ArtifactIndexFormat.SymbolRecord(id,-1,id==3?owner:owner+"#"+name,owner,name,id==3?"class":"field",
                    name,"I",1,"Type.class",List.of(),"{}"));
        }
        var expected=new TreeMap<String,List<Integer>>();
        for(var symbol:symbols){
            String path=dev.jvmd.index.ArtifactContext.namePath(symbol).toLowerCase(Locale.ROOT),name=symbol.name().toLowerCase(Locale.ROOT);
            var grams=new HashSet<String>();
            for(String text:List.of(path,name))for(int length=1;length<=3;length++)
                for(int i=0;i+length<=text.length();i++)grams.add(text.substring(i,i+length));
            for(String gram:grams)expected.computeIfAbsent(gram,ignored->new ArrayList<>()).add(symbol.id());
        }
        var data=new ArtifactIndexFormat.ArtifactData(facts(0,0).key(),symbols,List.of());
        try(var store=new RocksArtifactRepository(temp.resolve("gram-boundaries"))){
            store.publish(data,Set.of());
            for(var entry:expected.entrySet())assertThat(store.substringIds(data.key().cacheKey(),entry.getKey(),100))
                    .as("gram %s",entry.getKey()).containsExactlyElementsOf(entry.getValue());
            assertThat(store.verify(data.key().cacheKey())).isTrue();
        }
    }

    @Test void stagedNativeVerificationRejectsWrongCountsAndCorruptedData()throws Exception{
        org.rocksdb.RocksDB.loadLibrary();Path staged=temp.resolve("candidate.sst");
        try(var options=new org.rocksdb.Options().setCompressionType(org.rocksdb.CompressionType.NO_COMPRESSION)){
            try(var env=new org.rocksdb.EnvOptions();var writer=new org.rocksdb.SstFileWriter(env,options)){
                writer.open(staged.toString());writer.put("record".getBytes(),new byte[8192]);writer.finish();
            }
            RocksArtifactRepository.verifyStagedSst(staged,1,options);
            assertThatThrownBy(()->RocksArtifactRepository.verifyStagedSst(staged,2,options))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("record counts");
            byte[] bytes=Files.readAllBytes(staged);bytes[128]^=1;Files.write(staged,bytes);
            assertThatThrownBy(()->RocksArtifactRepository.verifyStagedSst(staged,1,options))
                    .isInstanceOf(org.rocksdb.RocksDBException.class);
        }
    }

    @Test void invalidSchemaAndEdgeNeverPublishAManifest()throws Exception{
        var original=facts(1,0);var symbol=original.symbols().getFirst();
        var malformed=new ArtifactIndexFormat.SymbolRecord(0,-1,symbol.key(),symbol.fqn(),symbol.name(),symbol.kind(),
                symbol.signature(),symbol.descriptor(),symbol.flags(),symbol.entry(),symbol.parameters(),null);
        var badSchema=new ArtifactIndexFormat.ArtifactData(original.key(),List.of(malformed),List.of());
        var badEdge=new ArtifactIndexFormat.ArtifactData(original.key(),original.symbols(),List.of(new ArtifactIndexFormat.Relationship(1,"dep.Type","calls")));
        try(var store=new RocksArtifactRepository(temp.resolve("invalid-records"))){
            for(var invalid:List.of(badSchema,badEdge)){
                assertThatThrownBy(()->store.publish(invalid,Set.of())).isInstanceOf(java.io.IOException.class);
                assertThat(store.contains(original.key().cacheKey())).isFalse();
            }
            assertThat(store.publish(original,Set.of()).reused()).isFalse();
            assertThat(store.verify(original.key().cacheKey())).isTrue();
        }
    }

    @Test void packedPostingsPreserveBlockBoundariesFilteringAndReverseEdges()throws Exception{
        var original=facts(1100,0);
        var edges=new ArrayList<ArtifactIndexFormat.Relationship>();
        for(int i=0;i<1100;i++)edges.add(new ArtifactIndexFormat.Relationship(i,"dep.Shared","calls"));
        var data=new ArtifactIndexFormat.ArtifactData(original.key(),original.symbols(),List.copyOf(edges));
        Path root=temp.resolve("packed");String key=data.key().cacheKey();
        try(var store=new RocksArtifactRepository(root)){store.publish(data,Set.of("dep.Shared"));}
        try(var store=new RocksArtifactRepository(root)){
            assertThat(store.verify(key)).isTrue();assertThat(store.artifact(key)).isEqualTo(data);
            assertThat(store.substringIds(key,"method",2000)).containsExactlyElementsOf(java.util.stream.IntStream.range(0,1100).boxed().toList());
            assertThat(store.reverseSources(key,"dep.Shared","calls",2000)).containsExactlyElementsOf(java.util.stream.IntStream.range(0,1100).boxed().toList());
            assertThat(store.incoming(key,"dep.Shared",Set.of("calls"),2000)).containsExactlyElementsOf(edges);
            assertThat(store.incoming(key,"dep.Shared",Set.of("calls"),257)).hasSize(257);
            assertThat(store.select(key,"8|gram|met|",254,4,s->s.id()%2==0)).extracting(ArtifactIndexFormat.SymbolRecord::id).containsExactly(256,258,260,262);
        }
        try(var files=Files.list(root.resolve("staging"))){assertThat(files.toList()).isEmpty();}
    }

    @Test void overlappingPackedRunsMergeInOrderAcrossMultiplePasses()throws Exception{
        String prior=System.getProperty("jvmd.index.sort_buffer_bytes");System.setProperty("jvmd.index.sort_buffer_bytes","65536");
        try{
            var original=facts(5000,0);var shuffled=new ArrayList<>(original.symbols());Collections.shuffle(shuffled,new Random(17));
            var data=new ArtifactIndexFormat.ArtifactData(original.key(),shuffled,List.of());String key=data.key().cacheKey();
            try(var store=new RocksArtifactRepository(temp.resolve("interleaved"))){
                store.publish(data,Set.of());assertThat(store.verify(key)).isTrue();
                assertThat(store.substringIds(key,"method",6000)).containsExactlyElementsOf(java.util.stream.IntStream.range(0,5000).boxed().toList());
                assertThat(store.select(key,"8|gram|met|",254,4,s->s.id()%2==0)).extracting(ArtifactIndexFormat.SymbolRecord::id).containsExactly(256,258,260,262);
                assertThat(((Number)store.status().get("sort_peak_bytes")).longValue()).isLessThanOrEqualTo(65536L);
            }
        }finally{if(prior==null)System.clearProperty("jvmd.index.sort_buffer_bytes");else System.setProperty("jvmd.index.sort_buffer_bytes",prior);}
    }

    @Test void atomicallyPublishesAndReusesImmutableGeneration()throws Exception{
        var data=facts(5000,10000);
        String cacheKey=data.key().cacheKey();
        try(var store=new RocksArtifactRepository(temp.resolve("rocks"))){
            long before=store.storageBytes();
            var first=store.publish(data,Set.of("dep.Type12","dep.Type77"));
            assertThat(first.reused()).isFalse();
            assertThat(store.contains(cacheKey)).isTrue();
            assertThat(store.artifact(cacheKey)).isEqualTo(data);
            assertThat(store.binaryId(cacheKey,"fixture.Type#method123()V")).isEqualTo(123);
            assertThat(store.nameIds(cacheKey,"method12",100)).isNotEmpty();
            assertThat(store.reverseSources(cacheKey,"dep.Type12","calls",100)).isNotEmpty();
            long published=store.storageBytes();
            assertThat(published).isGreaterThan(before);

            var second=store.publish(data,Set.of("dep.Type12","dep.Type77"));
            assertThat(second.reused()).isTrue();
            assertThat(store.storageBytes()).isEqualTo(published);
        }
    }

    @Test void abandonedStagingFileNeverBecomesVisible()throws Exception{
        Path root=temp.resolve("rocks"),staging=Files.createDirectories(root.resolve("staging"));
        Files.writeString(staging.resolve("orphan.sst.tmp"),"partial");
        var data=facts(10,20);
        try(var store=new RocksArtifactRepository(root)){
            assertThat(Files.list(staging).toList()).isEmpty();
            assertThat(store.contains(data.key().cacheKey())).isFalse();
        }
    }

    private static void publish(IndexStorage storage,ArtifactIndexFormat.ArtifactData facts,Set<String> references)throws Exception{
        storage.store().publishBinary(new IndexStore.ArtifactInput(new ArtifactContext("fixture:admission:1","jar","/fixture/"+facts.key().cacheKey()+".jar"),facts.key(),0,0),facts,references);
    }

    @Test void distinctArtifactsBuildInParallelWithinBoundedStorageBudget()throws Exception{
        long budget=4L*1024*1024;
        try(var storage=new RocksIndexStorage(temp.resolve("bounded"),budget);
            var executor=java.util.concurrent.Executors.newFixedThreadPool(4)){
            var futures=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<4;i++){
                final int n=i;
                futures.add(executor.submit(()->{
                    try{publish(storage,facts(2500,5000,(char)('b'+n)),Set.of("dep.Type12"));}
                    catch(Exception e){throw new RuntimeException(e);}
                }));
            }
            for(var future:futures)future.get();
            var status=storage.status();
            assertThat(((Number)((Map<?,?>)status.get("repository")).get("published")).longValue()).isEqualTo(4L);
            assertThat(((Number)status.get("peak_estimated_bytes_in_flight")).longValue()).isLessThanOrEqualTo(budget);
        }
    }

    @Test void generationSurvivesReopenAndReusesWithoutArtifactRewrite()throws Exception{
        Path root=temp.resolve("reopen");var data=facts(1000,2000);String cacheKey=data.key().cacheKey();
        try(var first=new RocksArtifactRepository(root)){
            first.publish(data,Set.of("dep.Type12"));assertThat(first.verify(cacheKey)).isTrue();
        }
        try(var reopened=new RocksArtifactRepository(root)){
            assertThat(reopened.verify(cacheKey)).isTrue();
            assertThat(reopened.artifact(cacheKey)).isEqualTo(data);
            assertThat(reopened.publish(data,Set.of("dep.Type12")).reused()).isTrue();
            assertThat(reopened.status()).containsEntry("published",0L).containsEntry("reused",1L);
        }
    }

    @Test void concurrentSameGenerationPublishesExactlyOnce()throws Exception{
        Path root=temp.resolve("same-key");var data=facts(2000,4000);
        try(var store=new RocksArtifactRepository(root);var executor=java.util.concurrent.Executors.newFixedThreadPool(4)){
            var futures=new ArrayList<java.util.concurrent.Future<RocksArtifactRepository.Publication>>();
            for(int i=0;i<4;i++)futures.add(executor.submit(()->store.publish(data,Set.of("dep.Type12"))));
            var values=new ArrayList<RocksArtifactRepository.Publication>();for(var future:futures)values.add(future.get());
            assertThat(values).filteredOn(value->!value.reused()).hasSize(1);
            assertThat(values).filteredOn(RocksArtifactRepository.Publication::reused).hasSize(3);
            assertThat(store.verify(data.key().cacheKey())).isTrue();
            assertThat(((Number)store.status().get("published")).longValue()).isEqualTo(1L);
        }
    }

    @Test void spillsSortedRunsAndVerifiesEveryPosting()throws Exception{
        String prior=System.getProperty("jvmd.index.sort_buffer_bytes");
        System.setProperty("jvmd.index.sort_buffer_bytes","65536");
        Path root=temp.resolve("spilled");var data=facts(1000,2000);String key=data.key().cacheKey();
        try{
            try(var store=new RocksArtifactRepository(root)){
                store.publish(data,Set.of("dep.Type12"));
                assertThat(store.verify(key)).isTrue();assertThat(store.artifact(key)).isEqualTo(data);
                assertThat(((Number)store.status().get("sort_peak_bytes")).longValue()).isLessThanOrEqualTo(65536L);
                assertThat(((Number)store.status().get("sort_spill_bytes")).longValue()).isPositive();
                try(var files=Files.list(root.resolve("staging"))){assertThat(files.toList()).isEmpty();}
            }
            try(var options=new org.rocksdb.Options();var db=org.rocksdb.RocksDB.open(options,root.resolve("db").toString())){
                db.delete((key+"|3|name|method0|00000000").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            try(var reopened=new RocksArtifactRepository(root)){assertThat(reopened.verify(key)).isFalse();}
        }finally{if(prior==null)System.clearProperty("jvmd.index.sort_buffer_bytes");else System.setProperty("jvmd.index.sort_buffer_bytes",prior);}
    }

    @Test void failedBuildLeavesNoPublishedManifestOrSortRuns()throws Exception{
        var data=facts(30,60);var duplicates=new ArrayList<>(data.relationships());duplicates.add(duplicates.getFirst());
        var invalid=new ArtifactIndexFormat.ArtifactData(data.key(),data.symbols(),duplicates);
        Path root=temp.resolve("failed-build");
        try(var store=new RocksArtifactRepository(root)){
            assertThatThrownBy(()->store.publish(invalid,Set.of())).isInstanceOf(java.io.IOException.class);
            assertThat(store.contains(data.key().cacheKey())).isFalse();
            try(var files=Files.list(root.resolve("staging"))){assertThat(files.toList()).isEmpty();}
            assertThat(store.publish(data,Set.of()).reused()).isFalse();
        }
    }

    private static ArtifactIndexFormat.ArtifactData facts(int symbols,int relationships){
        return facts(symbols,relationships,'a');
    }

    private static ArtifactIndexFormat.ArtifactData facts(int symbols,int relationships,char hashChar){
        var key=new ArtifactIndexFormat.Key(String.valueOf(hashChar).repeat(64),ArtifactIndexFormat.FORMAT_VERSION,
                ArtifactIndexFormat.INDEXER_VERSION,Runtime.version().feature(),"signatures");
        var values=new ArrayList<ArtifactIndexFormat.SymbolRecord>();
        for(int i=0;i<symbols;i++)values.add(new ArtifactIndexFormat.SymbolRecord(
                i,-1,"fixture.Type#method"+i+"()V","fixture.Type","method"+i,"method",
                "void method"+i+"()","()V",1,"fixture/Type.class",List.of(),"{}"));
        var edges=new ArrayList<ArtifactIndexFormat.Relationship>();
        for(int i=0;i<relationships;i++){
            int source=i%symbols,target=(i/symbols)*1000+(i%1000);
            edges.add(new ArtifactIndexFormat.Relationship(source,"dep.Type"+target,(i&1)==0?"calls":"return_type"));
        }
        return new ArtifactIndexFormat.ArtifactData(key,List.copyOf(values),List.copyOf(edges));
    }
    @Test void documentationOverlayIsContentAddressedSeparatelyFromBinaryFacts()throws Exception{
        var data=facts(20,40);
        try(var store=new RocksArtifactRepository(temp.resolve("docs"))){
            store.publish(data,Set.of());
            var sourceKey=new ArtifactIndexFormat.Key("f".repeat(64),ArtifactIndexFormat.FORMAT_VERSION,
                    ArtifactIndexFormat.INDEXER_VERSION,Runtime.version().feature(),"sources");
            var member=Map.<String,Object>of("doc","Example docs","source_file","jar:file:///repo/example-sources.jar!/fixture/Type.java",
                    "line",12,"source_start",100,"source_end",180,"body_start",130,"body_end",175,
                    "parameters",List.of("value"));
            String docs=store.publishDocumentation(data.key().cacheKey(),sourceKey,
                    Map.of("fixture.Type#method1()V",member),0);
            assertThat(store.verifyDocumentation(docs,data.key().cacheKey(),sourceKey.binarySha256())).isTrue();
            assertThat(store.documentation(docs,"fixture.Type#method1()V"))
                    .containsEntry("doc","Example docs")
                    .containsEntry("line",12);
            assertThat(store.documentation(docs,"fixture.Type#missing()V")).isEmpty();
            assertThat(store.publishDocumentation(data.key().cacheKey(),sourceKey,
                    Map.of("fixture.Type#method1()V",member),0)).isEqualTo(docs);
        }
    }

}
