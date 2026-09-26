package dev.jvmd.tests;

import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class MachineSemanticQueryProofTest {
    @TempDir Path root;

    @Test void persistedMemberRangeProofTracksOnlyTheQueriedPrefix()throws Exception{
        Path repo=Files.createDirectories(root.resolve("repo"));
        Path jar=IndexFixtures.jar(repo,"api","""
                package lib;
                public class Sample {
                    public int getOne(){return 1;}
                    public int setOne(){return 1;}
                }
                """,true);
        try(var index=new IndexService(root.resolve("index.db"),repo)){
            index.indexJar(jar,"fixture:api:1","jar");
            index.loadWorkspace("w",List.of(new IndexService.WorkspaceArtifact(jar.toString(),"compile")),List.of());
            var view=SemanticReadViews.machine(index.store(),"w");
            var owner=index.store().semanticTypesByName("Sample","w",10,IndexStore.SemanticLayer.MACHINE).stream()
                    .filter(value->value.fqn().equals("lib.Sample")).findFirst().orElseThrow();
            var rangeKey=SemanticReadView.memberIdentityKey(owner.id(),"get");
            var before=view.identity(QueryProof.Domain.MEMBER_RANGE,rangeKey).orElseThrow();
            var expected=new AlgebraicAccumulator("semantic-member-range-v1");
            for(var symbol:index.store().semanticMembersByOwner(owner.id(),"get","w",100,null,IndexStore.SemanticLayer.MACHINE).symbols())
                expected.add(symbol.resolution().symbolKey(),symbol.resolution().identity());
            assertThat(before).isEqualTo(expected.identity());

            IndexFixtures.jar(repo,"api","""
                    package lib;
                    public class Sample {
                        public int getOne(){return 1;}
                        public long setOne(){return 1;}
                    }
                    """,true);
            index.indexJar(jar,"fixture:api:1","jar");
            index.loadWorkspace("w",List.of(new IndexService.WorkspaceArtifact(jar.toString(),"compile")),List.of());
            assertThat(view.identity(QueryProof.Domain.MEMBER_RANGE,rangeKey)).contains(before);

            IndexFixtures.jar(repo,"api","""
                    package lib;
                    public class Sample {
                        public int getOne(){return 1;}
                        public int getTwo(){return 2;}
                        public long setOne(){return 1;}
                    }
                    """,true);
            index.indexJar(jar,"fixture:api:1","jar");
            index.loadWorkspace("w",List.of(new IndexService.WorkspaceArtifact(jar.toString(),"compile")),List.of());
            assertThat(view.identity(QueryProof.Domain.MEMBER_RANGE,rangeKey).orElseThrow()).isNotEqualTo(before);
        }
    }
}
