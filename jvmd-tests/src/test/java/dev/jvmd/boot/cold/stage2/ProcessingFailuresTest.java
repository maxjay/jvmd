package dev.jvmd.boot.cold.stage2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How annotation processing in header compilation fails (amendments 4.8): never by publishing headers a processor left incomplete,
 * never by losing a file's parse fault, never by leaking what the compilation opened, and never by a different root for the same input.
 */
@Tag("phase-3")
class ProcessingFailuresTest {
    @TempDir Path temp;

    @AfterAll static void release() { Stage2Support.release(); }

    /** One processor for four source-retained annotations, in a jar that declares it, and that the module also compiles against. */
    private static final Map<String, String> PROCESSOR = Map.of(
            "proc/Stamp.java", "package proc; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) public @interface Stamp { }",
            "proc/Fail.java", "package proc; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) public @interface Fail { }",
            "proc/Boom.java", "package proc; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) public @interface Boom { }",
            "proc/GenClass.java", "package proc; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) public @interface GenClass { }",
            "proc/Probe.java", "package proc; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) public @interface Probe { }",
            "proc/Slow.java", "package proc; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) public @interface Slow { }",
            "proc/TestProcessor.java", """
                    package proc;
                    import java.util.Set;
                    import javax.annotation.processing.*;
                    import javax.lang.model.SourceVersion;
                    import javax.lang.model.element.*;
                    @SupportedAnnotationTypes("proc.*")
                    public class TestProcessor extends AbstractProcessor {
                        @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                        @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                            var elements = processingEnv.getElementUtils();
                            try {
                                for (var e : round.getElementsAnnotatedWith(elements.getTypeElement("proc.Stamp"))) {
                                    var name = e.getSimpleName() + "Stamped";
                                    try (var w = processingEnv.getFiler().createSourceFile("p." + name, e).openWriter()) {
                                        // A generator's timestamp: the bytes differ on every run, the declarations do not.
                                        w.write("package p; // generated at " + System.nanoTime() + "\\npublic class " + name + " { public int x; }\\n");
                                    }
                                }
                                for (var e : round.getElementsAnnotatedWith(elements.getTypeElement("proc.Fail")))
                                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR, "failure requested", e);
                                for (var e : round.getElementsAnnotatedWith(elements.getTypeElement("proc.Boom")))
                                    throw new IllegalStateException("boom requested");
                                // What the processor's own class loader can see: the JDK, javac included, but not the process running javac.
                                for (var e : round.getElementsAnnotatedWith(elements.getTypeElement("proc.Probe"))) {
                                    var loader = getClass().getClassLoader();
                                    for (var name : new String[] {"com.sun.source.tree.Tree", "com.sun.tools.javac.code.Symbol", "java.beans.Introspector"})
                                        try { Class.forName(name, false, loader); }
                                        catch (ClassNotFoundException missing) { processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR, "not visible: " + name, e); }
                                    for (var name : new String[] {"org.assertj.core.api.Assertions", "dev.jvmd.boot.cold.stage2.Stage2", "com.fasterxml.jackson.databind.ObjectMapper"})
                                        try { Class.forName(name, false, loader); processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR, "visible: " + name, e); }
                                        catch (ClassNotFoundException hidden) { }
                                }
                                // A processor that takes a while, unless it is interrupted.
                                for (var e : round.getElementsAnnotatedWith(elements.getTypeElement("proc.Slow")))
                                    try { Thread.sleep(20_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                                for (var e : round.getElementsAnnotatedWith(elements.getTypeElement("proc.GenClass")))
                                    try (var out = processingEnv.getFiler().createClassFile("p.GenC", e).openOutputStream()) {
                                        out.write(java.lang.classfile.ClassFile.of().build(java.lang.constant.ClassDesc.of("p.GenC"), cb -> { }));
                                    }
                            } catch (java.io.IOException io) { throw new java.io.UncheckedIOException(io); }
                            return false;
                        }
                    }
                    """);

    private Path repository() throws IOException {
        var repo = temp.resolve("repository");
        var entries = new LinkedHashMap<String, byte[]>(Stage2Support.compile(Files.createDirectories(temp.resolve("work")), PROCESSOR, List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("proc.TestProcessor\n"));
        Stage2Support.pack(repo.resolve("proc/proc/1/proc-1.jar"), entries);
        return repo;
    }

    /** One module whose main scope compiles against the processor jar and runs it, from its processor path. */
    private static ProjectModel model(Path root, Map<String, String> sources) throws IOException {
        var files = new LinkedHashMap<String, String>();
        sources.forEach((name, text) -> files.put("a/src/main/java/" + name, text));
        Stage2Support.write(root, files);
        var json = Stage2Support.model(root, new Stage2Support.Mod("a", "g:a:1", List.of(Stage2Support.Dep.jar("proc:proc:1", "proc/proc/1/proc-1.jar"))));
        var doc = (ObjectNode) new ObjectMapper().readTree(json);
        var main = (ObjectNode) doc.withArray("modules").get(0).get("scopes").get("main");
        var processing = main.putObject("processing").put("mode", "full");
        processing.putArray("processorPath").add("proc/proc/1/proc-1.jar");
        processing.putArray("processors");
        return ProjectModel.parse(new ObjectMapper().writeValueAsBytes(doc));
    }

    private Stage2.Result boot(Path repository, ProjectModel model) throws IOException {
        var digest = Sha256.INSTANCE;
        return new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 2, repository, ClassFacts::of).run(Stage2Support.jdkOnly(digest).copy(), model);
    }

    private static long scratch() throws IOException {
        try (var dirs = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return dirs.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("jvmd-generated-") || n.startsWith("jvmd-processed-")).count();
        }
    }

    @Test void aFileThatDoesNotParseIsItsOwnFaultAndTheOthersAreStillProcessed() throws Exception {
        var repo = repository();
        var root = temp.resolve("project");
        var model = model(root, Map.of(
                "p/Broken.java", "package p; class Broken { int y }",
                "p/A.java", "package p; @proc.Stamp public class A { }"));
        var digest = Sha256.INSTANCE;
        var machine = Stage2Support.jdkOnly(digest).copy();
        var result = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 2, repo, ClassFacts::of).run(machine, model);
        assertThat(result.faults()).anyMatch(f -> f.contains("p/Broken.java")).noneMatch(f -> f.contains("p/A.java"));
        var leaf = MachineLeaf.decode(machine.get(MachineStore.leafKey(result.leaves().get("a/main"))), digest.width());
        assertThat(leaf.typeCount()).as("A and the AStamped its processing wrote; Broken declares nothing").isEqualTo(2);
    }

    /** A processor loads from its own path and the JDK only: never from the class path of the process that runs javac (the daemon's, or a test's). */
    @Test void aProcessorSeesTheJdkButNotTheProcessRunningJavac() throws Exception {
        var repo = repository();
        var model = model(temp.resolve("project"), Map.of("p/A.java", "package p; @proc.Probe public class A { }"));
        assertThat(boot(repo, model).faults()).isEmpty();
    }

    /** Interrupting the boot cancels it: the running job ends at its next scope, no other starts, and no root is written. */
    @Test void anInterruptedBootEndsSoonAndWritesNoRoot() throws Exception {
        var repo = repository();
        var model = model(temp.resolve("project"), Map.of("p/A.java", "package p; @proc.Slow public class A { }"));
        var digest = Sha256.INSTANCE;
        var store = Stage2Support.jdkOnly(digest).copy();
        var thrown = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var boot = Thread.ofPlatform().start(() -> {
            try { new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 2, repo, ClassFacts::of).run(store, model); }
            catch (Throwable t) { thrown.set(t); }
        });
        Thread.sleep(1_000);
        long interrupted = System.nanoTime();
        boot.interrupt();
        boot.join(15_000);
        assertThat(boot.isAlive()).as("the boot ended").isFalse();
        assertThat((System.nanoTime() - interrupted) / 1_000_000).as("well before the processor's 20 s").isLessThan(10_000);
        assertThat(thrown.get()).isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(store.hasLocalRoot(Stage2.projectKey(digest, model))).isFalse();
    }

    @Test void aProcessorErrorFailsTheBootRatherThanPublishingIncompleteHeaders() throws Exception {
        var repo = repository();
        var model = model(temp.resolve("project"), Map.of("p/A.java", "package p; @proc.Fail public class A { }"));
        assertThatThrownBy(() -> boot(repo, model)).hasMessageContaining("Annotation processing failed").hasMessageContaining("failure requested");
    }

    @Test void aProcessorThatThrowsFailsTheBootAndLeavesNothingBehind() throws Exception {
        var repo = repository();
        var model = model(temp.resolve("project"), Map.of("p/A.java", "package p; @proc.Boom public class A { }"));
        long before = scratch();
        assertThatThrownBy(() -> boot(repo, model)).hasMessageContaining("annotation processor failed").hasStackTraceContaining("boom requested");
        assertThat(scratch()).as("scratch directories of the failed compilation").isEqualTo(before);
    }

    @Test void whatProcessorsWriteIsDeterministicThoughItsBytesAreNot() throws Exception {
        var repo = repository();
        var model = model(temp.resolve("project"), Map.of("p/A.java", "package p; @proc.Stamp public class A { }"));
        assertThat(boot(repo, model).root()).as("two cold boots of one input").isEqualTo(boot(repo, model).root());
    }

    @Test void aClassFileAProcessorWritesIsAFaultNotASilentlyMissingType() throws Exception {
        var repo = repository();
        var model = model(temp.resolve("project"), Map.of("p/A.java", "package p; @proc.GenClass public class A { }"));
        assertThat(boot(repo, model).faults()).anyMatch(f -> f.contains("p/GenC.class") && f.contains("wrote a class file"));
    }

    @Test void processingWithoutAProcessorToRunLeavesHeaderFaultsAsFaults() throws Exception {
        var repo = temp.resolve("empty-repository");
        Files.createDirectories(repo);
        var root = temp.resolve("project");
        Stage2Support.write(root, Map.of("a/src/main/java/p/A.java", "package p; public class A { Missing m; }"));
        var doc = (ObjectNode) new ObjectMapper().readTree(Stage2Support.model(root, new Stage2Support.Mod("a", "g:a:1", List.of())));
        ((ObjectNode) doc.withArray("modules").get(0).get("scopes").get("main")).putObject("processing").put("mode", "full");
        var model = ProjectModel.parse(new ObjectMapper().writeValueAsBytes(doc));
        assertThat(boot(repo, model).faults()).as("an unresolved header type, not a processing failure").anyMatch(f -> f.contains("Missing"));
    }
}
