package dev.jvmd.boot.cold.local;

import dev.jvmd.analyzer.Analyzer;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** L3 and L5: every queued unit is handed out exactly once, a requested unit first. */
class UnitQueueTest {
    private static Context context(String module){
        return new Context(module,"main",new Analyzer.Context(module,"25",List.of(),List.of(),module,Map.of()));
    }

    @Test void eachUnitIsHandedOutOnceAndARequestedUnitComesFirst(){
        var lib=context("g:lib:1");var app=context("g:app:1");var queue=new UnitQueue();
        var units=new ArrayList<UnitQueue.Unit>();
        for(int i=0;i<10;i++)units.add(new UnitQueue.Unit(Path.of("/p/lib/L"+i+".java"),lib,"lib/L"+i+".java","L"+i));
        for(int i=0;i<10;i++)units.add(new UnitQueue.Unit(Path.of("/p/app/A"+i+".java"),app,"app/A"+i+".java","A"+i));
        queue.addAll(units);

        assertThat(queue.prioritize(Path.of("/p/app/A7.java"))).isTrue();
        var first=queue.next(4);
        assertThat(first.getFirst().path()).isEqualTo("app/A7.java");
        assertThat(first).allSatisfy(unit->assertThat(unit.context()).isEqualTo(app)).hasSize(4);
        // A unit already handed out is not queued again.
        assertThat(queue.prioritize(Path.of("/p/app/A7.java"))).isFalse();

        var handedOut=new ArrayList<UnitQueue.Unit>(first);
        for(var batch=queue.next(4);!batch.isEmpty();batch=queue.next(4)){
            assertThat(batch.stream().map(unit->unit.context().key()).distinct()).hasSize(1);
            handedOut.addAll(batch);
            if(handedOut.size()==8)queue.prioritize(Path.of("/p/lib/L9.java"));
        }
        assertThat(handedOut).hasSize(20).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(units);
        assertThat(queue.isEmpty()).isTrue();
    }
}
