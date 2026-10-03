package dev.jvmd.boot.cold.stage1;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.index.layer.machine.ClassFacts;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The memo's cost model (stage 1, 3.8): no class bytes are ever retained, a lone location pays nothing, a repeat is a hit. */
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

    private static Enumerate.Location jar(String name) { return new Enumerate.Location(name, 0, 0, Path.of(name), null, null, null); }

    @Test void aScopeOfOneLocationIsANoOpAndCostsNothing() throws Exception {
        var digest = new Counting();
        var memo = new ClassMemo(digest, List.of(jar("g/single/1/single-1.jar"), jar("g/many/1/many-1.jar"), jar("g/many/2/many-2.jar")));
        assertThat(memo.open(jar("g/single/1/single-1.jar"))).as("one version in the directory").isSameAs(ClassMemo.Scope.OFF);
        assertThat(memo.open(jar("g/many/1/many-1.jar"))).isNotSameAs(ClassMemo.Scope.OFF);

        var parses = new AtomicInteger();
        var facts = new ClassFacts("a/A", List.of(), List.of());
        byte[] a = {1, 2, 3};
        for (int i = 0; i < 3; i++) ClassMemo.Scope.OFF.get(crc(a), a.length, a, () -> { parses.incrementAndGet(); return facts; });
        assertThat(parses.get()).as("nothing is remembered, so every call parses").isEqualTo(3);
        assertThat(digest.hashes.get()).as("and nothing is hashed").isZero();
    }

    @Test void aJdkModuleIsAScopeOfOneLocationAndSoItIsOff() {
        var module = new Enumerate.Location("jrt:/java.base@/jdk", 0, 0, null, "java.base", null, null);
        var other = new Enumerate.Location("jrt:/java.logging@/jdk", 0, 0, null, "java.logging", null, null);
        var memo = new ClassMemo(Sha256.INSTANCE, List.of(module, other));
        assertThat(memo.open(module)).isSameAs(ClassMemo.Scope.OFF);
        assertThat(memo.open(other)).isSameAs(ClassMemo.Scope.OFF);
    }

    @Test void aScopeOfSeveralLocationsHashesWhenAClassIsFirstParsedAndComparesKappaOnARepeat() throws Exception {
        var digest = new Counting();
        var scope = new ClassMemo.Scope(digest);
        var parses = new AtomicInteger();
        var facts = new ClassFacts("a/A", List.of(), List.of());
        ClassMemo.Parse parse = () -> { parses.incrementAndGet(); return facts; };
        byte[] a = {1, 2, 3, 4}, other = {9, 9, 9, 9, 9};

        assertThat(scope.get(crc(a), a.length, a, parse)).isSameAs(facts);
        assertThat(parses.get()).isEqualTo(1);
        assertThat(digest.hashes.get()).as("kappa is computed when a class is first parsed, and stored with its facts").isEqualTo(1);

        assertThat(scope.get(crc(a), a.length, a.clone(), parse)).isSameAs(facts);
        assertThat(parses.get()).as("the repeat is a hit").isEqualTo(1);
        assertThat(digest.hashes.get()).as("the repeat computes its own kappa and compares: one hash, the stored one is not recomputed").isEqualTo(2);

        scope.get(crc(other), other.length, other, parse);
        assertThat(parses.get()).isEqualTo(2);
        assertThat(digest.hashes.get()).isEqualTo(3);
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

    /** The memo holds identities and facts only: no type in it, however nested, has a field that could keep class bytes. */
    @Test void noTypeOfTheMemoCanHoldClassBytes() {
        var types = new ArrayList<Class<?>>();
        types.add(ClassMemo.class);
        for (var nested : ClassMemo.class.getDeclaredClasses()) { types.add(nested); types.addAll(List.of(nested.getDeclaredClasses())); }
        assertThat(types.size()).isGreaterThan(3);
        for (var type : types)
            for (var field : type.getDeclaredFields())
                assertThat(field.getType()).as("%s.%s", type.getSimpleName(), field.getName()).isNotEqualTo(byte[].class)
                        .isNotEqualTo(java.nio.ByteBuffer.class).isNotEqualTo(java.lang.foreign.MemorySegment.class);
        assertThat(Modifier.isFinal(ClassMemo.class.getModifiers())).isTrue();
    }
}
