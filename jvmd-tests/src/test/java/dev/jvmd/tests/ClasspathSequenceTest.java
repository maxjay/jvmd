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
    private static ClasspathSequence.Entry entry(String key,String resolutionGeneration){
        return new ClasspathSequence.Entry(key,hash(resolutionGeneration));
    }

    @Test void exactOrderHasCanonicalMerkleIdentity(){
        var a=entry("A","a1");
        var b=entry("B","b1");
        var c=entry("C","c1");
        var d=entry("D","d1");
        var first=ClasspathSequence.of(List.of(a,b,c,d));
        var same=ClasspathSequence.of(List.of(a,b,c,d));
        var reordered=ClasspathSequence.of(List.of(a,c,b,d));

        assertThat(first.identity()).isEqualTo(same.identity());
        assertThat(first.entries()).containsExactly(a,b,c,d);
        assertThat(reordered.identity()).isNotEqualTo(first.identity());
        assertThat(first.diff(same).equal()).isTrue();
    }

    @Test void singleLeafResolutionChangeUsesNarrowMerkleDiff(){
        var entries=new ArrayList<ClasspathSequence.Entry>();
        for(int i=0;i<128;i++)entries.add(entry("artifact-"+i,"resolution-1-"+i));
        var before=ClasspathSequence.of(entries);
        var after=before.replace(73,entry("artifact-73","resolution-2"));

        var difference=before.diff(after);
        assertThat(after.entries()).containsExactlyElementsOf(
                withReplacement(entries,73,entry("artifact-73","resolution-2")));
        assertThat(before.identity()).isNotEqualTo(after.identity());
        assertThat(difference.intervals()).containsExactly(new ClasspathSequence.Interval(73,74,73,74));
        assertThat(difference.merkleNodesCompared()).isLessThan(before.size());
    }

    @Test void insertionProducesMinimalChangedIntervalAndCanonicalResult(){
        var a=entry("A","a1");
        var b=entry("B","b1");
        var c=entry("C","c1");
        var d=entry("D","d1");
        var x=entry("X","x1");
        var before=ClasspathSequence.of(List.of(a,b,c,d));
        var after=before.insert(2,x);

        assertThat(after.entries()).containsExactly(a,b,x,c,d);
        assertThat(after.identity()).isEqualTo(ClasspathSequence.of(List.of(a,b,x,c,d)).identity());
        assertThat(before.diff(after).intervals()).containsExactly(new ClasspathSequence.Interval(2,2,2,3));
    }

    @Test void removalProducesMinimalChangedIntervalAndCanonicalResult(){
        var a=entry("A","a1");
        var b=entry("B","b1");
        var c=entry("C","c1");
        var d=entry("D","d1");
        var before=ClasspathSequence.of(List.of(a,b,c,d));
        var after=before.remove(2);

        assertThat(after.entries()).containsExactly(a,b,d);
        assertThat(after.identity()).isEqualTo(ClasspathSequence.of(List.of(a,b,d)).identity());
        assertThat(before.diff(after).intervals()).containsExactly(new ClasspathSequence.Interval(2,3,2,2));
    }

    @Test void reorderChangesOnlyTheOrderedInterval(){
        var a=entry("A","a1");
        var b=entry("B","b1");
        var c=entry("C","c1");
        var d=entry("D","d1");
        var before=ClasspathSequence.of(List.of(a,b,c,d));
        var after=before.move(1,2);

        assertThat(after.entries()).containsExactly(a,c,b,d);
        assertThat(after.identity()).isEqualTo(ClasspathSequence.of(List.of(a,c,b,d)).identity());
        var difference=before.diff(after);
        assertThat(difference.intervals()).containsExactly(new ClasspathSequence.Interval(1,3,1,3));
    }

    @Test void structuralDiffSkipsLargeEqualRegionsByMerkleRange(){
        int size=4096;
        var entries=new ArrayList<ClasspathSequence.Entry>(size);
        for(int i=0;i<size;i++)entries.add(entry("artifact-"+i,"resolution-"+i));
        var before=ClasspathSequence.of(entries);

        var inserted=before.insert(2048,entry("inserted","resolution-inserted"));
        var insertion=before.diff(inserted);
        assertThat(insertion.intervals()).containsExactly(new ClasspathSequence.Interval(2048,2048,2048,2049));
        assertThat(insertion.merkleNodesCompared()).isLessThan(size/2);

        var removed=before.remove(2048);
        var removal=before.diff(removed);
        assertThat(removal.intervals()).containsExactly(new ClasspathSequence.Interval(2048,2049,2048,2048));
        assertThat(removal.merkleNodesCompared()).isLessThan(size/2);

        var reordered=before.move(2047,2049);
        var reorder=before.diff(reordered);
        assertThat(reorder.intervals()).containsExactly(new ClasspathSequence.Interval(2047,2050,2047,2050));
        assertThat(reorder.merkleNodesCompared()).isLessThan(size/2);
    }

    @Test void stableSlotKeyLetsResolutionGenerationReplacementPreserveOrder(){
        var before=ClasspathSequence.of(List.of(entry("A","a1"),entry("B","b1"),entry("C","c1")));
        var after=before.replace(1,entry("B","b2"));

        assertThat(after.entries().stream().map(ClasspathSequence.Entry::key).toList()).containsExactly("A","B","C");
        assertThat(before.diff(after).intervals()).containsExactly(new ClasspathSequence.Interval(1,2,1,2));
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
