package dev.jvmd.index.rocks;

import dev.jvmd.index.FileSemanticContribution;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RocksSemanticInvalidationTest {
    @TempDir Path temp;

    @Test void bodyOnlyChangeStaysLocalButApiChangePropagatesTransitively()throws Exception{
        Path a=temp.resolve("A.java"),b=temp.resolve("B.java"),c=temp.resolve("C.java");
        try(var state=new RocksSemanticInvalidation(temp.resolve("semantic"))){
            state.observeFile("module","ctx",file(a,"content-a1","api-a",Set.of(),Set.of("pkg.A"),Set.of()));
            state.observeFile("module","ctx",file(b,"content-b1","api-b",Set.of(a),Set.of("pkg.B"),Set.of()));
            state.observeFile("module","ctx",file(c,"content-c1","api-c",Set.of(b),Set.of("pkg.C"),Set.of()));

            var body=state.observeFile("module","ctx",file(a,"content-a2","api-a",Set.of(),Set.of("pkg.A"),Set.of()));
            assertThat(body.bodyOnly()).containsExactly(a.toAbsolutePath());
            assertThat(body.reanalyze()).containsExactly(a.toAbsolutePath());

            var api=state.observeFile("module","ctx",file(a,"content-a3","api-a2",Set.of(),Set.of("pkg.A"),Set.of()));
            assertThat(api.apiChanged()).containsExactly(a.toAbsolutePath());
            assertThat(api.reanalyze()).containsExactlyInAnyOrder(a.toAbsolutePath(),b.toAbsolutePath(),c.toAbsolutePath());
        }
    }

    @Test void cyclesTerminateAndUnresolvedTargetsInvalidateConservatively()throws Exception{
        Path a=temp.resolve("A.java"),b=temp.resolve("B.java"),u=temp.resolve("U.java");
        try(var state=new RocksSemanticInvalidation(temp.resolve("cycle"))){
            state.observeFile("module","ctx",file(a,"a1","api-a",Set.of(b),Set.of("pkg.A"),Set.of()));
            state.observeFile("module","ctx",file(b,"b1","api-b",Set.of(a),Set.of("pkg.B"),Set.of()));
            state.observeFile("module","ctx",file(u,"u1","api-u",Set.of(),Set.of("pkg.U"),Set.of("pkg.A")));
            var result=state.observeFile("module","ctx",file(a,"a2","api-a2",Set.of(b),Set.of("pkg.A"),Set.of()));
            assertThat(result.reanalyze()).containsExactlyInAnyOrder(a.toAbsolutePath(),b.toAbsolutePath(),u.toAbsolutePath());
        }
    }

    @Test void contextChangeInvalidatesAllAndDeletionInvalidatesDependants()throws Exception{
        Path a=temp.resolve("A.java"),b=temp.resolve("B.java");
        try(var state=new RocksSemanticInvalidation(temp.resolve("context"))){
            state.observeFile("module","ctx-1",file(a,"a1","api-a",Set.of(),Set.of("pkg.A"),Set.of()));
            state.observeFile("module","ctx-1",file(b,"b1","api-b",Set.of(a),Set.of("pkg.B"),Set.of()));

            var context=state.observeFile("module","ctx-2",file(a,"a1","api-a",Set.of(),Set.of("pkg.A"),Set.of()));
            assertThat(context.contextChanged()).isTrue();
            assertThat(context.reanalyze()).containsExactlyInAnyOrder(a.toAbsolutePath(),b.toAbsolutePath());

            var deleted=state.removeFiles("module",Set.of(a));
            assertThat(deleted.deleted()).containsExactly(a.toAbsolutePath());
            assertThat(deleted.reanalyze()).containsExactly(b.toAbsolutePath());
        }
    }

    @Test void unresolvedDependantsAndDeletionProducePersistentPerFileRevisions()throws Exception{
        Path a=temp.resolve("A.java"),b=temp.resolve("B.java"),c=temp.resolve("C.java"),root=temp.resolve("revisions");
        try(var state=new RocksSemanticInvalidation(root)){
            state.observeFile("m","ctx",file(a,"a1","api-a",Set.of(),Set.of("pkg.A"),Set.of()));
            state.observeFile("m","ctx",file(b,"b1","api-b",Set.of(),Set.of("pkg.B"),Set.of("pkg.A")));
            state.observeFile("m","ctx",file(c,"c1","api-c",Set.of(b),Set.of("pkg.C"),Set.of()));
            var result=state.observeFile("m","ctx",file(a,"a2","api-a2",Set.of(),Set.of("pkg.A"),Set.of()));
            assertThat(result.reanalyze()).containsExactlyInAnyOrder(a.toAbsolutePath(),b.toAbsolutePath(),c.toAbsolutePath());
            assertThat(state.revision(a)).isZero();assertThat(state.revision(b)).isEqualTo(1);assertThat(state.revision(c)).isEqualTo(1);
            state.observeFile("m","ctx",file(a,"a3","api-a2",Set.of(),Set.of("pkg.A"),Set.of()));
            assertThat(state.revision(b)).isEqualTo(1);
            state.removeFiles("m",Set.of(a));assertThat(state.revision(b)).isEqualTo(2);assertThat(state.revision(c)).isEqualTo(2);
        }
        try(var reopened=new RocksSemanticInvalidation(root)){assertThat(reopened.revision(b)).isEqualTo(2);}
    }


    @Test void ordinaryObservationUsesDirectPostingsWithoutModuleScan()throws Exception{
        Path root=temp.resolve("direct");Path isolated=root.resolve("Isolated.java");
        try(var state=new RocksSemanticInvalidation(temp.resolve("direct-state"))){
            state.observeFile("module","ctx",file(isolated,"i1","api-i",Set.of(),Set.of("pkg.Isolated"),Set.of()));
            for(int i=0;i<100;i++){
                Path value=root.resolve("F"+i+".java");
                state.observeFile("module","ctx",file(value,"c"+i,"api-"+i,Set.of(),Set.of("pkg.F"+i),Set.of()));
            }
            var before=state.status();
            var result=state.observeFile("module","ctx",file(isolated,"i2","api-i2",Set.of(),Set.of("pkg.Isolated"),Set.of()));
            var after=state.status();
            assertThat(result.reanalyze()).containsExactly(isolated.toAbsolutePath());
            assertThat(((Number)after.get("module_scans")).longValue()-((Number)before.get("module_scans")).longValue()).isZero();
            assertThat(((Number)after.get("contribution_reads")).longValue()-((Number)before.get("contribution_reads")).longValue()).isEqualTo(1);
            assertThat(((Number)after.get("reverse_posting_reads")).longValue()-((Number)before.get("reverse_posting_reads")).longValue()).isEqualTo(1);
        }
    }

    @Test void persistedPostingsSurviveRestartWithoutModuleRebuild()throws Exception{
        Path root=temp.resolve("restart-postings"),a=temp.resolve("restart-A.java"),b=temp.resolve("restart-B.java");
        try(var state=new RocksSemanticInvalidation(root)){
            state.observeFile("module","ctx",file(a,"a1","api-a",Set.of(),Set.of("pkg.A"),Set.of()));
            state.observeFile("module","ctx",file(b,"b1","api-b",Set.of(a),Set.of("pkg.B"),Set.of()));
        }
        try(var state=new RocksSemanticInvalidation(root)){
            var result=state.observeFile("module","ctx",file(a,"a2","api-a2",Set.of(),Set.of("pkg.A"),Set.of()));
            assertThat(result.reanalyze()).containsExactlyInAnyOrder(a.toAbsolutePath(),b.toAbsolutePath());
            assertThat(state.status()).containsEntry("module_scans",0L).containsEntry("migration_files",0L);
        }
    }

    @Test void unresolvedPrefixRelationsUsePostingsWithoutScanningFiles()throws Exception{
        Path exported=temp.resolve("Exported.java"),child=temp.resolve("Child.java"),parent=temp.resolve("Parent.java");
        try(var state=new RocksSemanticInvalidation(temp.resolve("unresolved-postings"))){
            state.observeFile("module","ctx",file(child,"c1","api-c",Set.of(),Set.of("pkg.Child"),Set.of("pkg.Type.Inner")));
            state.observeFile("module","ctx",file(parent,"p1","api-p",Set.of(),Set.of("pkg.Parent"),Set.of("pkg.Type")));
            state.observeFile("module","ctx",file(exported,"e1","api-e",Set.of(),Set.of("pkg.Other"),Set.of()));
            var before=state.status();
            var result=state.observeFile("module","ctx",file(exported,"e2","api-e2",Set.of(),Set.of("pkg.Type"),Set.of()));
            var after=state.status();
            assertThat(result.reanalyze()).containsExactlyInAnyOrder(exported.toAbsolutePath(),child.toAbsolutePath(),parent.toAbsolutePath());
            assertThat(((Number)after.get("module_scans")).longValue()-((Number)before.get("module_scans")).longValue()).isZero();
            assertThat(((Number)after.get("unresolved_posting_reads")).longValue()-((Number)before.get("unresolved_posting_reads")).longValue()).isGreaterThan(0);
        }
    }

    private static FileSemanticContribution file(Path file,String content,String api,Set<Path> deps,Set<String> exports,Set<String> unresolved){
        return new FileSemanticContribution(file,content,api,deps,exports,unresolved);
    }
}
