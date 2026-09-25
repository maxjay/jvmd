package dev.jvmd.tests;

import dev.jvmd.core.Hash256;
import dev.jvmd.index.MachineDependencyState;
import dev.jvmd.index.WorkspaceSemanticIdentity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-1")
class MachineWorkspaceIdentityTest {
    private static Hash256 hash(String value){
        return Hash256.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
    private static MachineDependencyState.Artifact artifact(String key,String resolution){
        return new MachineDependencyState.Artifact(key,hash(resolution));
    }
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
        var machine=new MachineDependencyState();
        machine.put(artifact("A","a1"));
        machine.put(artifact("B","b1"));
        machine.put(artifact("C","c1"));

        var machineBefore=machine.identity();
        var dependenciesBefore=machine.workspaceDependencies(List.of("A","B"));
        var workspaceBefore=workspace(dependenciesBefore.identity());

        machine.put(artifact("C","c2"));

        assertThat(machine.identity()).isNotEqualTo(machineBefore);
        var dependenciesAfter=machine.workspaceDependencies(List.of("A","B"));
        assertThat(dependenciesAfter.identity()).isEqualTo(dependenciesBefore.identity());
        assertThat(dependenciesBefore.diff(dependenciesAfter).equal()).isTrue();
        assertThat(workspace(dependenciesAfter.identity()).identity()).isEqualTo(workspaceBefore.identity());
    }

    @Test void relevantMachineArtifactChangeChangesOnlyWorkspacesThatSelectIt(){
        var machine=new MachineDependencyState();
        machine.put(artifact("A","a1"));
        machine.put(artifact("B","b1"));
        machine.put(artifact("C","c1"));

        var ab=machine.workspaceDependencies(List.of("A","B"));
        var c=machine.workspaceDependencies(List.of("C"));

        machine.put(artifact("C","c2"));

        var abAfter=machine.workspaceDependencies(List.of("A","B"));
        var cAfter=machine.workspaceDependencies(List.of("C"));
        assertThat(abAfter.identity()).isEqualTo(ab.identity());
        assertThat(cAfter.identity()).isNotEqualTo(c.identity());
        assertThat(c.diff(cAfter).intervals()).containsExactly(
                new dev.jvmd.index.ClasspathSequence.Interval(0,1,0,1));
    }

    @Test void workspaceDependencyIdentityPreservesResolverOrder(){
        var machine=new MachineDependencyState();
        machine.put(artifact("A","a1"));
        machine.put(artifact("B","b1"));
        machine.put(artifact("C","c1"));

        var abc=machine.workspaceDependencies(List.of("A","B","C"));
        var acb=machine.workspaceDependencies(List.of("A","C","B"));

        assertThat(abc.identity()).isNotEqualTo(acb.identity());
        assertThat(abc.entries().stream().map(dev.jvmd.index.ClasspathSequence.Entry::key).toList())
                .containsExactly("A","B","C");
    }

    @Test void equalResolutionIdentityDoesNotChurnMachineOrWorkspaceRoots(){
        var machine=new MachineDependencyState();
        machine.put(artifact("A","resolution-v1"));
        var machineBefore=machine.identity();
        var workspaceBefore=machine.workspaceDependencies(List.of("A")).identity();

        // Non-resolution enrichment (for example documentation) must not be encoded by changing
        // Artifact.resolutionIdentity. Re-publishing the same Java-resolution semantics is a no-op.
        machine.put(artifact("A","resolution-v1"));

        assertThat(machine.identity()).isEqualTo(machineBefore);
        assertThat(machine.workspaceDependencies(List.of("A")).identity()).isEqualTo(workspaceBefore);
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
        var machine=new MachineDependencyState();
        machine.put(artifact("A","a1"));

        assertThatThrownBy(()->machine.workspaceDependencies(List.of("A","missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown machine artifact");
        assertThatThrownBy(()->machine.workspaceDependencies(List.of("A","A")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate workspace classpath artifact");
    }
}
