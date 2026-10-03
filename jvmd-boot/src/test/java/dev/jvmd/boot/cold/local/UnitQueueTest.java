package dev.jvmd.boot.cold.local;

import dev.jvmd.analyzer.Analyzer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** 5.3 step 5: each unit is handed out once, and a requested unit's batch comes first. */
class UnitQueueTest {
    @TempDir Path root;

    private static Context context(String module){
        return new Context(module,"main",new Analyzer.Context(module,"25",List.of(),List.of(),module,Map.of()));
    }

    private UnitQueue.Unit unit(Context context,Map<Path,String> texts,String binaryName,String text){
        Path file=root.resolve(binaryName.replace('.','/')+".java");texts.put(file.toAbsolutePath().normalize(),text);
        return new UnitQueue.Unit(file,context,binaryName.replace('.','/')+".java",binaryName);
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
