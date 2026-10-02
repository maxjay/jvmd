package dev.jvmd.tests;

import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import dev.jvmd.core.tree.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-1")
class KeyedTreeTest {
    private static final KeyedTree.Spec<String,String> SPEC=new KeyedTree.StringKeys<>("test-tree"){
        @Override public byte[] encodeValue(String value){return value.getBytes(StandardCharsets.UTF_8);}
        @Override public String decodeValue(byte[] bytes){return new String(bytes,StandardCharsets.UTF_8);}
        @Override public Hash256 identity(String value){return CanonicalDigestWriter.digest("value",value);}
        @Override public Hash256 rangeIdentity(String value){return CanonicalDigestWriter.digest("range",value);}
    };

    private static Map<String,String> entries(int count){
        var result=new TreeMap<String,String>();
        for(int i=0;i<count;i++)result.put("key-"+String.format(Locale.ROOT,"%05d",i),"value-"+i);
        return result;
    }

    @Test void insertionOrderDoesNotChangeTheRoot(){
        var values=entries(500);var keys=new ArrayList<>(values.keySet());
        var forward=KeyedTree.empty(SPEC);for(String key:keys)forward=forward.put(key,values.get(key));
        var shuffled=new ArrayList<>(keys);Collections.shuffle(shuffled,new Random(7));
        var random=KeyedTree.empty(SPEC);for(String key:shuffled)random=random.put(key,values.get(key));
        var bulk=KeyedTree.build(SPEC,List.copyOf(values.entrySet()));
        assertThat(random.rootHash()).isEqualTo(forward.rootHash());
        assertThat(bulk.rootHash()).isEqualTo(forward.rootHash());
        assertThat(bulk.rangeSum()).isEqualTo(forward.rangeSum());
        assertThat(bulk.size()).isEqualTo(500);
    }

    @Test void removalRestoresThePriorRootAndUpdatesAreLocal(){
        var tree=KeyedTree.build(SPEC,List.copyOf(entries(200).entrySet()));
        var added=tree.put("key-extra","x");
        assertThat(added.rootHash()).isNotEqualTo(tree.rootHash());
        assertThat(added.remove("key-extra").rootHash()).isEqualTo(tree.rootHash());
        assertThat(tree.get("key-extra")).isNull();
        var replaced=tree.put("key-00010","changed");
        assertThat(replaced.get("key-00010")).isEqualTo("changed");
        assertThat(replaced.put("key-00010","value-10").rootHash()).isEqualTo(tree.rootHash());
        assertThat(tree.remove("absent")).isSameAs(tree);
    }

    @Test void rangeSumsEqualAnIndependentSum(){
        var values=entries(300);var tree=KeyedTree.build(SPEC,List.copyOf(values.entrySet()));
        for(var bounds:List.of(List.of("key-00000","key-00299"),List.of("key-00100","key-00199"),List.of("key-0005","key-0005￿"),List.of("a","b"))){
            var expected=AlgebraicAccumulator.Value.ZERO;
            for(var entry:values.entrySet())if(entry.getKey().compareTo(bounds.get(0))>=0&&entry.getKey().compareTo(bounds.get(1))<=0)
                expected=expected.plus(AlgebraicAccumulator.contribution("test-tree/range",entry.getKey().getBytes(StandardCharsets.UTF_8),SPEC.rangeIdentity(entry.getValue())));
            assertThat(tree.range(bounds.get(0),bounds.get(1))).as("range %s",bounds).isEqualTo(expected);
        }
        var cursor=tree.cursor("key-00100","key-00104");var seen=new ArrayList<String>();Map.Entry<String,String> next;
        while((next=cursor.next())!=null)seen.add(next.getKey());
        assertThat(seen).containsExactly("key-00100","key-00101","key-00102","key-00103","key-00104");
    }

    @Test void storedNodesReadBackAsTheSameTree()throws Exception{
        var values=entries(400);var tree=KeyedTree.build(SPEC,List.copyOf(values.entrySet()));
        var nodes=new HashMap<Hash256,byte[]>();tree.writeNodes(nodes::put);
        assertThat(nodes).hasSize(400);
        var read=KeyedTree.read(SPEC,tree.rootHash(),nodes::get);
        assertThat(read.rootHash()).isEqualTo(tree.rootHash());
        assertThat(read.range("key-00100","key-00199")).isEqualTo(tree.range("key-00100","key-00199"));
        assertThat(read.get("key-00321")).isEqualTo("value-321");
        assertThat(read.entries()).containsExactlyElementsOf(tree.entries());
        assertThat(read.put("key-00321","other").rootHash()).isEqualTo(tree.put("key-00321","other").rootHash());
        assertThat(KeyedTree.read(SPEC,KeyedTree.EMPTY,nodes::get).isEmpty()).isTrue();
    }

    @Test void bulkBuildRejectsUnorderedEntries(){
        assertThatThrownBy(()->KeyedTree.build(SPEC,List.of(Map.entry("b","1"),Map.entry("a","2"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void aggregateIsOrderIndependentAndReversible(){
        var empty=Aggregate.empty("content");
        var ab=empty.add("a","1").add("b","2");var ba=empty.add("b","2").add("a","1");
        assertThat(ab).isEqualTo(ba);
        assertThat(ab.remove("b","2")).isEqualTo(empty.add("a","1"));
        assertThat(ab.replace("a","1","a","3")).isEqualTo(empty.add("b","2").add("a","3"));
        assertThat(ab.cardinality()).isEqualTo(2);
    }

    @Test void rootRoundTripsAndCoversEveryPart()throws Exception{
        var aggregates=List.of(Aggregate.empty("resolution").add("x","1"),Aggregate.empty("documentation"));
        var root=new Root(1,CanonicalDigestWriter.digest("tree"),aggregates,3);
        assertThat(Root.decode(root.encode())).isEqualTo(root);
        assertThat(Root.decode(root.encode()).identity()).isEqualTo(root.identity());
        assertThat(new Root(1,root.tree(),List.of(aggregates.get(1),aggregates.get(0)),3).identity()).isEqualTo(root.identity());
        assertThat(new Root(2,root.tree(),aggregates,3).identity()).isNotEqualTo(root.identity());
        assertThat(new Root(1,root.tree(),aggregates,4).identity()).isNotEqualTo(root.identity());
        assertThat(new Root(1,root.tree(),List.of(aggregates.get(0).add("y","2"),aggregates.get(1)),3).identity()).isNotEqualTo(root.identity());
    }

    /** C3 and M4: staging holds one bounded batch whatever the layer's size; one sync, then the root. */
    @Test void commitStagesBoundedBatchesThenSyncsThenWritesTheRoot()throws Exception{
        var events=new ArrayList<String>();var staged=new ArrayList<Integer>();var stagedBytes=new ArrayList<Integer>();
        var commit=new Commit(new Commit.Store(){
            @Override public void stage(List<Map.Entry<byte[],byte[]>> batch){
                events.add("stage");staged.add(batch.size());stagedBytes.add(batch.stream().mapToInt(record->record.getKey().length+record.getValue().length).sum());
            }
            @Override public void sync(){events.add("sync");}
            @Override public void root(byte[] root){events.add("root");}
        },100);
        for(int i=0;i<5000;i++)commit.put(new byte[10],new byte[10]);
        assertThat(events).doesNotContain("root","sync");
        commit.root(new Root(1,KeyedTree.EMPTY,List.of(),0));
        assertThat(events.subList(events.size()-2,events.size())).containsExactly("sync","root");
        assertThat(staged.stream().mapToInt(Integer::intValue).sum()).isEqualTo(5000);
        assertThat(stagedBytes).allSatisfy(bytes->assertThat(bytes).isLessThanOrEqualTo(100));
        assertThat(events.stream().filter("sync"::equals).count()).isEqualTo(1);
        assertThat(events.stream().filter("root"::equals).count()).isEqualTo(1);
        assertThatThrownBy(()->commit.put(new byte[1],new byte[1])).isInstanceOf(IllegalStateException.class);
    }
}
