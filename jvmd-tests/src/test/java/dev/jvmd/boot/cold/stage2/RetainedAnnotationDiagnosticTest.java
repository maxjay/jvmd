package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.Fact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Independent native evidence for the still-open retained-annotation diagnostic projection. */
@Tag("phase-3")
class RetainedAnnotationDiagnosticTest {
    @TempDir Path directory;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE,new Digests.Sha3()); }

    @ParameterizedTest @MethodSource("digests")
    void changingOnlyRetainedMetadataChangesClientDiagnosticsWithEqualResolutionFacts(Digest digest) throws Exception {
        var dependencies=Files.createDirectories(directory.resolve("dependencies"));
        var output=Files.createDirectories(directory.resolve("output"));
        var mode=directory.resolve("Mode.java");var annotation=directory.resolve("Ann.java");
        var library=directory.resolve("Lib.java");var app=directory.resolve("App.java");
        Files.writeString(mode,"package q; public enum Mode { X,Y }");
        Files.writeString(annotation,"package q; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Ann { Mode value(); }");
        String lib="package q; @Ann(Mode.X) public class Lib { public static int call(){return 1;} }";
        Files.writeString(library,lib);
        Files.writeString(app,"package p; public class App { int value(){return q.Lib.call();} }");
        assertThat(compile(dependencies,dependencies,List.of(mode,annotation,library))).isEmpty();
        var before=ClassFacts.of(digest,Files.readAllBytes(dependencies.resolve("q/Lib.class")),"q/Lib");
        assertThat(compile(output,dependencies,List.of(app))).isEmpty();

        // Keep Lib.class and Ann.class untouched. Only the enum's API loses a constant.
        Files.writeString(mode,"package q; public enum Mode { Y }");
        assertThat(compile(dependencies,dependencies,List.of(mode))).isEmpty();
        var warnings=compile(output,dependencies,List.of(app));
        assertThat(warnings).containsExactly("compiler.warn.unknown.enum.constant");
        var client=Files.readAllBytes(output.resolve("p/App.class"));
        var enumBytes=Files.readAllBytes(dependencies.resolve("q/Mode.class"));
        var annotationBytes=Files.readAllBytes(dependencies.resolve("q/Ann.class"));

        // Now change only Lib's annotation value; no source/options or resolution facts of the client change.
        Files.writeString(library,lib.replace("Mode.X","Mode.Y"));
        assertThat(compile(dependencies,dependencies,List.of(library))).isEmpty();
        var after=ClassFacts.of(digest,Files.readAllBytes(dependencies.resolve("q/Lib.class")),"q/Lib");
        assertThat(after.facts().stream().map(Fact::h).toList()).isEqualTo(before.facts().stream().map(Fact::h).toList());
        assertThat(after.facts().stream().map(f->f.aEntry(digest)).filter(java.util.Objects::nonNull).map(e->e.h()).toList())
                .isNotEqualTo(before.facts().stream().map(f->f.aEntry(digest)).filter(java.util.Objects::nonNull).map(e->e.h()).toList());
        assertThat(Files.readAllBytes(dependencies.resolve("q/Mode.class"))).isEqualTo(enumBytes);
        assertThat(Files.readAllBytes(dependencies.resolve("q/Ann.class"))).isEqualTo(annotationBytes);
        assertThat(compile(output,dependencies,List.of(app))).isEmpty();
        assertThat(Files.readAllBytes(output.resolve("p/App.class"))).isEqualTo(client);
    }

    @ParameterizedTest @MethodSource("digests")
    void nativeEnumDeproxyCanObserveAPrivateNonEnumFieldMissingFromT(Digest digest) throws Exception {
        var dependencies=Files.createDirectories(directory.resolve("dependencies"));var output=Files.createDirectories(directory.resolve("output"));
        var mode=directory.resolve("Mode.java");var annotation=directory.resolve("Ann.java");var library=directory.resolve("Lib.java");var app=directory.resolve("App.java");
        Files.writeString(mode,"package q; public enum Mode { X,Y }");
        Files.writeString(annotation,"package q; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Ann { Mode value(); }");
        Files.writeString(library,"package q; @Ann(Mode.X) public class Lib { public static int call(){return 1;} }");
        Files.writeString(app,"package p; public class App { int value(){return q.Lib.call();} }");
        assertThat(compile(dependencies,dependencies,List.of(mode,annotation,library))).isEmpty();
        byte[] fixed=Files.readAllBytes(dependencies.resolve("q/Lib.class"));
        var facts=new java.util.ArrayList<ClassFacts>();var warnings=new java.util.ArrayList<List<String>>();
        for(var declaration:List.of("Y", "Y; private static final Mode X=Y;")) {
            Files.writeString(mode,"package q; public enum Mode { "+declaration+" }");
            assertThat(compile(dependencies,dependencies,List.of(mode))).isEmpty();
            facts.add(ClassFacts.of(digest,Files.readAllBytes(dependencies.resolve("q/Mode.class")),"q/Mode"));
            warnings.add(compile(output,dependencies,List.of(app)));
            assertThat(Files.readAllBytes(dependencies.resolve("q/Lib.class"))).isEqualTo(fixed);
        }
        assertThat(facts.get(0).facts().stream().map(Fact::h).toList()).isEqualTo(facts.get(1).facts().stream().map(Fact::h).toList());
        assertThat(facts.get(0).facts().stream().map(f->f.aEntry(digest)).filter(java.util.Objects::nonNull).map(e->e.h()).toList())
                .isEqualTo(facts.get(1).facts().stream().map(f->f.aEntry(digest)).filter(java.util.Objects::nonNull).map(e->e.h()).toList());
        assertThat(warnings.get(0)).containsExactly("compiler.warn.unknown.enum.constant");assertThat(warnings.get(1)).isEmpty();
    }

    @ParameterizedTest @MethodSource("digests")
    void privateAnnotationOrderChangesDiagnosticsAndWarningBudgetDespiteEqualFacts(Digest digest) throws Exception {
        var dependencies=Files.createDirectories(directory.resolve("dependencies"));
        var output=Files.createDirectories(directory.resolve("output"));
        var mode=directory.resolve("Mode.java");var ann=directory.resolve("Ann.java");var other=directory.resolve("Other.java");
        var library=directory.resolve("Lib.java");var app=directory.resolve("App.java");
        Files.writeString(mode,"package q; public enum Mode { X,Y,Z }");
        Files.writeString(ann,"package q; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Ann { Mode value(); }");
        Files.writeString(other,"package q; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Other { Mode value(); }");
        Files.writeString(app,"package p; public class App { int value(){return q.Lib.call();} }");
        var binaries=new java.util.ArrayList<byte[]>();
        for(var annotations:List.of("@Ann(Mode.X) @Other(Mode.Z)","@Other(Mode.Z) @Ann(Mode.X)")) {
            Files.writeString(library,"package q; public class Lib { "+annotations+" private int hidden; public static int call(){return 1;} }");
            assertThat(compile(dependencies,dependencies,List.of(mode,ann,other,library))).isEmpty();
            binaries.add(Files.readAllBytes(dependencies.resolve("q/Lib.class")));
        }
        var a=ClassFacts.of(digest,binaries.get(0),"q/Lib");var b=ClassFacts.of(digest,binaries.get(1),"q/Lib");
        assertThat(a.facts().stream().map(Fact::h).toList()).isEqualTo(b.facts().stream().map(Fact::h).toList());
        assertThat(a.facts().stream().map(f->f.aEntry(digest)).filter(java.util.Objects::nonNull).map(e->e.h()).toList())
                .isEqualTo(b.facts().stream().map(f->f.aEntry(digest)).filter(java.util.Objects::nonNull).map(e->e.h()).toList());
        Files.writeString(mode,"package q; public enum Mode { Y }");
        assertThat(compile(dependencies,dependencies,List.of(mode))).isEmpty();
        var ordered=new java.util.ArrayList<List<String>>();var limited=new java.util.ArrayList<List<String>>();
        byte[] client=null;
        for(var bytes:binaries) {
            Files.write(dependencies.resolve("q/Lib.class"),bytes);
            ordered.add(messages(output,dependencies,List.of(app),List.of()).stream()
                    .filter(d->d.getCode().equals("compiler.warn.unknown.enum.constant")).map(d->d.getMessage(Locale.ROOT)).toList());
            limited.add(messages(output,dependencies,List.of(app),List.of("-Xmaxwarns","1")).stream()
                    .filter(d->d.getCode().equals("compiler.warn.unknown.enum.constant")).map(d->d.getMessage(Locale.ROOT)).toList());
            byte[] current=Files.readAllBytes(output.resolve("p/App.class"));
            if(client!=null)assertThat(current).isEqualTo(client);client=current;
        }
        assertThat(ordered.get(0)).hasSize(2);assertThat(ordered.get(1)).containsExactlyElementsOf(ordered.get(0).reversed());
        assertThat(limited.get(0)).containsExactly(ordered.get(0).getFirst());
        assertThat(limited.get(1)).containsExactly(ordered.get(1).getFirst());
        assertThat(limited.get(0)).isNotEqualTo(limited.get(1));
    }

    @ParameterizedTest @MethodSource("digests")
    void resolutionStubDropsPrivateReaderEffectsEvenWithIdenticalT(Digest digest) throws Exception {
        var dependencies=Files.createDirectories(directory.resolve("dependencies"));var output=Files.createDirectories(directory.resolve("output"));
        var mode=directory.resolve("Mode.java");var ann=directory.resolve("Ann.java");var library=directory.resolve("Lib.java");var app=directory.resolve("App.java");
        Files.writeString(mode,"package q; public enum Mode { X,Y }");
        Files.writeString(ann,"package q; public @interface Ann { Mode value(); }");
        Files.writeString(library,"package q; public class Lib { @Ann(Mode.X) private int hidden; public static int call(){return 1;} }");
        Files.writeString(app,"package p; public class App { int value(){return q.Lib.call();} }");
        assertThat(compile(dependencies,dependencies,List.of(mode,ann,library))).isEmpty();
        var real=Files.readAllBytes(dependencies.resolve("q/Lib.class"));
        var facts=ClassFacts.of(digest,real,"q/Lib");
        byte[] headerView;
        try(var headers=HeaderCompiler.compile(List.of(new HeaderCompiler.Source("Lib.java",library)),List.of(dependencies),
                Path.of(System.getProperty("java.home")),Runtime.version().feature(),List.of(),digest)) {
            assertThat(headers.units.getFirst().faults).isEmpty();
            var taskField=HeaderCompiler.Compiled.class.getDeclaredField("task");taskField.setAccessible(true);
            var task=taskField.get(headers);
            var context=task.getClass().getMethod("getContext").invoke(task);
            var loader=ModuleLayer.boot().findLoader("jdk.compiler");
            var contextType=Class.forName("com.sun.tools.javac.util.Context",false,loader);
            var symbolType=Class.forName("com.sun.tools.javac.code.Symbol$ClassSymbol",false,loader);
            var writerType=Class.forName("com.sun.tools.javac.jvm.ClassWriter",false,loader);
            var writer=writerType.getMethod("instance",contextType).invoke(null,context);
            var bytes=new java.io.ByteArrayOutputStream();
            writerType.getMethod("writeClassFile",java.io.OutputStream.class,symbolType)
                    .invoke(writer,bytes,headers.units.getFirst().declared.getFirst());
            headerView=bytes.toByteArray();
        }
        assertThat(ClassFacts.of(digest,headerView,"q/Lib").reader().recipe())
                .usingRecursiveComparison().isEqualTo(facts.reader().recipe());
        Files.writeString(mode,"package q; public enum Mode { Y }");
        assertThat(compile(dependencies,dependencies,List.of(mode))).isEmpty();
        assertThat(compile(output,dependencies,List.of(app))).containsExactly("compiler.warn.unknown.enum.constant");
        var nativeBytes=Files.readAllBytes(output.resolve("p/App.class"));
        var f=new ProofIndexTest.Fixture(digest);
        var leaf=f.leaf(facts.facts().stream().map(Fact::entry).toList());
        var stubs=dev.jvmd.index.layer.machine.Stubs.stubs(digest,f.tree,leaf,
                id->f.store.get(dev.jvmd.index.layer.machine.MachineStore.nodeKey(id)),dev.jvmd.index.layer.machine.Stubs.Cache.NONE);
        assertThat(stubs).hasSize(1);var stub=stubs.getFirst().bytes();
        assertThat(ClassFacts.of(digest,stub,"q/Lib").facts().stream().map(Fact::h).toList())
                .isEqualTo(facts.facts().stream().map(Fact::h).toList());
        Files.write(dependencies.resolve("q/Lib.class"),stub);
        assertThat(compile(output,dependencies,List.of(app))).isEmpty();
        assertThat(Files.readAllBytes(output.resolve("p/App.class"))).isEqualTo(nativeBytes);
        // Restoring the actual compiler view restores the effect; no cache or source edit is involved.
        Files.write(dependencies.resolve("q/Lib.class"),real);
        assertThat(compile(output,dependencies,List.of(app))).containsExactly("compiler.warn.unknown.enum.constant");
        // A separately keyed compiler view can preserve this effect using javac's own header model.
        // This probe deliberately does not change ST or install a production view.
        Files.write(dependencies.resolve("q/Lib.class"),headerView);
        assertThat(compile(output,dependencies,List.of(app))).containsExactly("compiler.warn.unknown.enum.constant");
        assertThat(Files.readAllBytes(output.resolve("p/App.class"))).isEqualTo(nativeBytes);
    }

    private List<String> compile(Path output,Path dependencies,List<Path> sources) throws Exception {
        return messages(output,dependencies,sources,List.of()).stream().map(d->d.getCode()).toList();
    }
    private List<javax.tools.Diagnostic<? extends JavaFileObject>> messages(Path output,Path dependencies,List<Path> sources,List<String> extra) throws Exception {
        var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var files=compiler.getStandardFileManager(diagnostics,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8)) {
            var options=new java.util.ArrayList<>(List.of("-proc:none","-implicit:none","-classpath",dependencies.toString(),"-d",output.toString()));options.addAll(extra);
            var task=compiler.getTask(null,files,diagnostics,options,
                    null,files.getJavaFileObjectsFromPaths(sources));
            assertThat(task.call()).as(diagnostics.getDiagnostics().toString()).isTrue();
        }
        return diagnostics.getDiagnostics();
    }
}
