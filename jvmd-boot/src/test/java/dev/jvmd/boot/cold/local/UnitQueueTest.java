package dev.jvmd.boot.cold.local;

import dev.jvmd.analyzer.Analyzer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** L3, L5 and 5.3 step 5: batches keep dependency cycles together, and each unit is handed out once. */
class UnitQueueTest {
    @TempDir Path root;

    private static Context context(String module){
        return new Context(module,"main",new Analyzer.Context(module,"25",List.of(),List.of(),module,Map.of()));
    }

    private UnitQueue.Unit unit(Context context,Map<Path,String> texts,String binaryName,String text){
        Path file=root.resolve(binaryName.replace('.','/')+".java");texts.put(file.toAbsolutePath().normalize(),text);
        return new UnitQueue.Unit(file,context,binaryName.replace('.','/')+".java",binaryName);
    }

    @Test void batchesKeepCyclesTogetherInDependencyOrder()throws Exception{
        var app=context("g:app:1");var texts=new HashMap<Path,String>();var units=new ArrayList<UnitQueue.Unit>();
        // a.A <-> a.B form a cycle; c.C uses a.A; d.D stands alone; e.E names a.B by its qualified name.
        units.add(unit(app,texts,"a.A","package a; public class A { B b; }"));
        units.add(unit(app,texts,"a.B","package a; public class B { A a; }"));
        units.add(unit(app,texts,"c.C","package c; import a.A; public class C { A a; }"));
        units.add(unit(app,texts,"d.D","package d; public class D { }"));
        units.add(unit(app,texts,"e.E","package e; public class E { a.B.Inner b; }"));
        var batches=UnitGraph.batches(units,texts);
        var order=new ArrayList<String>();for(var batch:batches)for(var unit:batch)order.add(unit.binaryName());
        assertThat(order).containsExactlyInAnyOrder("a.A","a.B","c.C","d.D","e.E");
        assertThat(order.indexOf("a.A")).isLessThan(order.indexOf("c.C"));
        assertThat(order.indexOf("a.B")).isLessThan(order.indexOf("e.E"));
        assertThat(batches).anySatisfy(batch->assertThat(batch).extracting(UnitQueue.Unit::binaryName).contains("a.A","a.B"));
    }

    @Test void aCycleLargerThanABatchIsNotSplit()throws Exception{
        var app=context("g:app:1");var texts=new HashMap<Path,String>();var units=new ArrayList<UnitQueue.Unit>();
        int size=UnitGraph.UNITS+10;
        for(int i=0;i<size;i++)units.add(unit(app,texts,"r.R"+i,"package r; public class R"+i+" { R"+((i+1)%size)+" next; }"));
        units.add(unit(app,texts,"s.S","package s; public class S { }"));
        var batches=UnitGraph.batches(units,texts);
        assertThat(batches).anySatisfy(batch->assertThat(batch).hasSize(size));
    }

    @Test void eachBatchIsHandedOutOnceAndARequestedUnitsBatchComesFirst(){
        var lib=context("g:lib:1");var app=context("g:app:1");var queue=new UnitQueue();var texts=new HashMap<Path,String>();
        var libBatch=List.of(unit(lib,texts,"l.L0","package l; class L0 {}"),unit(lib,texts,"l.L1","package l; class L1 {}"));
        var appFirst=List.of(unit(app,texts,"p.A0","package p; class A0 {}"));
        var appSecond=List.of(unit(app,texts,"p.A7","package p; class A7 {}"),unit(app,texts,"p.A8","package p; class A8 {}"));
        queue.addAll(List.of(libBatch));queue.addAll(List.of(appFirst,appSecond));

        assertThat(queue.prioritize(root.resolve("p/A7.java"))).isTrue();
        assertThat(queue.next()).isEqualTo(appSecond);
        // A unit already handed out is not queued again.
        assertThat(queue.prioritize(root.resolve("p/A7.java"))).isFalse();
        var handedOut=new ArrayList<>(appSecond);
        for(var batch=queue.next();!batch.isEmpty();batch=queue.next())handedOut.addAll(batch);
        assertThat(handedOut).hasSize(5).doesNotHaveDuplicates();
        assertThat(queue.isEmpty()).isTrue();
    }
}
