package dev.jvmd.tests;

import dev.jvmd.core.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class DiagnosticSchedulingTest {
    @Test void interactiveJobRunsAtBatchBoundaryOnSameOwnerWithIsolatedRequestScope()throws Exception{
        try(var session=new Session("diagnostics",Path.of("."))){
            var started=new CountDownLatch(1);var resume=new CountDownLatch(1);var interactive=new CountDownLatch(1);
            var owner=new java.util.concurrent.atomic.AtomicReference<Thread>();
            try(var clients=Executors.newVirtualThreadPerTaskExecutor()){
                var workspace=clients.submit(()->session.execute(5,()->RequestScope.call("diag.get",()->{
                    owner.set(Thread.currentThread());RequestScope.memo("value",()->"workspace");started.countDown();resume.await();
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                    while(interactive.getCount()>0&&System.nanoTime()<deadline){session.yieldInteractive();Thread.onSpinWait();}
                    assertThat(interactive.getCount()).isZero();
                    assertThat(RequestScope.method()).isEqualTo("diag.get");
                    assertThat(RequestScope.memo("value",()->"wrong")).isEqualTo("workspace");return true;
                })));
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
                var query=clients.submit(()->session.execute(1,()->RequestScope.call("lsp.diagnostics",()->{
                    assertThat(Thread.currentThread()).isSameAs(owner.get());assertThat(Thread.currentThread().isVirtual()).isFalse();
                    assertThat(RequestScope.memo("value",()->"interactive")).isEqualTo("interactive");interactive.countDown();return true;
                })));
                resume.countDown();assertThat(query.get(10,TimeUnit.SECONDS)).isTrue();assertThat(workspace.get(10,TimeUnit.SECONDS)).isTrue();
            }finally{resume.countDown();}
        }
    }
}
