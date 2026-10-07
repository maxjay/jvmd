package dev.jvmd.boot.cold.stage3;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.Format;
import dev.jvmd.index.rocks.layer.Generation;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class OutputRecoveryTest {
    @TempDir Path directory;
    static Stream<Digest> digests() {
        return Stream.of(Sha256.INSTANCE, new Digest() {
            public String name() { return "SHA3-256"; }
            public int width() { return 32; }
            public Hasher hasher() {
                try {
                    var hash = MessageDigest.getInstance(name());
                    return new Hasher() {
                        public void update(byte[] bytes, int offset, int length) { hash.update(bytes, offset, length); }
                        public Identity finish() { return Identity.of(hash.digest()); }
                    };
                } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
            }
        });
    }
    private Generation disk(Digest digest, String name) {
        var disk = Generation.of(directory.resolve(name), Format.of(digest, Runtime.version().feature()));
        try (var machine = disk.create()) { machine.putRoot(new byte[] {1}); }
        return disk;
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static Root output(ContentTree tree, LocalStore store, Map<String, String> files) {
        var classes = new ArrayList<ResultRecord.ClassFile>();
        files.forEach((name, value) -> {
            var content = bytes(value); var id = tree.digest().hash(content);
            store.put(LocalStore.classFileKey(id), content); classes.add(new ResultRecord.ClassFile(name, id));
        });
        var root = Output.build(tree, store, List.of(new ResultRecord(true, classes, List.of())));
        store.flush(); store.sync(); return root;
    }
    private static void assertFiles(Path directory, Map<String, String> expected) throws IOException {
        var actual = new TreeMap<String, String>();
        try (var paths = Files.walk(directory)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".class")).toList())
                actual.put(directory.relativize(path).toString().replace('\\', '/').replaceFirst("\\.class$", ""), Files.readString(path));
        }
        assertThat(actual).isEqualTo(expected);
        try (var paths = Files.list(directory.resolve(".jvmd-output"))) {
            assertThat(paths.map(p -> p.getFileName().toString()).toList()).containsExactly("lock");
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void failedInstallThenDifferentTargetCannotLeaveUntrackedPartialClasses(Digest digest) throws Exception {
        var disk = disk(digest, "store"); var tree = new ContentTree(digest); var project = digest.hash(bytes("project"));
        var directory = this.directory.resolve("out"); Root newer;
        try (var store = disk.openLocal()) {
            var empty = output(tree, store, Map.of());
            Output.materialise(tree, store, project, "app", 0, empty, directory);
            var failed = output(tree, store, Map.of("a/A", "a", "b/B", "b"));
            newer = output(tree, store, Map.of("c/C", "c"));
            Files.writeString(directory.resolve("b"), "obstruction");
            assertThatThrownBy(() -> Output.materialise(tree, store, project, "app", 0, failed, directory)).isInstanceOf(IOException.class);
            assertThat(directory.resolve("a/A.class")).exists();
            Files.delete(directory.resolve("b"));
        }
        try (var store = disk.openLocal()) {
            assertThat(Output.materialise(tree, store, project, "app", 0, newer, directory)).isEqualTo(new Output.Changes(3, 2));
            assertFiles(directory, Map.of("c/C", "c"));
            assertThat(Output.materialise(tree, store, project, "app", 0, newer, directory)).isEqualTo(new Output.Changes(0, 0));
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void everyTransitionBoundaryRecoversAfterReopenToSameNewerOrOriginalTarget(Digest digest) throws Exception {
        var old = Map.of("p/Remove", "old", "p/Replace", "old", "p/Keep", "same");
        var next = Map.of("p/Add", "add", "p/Replace", "new", "p/Keep", "same");
        var newer = Map.of("p/Remove", "restored", "p/Later", "later", "p/Keep", "same");
        var tree = new ContentTree(digest); var project = digest.hash(bytes("project"));
        // 1 intent + (write, force/close) x 2 stages + 1 deletion + 2 installs + 1 commit = 9 boundaries.
        for (int fault = 1; fault <= 9; fault++) for (int retry = 0; retry < 3; retry++) {
            int failAt = fault; var disk = disk(digest, "case-" + fault + "-" + retry);
            var out = directory.resolve("out-" + fault + "-" + retry); Root before, target, later;
            var observed = new AtomicInteger();
            try (var store = disk.openLocal()) {
                before = output(tree, store, old); target = output(tree, store, next); later = output(tree, store, newer);
                Output.materialise(tree, store, project, "app", 0, before, out);
                Files.writeString(out.resolve("user.txt"), "keep");
                assertThatThrownBy(() -> Output.materialise(tree, store, project, "app", 0, target, out, step -> {
                    if (observed.incrementAndGet() == failAt) throw new IOException("injected " + step);
                })).isInstanceOf(IOException.class).hasMessageContaining("injected");
                var id = digest.hash(bytes(out.toRealPath().toString()));
                var mat = DefinerIndex.decodeRoot(store.get(LocalStore.materialisedKey(project, "app", 0, id)), digest.width());
                assertThat(mat).isEqualTo(failAt == 9 ? target : before);
                assertThat(store.get(LocalStore.materialisingKey(project, "app", 0, id)).length == 0).isEqualTo(failAt == 9);
                if (failAt == 9) assertFiles(out, next); // MAT never claims a target before its files are complete.
            }
            try (var store = disk.openLocal()) {
                var requested = retry == 0 ? target : retry == 1 ? later : before;
                Output.materialise(tree, store, project, "app", 0, requested, out);
                assertFiles(out, retry == 0 ? next : retry == 1 ? newer : old);
                assertThat(Files.readString(out.resolve("user.txt"))).isEqualTo("keep");
                assertThat(Output.materialise(tree, store, project, "app", 0, requested, out)).isEqualTo(new Output.Changes(0, 0));
            }
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void concurrentRequestsSerializeTheWholeTransitionAndRejectDifferentOwners(Digest digest) throws Exception {
        var disk = disk(digest, "store"); var tree = new ContentTree(digest); var project = digest.hash(bytes("project"));
        var out = directory.resolve("out"); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var store = disk.openLocal(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = output(tree, store, Map.of("A", "a")); var b = output(tree, store, Map.of("B", "b"));
            var first = executor.submit(() -> Output.materialise(tree, store, project, "app", 0, a, out, step -> {
                if (step == Output.Step.INTENT) { entered.countDown(); try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("timeout"); }
                    catch (InterruptedException e) { throw new IOException(e); } }
            }));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var secondStarted = new CountDownLatch(1);
            var second = executor.submit(() -> { secondStarted.countDown(); return Output.materialise(tree, store, project, "app", 0, b, out); });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.isDone()).isFalse(); release.countDown();
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            assertFiles(out, Map.of("B", "b"));
            assertThatThrownBy(() -> Output.materialise(tree, store, project, "other", 0, a, out))
                    .isInstanceOf(IOException.class).hasMessageContaining("another materialisation");
            var independent = disk(digest, "independent-store");
            try (var other = independent.openLocal()) {
                var root = output(tree, other, Map.of("Other", "other"));
                assertThatThrownBy(() -> Output.materialise(tree, other, project, "app", 0, root, out))
                        .isInstanceOf(IOException.class).hasMessageContaining("another materialisation");
            }
            assertFiles(out, Map.of("B", "b"));
        } finally { release.countDown(); }
    }

    @ParameterizedTest @MethodSource("digests")
    void payloadRetentionDoesNotGrowWithTotalOutputBytes(Digest digest) throws Exception {
        for (int count : List.of(16, 64)) {
            var disk = disk(digest, "store-" + count); var tree = new ContentTree(digest);
            try (var store = disk.openLocal()) {
                var classes = new ArrayList<ResultRecord.ClassFile>();
                for (int i = 0; i < count; i++) {
                    var bytes = new byte[1024 * 1024]; Arrays.fill(bytes, (byte) i); var id = digest.hash(bytes);
                    store.put(LocalStore.classFileKey(id), bytes); store.flush();
                    classes.add(new ResultRecord.ClassFile("p/C" + i, id));
                }
                var output = Output.build(tree, store, List.of(new ResultRecord(true, classes, List.of()))); store.flush();
                var references = new ArrayList<WeakReference<byte[]>>(); var staged = new AtomicInteger();
                var maxLive = new AtomicInteger();
                var measured = (LocalStore) java.lang.reflect.Proxy.newProxyInstance(LocalStore.class.getClassLoader(), new Class<?>[]{LocalStore.class}, (proxy, method, args) -> {
                    boolean cf = method.getName().equals("get") && new String((byte[]) args[0], 0, 3, StandardCharsets.US_ASCII).equals("CF|");
                    if (cf) {
                        System.gc();
                        int live = (int) references.stream().filter(ref -> ref.get() != null).count(); maxLive.accumulateAndGet(live, Math::max);
                        assertThat(live).as("retained prior payloads at read %s of %s", references.size(), count).isLessThanOrEqualTo(1);
                        assertThat(staged.get()).isEqualTo(references.size());
                    }
                    Object value;
                    try { value = method.invoke(store, args); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    if (cf) references.add(new WeakReference<>((byte[]) value)); return value;
                });
                Output.materialise(tree, measured, digest.hash(bytes("project")), "app", 0, output, directory.resolve("out-" + count), step -> {
                    if (step == Output.Step.STAGE) staged.incrementAndGet();
                });
                assertThat(references).hasSize(count); assertThat(staged.get()).isEqualTo(count);
                System.out.printf("F19 %s totalMiB=%d priorLiveMiB=%d staged=%d%n", digest.name(), count, maxLive.get(), staged.get());
            }
        }
    }
}
