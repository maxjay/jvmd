package dev.jvmd.tests;

import dev.jvmd.core.LiveStateTree;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-1")
class LiveStateTreeTest {
    @TempDir Path root;

    private LiveStateTree tree(){return new LiveStateTree(List.of(root));}
    private LiveStateTree.Leaf leaf(Path file,String content,String api,String... names){
        return LiveStateTree.source(file,content,api,List.of(names));
    }

    @Test void bodyOnlyEditChangesContentAndMerkleButNotSemanticOrMembershipIdentity(){
        Path file=root.resolve("p/A.java");var tree=tree();tree.put(leaf(file,"content-1","api-1","p.A","value"));var before=tree.state();
        var transition=tree.put(leaf(file,"content-2","api-1","p.A","value"));var after=tree.state();
        assertThat(transition.changed()).containsExactlyInAnyOrder(LiveStateTree.Domain.CONTENT,LiveStateTree.Domain.MERKLE);
        assertThat(after.content()).isNotEqualTo(before.content());assertThat(after.merkle()).isNotEqualTo(before.merkle());
        assertThat(after.api()).isEqualTo(before.api());assertThat(after.namespace()).isEqualTo(before.namespace());assertThat(after.membership()).isEqualTo(before.membership());
    }

    @Test void apiAndNamespaceRemainIndependentInformationDomains(){
        Path file=root.resolve("p/A.java");var tree=tree();tree.put(leaf(file,"content-1","api-1","p.A","value"));var before=tree.state();
        var api=tree.put(leaf(file,"content-2","api-2","p.A","value"));
        assertThat(api.changed()).containsExactlyInAnyOrder(LiveStateTree.Domain.CONTENT,LiveStateTree.Domain.API,LiveStateTree.Domain.MERKLE);
        assertThat(api.after().namespace()).isEqualTo(before.namespace());assertThat(api.after().membership()).isEqualTo(before.membership());
        var names=tree.put(leaf(file,"content-3","api-3","p.A","value","added"));
        assertThat(names.changed()).contains(LiveStateTree.Domain.CONTENT,LiveStateTree.Domain.API,LiveStateTree.Domain.NAMESPACE,LiveStateTree.Domain.MERKLE).doesNotContain(LiveStateTree.Domain.MEMBERSHIP);
    }

    @Test void addRemoveAndRenameArePathBoundAndRestoreExactIdentity(){
        Path a=root.resolve("p/A.java"),b=root.resolve("p/B.java");var tree=tree();var empty=tree.state();
        var added=tree.put(leaf(a,"content","api","p.A"));
        assertThat(added.changed()).containsExactlyInAnyOrder(LiveStateTree.Domain.values());
        assertThat(added.after().membership().cardinality()).isEqualTo(1);
        var atA=tree.state();tree.remove(a);var restored=tree.state();
        assertThat(restored.merkle()).isEqualTo(empty.merkle());assertThat(restored.membership()).isEqualTo(empty.membership());
        assertThat(restored.content()).isEqualTo(empty.content());assertThat(restored.api()).isEqualTo(empty.api());assertThat(restored.namespace()).isEqualTo(empty.namespace());
        tree.put(leaf(b,"content","api","p.A"));var atB=tree.state();
        assertThat(atB.merkle()).isNotEqualTo(atA.merkle());assertThat(atB.membership()).isNotEqualTo(atA.membership());
        assertThat(atB.content()).isNotEqualTo(atA.content());assertThat(atB.api()).isNotEqualTo(atA.api());assertThat(atB.namespace()).isNotEqualTo(atA.namespace());
    }

    @Test void reversionRestoresFingerprintButEpochProvesInterveningTransitions(){
        Path file=root.resolve("A.java");var tree=tree();tree.put(leaf(file,"A","api","A"));var start=tree.state();
        tree.put(leaf(file,"B","api","A"));tree.put(leaf(file,"A","api","A"));var end=tree.state();
        assertThat(end.merkle()).isEqualTo(start.merkle());assertThat(end.content()).isEqualTo(start.content());assertThat(end.api()).isEqualTo(start.api());
        assertThat(end.namespace()).isEqualTo(start.namespace());assertThat(end.membership()).isEqualTo(start.membership());
        assertThat(end.epoch()).isEqualTo(start.epoch()+2);
    }

    @Test void unrelatedBranchesKeepTheirIdentityAndEpoch(){
        Path x=root.resolve("x/A.java"),y=root.resolve("y/B.java");var tree=tree();tree.put(leaf(x,"x1","x-api","x.A"));tree.put(leaf(y,"y1","y-api","y.B"));
        var yBefore=tree.branch(root.resolve("y")).orElseThrow();tree.put(leaf(x,"x2","x-api","x.A"));var yAfter=tree.branch(root.resolve("y")).orElseThrow();
        assertThat(yAfter).isEqualTo(yBefore);
    }

    @Test void structuralAndAlgebraicIdentitiesDoNotDependOnInsertionOrder(){
        Path a=root.resolve("a/A.java"),b=root.resolve("b/B.java"),c=root.resolve("b/C.java");var values=List.of(leaf(a,"1","api-a","a.A"),leaf(b,"2","api-b","b.B"),leaf(c,"3","api-c","b.C"));
        var left=tree();values.forEach(left::put);var right=tree();var reversed=new ArrayList<>(values);Collections.reverse(reversed);reversed.forEach(right::put);
        assertThat(left.state().merkle()).isEqualTo(right.state().merkle());assertThat(left.state().membership()).isEqualTo(right.state().membership());
        assertThat(left.state().content()).isEqualTo(right.state().content());assertThat(left.state().api()).isEqualTo(right.state().api());assertThat(left.state().namespace()).isEqualTo(right.state().namespace());
        assertThat(left.branch(root.resolve("b")).orElseThrow().merkle()).isEqualTo(right.branch(root.resolve("b")).orElseThrow().merkle());
    }

    @Test void namespaceFingerprintUsesCanonicalExportedNameSet(){
        assertThat(LiveStateTree.namespace(List.of("p.B","p.A","p.A"))).isEqualTo(LiveStateTree.namespace(List.of("p.A","p.B")));
    }
}
