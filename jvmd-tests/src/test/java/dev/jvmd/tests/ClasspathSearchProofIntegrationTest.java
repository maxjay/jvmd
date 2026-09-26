package dev.jvmd.tests;

import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class ClasspathSearchProofIntegrationTest {
    @TempDir Path root;

    @Test void structuralDiffDiscoversOnlySearchesWhoseWinningPrefixCanChange()throws Exception{
        Path repo=Files.createDirectories(root.resolve("repo"));
        Path a=jar(repo.resolve("a"),"a","Winner","package p; public class Winner {}");
        Path b=jar(repo.resolve("b"),"b","Other","package p; public class Other {}");
        Path c=jar(repo.resolve("c"),"c","FromC","package p; public class FromC {}");

        try(var index=new IndexService(root.resolve("index.db"),repo)){
            index(index,a,"fixture:a:1");index(index,b,"fixture:b:1");index(index,c,"fixture:c:1");
            load(index,"w",a,b,c);

            var beforeSequence=index.store().semanticClasspathSequence("w").orElseThrow();
            var early=index.store().semanticClasspathSearch("w","p.Winner").orElseThrow();
            var cSearch=index.store().semanticClasspathSearch("w","p.FromC").orElseThrow();
            assertThat(early.searchedEntries()).isEqualTo(1);
            assertThat(cSearch.searchedEntries()).isEqualTo(3);

            // C's artifact resolution root changes, but the queried type declaration does not.
            jar(repo.resolve("c"),"c","FromC","package p; public class FromC { public int extra(){return 1;} }");
            index(index,c,"fixture:c:1");load(index,"w",a,b,c);
            var bodyMemberSequence=index.store().semanticClasspathSequence("w").orElseThrow();
            var diff=beforeSequence.diff(bodyMemberSequence);
            assertThat(diff.intervals()).containsExactly(new ClasspathSequence.Interval(2,3,2,3));

            var fixed=ClasspathSearchProofs.update(beforeSequence,bodyMemberSequence,List.of(early,cSearch),
                    binary->index.store().semanticClasspathSearch("w",binary));
            assertThat(fixed.reconsidered()).containsExactly(cSearch.key());
            assertThat(fixed.equal()).containsExactly(cSearch.key());
            assertThat(fixed.changed()).isEmpty();

            // A resolution-relevant change to C's winning type changes only that search proof.
            var currentC=index.store().semanticClasspathSearch("w","p.FromC").orElseThrow();
            jar(repo.resolve("c"),"c","FromC","package p; public final class FromC { public int extra(){return 1;} }");
            index(index,c,"fixture:c:1");load(index,"w",a,b,c);
            var relevantSequence=index.store().semanticClasspathSequence("w").orElseThrow();
            var relevant=ClasspathSearchProofs.update(bodyMemberSequence,relevantSequence,List.of(early,currentC),
                    binary->index.store().semanticClasspathSearch("w",binary));
            assertThat(relevant.reconsidered()).containsExactly(currentC.key());
            assertThat(relevant.changed()).containsOnlyKeys(currentC.key());
            assertThat(relevant.equal()).isEmpty();
        }
    }

    @Test void insertionRemovalAndReorderPreserveOrChangeOnlyActualWinnerSemantics()throws Exception{
        Path repo=Files.createDirectories(root.resolve("precedence"));
        Path a=jar(repo.resolve("a"),"a","Other","package p; public class Other {}");
        Path b=jar(repo.resolve("b"),"b","Target","package p; public class Target { public int b(){return 1;} }");
        Path c=jar(repo.resolve("c"),"c","Target","package p; public class Target { public int c(){return 2;} }");
        Path absent=jar(repo.resolve("d"),"d","Irrelevant","package p; public class Irrelevant {}");
        Path present=jar(repo.resolve("e"),"e","Target","package p; public final class Target { public int e(){return 3;} }");

        try(var index=new IndexService(root.resolve("precedence.db"),repo)){
            index(index,a,"fixture:a:1");index(index,b,"fixture:b:1");index(index,c,"fixture:c:1");
            index(index,absent,"fixture:d:1");index(index,present,"fixture:e:1");

            load(index,"w",a,b,c);
            var baseSequence=index.store().semanticClasspathSequence("w").orElseThrow();
            var base=index.store().semanticClasspathSearch("w","p.Target").orElseThrow();
            assertThat(base.resolved()).isTrue();
            assertThat(base.searchedEntries()).isEqualTo(2);
            assertThat(base.winnerArtifactKey()).isEqualTo(b.toAbsolutePath().normalize().toString());

            // Inserting an artifact before the winner must be reconsidered, but proven absence is a fixed point.
            load(index,"w",a,absent,b,c);
            var absentSequence=index.store().semanticClasspathSequence("w").orElseThrow();
            var absentInsert=ClasspathSearchProofs.update(baseSequence,absentSequence,List.of(base),
                    binary->index.store().semanticClasspathSearch("w",binary));
            assertThat(absentInsert.reconsidered()).containsExactly(base.key());
            assertThat(absentInsert.equal()).containsExactly(base.key());
            assertThat(absentInsert.changed()).isEmpty();

            // Removing that absent artifact is likewise semantically equal after reconsideration.
            var afterAbsent=index.store().semanticClasspathSearch("w","p.Target").orElseThrow();
            load(index,"w",a,b,c);
            var removedSequence=index.store().semanticClasspathSequence("w").orElseThrow();
            var absentRemoval=ClasspathSearchProofs.update(absentSequence,removedSequence,List.of(afterAbsent),
                    binary->index.store().semanticClasspathSearch("w",binary));
            assertThat(absentRemoval.equal()).containsExactly(base.key());
            assertThat(absentRemoval.changed()).isEmpty();

            // A present insertion before the old winner changes the winner proof.
            load(index,"w",a,present,b,c);
            var presentSequence=index.store().semanticClasspathSequence("w").orElseThrow();
            var presentInsert=ClasspathSearchProofs.update(removedSequence,presentSequence,List.of(base),
                    binary->index.store().semanticClasspathSearch("w",binary));
            assertThat(presentInsert.changed()).containsOnlyKeys(base.key());
            var presentWinner=index.store().semanticClasspathSearch("w","p.Target").orElseThrow();
            assertThat(presentWinner.winnerArtifactKey()).isEqualTo(present.toAbsolutePath().normalize().toString());

            // Removing the current winner changes resolution to B.
            load(index,"w",a,b,c);
            var winnerRemovedSequence=index.store().semanticClasspathSequence("w").orElseThrow();
            var winnerRemoval=ClasspathSearchProofs.update(presentSequence,winnerRemovedSequence,List.of(presentWinner),
                    binary->index.store().semanticClasspathSearch("w",binary));
            assertThat(winnerRemoval.changed()).containsOnlyKeys(base.key());

            // Reordering competing declarations changes precedence.
            load(index,"w",a,c,b);
            var reorderedSequence=index.store().semanticClasspathSequence("w").orElseThrow();
            var reorder=ClasspathSearchProofs.update(winnerRemovedSequence,reorderedSequence,List.of(base),
                    binary->index.store().semanticClasspathSearch("w",binary));
            assertThat(reorder.changed()).containsOnlyKeys(base.key());
            assertThat(index.store().semanticClasspathSearch("w","p.Target").orElseThrow().winnerArtifactKey())
                    .isEqualTo(c.toAbsolutePath().normalize().toString());

            // Structural changes wholly after an established first-slot winner are not reconsidered.
            load(index,"w",b,a,c);
            var firstSequence=index.store().semanticClasspathSequence("w").orElseThrow();
            var first=index.store().semanticClasspathSearch("w","p.Target").orElseThrow();
            assertThat(first.searchedEntries()).isEqualTo(1);
            load(index,"w",b,c,a);
            var laterReorder=index.store().semanticClasspathSequence("w").orElseThrow();
            var skipped=ClasspathSearchProofs.update(firstSequence,laterReorder,List.of(first),
                    binary->index.store().semanticClasspathSearch("w",binary));
            assertThat(skipped.structuralDiff().equal()).isFalse();
            assertThat(skipped.reconsidered()).isEmpty();
            assertThat(skipped.changed()).isEmpty();
        }
    }

    private static Path jar(Path dir,String name,String type,String source)throws Exception{
        return IndexFixtures.jar(dir,name,type+".java",source,true);
    }
    private static void index(IndexService index,Path jar,String gav)throws Exception{
        index.indexJar(jar,gav,"jar");
    }
    private static void load(IndexService index,String workspace,Path... jars)throws Exception{
        index.loadWorkspace(workspace,Arrays.stream(jars)
                .map(path->new IndexService.WorkspaceArtifact(path.toString(),"compile")).toList(),List.of());
    }
}
