package dev.jvmd.boot.cold.stage2;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.main.JavaCompiler;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.local.FileRow;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /** One source file of the scope: its path relative to the project root, and where to read it. */
    record Source(String path, Path file) { }

    /** What javac made of one file. {@code declared} are the top-level types it owns; {@code faults} are the file-grain and duplicate faults. */
    static final class Unit {
        /** The file's path, relative to the project root. */
        final String path;
        /**
         * {@code κ_file} and the size of the bytes javac was given, taken when it read the file: the file is read once, here, hashed, decoded
         * and handed over, and nothing of its text is kept by this code. Null if javac could not read the file.
         */
        Identity kappa;
        long size;
        String parseError;
        final List<TypeElement> declared = new ArrayList<>();
        final List<FileRow.Fault> faults = new ArrayList<>();
        /** The module declaration of a {@code module-info.java}: parsed, never entered (E.3). Null for every other file. */
        com.sun.source.tree.ModuleTree module;

        Unit(String path) { this.path = path; }

        boolean parsed() { return parseError == null; }
    }

    /** The result of header compilation. {@link #elements} and {@link #types} stay valid until {@link #close()}. */
    static final class Compiled implements AutoCloseable {
        final List<Unit> units;
        final Elements elements;
        final Types types;
        final Trees trees;
        private final JavacTaskImpl task;
        private final StandardJavaFileManager files;

        Compiled(List<Unit> units, Elements elements, Types types, Trees trees, JavacTaskImpl task, StandardJavaFileManager files) {
            this.units = units;
            this.elements = elements;
            this.types = types;
            this.trees = trees;
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

    /** A source file read when javac asks for it. The first read fixes the unit's {@code κ}; the text is returned and not kept. */
    private static final class SourceObject extends SimpleJavaFileObject {
        private final Unit unit;
        private final Path file;
        private final Charset charset;
        private final Digest digest;

        SourceObject(URI uri, Unit unit, Path file, Charset charset, Digest digest) {
            super(uri, Kind.SOURCE);
            this.unit = unit;
            this.file = file;
            this.charset = charset;
            this.digest = digest;
        }

        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            var bytes = Files.readAllBytes(file);
            if (unit.kappa == null) { unit.kappa = digest.hash(bytes); unit.size = bytes.length; }
            return new String(bytes, charset);
        }
    }

    /**
     * The lowest source level the running javac accepts: JDK 25 answers {@code -source 7} and below with "no longer supported. Use 8 or
     * later", while 8 works with {@code --system}. A module whose release is lower is compiled at this level, which accepts everything
     * the older levels did.
     */
    static final int MINIMUM_SOURCE = 8;

    /**
     * The release javac runs at and the descriptor records (C.1, E.4): the running feature version for a module that compiles with
     * preview features (preview requires it) or states no release, otherwise the model's release clamped to what the running javac
     * accepts, so that a release it cannot take is a substitution and not a refusal of the boot.
     */
    static int effectiveRelease(int release, boolean preview, int feature) {
        if (preview || release <= 0) return feature;
        return Math.max(MINIMUM_SOURCE, Math.min(release, feature));
    }

    /**
     * @param classpath jars and stub directories, in classpath order
     * @param release   the language level; with {@code --enable-preview} the caller has already made it the running feature version
     */
    static Compiled compile(List<Source> sources, List<Path> classpath, Path jdkHome, int release, List<String> javacOptions, Digest digest) {
        return compile(sources, classpath, jdkHome, release, javacOptions, digest, null);
    }

    static Compiled compile(List<Source> sources, List<Path> classpath, Path jdkHome, int release, List<String> javacOptions, Digest digest, ProcessorHost host) {
        // A scope with no source files has an empty leaf (appendix A); javac would call it an error.
        if (sources.isEmpty()) return new Compiled(List.of(), null, null, null, null, null);
        var options = new ArrayList<String>();
        options.addAll(List.of("-source", String.valueOf(release), "-Xlint:-options", host == null ? "-proc:none" : "-proc:full", "-implicit:none"));
        options.add("--class-path");
        // An empty classpath would be the current directory; a path that cannot exist is the empty classpath.
        options.add(classpath.isEmpty() ? Path.of(System.getProperty("java.io.tmpdir"), "jvmd-no-classpath").toString()
                : String.join(File.pathSeparator, classpath.stream().map(Path::toString).toList()));
        options.addAll(List.of("--system", jdkHome.toString()));
        options.addAll(filtered(javacOptions));
        options.addAll(List.of("-encoding", charset(javacOptions).name()));
        if (host != null) options.addAll(List.of("-s", host.generatedDirectory().toString(), "-d", host.generatedDirectory().toString()));

        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var processorOwners = host == null ? null : new java.util.IdentityHashMap<com.sun.tools.javac.util.JCDiagnostic, String>();
        var processorMessages = host == null ? null : new ArrayList<dev.jvmd.index.layer.local.ProcessorRecords.Message>();
        javax.tools.DiagnosticListener<JavaFileObject> listener = diagnostic -> {
            diagnostics.report(diagnostic);
            if (host == null) return;
            String processor = processorOwners.get(rawDiagnostic(diagnostic));
            if (processor == null && !diagnostic.getCode().contains(".proc.")) return;
            // Snapshot when reported: a JCDiagnostic retains mutable javac trees whose end positions can change in later phases.
            processorMessages.add(new dev.jvmd.index.layer.local.ProcessorRecords.Message(processor == null ? "" : processor,
                    diagnostic.getSource() == null ? null : host.sourcePath(diagnostic.getSource().toUri()), diagnostic.getKind(),
                    diagnostic.getPosition(), diagnostic.getStartPosition(), diagnostic.getEndPosition(), diagnostic.getLineNumber(),
                    diagnostic.getColumnNumber(), diagnostic.getCode(), diagnostic.getMessage(Locale.ROOT)));
        };
        var charset = charset(javacOptions);
        var files = compiler.getStandardFileManager(listener, Locale.ROOT, charset);
        var units = new ArrayList<Unit>(sources.size());
        var objects = new ArrayList<SourceObject>(sources.size());
        // javac hands its own wrappers back, so a file is recognised by its URI, never by the object it was given.
        var byUri = new java.util.HashMap<URI, Unit>();
        for (var source : sources) {
            var unit = new Unit(source.path());
            units.add(unit);
            var object = new SourceObject(source.file().toAbsolutePath().normalize().toUri(), unit, source.file(), charset, digest);
            objects.add(object);
            byUri.put(object.toUri(), unit);
        }
        JavacTaskImpl task = null;
        try {
            task = (JavacTaskImpl) compiler.getTask(null, files, listener, options, null, objects);
            task.setLocale(Locale.ROOT);
            if (host != null) {
                new ProcessorTrees(task.getContext(), host);
                var log = com.sun.tools.javac.util.Log.instance(task.getContext());
                host.diagnosticScope(new ProcessorHost.DiagnosticScope() {
                    @Override public <T> T call(String processor, java.util.function.Supplier<T> action) {
                        // Associate at emission time, before javac defers the message. Forward to the existing handler so
                        // duplicate handling, warning suppression and error/warning limits retain native javac semantics.
                        var handler = log.new DiagnosticHandler() {
                            @Override public void report(com.sun.tools.javac.util.JCDiagnostic diagnostic) {
                                processorOwners.put(diagnostic, processor);
                                prev.report(diagnostic);
                            }
                        };
                        try { return action.get(); }
                        finally { log.popDiagnosticHandler(handler); }
                    }
                });
                task.setProcessors(host.processors());
            }
            var generatedTrees = new ArrayList<CompilationUnitTree>();
            task.addTaskListener(new com.sun.source.util.TaskListener() {
                @Override public void finished(com.sun.source.util.TaskEvent event) {
                    if (event.getKind() == com.sun.source.util.TaskEvent.Kind.PARSE && event.getCompilationUnit() != null
                            && !byUri.containsKey(event.getCompilationUnit().getSourceFile().toUri())) generatedTrees.add(event.getCompilationUnit());
                }
            });
            var parsed = new ArrayList<CompilationUnitTree>();
            for (var tree : task.parse()) parsed.add(tree);
            for (var d : diagnostics.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.ERROR) continue;
                var unit = d.getSource() == null ? null : byUri.get(d.getSource().toUri());
                if (unit != null) {
                    var message = message(d);
                    unit.parseError = unit.parseError == null ? message : unit.parseError + "; " + message;
                } else throw new IllegalStateException("javac refused the module's options: " + d.getMessage(Locale.ROOT));
            }
            var entered = new ArrayList<CompilationUnitTree>();
            var consumed = new ArrayList<CompilationUnitTree>();
            for (var tree : parsed) {
                var unit = byUri.get(tree.getSourceFile().toUri());
                // Classpath mode has no use for a module declaration: its descriptor fact is read from the parsed directives.
                if (unit.parsed() && tree.getModule() != null) unit.module = tree.getModule();
                if (unit.parsed() && tree.getModule() == null) {
                    entered.add(tree);
                    consumed.add(tree);
                } else {
                    // Consume javac's pending-file slot without entering the excluded declaration. Otherwise processor
                    // Element lookups call ensureEntered(), which tries to initialize the module graph a second time.
                    var empty = com.sun.tools.javac.tree.TreeMaker.instance(task.getContext()).TopLevel(com.sun.tools.javac.util.List.nil());
                    empty.sourcefile = ((com.sun.tools.javac.tree.JCTree.JCCompilationUnit) tree).sourcefile;
                    consumed.add(empty);
                }
            }
            task.enter(consumed);

            // Processor rounds defer their diagnostics until after PARSE has finished. Classify them using javac's syntax
            // flag, not translated message text; a resolution error remains a declaration fault in SourceFacts.
            var generatedParseErrors = new java.util.HashMap<URI, String>();
            for (var diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() != Diagnostic.Kind.ERROR || diagnostic.getSource() == null) continue;
                var raw = rawDiagnostic(diagnostic);
                if (raw != null && raw.isFlagSet(com.sun.tools.javac.util.JCDiagnostic.DiagnosticFlag.SYNTAX))
                    generatedParseErrors.merge(diagnostic.getSource().toUri(), message(diagnostic), (a, b) -> a + "; " + b);
            }

            for (var tree : generatedTrees) {
                var uri = tree.getSourceFile().toUri();
                var unit = new Unit(host.sourcePath(uri));
                var bytes = Files.readAllBytes(Path.of(uri));
                unit.kappa = digest.hash(bytes);
                unit.size = bytes.length;
                unit.parseError = generatedParseErrors.get(uri);
                byUri.put(uri, unit);
                units.add(unit);
                entered.add(tree);
            }

            if (host != null) for (var output : host.outputs()) {
                if (output.kind() != JavaFileObject.Kind.SOURCE || byUri.containsKey(output.uri())) continue;
                var unit = new Unit(host.sourcePath(output.uri()));
                unit.kappa = digest.hash(output.bytes());
                unit.size = output.bytes().length;
                unit.parseError = "annotation processing did not enter this generated source";
                units.add(unit);
                byUri.put(output.uri(), unit);
            }

            var trees = Trees.instance(task);
            for (var tree : entered) {
                var unit = byUri.get(tree.getSourceFile().toUri());
                if (!unit.parsed()) continue;
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
            if (host != null) host.diagnostics(new dev.jvmd.index.layer.local.ProcessorRecords.Diagnostics(processorMessages));
            return new Compiled(units, task.getElements(), task.getTypes(), trees, task, files);
        } catch (IOException e) {
            release(task, files);
            throw new java.io.UncheckedIOException(e);
        } catch (RuntimeException | Error e) {
            release(task, files);
            throw e;
        }
    }

    private static String message(Diagnostic<? extends JavaFileObject> diagnostic) {
        return diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber() + " " + diagnostic.getMessage(Locale.ROOT);
    }

    private static com.sun.tools.javac.util.JCDiagnostic rawDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        return diagnostic instanceof com.sun.tools.javac.api.ClientCodeWrapper.DiagnosticSourceUnwrapper wrapped ? wrapped.d
                : diagnostic instanceof com.sun.tools.javac.util.JCDiagnostic raw ? raw : null;
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
        return Keys.typeKey(pkg + klass.getSimpleName());
    }

    static Charset charset(List<String> options) {
        var charset = StandardCharsets.UTF_8;
        for (int i = 0; i + 1 < options.size(); i++) if (options.get(i).equals("-encoding")) charset = Charset.forName(options.get(++i));
        return charset;
    }

    static Identity optionsHash(Digest digest, List<String> options, int release, Identity processorPathHash, List<String> processors) {
        var filtered = filtered(options);
        var out = new dev.jvmd.core.tree.Codec.Writer().u32(filtered.size());
        for (var option : filtered) out.str(option);
        out.u8(release).str(charset(options).name()).optId(processorPathHash).u32(processors.size());
        for (var processor : processors) out.str(processor);
        return digest.hash(out.toBytes());
    }

    /** Options that take an argument, and are dropped with it (C.1). */
    private static final Set<String> DROPPED_WITH_ARGUMENT = Set.of("-d", "-s", "-h", "-processor", "-processorpath", "--processor-path", "--release", "-source", "--source",
            "-target", "--target", "--module-version", "-classpath", "-cp", "--class-path", "-sourcepath", "--source-path", "--module-path", "-p", "--system");

    /** The module's own options minus everything this task decides itself: output, processing, classpath, release and system. */
    static List<String> filtered(List<String> options) {
        var out = new ArrayList<String>();
        for (int i = 0; i < options.size(); i++) {
            String option = options.get(i);
            if (DROPPED_WITH_ARGUMENT.contains(option)) { i++; continue; }
            if (option.startsWith("-proc:") || option.startsWith("-implicit:") || option.startsWith("-Xplugin")) continue;
            int eq = option.indexOf('=');
            if (option.startsWith("--") && eq > 0 && DROPPED_WITH_ARGUMENT.contains(option.substring(0, eq))) continue;
            // Header compilation is classpath mode: exports to the original named module must target this task's unnamed module.
            if (option.equals("--add-exports") && i + 1 < options.size()) {
                out.add(option);
                out.add(unnamedExport(options.get(++i)));
                continue;
            }
            if (option.startsWith("--add-exports=")) option = "--add-exports=" + unnamedExport(option.substring("--add-exports=".length()));
            out.add(option);
        }
        return out;
    }

    private static String unnamedExport(String value) {
        int target = value.lastIndexOf('=');
        return target < 0 ? value : value.substring(0, target + 1) + "ALL-UNNAMED";
    }
}
