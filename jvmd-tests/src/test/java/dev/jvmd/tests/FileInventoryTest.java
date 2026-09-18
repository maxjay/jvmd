package dev.jvmd.tests;

import dev.jvmd.core.FileInventory;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class FileInventoryTest {
    @TempDir Path root;
    @Test void snapshotRenamesInsideSourceRootDoNotBreakReconciliation()throws Exception{
        Path source=Files.writeString(root.resolve("Stable.java"),"class Stable {}"),state=Files.createDirectories(root.resolve("state/diagnostics"));
        var running=new AtomicBoolean(true);var writes=new AtomicInteger();var started=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()){
            var writer=executor.submit(()->{
                while(running.get())try{
                    Path temporary=Files.createTempFile(state,"snapshot-",".tmp");Files.writeString(temporary,"{}");
                    Files.move(temporary,state.resolve("snapshot.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    writes.incrementAndGet();started.countDown();
                }catch(Exception e){throw new IllegalStateException(e);}
            });
            try{
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
                for(int i=0;i<100;i++)assertThat(FileInventory.matching(root,".java")).containsExactly(source);
            }finally{running.set(false);writer.get();}
        }
        assertThat(writes.get()).isPositive();
    }
}
