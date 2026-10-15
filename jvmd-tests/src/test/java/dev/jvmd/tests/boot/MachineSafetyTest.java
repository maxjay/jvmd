package dev.jvmd.tests.boot;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_void;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jvmd.boot.BootDecision;
import dev.jvmd.boot.cold.stage1.Enumerate;
import dev.jvmd.boot.cold.stage1.Stage1;
import dev.jvmd.core.Config;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.Format;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.MachineTree;
import dev.jvmd.index.rocks.layer.Generation;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Failures that must stay local: one bad class is one fault, a store that will not open is never deleted, a failed boot leaves no worker. */
@Tag("phase-3")
class MachineSafetyTest {
    @TempDir Path temp;

    /** A class with one public static method {@code m()V}, or two identical ones: legal to build, illegal by JVMS 4.6. */
    private static byte[] classWithMethods(String internalName, int copies) {
        return ClassFile.of().build(ClassDesc.ofInternalName(internalName), cb -> {
            cb.withSuperclass(CD_Object);
            for (int i = 0; i < copies; i++)
                cb.withMethodBody("m", MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> b.return_());
        });
    }

    @Test void aClassWithDuplicateMembersIsOneFaultAndTheRestOfTheJarIsIndexed() throws Exception {
        var jar = BootFixtures.pack(temp.resolve("g/dup/1/dup-1.jar"), MachineColdBootTest.T1,
                Map.of("p/Dup.class", classWithMethods("p/Dup", 2), "p/Good.class", classWithMethods("p/Good", 1)));
        var store = new InMemoryMachineStore();
        var digest = Sha256.INSTANCE;
        var result = new Stage1(digest, new ContentTree(digest), 25, 2, ClassFacts::of).run(store, List.of(Enumerate.jar("dup.jar", jar)));

        assertThat(result.faults()).containsExactly("dup.jar: p/Dup.class");
        var path = MachineTree.decodePath(store.get(MachineStore.pathKey("dup.jar")), digest.width());
        assertThat(path.faults()).containsExactly("p/Dup.class");
        var leaf = MachineLeaf.decode(store.get(MachineStore.leafKey(path.k())), digest.width());
        assertThat(leaf.typeCount()).as("only p/Good: none of p/Dup's facts were admitted").isEqualTo(1);
        assertThat(leaf.factCount()).as("p/Good's type fact and its method m()V").isEqualTo(2);
    }

    @Test void aGenerationThatCannotBeOpenedIsAnErrorAndIsNeverDeleted() throws Exception {
        var index = temp.resolve("index");
        var generation = Generation.of(index, Format.of(Sha256.INSTANCE, Runtime.version().feature()));
        Files.createDirectories(generation.directory());
        // Not a store RocksDB can open: CURRENT names a manifest that does not exist. It could equally be a damaged real one.
        Files.writeString(generation.directory().resolve("CURRENT"), "MANIFEST-000999\n");
        var sentinel = Files.writeString(generation.directory().resolve("000042.sst"), "committed data");

        assertThatThrownBy(generation::hasRoot).isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be opened");
        assertThatThrownBy(generation::create).isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be opened");
        var config = new Config(Path.of(System.getProperty("java.home")), null, temp.resolve("m2"), 3, Duration.ofMinutes(1), 1024, false,
                temp.resolve("state"), temp.resolve("socket"));
        assertThatThrownBy(() -> BootDecision.machine(index, config)).as("the daemon refuses to start; it does not cold boot over it")
                .isInstanceOf(IllegalStateException.class);
        assertThat(sentinel).exists().hasContent("committed data");
    }

    @Test void anEmptyGenerationDirectoryHasNoRootAndIsReplaced() throws Exception {
        var generation = Generation.of(temp.resolve("index"), Format.of(Sha256.INSTANCE, 25));
        Files.createDirectories(generation.directory());
        assertThat(generation.hasRoot()).isFalse();
        try (var store = generation.create()) { assertThat(store.hasRoot()).isFalse(); }
    }

    @Test void aFailedBootJoinsItsWorkersBeforeItReturns() throws Exception {
        var bad = BootFixtures.pack(temp.resolve("bad.jar"), MachineColdBootTest.T1, Map.of("p/Bad.class", classWithMethods("p/Bad", 1)));
        var slow = BootFixtures.pack(temp.resolve("slow.jar"), MachineColdBootTest.T1, Map.of("p/Slow.class", classWithMethods("p/Slow", 1)));
        var digest = Sha256.INSTANCE;
        // p/Bad's parse fails outright (not a fault: an internal error, which aborts the boot). p/Slow's parse takes a while and,
        // like a worker inside a native write, does not stop when interrupted.
        Stage1.Parser parser = (d, bytes, owner) -> {
            if (owner.equals("p/Bad")) throw new IllegalStateException("boom");
            long until = System.nanoTime() + Duration.ofMillis(400).toNanos();
            while (System.nanoTime() < until) Thread.onSpinWait();
            return ClassFacts.of(d, bytes, owner);
        };
        var stage = new Stage1(digest, new ContentTree(digest), 25, 2, parser);
        assertThatThrownBy(() -> stage.run(new InMemoryMachineStore(), List.of(Enumerate.jar("bad.jar", bad), Enumerate.jar("slow.jar", slow))))
                .hasMessageContaining("boom");
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> t.getName().startsWith("jvmd-stage1-") && t.isAlive());
    }
}
