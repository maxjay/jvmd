package dev.jvmd.tests;

import dev.jvmd.core.Hash256;
import dev.jvmd.core.tree.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-1")
class KeyedTreeTest {
    private static final KeyedTree.Schema<String> SCHEMA=new KeyedTree.Schema<>("keyed-tree-test",
            value->Hash256.sha256(value.getBytes(StandardCharsets.UTF_8)),
            List.of(new KeyedTree.Projection<>("signature",value->value.startsWith("body:")?null:value.split("\\|")[0])));

    private static Map<String,String> entries(int count){
        var result=new LinkedHashMap<String,String>();
        for(int i=0;i<count;i++)result.put("Owner#m"+String.format("%04d",i),"sig"+(i%7)+"|body"+i);
        return result;
    }

    @Test void equalKeySetsGiveEqualRootsInAnyInsertionOrder(){
        var values=entries(500);var keys=new ArrayList<>(values.keySet());
        var forward=KeyedTree.empty(SCHEMA);for(String key:keys)forward=forward.put(key,values.get(key));
        Collections.shuffle(keys,new Random(7));
        var shuffled=KeyedTree.empty(SCHEMA);for(String key:keys)shuffled=shuffled.put(key,values.get(key));
        var bulk=KeyedTree.build(SCHEMA,values);
        assertThat(shuffled.rootHash()).isEqualTo(forward.rootHash());
        assertThat(bulk.rootHash()).isEqualTo(forward.rootHash());
        assertThat(bulk.total(0)).isEqualTo(forward.total(0));
        assertThat(bulk.size()).isEqualTo(500);
    }

    @Test void removeThenReinsertRestoresTheRootAndEarlierVersionsStayReadable(){
        var tree=KeyedTree.build(SCHEMA,entries(64));var before=tree.rootHash();
        var removed=tree.remove("Owner#m0010");
        assertThat(removed.rootHash()).isNotEqualTo(before);
        assertThat(tree.get("Owner#m0010")).isNotNull();
        assertThat(removed.put("Owner#m0010",tree.get("Owner#m0010")).rootHash()).isEqualTo(before);
        assertThat(KeyedTree.empty(SCHEMA).put("a","x").remove("a").rootHash()).isEqualTo(KeyedTree.empty(SCHEMA).rootHash());
    }

    @Test void rangeSumsEqualTheSumOverListedEntriesAndIgnoreOtherProjections(){
        var tree=KeyedTree.build(SCHEMA,entries(300));
        var listed=Aggregate.empty("signature");var cursor=tree.cursor("Owner#m01","Owner#m01￿");
        for(var entry=cursor.next();entry!=null;entry=cursor.next())listed=listed.add(entry.getKey(),entry.getValue().split("\\|")[0]);
        assertThat(tree.prefix(0,"Owner#m01")).isEqualTo(listed.value());
        assertThat(listed.cardinality()).isEqualTo(100);
        var bodyOnly=tree.put("Owner#m0105","sig0|changed body");
        assertThat(bodyOnly.rootHash()).isNotEqualTo(tree.rootHash());
        assertThat(bodyOnly.prefix(0,"Owner#m01")).isEqualTo(tree.prefix(0,"Owner#m01"));
        var signature=tree.put("Owner#m0105","sig9|body");
        assertThat(signature.prefix(0,"Owner#m01")).isNotEqualTo(tree.prefix(0,"Owner#m01"));
        assertThat(signature.prefix(0,"Owner#m02")).isEqualTo(tree.prefix(0,"Owner#m02"));
    }

    @Test void diffReportsExactlyTheChangedEntries(){
        var tree=KeyedTree.build(SCHEMA,entries(1000));
        var next=tree.put("Owner#m0500","changed").remove("Owner#m0001").put("Owner#n","added");
        assertThat(next.diff(tree)).containsExactly(
                new KeyedTree.Change<>("Owner#m0001","sig1|body1",null),
                new KeyedTree.Change<>("Owner#m0500","sig3|body500","changed"),
                new KeyedTree.Change<>("Owner#n",null,"added"));
        assertThat(tree.diff(tree)).isEmpty();
    }

    @Test void storedNodesReadBackAsTheSameTree(){
        var tree=KeyedTree.build(SCHEMA,entries(200));
        var stored=new HashMap<Hash256,KeyedTree.StoredNode>();
        for(var node:tree.nodes(value->value.getBytes(StandardCharsets.UTF_8)))stored.put(node.hash(),node);
        assertThat(stored).hasSize(200);
        var read=KeyedTree.read(SCHEMA,tree.rootHash(),stored::get,bytes->new String(bytes,StandardCharsets.UTF_8));
        assertThat(read.rootHash()).isEqualTo(tree.rootHash());assertThat(read.entries()).isEqualTo(tree.entries());
        assertThat(read.prefix(0,"Owner#m00")).isEqualTo(tree.prefix(0,"Owner#m00"));
        var empty=KeyedTree.empty(SCHEMA);
        assertThat(KeyedTree.read(SCHEMA,empty.rootHash(),stored::get,bytes->"").isEmpty()).isTrue();
    }

    @Test void rootRoundTripsAndRejectsTruncatedRecords(){
        var tree=KeyedTree.build(SCHEMA,entries(10));
        var aggregate=Aggregate.empty("content").add("a","1").add("b","2");
        var root=Root.of(3,tree.rootHash(),List.of(aggregate),tree.size());
        byte[] encoded=root.encode();
        assertThat(Root.decode(encoded)).contains(root);
        assertThat(Root.decode(Arrays.copyOf(encoded,encoded.length-1))).isEmpty();
        assertThat(Root.decode(null)).isEmpty();
        assertThat(aggregate.remove("a","1").add("a","1")).isEqualTo(aggregate);
        assertThat(Aggregate.empty("content").add("b","2").add("a","1")).isEqualTo(aggregate);
    }
}
