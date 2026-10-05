package dev.jvmd.boot.cold.stage2;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.main.JavaCompiler;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.index.layer.local.FileRow;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Header compilation (stage 2, 3.1 and appendix C.1): javac's parse, {@code enter} and member completion over a classpath of jars
 * and stubs, and nothing after it. {@code JavacTask.analyze} and {@code generate} are never called, so no method body is
 * attributed. Every source file of a module and scope goes through one task, because declarations resolve against each other.
 *
 * <p>A file that does not parse is left out of {@code enter}: it declares nothing, and what refers to it faults at the right grain.
 * A type that an earlier file of the scope already declared is a declaration fault on the later one (C.5).
 */
final class HeaderCompiler {
    private HeaderCompiler() { }

    /** One source file of the scope: its path relative to the project root, and its bytes. */
    record Source(String path, byte[] bytes) { }

    /** What javac made of one file. {@code declared} are the top-level types it owns; {@code faults} are the file-grain and duplicate faults. */
    static final class Unit {
        final Source source;
        String parseError;
        final List<TypeElement> declared = new ArrayList<>();
        final List<FileRow.Fault> faults = new ArrayList<>();

        Unit(Source source) { this.source = source; }

        boolean parsed() { return parseError == null; }
    }

    /** The result of header compilation. {@link #elements} and {@link #types} stay valid until {@link #close()}. */
    static final class Compiled implements AutoCloseable {
        final List<Unit> units;
        final Elements elements;
        final Types types;
        private final JavacTaskImpl task;
        private final StandardJavaFileManager files;

        Compiled(List<Unit> units, Elements elements, Types types, JavacTaskImpl task, StandardJavaFileManager files) {
            this.units = units;
            this.elements = elements;
            this.types = types;
            this.task = task;
            this.files = files;
        }

        /** Closing the file manager alone leaves every classpath jar open (javac's compiler holds them); on Windows that locks the jar. */
        @Override public void close() { release(task, files); }
    }

    private static void release(JavacTaskImpl task, StandardJavaFileManager files) {
        if (files == null) return;
        try { if (task != null) JavaCompiler.instance(task.getContext()).close(); } finally {
            try { files.close(); } catch (IOException ignored) { /* nothing to recover */ }
        }
    }

    private static final class SourceObject extends SimpleJavaFileObject {
        final String text;

        SourceObject(URI uri, String text) {
            super(uri, Kind.SOURCE);
            this.text = text;
        }

        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return text; }
    }

    /**
     * @param classpath jars and stub directories, in classpath order
     * @param release   the language level; with {@code --enable-preview} the caller has already made it the running feature version
     */
    static Compiled compile(List<Source> sources, List<Path> classpath, Path jdkHome, int release, List<String> javacOptions) {
        // A scope with no source files has an empty leaf (appendix A); javac would call it an error.
        if (sources.isEmpty()) return new Compiled(List.of(), null, null, null, null);
        var options = new ArrayList<String>();
        options.addAll(List.of("-source", String.valueOf(release), "-Xlint:-options", "-proc:none", "-implicit:none"));
        options.add("--class-path");
        // An empty classpath would be the current directory; a path that cannot exist is the empty classpath.
        options.add(classpath.isEmpty() ? Path.of(System.getProperty("java.io.tmpdir"), "jvmd-no-classpath").toString()
                : String.join(File.pathSeparator, classpath.stream().map(Path::toString).toList()));
        options.addAll(List.of("--system", jdkHome.toString()));
        options.addAll(filtered(javacOptions));

        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var charset = charset(javacOptions);
        var files = compiler.getStandardFileManager(diagnostics, null, charset);
        var units = new ArrayList<Unit>(sources.size());
        var objects = new ArrayList<SourceObject>(sources.size());
        // javac hands its own wrappers back, so a file is recognised by its URI, never by the object it was given.
        var byUri = new java.util.HashMap<URI, Unit>();
        for (var source : sources) {
            var unit = new Unit(source);
            units.add(unit);
            var object = new SourceObject(uri(source.path()), new String(source.bytes(), charset));
            objects.add(object);
            byUri.put(object.toUri(), unit);
        }
        JavacTaskImpl task = null;
        try {
            task = (JavacTaskImpl) compiler.getTask(null, files, diagnostics, options, null, objects);
            var parsed = new ArrayList<CompilationUnitTree>();
            for (var tree : task.parse()) parsed.add(tree);
            for (var d : diagnostics.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.ERROR) continue;
                var unit = d.getSource() == null ? null : byUri.get(d.getSource().toUri());
                if (unit != null) {
                    var message = d.getLineNumber() + ":" + d.getColumnNumber() + " " + d.getMessage(null);
                    unit.parseError = unit.parseError == null ? message : unit.parseError + "; " + message;
                } else throw new IllegalStateException("javac refused the module's options: " + d.getMessage(null));
            }
            var entered = new ArrayList<CompilationUnitTree>();
            for (var tree : parsed) if (byUri.get(tree.getSourceFile().toUri()).parsed()) entered.add(tree);
            task.enter(entered);

            var trees = Trees.instance(task);
            for (var tree : entered) {
                var unit = byUri.get(tree.getSourceFile().toUri());
                for (var decl : tree.getTypeDecls()) {
                    if (!(decl instanceof ClassTree klass)) continue;
                    Element element = trees.getElement(TreePath.getPath(tree, klass));
                    boolean owned = element instanceof TypeElement && element.asType().getKind() != TypeKind.ERROR;
                    if (owned) {
                        var path = trees.getPath(element);
                        owned = path != null && path.getCompilationUnit() == tree;
                    }
                    if (owned) { unit.declared.add((TypeElement) element); complete((TypeElement) element); }
                    else unit.faults.add(new FileRow.Fault(typeKey(tree, klass), "duplicate class " + klass.getSimpleName()));
                }
            }
            return new Compiled(units, task.getElements(), task.getTypes(), task, files);
        } catch (RuntimeException e) {
            release(task, files);
            throw e;
        }
    }

    /** C.1: forces completion of a type's header and members, so that everything javac resolves is resolved before facts are read. */
    private static void complete(TypeElement type) {
        type.getSuperclass();
        type.getInterfaces();
        type.getTypeParameters();
        type.getPermittedSubclasses();
        type.getRecordComponents();
        for (var member : type.getEnclosedElements()) {
            if (member instanceof TypeElement nested) complete(nested);
            else if (member instanceof javax.lang.model.element.ExecutableElement method) { method.getReturnType(); method.getParameters(); method.getThrownTypes(); }
            else member.asType();
        }
    }

    private static byte[] typeKey(CompilationUnitTree unit, ClassTree klass) {
        String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString().replace('.', '/') + "/";
        return new Codec.Writer(64).zstr(pkg + klass.getSimpleName()).u8(0).toBytes();
    }

    private static URI uri(String path) {
        try { return new URI("mem", "", "/" + path, null); } catch (URISyntaxException e) { throw new IllegalArgumentException(e); }
    }

    private static Charset charset(List<String> options) {
        int at = options.indexOf("-encoding");
        return at >= 0 && at + 1 < options.size() ? Charset.forName(options.get(at + 1)) : StandardCharsets.UTF_8;
    }

    /** Options that take an argument, and are dropped with it (C.1). */
    private static final Set<String> DROPPED_WITH_ARGUMENT = Set.of("-d", "-s", "-h", "-processor", "-processorpath", "--processor-path", "--release", "-source", "--source",
            "-target", "--target", "-classpath", "-cp", "--class-path", "-sourcepath", "--source-path", "--module-path", "-p", "--system");

    /** The module's own options minus everything this task decides itself: output, processing, classpath, release and system. */
    static List<String> filtered(List<String> options) {
        var out = new ArrayList<String>();
        for (int i = 0; i < options.size(); i++) {
            String option = options.get(i);
            if (DROPPED_WITH_ARGUMENT.contains(option)) { i++; continue; }
            if (option.startsWith("-proc:") || option.startsWith("-implicit:") || option.startsWith("-Xplugin")) continue;
            int eq = option.indexOf('=');
            if (option.startsWith("--") && eq > 0 && DROPPED_WITH_ARGUMENT.contains(option.substring(0, eq))) continue;
            out.add(option);
        }
        return out;
    }
}
