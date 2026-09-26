package dev.jvmd.tests;

import dev.jvmd.core.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-2")
class LiveSourceStateTest {
    @TempDir Path root;

    @Test void editorOverlayMutatesContentAndMembershipBeforeAnyRequest()throws Exception{
        Path disk=Files.writeString(root.resolve("A.java"),"class A {}");
        try(var documents=new Documents(new FileStateRegistry())){
            var state=documents.liveState(List.of(root));var initial=state.snapshot();
            documents.open(disk,"class A { int x; }",1);
            assertThat(state.leaf(disk)).get().extracting(v->v.content().value()).isEqualTo(documents.hash(disk));
            assertThat(state.snapshot().state().content()).isNotEqualTo(initial.state().content());
            Path unsaved=root.resolve("Unsaved.java");documents.open(unsaved,"class Unsaved {}",1);
            assertThat(state.paths()).contains(unsaved.toAbsolutePath().normalize());
            documents.close(unsaved);assertThat(state.paths()).doesNotContain(unsaved.toAbsolutePath().normalize());
            documents.close(disk);
            assertThat(state.leaf(disk)).get().extracting(v->v.content().value()).isEqualTo(Hashing.sha256(disk));
        }
    }

    @Test void semanticIdentityIsAcceptedOnlyForTheAnalysedContent()throws Exception{
        Path file=Files.writeString(root.resolve("A.java"),"class A {}");
        try(var documents=new Documents(new FileStateRegistry())){
            var state=documents.liveState(List.of(root));String first=state.leaf(file).orElseThrow().content().value();
            assertThat(state.snapshot().state().semanticsCurrent()).isFalse();
            assertThat(state.semantic(file,first,"api-1",List.of("A"))).isTrue();var semantic=state.snapshot().state();
            assertThat(semantic.semanticsCurrent()).isTrue();assertThat(semantic.pendingSemanticFiles()).isZero();
            documents.open(file,"class A { int body; }",1);String second=documents.hash(file);
            var pending=state.snapshot().state();
            assertThat(pending.api()).isEqualTo(semantic.api());
            assertThat(pending.semanticsCurrent()).isFalse();assertThat(pending.pendingSemanticFiles()).isEqualTo(1);
            var leaf=state.leaf(file).orElseThrow();assertThat(leaf.semanticContent().value()).isEqualTo(first);assertThat(leaf.content().value()).isEqualTo(second);
            assertThat(state.semantic(file,first,"stale-api",List.of("Stale"))).isFalse();
            assertThat(state.snapshot().state().semanticsCurrent()).isFalse();
            assertThat(state.semantic(file,second,"api-1",List.of("A"))).isTrue();
            assertThat(state.snapshot().state().api()).isEqualTo(semantic.api());
            assertThat(state.snapshot().state().semanticsCurrent()).isTrue();
        }
    }

    @Test void watcherObservesClosedDiskEditsIncludingPreservedMtime()throws Exception{
        Path file=Files.writeString(root.resolve("A.java"),"class A { int x=1; }");
        try(var documents=new Documents(new FileStateRegistry())){
            var state=documents.liveState(List.of(root));String before=state.leaf(file).orElseThrow().content().value();
            FileTime timestamp=Files.getLastModifiedTime(file);Files.writeString(file,"class A { int x=2; }");Files.setLastModifiedTime(file,timestamp);
            await(Duration.ofSeconds(5),()->state.leaf(file).map(v->!v.content().value().equals(before)).orElse(false));
            assertThat(state.snapshot().trusted()).isTrue();
        }
    }

    @Test void settlementWaitsForAnAlreadyDequeuedSourceMutation()throws Exception{
        Path file=Files.writeString(root.resolve("A.java"),"class A { int x=1; }");
        var files=new FileStateRegistry();
        try(var documents=new Documents(files)){
            var state=documents.liveState(List.of(root));
            var watcherField=LiveSourceState.class.getDeclaredField("watchThread");watcherField.setAccessible(true);
            Thread watcher=(Thread)watcherField.get(state);
            assertThat(watcher).isNotNull();
            String changed="class A { int x=2; }";
            var result=new java.util.concurrent.CompletableFuture<String>();
            Thread reader=Thread.ofPlatform().unstarted(()->{
                try{state.settleWatchEvents();result.complete(state.contentHash(file));}
                catch(Throwable failure){result.completeExceptionally(failure);}
            });
            synchronized(files){
                var timestamp=Files.getLastModifiedTime(file);
                Files.writeString(file,changed);Files.setLastModifiedTime(file,timestamp);
                // The watcher has removed the event from its queue but cannot publish its hash.
                await(Duration.ofSeconds(5),()->watcher.getState()==Thread.State.BLOCKED
                        &&Arrays.stream(watcher.getStackTrace()).anyMatch(frame->frame.getMethodName().equals("hash")));
                reader.start();
                await(Duration.ofSeconds(5),()->result.isDone()||reader.getState()==Thread.State.BLOCKED);
            }
            assertThat(result.get(5,java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(Hashing.sha256(changed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            reader.join();
        }
    }

    @Test void uncertaintyAdvancesEpochEvenWhenReconciliationRestoresSameIdentity()throws Exception{
        Files.writeString(root.resolve("A.java"),"class A {}");
        try(var documents=new Documents(new FileStateRegistry())){
            var state=documents.liveState(List.of(root));var before=state.snapshot();
            state.markUncertain("test overflow");var uncertain=state.snapshot();
            assertThat(uncertain.trusted()).isFalse();assertThat(uncertain.state().merkle()).isEqualTo(before.state().merkle());
            assertThat(uncertain.state().epoch()).isGreaterThan(before.state().epoch());
            state.reconcile();var after=state.snapshot();
            assertThat(after.trusted()).isTrue();assertThat(after.state().merkle()).isEqualTo(before.state().merkle());
            assertThat(after.state().epoch()).isGreaterThan(uncertain.state().epoch());
        }
    }

    @Test void boundedSourceHistoryLossReportsUncertaintyWithoutWorkspaceReconciliation()throws Exception{
        Path file=Files.writeString(root.resolve("A.java"),"class A { int value; }");
        try(var documents=new Documents(new FileStateRegistry())){
            var state=documents.liveState(List.of(root));
            var before=state.snapshot();
            documents.open(file,"class A { int value=1; }",1);
            for(int version=2;version<32775;version++)
                documents.change(file,version,List.of(new Documents.Change(null,"class A { int value="+version+"; }")));

            var after=state.snapshot();
            assertThat(after.inputEpoch()).isGreaterThan(before.inputEpoch());
            assertThat(state.changedPathsSince(before.inputEpoch())).isEmpty();
            assertThat(after.reconciliations()).isEqualTo(before.reconciliations());
            assertThat(after.trusted()).isTrue();
            assertThat(state.paths()).contains(file.toAbsolutePath().normalize());
        }
    }

    @Test void recreatedWatchedRootIsVisibleAtTheNextCorrectnessBoundary()throws Exception{
        Path sourceRoot=Files.createDirectories(root.resolve("src"));
        Path first=Files.writeString(sourceRoot.resolve("A.java"),"class A {}");
        try(var documents=new Documents(new FileStateRegistry())){
            var state=documents.liveState(List.of(sourceRoot));
            var initial=state.snapshot();
            assertThat(state.paths()).contains(first.toAbsolutePath().normalize());

            Files.delete(first);
            Files.delete(sourceRoot);
            state.settleWatchEvents();
            var removed=state.snapshot();
            assertThat(state.paths()).doesNotContain(first.toAbsolutePath().normalize());
            assertThat(state.status()).containsEntry("verification_only",true);

            Path recreated=Files.createDirectories(sourceRoot);
            Path second=Files.writeString(recreated.resolve("B.java"),"class B {}");
            state.verifyTransactionBoundary();
            var after=state.snapshot();

            assertThat(after.trusted()).isTrue();
            assertThat(state.paths()).contains(second.toAbsolutePath().normalize()).doesNotContain(first.toAbsolutePath().normalize());
            assertThat(after.state().membership()).isNotEqualTo(removed.state().membership());
            assertThat(after.state().content()).isNotEqualTo(removed.state().content());
            assertThat(after.state().merkle()).isNotEqualTo(initial.state().merkle());
        }
    }

    private static void await(Duration timeout,java.util.function.BooleanSupplier condition)throws Exception{
        long deadline=System.nanoTime()+timeout.toNanos();
        while(System.nanoTime()<deadline){if(condition.getAsBoolean())return;Thread.sleep(20);}
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
