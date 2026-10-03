package dev.jvmd.tests.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jvmd.boot.cold.stage1.Entries;
import dev.jvmd.boot.cold.stage1.Enumerate;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.MachineTree;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The jar reader (stage 1, C.2): Zip64, and a size bound derived from DEFLATE instead of chosen. */
@Tag("phase-3")
class ZipReaderTest {
    @TempDir Path temp;

    private Map<String, byte[]> classes() throws IOException {
        return BootFixtures.compile(Files.createTempDirectory(temp, "z-"), MachineColdBootTest.library(""));
    }

    private Enumerate.Location write(String name, byte[] jar) throws IOException {
        var file = temp.resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, jar);
        return Enumerate.jar(name, file);
    }

    /** A jar with more than 65535 entries is a jar. ZipOutputStream writes the Zip64 end-of-central-directory records for it. */
    @Test void anArchiveOfSeventyThousandEntriesIsAJarNotAFault() throws Exception {
        var out = new ByteArrayOutputStream();
        var classes = classes();
        try (var zip = new ZipOutputStream(out)) {
            for (var e : classes.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
            for (int i = 0; i < 70_000; i++) {
                zip.putNextEntry(new ZipEntry("res/e" + i + ".txt"));
                zip.closeEntry();
            }
        }
        var bytes = out.toByteArray();
        var items = Entries.zip(bytes, 25).classes();
        assertThat(items).hasSameSizeAs(classes.keySet());
        for (var item : items) assertThat(Entries.zip(bytes, 25).read(item)).isEqualTo(classes.get(item.path()));

        var store = new InMemoryMachineStore();
        var result = MachineColdBootTest.boot(Sha256.INSTANCE, 2, List.of(write("big/z/1/z-1.jar", bytes)), store);
        assertThat(result.faults()).isEmpty();
        var path = MachineTree.decodePath(store.get(MachineStore.pathKey("big/z/1/z-1.jar")), 32);
        assertThat(path.k()).isNotNull();
        assertThat(MachineLeaf.decode(store.get(MachineStore.leafKey(path.k())), 32).factCount()).isGreaterThan(0);
    }

    /** Zip64 end-of-central-directory locator and record, and the Zip64 extra field of every entry (sizes and offset). */
    @Test void theZip64RecordsAndExtraFieldsAreHonoured() throws Exception {
        var classes = classes();
        var builder = new ZipBuilder();
        for (var e : classes.entrySet()) builder.deflated(e.getKey(), e.getValue(), true);
        var bytes = builder.build(true);
        var entries = Entries.zip(bytes, 25);
        var items = entries.classes();
        assertThat(items).hasSameSizeAs(classes.keySet());
        for (var item : items) {
            assertThat(entries.read(item)).isEqualTo(classes.get(item.path()));
            assertThat(item.size()).isEqualTo(classes.get(item.path()).length);
        }
    }

    @Test void aSaturatedEndRecordWithoutZip64RecordsIsCorrupt() throws Exception {
        var bytes = new ZipBuilder().deflated("p/A.class", classes().values().iterator().next(), false).build(true);
        // Overwrite the Zip64 locator signature so the saturated EOCD has nothing to point to.
        int locator = bytes.length - 22 - 20;
        bytes[locator] = 0;
        assertThatThrownBy(() -> Entries.zip(bytes, 25)).isInstanceOf(IOException.class).hasMessageContaining("Zip64");
    }

    /**
     * DEFLATE cannot expand more than 1032:1, so a declared size above 1032 * compressed + 1032 is impossible and is a fault of
     * that entry before any buffer exists. The rest of the jar is indexed.
     */
    @Test void anImpossibleDeflateSizeIsAFaultOfThatEntryAndTheRestOfTheJarIsIndexed() throws Exception {
        var classes = classes();
        var builder = new ZipBuilder();
        for (var e : classes.entrySet()) builder.deflated(e.getKey(), e.getValue(), false);
        // 20 bytes of data compress to well under 40 bytes: 2,000,000,000 declared is far beyond 1032 * 40 + 1032.
        builder.deflated("bad/Bomb.class", new byte[20], false, 2_000_000_000L);
        // A stored entry is its own size: this one claims 99 bytes and holds 5.
        builder.stored("bad/Stored.class", new byte[5], 99);
        var bytes = builder.build(false);

        var entries = Entries.zip(bytes, 25);
        var bomb = entries.classes().stream().filter(i -> i.owner().equals("bad/Bomb")).findFirst().orElseThrow();
        assertThatThrownBy(() -> entries.read(bomb)).isInstanceOf(IOException.class).hasMessageContaining("impossible for a DEFLATE stream");
        var stored = entries.classes().stream().filter(i -> i.owner().equals("bad/Stored")).findFirst().orElseThrow();
        assertThatThrownBy(() -> entries.read(stored)).isInstanceOf(IOException.class).hasMessageContaining("Stored entry size");

        var store = new InMemoryMachineStore();
        var result = MachineColdBootTest.boot(Sha256.INSTANCE, 1, List.of(write("bound/z/1/z-1.jar", bytes)), store);
        var path = MachineTree.decodePath(store.get(MachineStore.pathKey("bound/z/1/z-1.jar")), 32);
        assertThat(path.faults()).containsExactly("bad/Bomb.class", "bad/Stored.class");
        assertThat(result.faults()).hasSize(2);
        assertThat(path.k()).isNotNull();
        assertThat(MachineLeaf.decode(store.get(MachineStore.leafKey(path.k())), 32).factCount()).as("the rest of the jar is indexed").isGreaterThan(0);
    }
}
