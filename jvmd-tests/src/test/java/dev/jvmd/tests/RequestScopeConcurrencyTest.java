package dev.jvmd.tests;

import dev.jvmd.core.RequestScope;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class RequestScopeConcurrencyTest {
    @Test void memoSupplierExecutesOnceAcrossParallelActors()throws Exception{
        var calls=new AtomicInteger();
        var values=RequestScope.call("diag.get",()->{
            var request=RequestScope.current();
            var results=new ArrayList<Object>();
            try(var executor=Executors.newFixedThreadPool(4)){
                var futures=new ArrayList<Future<Object>>();
                for(int i=0;i<8;i++)futures.add(executor.submit(()->RequestScope.with(request,()->
                        RequestScope.memo("shared",()->{
                            calls.incrementAndGet();
                            Thread.sleep(25);
                            return new Object();
                        }))));
                for(var future:futures)results.add(future.get());
            }
            return List.copyOf(results);
        });
        assertThat(calls).hasValue(1);
        assertThat(values).hasSize(8);
        Object first=values.getFirst();
        assertThat(values).allMatch(value->value==first);
    }
}
