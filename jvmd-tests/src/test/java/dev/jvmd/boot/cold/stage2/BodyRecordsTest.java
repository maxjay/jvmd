package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("phase-3")
class BodyRecordsTest {
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }

    @ParameterizedTest @MethodSource("digests")
    void javacOffsetsAreUtf16AndNoposAndDiagnosticOrderSurviveTheCodec(Digest digest) throws Exception {
        String source = "class Broken { String s = \"\ud83d\ude00\"; void f() { missing(); } }";
        var unit = new SimpleJavaFileObject(URI.create("memory:///Broken.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        var compiler = ToolProvider.getSystemJavaCompiler();
        var messages = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(messages, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, files, messages, List.of("-proc:none"), null, List.of(unit));
            task.setLocale(Locale.ROOT);
            assertThat(task.call()).isFalse();
        }
        var nativeError = messages.getDiagnostics().stream().filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR).findFirst().orElseThrow();
        assertThat(nativeError.getStartPosition()).isEqualTo(source.indexOf("missing"));
        assertThat(nativeError.getStartPosition()).isNotEqualTo(source.substring(0, source.indexOf("missing")).getBytes(StandardCharsets.UTF_8).length);
        var error = new ResultRecord.Diagnostic(0, nativeError.getStartPosition(), nativeError.getEndPosition(), nativeError.getCode(), nativeError.getMessage(Locale.ROOT));
        var note = new ResultRecord.Diagnostic(2, -1, -1, "note", "without a source position " + (char)0xd800 + " " + (char)0xdc00);
        var result = new ResultRecord(false, List.of(), List.of(note, error, note));
        assertThat(ResultRecord.decode(result.encode(), digest.width())).isEqualTo(result);
        var invalid = result.encode(); invalid[0] = 7;
        assertThatThrownBy(() -> ResultRecord.decode(invalid, digest.width())).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @MethodSource("digests")
    void namesAndNamespacesKeepProofsResultsClassBytesAndUsesDistinct(Digest digest) {
        var a = digest.hash(new byte[] {1}); var b = digest.hash(new byte[] {2});
        assertThat(LocalStore.proofKey(a, Stage2Support.source("src/A.java"))).isNotEqualTo(LocalStore.proofKey(b, Stage2Support.source("src/A.java")))
                .isNotEqualTo(LocalStore.proofKey(a, Stage2Support.source("test/A.java")));
        assertThat(LocalStore.resultKey(a)).isNotEqualTo(LocalStore.resultKey(b)).isNotEqualTo(LocalStore.usesKey(a));
        assertThat(LocalStore.classFileKey(a)).isNotEqualTo(MachineStore.nodeKey(a));
        var x = new ResultRecord.ClassFile("p/A", a); var y = new ResultRecord.ClassFile("p/A$Inner", b);
        var result = new ResultRecord(true, List.of(y, x), List.of());
        assertThat(result.encode()).isEqualTo(new ResultRecord(true, List.of(x, y), List.of()).encode());
        assertThat(ResultRecord.decode(result.encode(), digest.width())).isEqualTo(result);
        assertThatThrownBy(() -> new ResultRecord(true, List.of(x, x), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @MethodSource("digests")
    void usesRetainSeparateTNDKeysAndCanonicalOccurrences(Digest digest) {
        var one = new UsesRecord.Span(3, 8); var two = new UsesRecord.Span(20, 25);
        var t = new UsesRecord.Use(Proof.T, "p/Base", Keys.METHOD, "read", List.of(two, one, two));
        var n = new UsesRecord.Use(Proof.N, "p/Base", Keys.TYPE, "Nested", List.of(one));
        var d = new UsesRecord.Use(UsesRecord.D, "p/Missing", Keys.TYPE, "", List.of(two));
        var uses = new UsesRecord(List.of(d, n, t));
        assertThat(uses.encode()).isEqualTo(new UsesRecord(List.of(t, d, n)).encode());
        assertThat(UsesRecord.decode(uses.encode())).isEqualTo(uses);
        assertThat(t.spans()).containsExactly(one, two);
        assertThat(t.key()).isEqualTo(new Proof.Range(Proof.T, "p/Base", Keys.METHOD, "read").key());
        assertThatThrownBy(() -> new UsesRecord.Use(7, "p/Base", Keys.TYPE, "", List.of(one))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsesRecord.Span(8, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @MethodSource("digests")
    void aLocalRecommitMakesTheOldBodiesRootStaleWithoutChangingIt(Digest digest) {
        var a = digest.hash(new byte[] {1}); var b = digest.hash(new byte[] {2}); var c = digest.hash(new byte[] {3});
        var local = LocalRoot.decode(digest, LocalRoot.encode(digest, "layout=4;local=5;javac=25;locale=root", new Root(a, b, 2, 0), b, c));
        var bodies = new BodiesRoot(BodiesRoot.format(local.format()), c, a, b, c);
        var original = bodies.encode();
        assertThat(BodiesRoot.decode(original, digest.width())).isEqualTo(bodies);
        assertThat(bodies.current(local)).isTrue();
        assertThat(new BodiesRoot(local.format() + ";bodies=1", c, a, b, c).current(local)).isFalse();
        assertThat(new BodiesRoot(local.format() + ";bodies=2", c, a, b, c).current(local)).isFalse();
        assertThat(new BodiesRoot(local.format() + ";bodies=3", c, a, b, c).current(local)).isFalse();
        var recommitted = LocalRoot.decode(digest, LocalRoot.encode(digest, local.format(), new Root(c, a, 3, 0), b, c));
        assertThat(bodies.current(recommitted)).isFalse();
        assertThat(bodies.encode()).isEqualTo(original);
        assertThat(LocalStore.bodiesRootKey(a)).isNotEqualTo(LocalStore.localRootKey(a));
        assertThat(LocalStore.bodiesRootHistoryKey(a, 1)).isNotEqualTo(LocalStore.bodiesRootHistoryKey(a, 2));
        assertThat(LocalStore.outputKey(a, "m", 0)).isNotEqualTo(LocalStore.outputKey(a, "m", 1));
        assertThat(LocalStore.materialisedKey(a, "m", 0, b)).isNotEqualTo(LocalStore.materialisedKey(a, "m", 0, c));
    }
}
