package dev.jvmd.tests;

import dev.jvmd.core.Hash256;
import dev.jvmd.index.ClasspathSequence;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-1")
class ClasspathSequenceTest {
    private static Hash256 hash(String value){
        return Hash256.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
    private static ClasspathSequence.Entry entry(String key,String generation){
        return new ClasspathSequence.Entry(key,hash(generation));
    }

    @Test void exactOrderHasCanonicalMerkleIdentity(){
        var a=entry("A","a1"),b=entry("B","b1"),c=entry("C","c1"),d=entry("D","d1");
        var first=ClasspathSequence.of(List.of(a,b,c,d));
        var same=ClasspathSequence.of(List.of(a,b,c,d));
        var reordered=ClasspathSequence.of(List.of(a,c,b,d));

        assertThat(first.identity()).isEqualTo(same.identity());
        assertThat(first.entries()).containsExactly(a,b,c,d);
        assertThat(reordered.identity()).isNotEqualTo(first.identity());
        assertThat(first.diff(same).equal()).isTrue();
    }

    @Test void singleLeafContentChangeUsesNarrowMerkleDiff(){
        var entries=new ArrayList<ClasspathSequence.Entry>();
        for(int i=0;i<128;i++)entries.add(entry("artifact-"+i,"generation-1-"+i));
        var before=ClasspathSequence.of(entries);
        var after=before.replace(73,entry("artifact-73","generation-2"));

        var difference=before.diff(after);
        assertThat(after.entries()).containsExactlyElementsOf(
                withReplacement(entries,73,entry("artifact-73","generation-2")));
        assertThat(before.identity()).isNotEqualTo(after.identity());
        assertThat(difference.structuralFallback()).isFalse();
        assertThat(difference.intervals()).containsExactly(new ClasspathSequence.Interval(73,74,73,74));
        assertThat(difference.merkleNodesCompared()).isLessThan(before.size());
    }

    @Test void insertionProducesMinimalChangedIntervalAndCanonicalResult(){
        var a=entry("A","a1"),b=entry("B","b1"),c=entry("C","c1"),d=entry("D","d1"),x=entry("X","x1");
        var before=ClasspathSequence.of(List.of(a,b,c,d));
        var after=before.insert(2,x);

        assertThat(after.entries()).containsExactly(a,b,x,c,d);
        assertThat(after.identity()).isEqualTo(ClasspathSequence.of(List.of(a,b,x,c,d)).identity());
        assertThat(before.diff(after).intervals()).containsExactly(new ClasspathSequence.Interval(2,2,2,3));
        assertThat(before.diff(after).structuralFallback()).isTrue();
    }

    @Test void removalProducesMinimalChangedIntervalAndCanonicalResult(){
        var a=entry("A","a1"),b=entry("B","b1"),c=entry("C","c1"),d=entry("D","d1");
        var before=ClasspathSequence.of(List.of(a,b,c,d));
        var after=before.remove(2);

        assertThat(after.entries()).containsExactly(a,b,d);
        assertThat(after.identity()).isEqualTo(ClasspathSequence.of(List.of(a,b,d)).identity());
        assertThat(before.diff(after).intervals()).containsExactly(new ClasspathSequence.Interval(2,3,2,2));
    }

    @Test void reorderChangesOnlyTheOrderedInterval(){
        var a=entry("A","a1"),b=entry("B","b1"),c=entry("C","c1"),d=entry("D","d1");
        var before=ClasspathSequence.of(List.of(a,b,c,d));
        var after=before.move(1,2);

        assertThat(after.entries()).containsExactly(a,c,b,d);
        assertThat(after.identity()).isEqualTo(ClasspathSequence.of(List.of(a,c,b,d)).identity());
        var difference=before.diff(after);
        assertThat(difference.structuralFallback()).isTrue();
        assertThat(difference.intervals()).containsExactly(new ClasspathSequence.Interval(1,3,1,3));
    }

    @Test void stableSlotKeyLetsGenerationReplacementPreserveOrder(){
        var before=ClasspathSequence.of(List.of(entry("A","a1"),entry("B","b1"),entry("C","c1")));
        var after=before.replace(1,entry("B","b2"));

        assertThat(after.entries().stream().map(ClasspathSequence.Entry::key)).containsExactly("A","B","C");
        assertThat(before.diff(after).intervals()).containsExactly(new ClasspathSequence.Interval(1,2,1,2));
        assertThat(before.diff(after).structuralFallback()).isFalse();
    }

    @Test void duplicateStableKeysAreRejected(){
        assertThatThrownBy(()->ClasspathSequence.of(List.of(entry("A","a1"),entry("A","a2"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate classpath entry key");
        var sequence=ClasspathSequence.of(List.of(entry("A","a1"),entry("B","b1")));
        assertThatThrownBy(()->sequence.insert(1,entry("A","a2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<ClasspathSequence.Entry> withReplacement(
            List<ClasspathSequence.Entry> input,int index,ClasspathSequence.Entry value){
        var copy=new ArrayList<>(input);copy.set(index,value);return List.copyOf(copy);
    }
}
