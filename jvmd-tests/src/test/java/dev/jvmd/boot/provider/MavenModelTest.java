package dev.jvmd.boot.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmd.resolver.Resolution;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The provider's translation rules that a real reactor does not all exercise at once. */
@Tag("phase-3")
class MavenModelTest {
    private static final Path ROOT = Path.of("/work/project").toAbsolutePath();
    private static final Path REPO = Path.of("/work/m2").toAbsolutePath();

    private static Resolution.Module module(String name, Resolution.Processing processing) {
        var dir = ROOT.resolve(name);
        return new Resolution.Module("g:" + name + ":1", dir.toString(), "jar", List.of(dir.resolve("src/main/java").toString()), List.of(),
                dir.resolve("target/classes").toString(), dir.resolve("target/test-classes").toString(), "17", List.of(),
                List.of("--release", "17"), List.of("--release", "17"), processing, Resolution.Processing.none());
    }

    private static Resolution resolution(Resolution.Module... modules) {
        var order = new java.util.HashMap<String, List<String>>();
        for (var m : modules) { order.put(m.gav() + ":main", List.of()); order.put(m.gav() + ":test", List.of()); }
        return new Resolution(ROOT.toString(), List.of(modules), List.of(), List.of(), Map.of(), List.of(), "", true, false, order);
    }

    @Test void anUnsetProcBelowRelease23IsFullProcessingFoundOnTheClasspathAndSaysSo() {
        var unset = new Resolution.Processing(List.of("/cp/a.jar"), List.of(), false, false, "", "", false);
        var result = MavenModel.of(resolution(module("a", unset)), REPO, ROOT);
        var processing = result.parse().modules().getFirst().main().processing();
        assertThat(processing.mode()).as("javac before 23 processed without being asked").isEqualTo("full");
        assertThat(processing.processorPath()).as("found on the classpath, as javac does without a processor path").isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.startsWith("a (main): proc is not set"));
    }

    @Test void anUnsetProcAtRelease23OrLaterIsNone() {
        var unset = new Resolution.Processing(List.of("/cp/a.jar"), List.of(), false, false, "", "", false);
        var dir = ROOT.resolve("a");
        var modern = new Resolution.Module("g:a:1", dir.toString(), "jar", List.of(dir.resolve("src/main/java").toString()), List.of(),
                dir.resolve("target/classes").toString(), dir.resolve("target/test-classes").toString(), "25", List.of(),
                List.of("--release", "25"), List.of("--release", "25"), unset, Resolution.Processing.none());
        var result = MavenModel.of(resolution(modern), REPO, ROOT);
        assertThat(result.parse().modules().getFirst().main().processing().enabled()).as("built by 23 or later: no implicit processing").isFalse();
        assertThat(result.warnings()).noneMatch(w -> w.contains("proc is not set"));
    }

    @Test void procNoneIsNoProcessingAndOnlyIsKept() {
        var none = new Resolution.Processing(List.of(), List.of(), false, false, "", "none", false);
        var only = new Resolution.Processing(List.of(), List.of("p.Gen"), true, false, "", "only", false);
        var model = MavenModel.of(resolution(module("a", none), module("b", only)), REPO, ROOT).parse();
        assertThat(model.modules().get(0).main().processing().enabled()).isFalse();
        assertThat(model.modules().get(1).main().processing().mode()).isEqualTo("only");
        assertThat(model.modules().get(1).main().processing().processors()).containsExactly("p.Gen");
    }

    @Test void anExplicitProcessorPathIsWrittenLikeDependencyLocations() {
        var explicit = new Resolution.Processing(List.of(REPO.resolve("org/p/gen/1/gen-1.jar").toString(), "/elsewhere/tool.jar"),
                List.of(), true, false, "", "", true);
        var model = MavenModel.of(resolution(module("a", explicit)), REPO, ROOT).parse();
        assertThat(model.modules().getFirst().main().processing().processorPath())
                .containsExactly("org/p/gen/1/gen-1.jar", Path.of("/elsewhere/tool.jar").toAbsolutePath().toString().replace('\\', '/'));
    }
}
