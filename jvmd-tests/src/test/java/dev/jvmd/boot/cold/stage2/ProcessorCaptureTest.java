package dev.jvmd.boot.cold.stage2;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import javax.annotation.processing.FilerException;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class ProcessorCaptureTest {
    @TempDir Path dir;

    @Test void exactWriterEncodingRawBytesAndCloseOnceAreIndependentOfDisk() throws Exception {
        for (var charset : List.of(StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1)) {
            var capture = new ProcessorCapture(dir.resolve(charset.name()), charset);
            var outputs = new ArrayList<byte[]>();
            var source = capture.javaFile("processor.One", "p.F", Kind.SOURCE, (file, bytes) -> outputs.add(bytes));
            var writer = source.openWriter(); writer.write("class F { String x = \"café\"; }");
            assertThat(outputs).isEmpty(); writer.close(); writer.close();
            assertThat(outputs).hasSize(1); assertThat(outputs.getFirst()).isEqualTo("class F { String x = \"café\"; }".getBytes(charset));
            assertThatThrownBy(source::openOutputStream).isInstanceOf(java.io.IOException.class);
            assertThatThrownBy(source::openInputStream).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> source.getCharContent(true)).isInstanceOf(IllegalStateException.class);
            assertThat(source.delete()).isFalse();
            var raw = capture.javaFile("processor.Two", "p.Raw", Kind.SOURCE, (file, bytes) -> outputs.add(bytes));
            var stream = raw.openOutputStream(); stream.write(new byte[]{0, (byte) 255, 7}); stream.close(); stream.close();
            assertThat(outputs).hasSize(2); assertThat(outputs.getLast()).containsExactly(0, (byte) 255, 7);
            assertThatThrownBy(() -> stream.write(3)).isInstanceOf(java.io.IOException.class);
            var faults = new ArrayList<String>(); capture.finish((name, reason) -> faults.add(name + reason));
            assertThat(faults).isEmpty(); assertThat(dir.resolve(charset.name())).doesNotExist();
        }
    }

    @Test void allProcessorsShareTheNativeSourceClassAndResourceReopeningRules() throws Exception {
        var capture = new ProcessorCapture(dir.resolve("capture"), StandardCharsets.UTF_8);
        var bytes = new ArrayList<byte[]>();
        var first = capture.javaFile("one", "p.F", Kind.SOURCE, (file, output) -> bytes.add(output));
        assertThatThrownBy(() -> capture.javaFile("two", "p.F", Kind.CLASS, (file, output) -> {})).isInstanceOf(FilerException.class);
        assertThatThrownBy(() -> capture.resource("two", StandardLocation.SOURCE_OUTPUT, "p", "F.java", (file, output) -> {})).isInstanceOf(FilerException.class);
        assertThatThrownBy(() -> capture.checkRead(StandardLocation.SOURCE_OUTPUT, "p", "F.java")).isInstanceOf(FilerException.class);
        assertThatThrownBy(() -> capture.javaFile("one", "../F", Kind.SOURCE, (file, output) -> {})).isInstanceOf(FilerException.class);
        assertThatThrownBy(() -> capture.resource("one", StandardLocation.CLASS_OUTPUT, "p", "../x", (file, output) -> {})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> capture.resource("one", StandardLocation.CLASS_PATH, "p", "x", (file, output) -> {})).isInstanceOf(IllegalArgumentException.class);
        assertThat(bytes).isEmpty();
        try (var out = first.openWriter()) { out.write("package p; class F {}"); }
        assertThat(bytes).hasSize(1);
        // A package descriptor is a legal Java output, and resources cannot collide with it in the same output location.
        var pkg = capture.javaFile("one", "p.package-info", Kind.SOURCE, (file, output) -> bytes.add(output));
        try (var out = pkg.openWriter()) { out.write("package p;"); }
        assertThatThrownBy(() -> capture.resource("two", StandardLocation.SOURCE_OUTPUT, "p", "package-info.java", (file, output) -> {})).isInstanceOf(FilerException.class);
    }

    @Test void anOpenedButUnclosedOutputCannotBeMistakenForAnEmptyReusableDerivation() throws Exception {
        var capture = new ProcessorCapture(dir.resolve("capture"), StandardCharsets.UTF_8);
        var outputs = new ArrayList<byte[]>();
        capture.javaFile("processor.Open", "p.F", Kind.SOURCE, (file, bytes) -> outputs.add(bytes)).openWriter().write("unfinished");
        capture.javaFile("processor.Unopened", "p.G", Kind.SOURCE, (file, bytes) -> outputs.add(bytes));
        var faults = new TreeMap<String, String>(); capture.finish(faults::put);
        assertThat(outputs).isEmpty(); assertThat(faults).containsOnlyKeys("processor.Open", "processor.Unopened");
        assertThat(faults.values()).allMatch(reason -> reason.contains("unclosed Filer output"));
    }
}
