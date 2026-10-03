package dev.jvmd.boot.cold.stage1;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.index.layer.machine.ClassFacts;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The memo's cost model (stage 1, 3.8): keyed by (crc32, size) first, so a first occurrence is a miss that is never hashed. */
@Tag("phase-3")
class ClassMemoTest {
    /** A digest that counts how many hashes were started. */
    private static final class Counting implements Digest {
        final AtomicInteger hashes = new AtomicInteger();
        @Override public String name() { return Sha256.INSTANCE.name(); }
        @Override public int width() { return Sha256.INSTANCE.width(); }
        @Override public Hasher hasher() { hashes.incrementAndGet(); return Sha256.INSTANCE.hasher(); }
    }

    private static long crc(byte[] bytes) { var c = new CRC32(); c.update(bytes); return c.getValue(); }

    @Test void aFirstOccurrenceIsNeverHashedAndKappaIsComputedOnlyToConfirmACandidate() throws Exception {
        var digest = new Counting();
        var scope = new ClassMemo.Scope(digest);
        var parses = new AtomicInteger();
        var facts = new ClassFacts("a/A", List.of(), List.of());
        ClassMemo.Parse parse = () -> { parses.incrementAndGet(); return facts; };
        byte[] a = {1, 2, 3, 4}, other = {9, 9, 9, 9, 9};

        assertThat(scope.get(crc(a), a.length, a, parse)).isSameAs(facts);
        assertThat(parses.get()).isEqualTo(1);
        assertThat(digest.hashes.get()).as("first occurrence: a miss, not hashed").isZero();

        assertThat(scope.get(crc(a), a.length, a.clone(), parse)).isSameAs(facts);
        assertThat(parses.get()).as("the repeat is a hit").isEqualTo(1);
        assertThat(digest.hashes.get()).as("kappa of the newcomer and of the stored candidate, to confirm equality").isEqualTo(2);

        scope.get(crc(a), a.length, a.clone(), parse);
        assertThat(parses.get()).isEqualTo(1);
        assertThat(digest.hashes.get()).as("the candidate's kappa is now known: only the newcomer is hashed").isEqualTo(3);

        scope.get(crc(other), other.length, other, parse);
        assertThat(parses.get()).as("a different class is a different bucket: parsed, and still not hashed").isEqualTo(2);
        assertThat(digest.hashes.get()).isEqualTo(3);
    }

    @Test void aModuleClassWithNoCentralDirectoryGetsItsCrcFromItsBytesAndSharesTheSamePath() throws Exception {
        var scope = new ClassMemo.Scope(Sha256.INSTANCE);
        var parses = new AtomicInteger();
        var facts = new ClassFacts("a/A", List.of(), List.of());
        ClassMemo.Parse parse = () -> { parses.incrementAndGet(); return facts; };
        byte[] a = {5, 6, 7};
        scope.get(crc(a), a.length, a, parse);
        scope.get(-1, -1, a.clone(), parse); // as read from jrt:, where crc32 and size are unknown
        assertThat(parses.get()).isEqualTo(1);
    }

    @Test void twoClassesWithTheSameCrcAndSizeAreToldApartByKappa() throws Exception {
        var scope = new ClassMemo.Scope(Sha256.INSTANCE);
        var first = new ClassFacts("a/A", List.of(), List.of());
        var second = new ClassFacts("a/B", List.of(), List.of());
        byte[] x = {1, 2, 3}, y = {3, 2, 1};
        // Same claimed crc and size for different bytes: what a CRC collision looks like to the memo.
        assertThat(scope.get(77, 3, x, () -> first)).isSameAs(first);
        assertThat(scope.get(77, 3, y, () -> second)).isSameAs(second);
        assertThat(scope.get(77, 3, x.clone(), () -> { throw new AssertionError("hit expected"); })).isSameAs(first);
        assertThat(scope.get(77, 3, y.clone(), () -> { throw new AssertionError("hit expected"); })).isSameAs(second);
    }

    @Test void aFaultIsNotMemoized() {
        var scope = new ClassMemo.Scope(Sha256.INSTANCE);
        byte[] a = {1};
        var attempts = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            try { scope.get(crc(a), 1, a, () -> { attempts.incrementAndGet(); throw new ClassFacts.Fault("bad"); }); }
            catch (ClassFacts.Fault expected) { /* each attempt parses again */ }
        }
        assertThat(attempts.get()).isEqualTo(2);
    }
}
