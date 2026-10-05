package dev.jvmd.index.rocks.layer;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.machine.Format;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * The generation directory {@code indexDir / FORMAT} (stage 1, 2.5). A different FORMAT is a different, empty directory: there is
 * no migration. {@link #create} fails if a ROOT exists, and replaces a directory that has none (a boot that died before its root
 * leaves nothing that is trusted).
 */
public final class Generation {
    private final Path directory;

    private Generation(Path directory) { this.directory = directory; }

    public static Generation of(Path indexDir, Format format) { return new Generation(indexDir.resolve(format.directoryName())); }

    public Path directory() { return directory; }

    /** True if the directory holds a store with a ROOT. A missing or unreadable store has none. */
    public boolean hasRoot() {
        if (!Files.isDirectory(directory)) return false;
        try (var store = new RocksMachineStore(directory, true)) {
            return store.hasRoot();
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** A fresh store for a cold boot. Throws if this generation already has a ROOT; never deletes a trusted generation. */
    public RocksMachineStore create() {
        if (hasRoot()) throw new IllegalStateException("Machine index generation already committed: " + directory);
        try {
            if (Files.exists(directory)) deleteRecursively(directory);
            Files.createDirectories(directory);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return new RocksMachineStore(directory, false);
    }

    /** The committed store, for reading (warm boot, tests). */
    public RocksMachineStore open() {
        if (!hasRoot()) throw new IllegalStateException("No committed machine index in " + directory);
        return new RocksMachineStore(directory, true);
    }

    /** The LOCAL generation of one project inside this MACHINE generation: a key prefix, not a directory (stage 2, 2.5 and 9.1). */
    public Local local(Identity projectKey, String format) { return new Local(projectKey, format); }

    public final class Local {
        private final Identity projectKey;
        private final String format;

        private Local(Identity projectKey, String format) { this.projectKey = projectKey; this.format = format; }

        /** True if the project has a LOCAL root of this FORMAT. A root of another FORMAT is a cold boot, never a migration. */
        public boolean hasLocalRoot() {
            if (!Files.isDirectory(directory)) return false;
            try (var store = new RocksMachineStore(directory, true)) {
                var value = store.get(LocalStore.localRootKey(projectKey));
                return value != null && new Codec.Reader(value).str().equals(format);
            } catch (RuntimeException unreadable) {
                return false;
            }
        }

        /** The committed MACHINE store, writable, as a LOCAL store. Throws if MACHINE has no ROOT or this project already has a root. */
        public RocksLocalStore createLocal() {
            if (!hasRoot()) throw new IllegalStateException("No committed machine index in " + directory);
            if (hasLocalRoot()) throw new IllegalStateException("Local generation already committed for this project");
            return new RocksLocalStore(new RocksMachineStore(directory, false));
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
