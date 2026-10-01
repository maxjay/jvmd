package dev.jvmd.core;

import java.nio.file.Path;
import java.util.*;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/** Any put/remove history ending in the same leaves yields the same live-state identities. */
@Tag("phase-1")
class LiveStateTreeHistoryTest {
    @TempDir Path root;

    private static final Map<String,ToLongFunction<String>> PRIORITIES=Map.of(
            "default",key->IdentityEncoder.of("merkle-priority-v2",key).lo(),
            "all-ties",key->0L,
            "partial-ties",key->key.length()%3==0?-1L:key.length());

    @Test void historyIndependentUnderEveryPriorityFunction(){
        PRIORITIES.forEach((name,priority)->{
            var random=new Random(name.hashCode());
            for(int round=0;round<20;round++){
                var tree=new LiveStateTree(List.of(root),priority);var model=new TreeMap<Path,LiveStateTree.Leaf>();
                for(int step=0;step<120;step++){
                    Path file=root.resolve("d"+random.nextInt(3)).resolve("F"+random.nextInt(25)+".java");
                    if(random.nextInt(4)==0){tree.remove(file);model.remove(file);}
                    else{var leaf=leaf(file,random.nextInt(3));tree.put(leaf);model.put(file,leaf);}
                }
                var bulk=new LiveStateTree(List.of(root),priority);model.values().forEach(bulk::put);
                var reversed=new LiveStateTree(List.of(root),priority);model.descendingMap().values().forEach(reversed::put);
                assertSameIdentity(name,tree.state(),bulk.state());
                assertSameIdentity(name,tree.state(),reversed.state());
            }
        });
    }

    @Test void forcedTiesStillDistinguishContent(){
        var tied=new LiveStateTree(List.of(root),key->0L);var other=new LiveStateTree(List.of(root),key->0L);
        for(int i=0;i<10;i++){tied.put(leaf(root.resolve("F"+i+".java"),0));other.put(leaf(root.resolve("F"+i+".java"),i==5?1:0));}
        assertThat(tied.state().merkle()).isNotEqualTo(other.state().merkle());
    }

    private static LiveStateTree.Leaf leaf(Path file,int version){
        String name=file.getFileName().toString();
        return LiveStateTree.source(file,"content-"+name+"-"+version,"api-"+name+"-"+(version%2),List.of(name,"n"+version));
    }
    private static void assertSameIdentity(String name,LiveStateTree.State actual,LiveStateTree.State expected){
        assertThat(actual.merkle()).as(name).isEqualTo(expected.merkle());
        assertThat(actual.membership()).as(name).isEqualTo(expected.membership());
        assertThat(actual.content()).as(name).isEqualTo(expected.content());
        assertThat(actual.api()).as(name).isEqualTo(expected.api());
        assertThat(actual.namespace()).as(name).isEqualTo(expected.namespace());
        assertThat(actual.files()).as(name).isEqualTo(expected.files());
    }
}
