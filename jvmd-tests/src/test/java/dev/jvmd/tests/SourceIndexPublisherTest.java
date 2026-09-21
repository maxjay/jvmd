package dev.jvmd.tests;

import dev.jvmd.index.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class SourceIndexPublisherTest {
    @Test void blockedPublicationDoesNotBlockEnqueueAndLatestPendingVersionWins()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var versions=new CopyOnWriteArrayList<String>();
        try(var publisher=new SourceIndexPublisher(delta->{entered.countDown();release.await();versions.add(delta.sourceHash());},4096)){
            try{
                publisher.enqueue(delta("first"));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                publisher.enqueue(delta("second"));publisher.enqueue(delta("third"));
                assertThat(publisher.status()).containsEntry("queued_files",1).containsEntry("coalesced",1L);
                assertThat(versions).isEmpty();
            }finally{release.countDown();}
        }
        assertThat(versions).containsExactly("first","third");
    }

    @Test void identicalDeltaQueuedDuringAnInFlightWriteIsNotWrittenAgain()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var versions=new CopyOnWriteArrayList<String>();
        var publisher=new SourceIndexPublisher(delta->{entered.countDown();release.await();versions.add(delta.sourceHash());},4096);
        try{
            publisher.enqueue(delta("same"));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            publisher.enqueue(delta("same"));
            assertThat(publisher.status()).containsEntry("queued_files",1);
        }finally{
            release.countDown();
            publisher.close();
        }
        assertThat(versions).containsExactly("same");
        assertThat(publisher.status()).containsEntry("writes",1L).containsEntry("skipped",1L);
    }

    private static SourceIndexPublisher.Delta delta(String hash){
        var contribution=new FileSemanticContribution(Path.of("A.java"),hash,"",Set.of(),Set.of(),Set.of());
        return new SourceIndexPublisher.Delta(contribution,hash,List.of(),2,List.of(),256,"","");
    }
}
