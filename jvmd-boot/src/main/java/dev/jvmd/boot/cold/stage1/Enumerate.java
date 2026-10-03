package dev.jvmd.boot.cold.stage1;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Step 1 (stage 1, 4 and appendix C.1/C.3): the repository and the JDKs as an ordered list of {@code (location, stamp)}. One
 * thread, one stat per file. The order is the job order.
 */
public final class Enumerate {
    private Enumerate() { }

    /**
     * One thing to index. A jar has a {@code file}; a JDK module has a {@code module}, the {@code jrt} filesystem of its JDK and
     * the {@code bh} computed once per JDK (C.3), because there are no module bytes to hash on their own.
     */
    public record Location(String name, long size, long mtimeNanos, Path file, String module, FileSystem jrt, Identity bh) {
        public boolean isModule() { return module != null; }
    }

    /** Unsigned byte order of the UTF-8 names: the order of every walk and every tie-break (C.1). */
    public static int compareNames(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** A single jar as a location named {@code name} (tests, and the repository walk). */
    public static Location jar(String name, Path file) throws IOException {
        var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new Location(name, attributes.size(), mtimeNanos(attributes), file, null, null, null);
    }

    private static long mtimeNanos(java.nio.file.attribute.BasicFileAttributes attributes) {
        var t = attributes.lastModifiedTime().toInstant();
        return t.getEpochSecond() * 1_000_000_000L + t.getNano();
    }

    /**
     * C.1: depth first, children in unsigned byte order of their names, symbolic links not followed, names starting with '.'
     * skipped. Kept: regular {@code *.jar} except {@code *-sources.jar} and {@code *-javadoc.jar}.
     */
    public static List<Location> repository(Path root) throws IOException {
        var out = new ArrayList<Location>();
        if (Files.isDirectory(root)) walk(root, root, out);
        return out;
    }

    private static void walk(Path root, Path directory, List<Location> out) throws IOException {
        var children = new ArrayList<Path>();
        try (var stream = Files.newDirectoryStream(directory)) { for (var child : stream) children.add(child); }
        children.sort((a, b) -> compareNames(a.getFileName().toString(), b.getFileName().toString()));
        for (var child : children) {
            String name = child.getFileName().toString();
            if (name.startsWith(".")) continue;
            if (Files.isSymbolicLink(child)) continue;
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) { walk(root, child, out); continue; }
            if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!name.endsWith(".jar") || name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar")) continue;
            out.add(jar(root.relativize(child).toString().replace('\\', '/'), child));
        }
    }

    /**
     * C.3: one location per module of a JDK, {@code jrt:/<module>@<jdkHome>}, modules in unsigned byte order. {@code lib/modules}
     * is read and digested once: {@code bh = Digest(jdkDigest || zstr moduleName)}, so two installs of one build share every
     * module through {@code Seen}. Filesystems opened for other JDK homes are added to {@code opened} for the caller to close.
     */
    public static List<Location> jdk(Path jdkHome, Digest digest, List<FileSystem> opened) throws IOException {
        Path modules = jdkHome.resolve("lib/modules");
        var attributes = Files.readAttributes(modules, java.nio.file.attribute.BasicFileAttributes.class);
        // Streamed, not read into one array: lib/modules is over a hundred megabytes. The buffer sizes a read, not a limit.
        var hasher = digest.hasher();
        var buffer = new byte[1 << 20];
        try (var in = Files.newInputStream(modules)) {
            for (int n; (n = in.read(buffer)) > 0; ) hasher.update(buffer, 0, n);
        }
        var jdkDigest = hasher.finish();
        FileSystem jrt;
        if (sameFile(jdkHome, Path.of(System.getProperty("java.home")))) jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        else { jrt = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", jdkHome.toString())); opened.add(jrt); }
        var names = new ArrayList<String>();
        try (var stream = Files.newDirectoryStream(jrt.getPath("/modules"))) { for (var m : stream) names.add(m.getFileName().toString()); }
        names.sort(Enumerate::compareNames);
        var out = new ArrayList<Location>(names.size());
        for (var module : names) {
            var bh = digest.hash(jdkDigest.view(), module.getBytes(StandardCharsets.UTF_8), new byte[] {0});
            out.add(new Location("jrt:/" + module + "@" + jdkHome, attributes.size(), mtimeNanos(attributes), null, module, jrt, bh));
        }
        return out;
    }

    static boolean sameFile(Path a, Path b) {
        try { return Files.isSameFile(a, b); } catch (IOException e) { return a.equals(b); }
    }
}
