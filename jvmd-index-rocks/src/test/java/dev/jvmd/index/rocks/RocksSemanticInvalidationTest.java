package dev.jvmd.index.rocks;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RocksSemanticInvalidationTest {
    @TempDir Path temp;

    @Test void bodyOnlyChangeStaysLocalButApiChangePropagatesTransitively()throws Exception{
        Path a=temp.resolve("A.java").toAbsolutePath(),b=temp.resolve("B.java").toAbsolutePath(),c=temp.resolve("C.java").toAbsolutePath();
        try(var state=new RocksSemanticInvalidation(temp.resolve("semantic"))){
            var initial=Map.of(
                    a,file("content-a1","api-a",Set.of(),Set.of("pkg.A"),Set.of()),
                    b,file("content-b1","api-b",Set.of(a),Set.of("pkg.B"),Set.of()),
                    c,file("content-c1","api-c",Set.of(b),Set.of("pkg.C"),Set.of()));
            state.update("module","ctx",initial);

            var body=new LinkedHashMap<>(initial);body.put(a,file("content-a2","api-a",Set.of(),Set.of("pkg.A"),Set.of()));
            var bodyResult=state.update("module","ctx",body);
            assertThat(bodyResult.bodyOnly()).containsExactly(a);
            assertThat(bodyResult.reanalyze()).containsExactly(a);

            var api=new LinkedHashMap<>(body);api.put(a,file("content-a3","api-a2",Set.of(),Set.of("pkg.A"),Set.of()));
            var apiResult=state.update("module","ctx",api);
            assertThat(apiResult.apiChanged()).containsExactly(a);
            assertThat(apiResult.reanalyze()).containsExactlyInAnyOrder(a,b,c);
        }
    }

    @Test void cyclesTerminateAndUnresolvedTargetsInvalidateConservatively()throws Exception{
        Path a=temp.resolve("A.java").toAbsolutePath(),b=temp.resolve("B.java").toAbsolutePath(),u=temp.resolve("U.java").toAbsolutePath();
        try(var state=new RocksSemanticInvalidation(temp.resolve("cycle"))){
            var initial=Map.of(
                    a,file("a1","api-a",Set.of(b),Set.of("pkg.A"),Set.of()),
                    b,file("b1","api-b",Set.of(a),Set.of("pkg.B"),Set.of()),
                    u,file("u1","api-u",Set.of(),Set.of("pkg.U"),Set.of("pkg.A")));
            state.update("module","ctx",initial);
            var changed=new LinkedHashMap<>(initial);changed.put(a,file("a2","api-a2",Set.of(b),Set.of("pkg.A"),Set.of()));
            var result=state.update("module","ctx",changed);
            assertThat(result.reanalyze()).containsExactlyInAnyOrder(a,b,u);
        }
    }

    @Test void contextChangeInvalidatesAllAndDeletionInvalidatesDependants()throws Exception{
        Path a=temp.resolve("A.java").toAbsolutePath(),b=temp.resolve("B.java").toAbsolutePath();
        try(var state=new RocksSemanticInvalidation(temp.resolve("context"))){
            var initial=Map.of(
                    a,file("a1","api-a",Set.of(),Set.of("pkg.A"),Set.of()),
                    b,file("b1","api-b",Set.of(a),Set.of("pkg.B"),Set.of()));
            state.update("module","ctx-1",initial);
            var context=state.update("module","ctx-2",initial);
            assertThat(context.contextChanged()).isTrue();
            assertThat(context.reanalyze()).containsExactlyInAnyOrder(a,b);

            var onlyB=Map.of(b,file("b1","api-b",Set.of(a),Set.of("pkg.B"),Set.of()));
            var deleted=state.update("module","ctx-2",onlyB);
            assertThat(deleted.deleted()).containsExactly(a);
            assertThat(deleted.reanalyze()).containsExactly(b);
        }
    }

    private static RocksSemanticInvalidation.FileInput file(String content,String api,Set<Path> deps,Set<String> exports,Set<String> unresolved){
        return new RocksSemanticInvalidation.FileInput(content,api,deps,exports,unresolved);
    }
}
