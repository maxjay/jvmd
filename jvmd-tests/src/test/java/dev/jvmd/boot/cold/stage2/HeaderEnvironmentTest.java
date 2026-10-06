package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.LocalFormat;
import dev.jvmd.index.layer.local.SourceFacts;
import dev.jvmd.index.layer.machine.LeafBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("phase-3")
class HeaderEnvironmentTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }

    @ParameterizedTest @MethodSource("digests")
    void sourceEncodingIsExplicitAndPartOfTheCompilerInputs(Digest digest) throws Exception {
        var file = dir.resolve("Text.java");
        Files.writeString(file, "class Text { static final String VALUE = \"café\"; }", StandardCharsets.UTF_8);
        var implicit = projection(digest, file, List.of());
        var utf8 = projection(digest, file, List.of("-encoding", "UTF-8"));
        var latin = projection(digest, file, List.of("-encoding", "ISO-8859-1"));
        assertThat(implicit).isEqualTo(utf8);
        assertThat(latin).isNotEqualTo(utf8);
        assertThat(options(digest, List.of("-encoding", "UTF-8"), List.of())).isNotEqualTo(options(digest, List.of("-encoding", "ISO-8859-1"), List.of()));
        assertThat(options(digest, List.of(), List.of("p.First"))).isNotEqualTo(options(digest, List.of(), List.of("p.Second")));
        assertThat(HeaderCompiler.charset(List.of("-encoding", "ISO-8859-1", "-encoding", "UTF-8"))).isEqualTo(StandardCharsets.UTF_8);
    }

    @ParameterizedTest @MethodSource("digests")
    void diagnosticsUseRootLocaleAndFormatPinsTheFullCompilerVersion(Digest digest) throws Exception {
        var file = dir.resolve("Bad.java");
        Files.writeString(file, "class Bad { int missingSemicolon }");
        var previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.JAPANESE);
            var japanese = parseError(digest, file);
            Locale.setDefault(Locale.FRENCH);
            var french = parseError(digest, file);
            assertThat(french).isEqualTo(japanese).contains("expected");
        } finally { Locale.setDefault(previous); }
        assertThat(LocalFormat.of(dev.jvmd.index.layer.machine.Format.of(digest, Stage2Support.FEATURE))).contains("javac=" + Runtime.version(), "locale=root");
    }

    private Identity options(Digest digest, List<String> options, List<String> processors) {
        return HeaderCompiler.optionsHash(digest, options, Stage2Support.FEATURE, null, processors);
    }

    private Identity projection(Digest digest, Path file, List<String> options) {
        try (var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source(file.getFileName().toString(), file)), List.of(),
                Stage2Support.JDK, Stage2Support.FEATURE, options, digest)) {
            var facts = new SourceFacts(digest, compiled.elements, compiled.types, false).of(compiled.units.getFirst().declared);
            assertThat(facts.faults()).isEmpty();
            var builder = new LeafBuilder(new ContentTree(digest), new InMemoryLocalStore());
            facts.facts().stream().sorted((a, b) -> Arrays.compareUnsigned(a.m(), b.m())).forEach(builder::add);
            builder.edges(facts.edges());
            return builder.seal();
        }
    }

    private String parseError(Digest digest, Path file) {
        try (var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("Bad.java", file)), List.of(),
                Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest)) {
            return compiled.units.getFirst().parseError;
        }
    }
}
