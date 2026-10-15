package dev.jvmd.index.rocks.layer;

import dev.jvmd.index.layer.machine.Format;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * The generation directory {@code indexDir / FORMAT} (stage 1, 2.5). A different FORMAT is a different, empty directory: there is
 * no migration. {@link #create} fails if a ROOT exists, and replaces a store that opens without one (a boot that died before its
 * root leaves nothing that is trusted). A store that exists but cannot be opened is neither: it may hold MACHINE and every
 * project's LOCAL root, so it is an error and is never deleted.
 */
public final class Generation {
    private final Path directory;

    private Generation(Path directory) { this.directory = directory; }

    public static Generation of(Path indexDir, Format format) { return new Generation(indexDir.resolve(format.directoryName())); }

    public Path directory() { return directory; }

    /**
     * True if the directory holds a store with a ROOT. A missing directory, or one RocksDB never finished creating, has none.
     *
     * @throws IllegalStateException if a store exists but cannot be opened: that is not the same as having no ROOT
     */
    public boolean hasRoot() {
        if (!Files.isDirectory(directory) || neverCreated(directory)) return false;
        try (var store = new RocksMachineStore(directory, true)) {
            return store.hasRoot();
        } catch (RuntimeException unreadable) {
            throw new IllegalStateException("Machine index generation " + directory + " exists but cannot be opened; it was left untouched. "
                    + "Move it aside to cold boot a new one.", unreadable);
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

    /**
     * The committed MACHINE store, opened once and writable, as a LOCAL store (stage 2, 2.5 and 9.1): LOCAL records live in the same
     * RocksDB under their own key prefixes, so a LOCAL generation is a key prefix and not a store of its own. The caller asks the one
     * open store whether the project is committed ({@code LocalStore.getLocalRoot}) and boots into it if not.
     *
     * @throws IllegalStateException if this generation has no committed MACHINE root
     */
    public RocksLocalStore openLocal() {
        if (!Files.isDirectory(directory)) throw new IllegalStateException("MACHINE generation is not committed: " + directory);
        var machine = new RocksMachineStore(directory, false);
        if (!machine.hasRoot()) {
            machine.close();
            throw new IllegalStateException("MACHINE generation is not committed: " + directory);
        }
        return new RocksLocalStore(machine);
    }

    /**
     * A directory RocksDB never finished creating: no {@code CURRENT}, which RocksDB installs atomically when a database is first set
     * up, and no table or write-ahead log files. A create killed or failed inside {@code RocksDB.open} leaves only LOCK, LOG, IDENTITY
     * or a manifest, from which nothing is reachable.
     */
    private static boolean neverCreated(Path dir) {
        if (Files.exists(dir.resolve("CURRENT"))) return false;
        try (var entries = Files.list(dir)) {
            return entries.map(p -> p.getFileName().toString()).noneMatch(name -> name.endsWith(".sst") || name.endsWith(".log"));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
