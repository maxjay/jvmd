package dev.jvmd.tests;

import dev.jvmd.core.Hash256;
import dev.jvmd.index.WorkspaceSemanticIdentity;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineTree;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-1")
/** A route's identity depends only on the MACHINE leaves it selects, in resolver order. */
class MachineWorkspaceIdentityTest {
    private static Hash256 hash(String value){
        return Hash256.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
    private static MachineLeaf artifact(String content,String resolution){
        return new MachineLeaf(hash(content).hex(),"signatures",List.of(),hash(resolution),hash("docs"),null,null,hash("semantic-"+content),0,0,0);
    }
    /** The MACHINE leaf key of a test content. */
    private static String key(String content){return artifact(content,"").cacheKey();}
    private static List<String> keys(String... contents){return java.util.Arrays.stream(contents).map(MachineWorkspaceIdentityTest::key).toList();}
    private static WorkspaceSemanticIdentity workspace(Hash256 dependencies){
        return new WorkspaceSemanticIdentity(
                dependencies,
                hash("compiler-options"),
                hash("processor-config"),
                hash("source-membership"),
                hash("generated-output"),
                List.of(new WorkspaceSemanticIdentity.Module(
                        "app",hash("api"),hash("namespace"),hash("hierarchy"))));
    }

    @Test void unrelatedMachineArtifactChangeLeavesSelectedWorkspaceMathematicallyEqual(){
        var machine=MachineTree.build(List.of(artifact("A","a1"),artifact("B","b1"),artifact("C","c1")));

        var machineBefore=machine.resolution().identity();
        var dependenciesBefore=machine.sequence(keys("A","B"));
        var workspaceBefore=workspace(dependenciesBefore.identity());

        machine=machine.put(artifact("C","c2"));

        assertThat(machine.resolution().identity()).isNotEqualTo(machineBefore);
        var dependenciesAfter=machine.sequence(keys("A","B"));
        assertThat(dependenciesAfter.identity()).isEqualTo(dependenciesBefore.identity());
        assertThat(dependenciesBefore.diff(dependenciesAfter).equal()).isTrue();
        assertThat(workspace(dependenciesAfter.identity()).identity()).isEqualTo(workspaceBefore.identity());
    }

    @Test void relevantMachineArtifactChangeChangesOnlyWorkspacesThatSelectIt(){
        var machine=MachineTree.build(List.of(artifact("A","a1"),artifact("B","b1"),artifact("C","c1")));

        var ab=machine.sequence(keys("A","B"));
        var c=machine.sequence(keys("C"));

        machine=machine.put(artifact("C","c2"));

        var abAfter=machine.sequence(keys("A","B"));
        var cAfter=machine.sequence(keys("C"));
        assertThat(abAfter.identity()).isEqualTo(ab.identity());
        assertThat(cAfter.identity()).isNotEqualTo(c.identity());
        assertThat(c.diff(cAfter).intervals()).containsExactly(
                new dev.jvmd.index.ClasspathSequence.Interval(0,1,0,1));
    }

    @Test void workspaceDependencyIdentityPreservesResolverOrder(){
        var machine=MachineTree.build(List.of(artifact("A","a1"),artifact("B","b1"),artifact("C","c1")));

        var abc=machine.sequence(keys("A","B","C"));
        var acb=machine.sequence(keys("A","C","B"));

        assertThat(abc.identity()).isNotEqualTo(acb.identity());
        assertThat(abc.entries().stream().map(dev.jvmd.index.ClasspathSequence.Entry::key).toList())
                .containsExactlyElementsOf(keys("A","B","C"));
    }

    @Test void equalResolutionIdentityDoesNotChurnMachineOrWorkspaceRoots(){
        var machine=MachineTree.build(List.of(artifact("A","resolution-v1")));
        var machineBefore=machine.resolution().identity();
        var workspaceBefore=machine.sequence(keys("A")).identity();

        // Non-resolution enrichment (for example documentation) must not be encoded by changing
        // Artifact.resolutionIdentity. Re-publishing the same Java-resolution semantics is a no-op.
        machine=machine.put(artifact("A","resolution-v1"));

        assertThat(machine.resolution().identity()).isEqualTo(machineBefore);
        assertThat(machine.sequence(keys("A")).identity()).isEqualTo(workspaceBefore);
    }

    @Test void workspaceRootComposesItsSemanticDomainsWithoutMachineRoot(){
        var dependencies=hash("selected-A-B");
        var base=workspace(dependencies);
        var changedApi=new WorkspaceSemanticIdentity(
                dependencies,hash("compiler-options"),hash("processor-config"),hash("source-membership"),hash("generated-output"),
                List.of(new WorkspaceSemanticIdentity.Module("app",hash("api-2"),hash("namespace"),hash("hierarchy"))));
        var changedNamespace=new WorkspaceSemanticIdentity(
                dependencies,hash("compiler-options"),hash("processor-config"),hash("source-membership"),hash("generated-output"),
                List.of(new WorkspaceSemanticIdentity.Module("app",hash("api"),hash("namespace-2"),hash("hierarchy"))));
        var changedHierarchy=new WorkspaceSemanticIdentity(
                dependencies,hash("compiler-options"),hash("processor-config"),hash("source-membership"),hash("generated-output"),
                List.of(new WorkspaceSemanticIdentity.Module("app",hash("api"),hash("namespace"),hash("hierarchy-2"))));

        assertThat(changedApi.identity()).isNotEqualTo(base.identity());
        assertThat(changedNamespace.identity()).isNotEqualTo(base.identity());
        assertThat(changedHierarchy.identity()).isNotEqualTo(base.identity());
        assertThat(base.dependencyRoot()).isEqualTo(dependencies);
    }

    @Test void unknownOrDuplicateWorkspaceArtifactsAreRejected(){
        var machine=MachineTree.build(List.of(artifact("A","a1")));

        assertThatThrownBy(()->machine.sequence(keys("A","missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown MACHINE leaf");
        assertThatThrownBy(()->machine.sequence(keys("A","A")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate MACHINE leaf in a sequence");
    }
}
