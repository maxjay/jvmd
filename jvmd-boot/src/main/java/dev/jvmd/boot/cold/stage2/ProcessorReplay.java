package dev.jvmd.boot.cold.stage2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

/** Proves an isolating derivation by executing fresh processor state against captured answers for one origin. */
final class ProcessorReplay {
    private ProcessorReplay() { }

    record Environment(Map<String, String> options, SourceVersion sourceVersion, boolean preview) {
        static Environment of(ProcessingEnvironment environment) {
            return new Environment(Map.copyOf(environment.getOptions()), environment.getSourceVersion(), environment.isPreviewEnabled());
        }
    }

    /** Invocation-local input snapshot. The model-query table provides all later Element/Types answers. */
    static final class Round {
        final int number;
        final boolean over, error;
        final Set<? extends TypeElement> annotations;
        final Set<? extends Element> roots;
        final Map<String, Set<? extends Element>> annotated = new LinkedHashMap<>();
        private final Map<TypeElement, String> annotationNames = new IdentityHashMap<>();
        private final Function<Element, URI> captureOrigin;
        Round(int number, Set<? extends TypeElement> annotations, RoundEnvironment round, Function<Element, URI> captureOrigin) {
            this.number = number;
            this.captureOrigin = captureOrigin;
            this.over = round.processingOver(); this.error = round.errorRaised();
            this.annotations = ordered(annotations); this.roots = ordered(round.getRootElements());
            roots.forEach(captureOrigin::apply);
            for (var annotation : annotations) query(annotation, round.getElementsAnnotatedWith(annotation));
        }
        void query(TypeElement annotation, Set<? extends Element> result) {
            String name = annotation.getQualifiedName().toString();
            annotationNames.put(annotation, name);
            query(name, result);
        }
        void query(String name, Set<? extends Element> result) {
            result.forEach(captureOrigin::apply);
            annotated.put(name, ordered(result));
        }
        String name(TypeElement annotation) {
            var name = annotationNames.get(annotation);
            if (name == null) throw new ProcessorReads.ReplayUnavailable("unobserved round annotation handle");
            return name;
        }
    }

    /** Source provenance is captured during the live round, never recovered with Trees during replay. */
    static final class Origins {
        private final Map<Element, URI> paths = new IdentityHashMap<>();
        URI capture(Element element, URI path) { paths.put(element, path); return path; }
        URI get(Element element) {
            element = ProcessorReads.nativeObject(element);
            if (!paths.containsKey(element)) throw new ProcessorReads.ReplayUnavailable("unobserved source provenance");
            return paths.get(element);
        }
    }

    record Result(byte[] proof, List<ProcessorReads.Read> reads) { }

    static Result run(URL[] path, String processorClass, Environment environment, List<String> setup, List<Round> rounds,
                      ProcessorReads captured, URI origin, Function<Element, URI> originOf, Charset charset,
                      List<ProcessorHost.Output> expected) throws ReflectiveOperationException, IOException {
        var observations = new ArrayList<ProcessorReads.Read>();
        var reads = captured.replay(observations::add, reason -> { throw new ProcessorReads.ReplayUnavailable(reason); });
        var filer = new MemoryFiler(origin, originOf, charset);
        try (var loader = new URLClassLoader(path, ClassLoader.getPlatformClassLoader())) {
            var processor = (Processor) Class.forName(processorClass, true, loader).getConstructor().newInstance();
            Set<String> supported = Set.of();
            var replayEnvironment = new ReplayEnvironment(environment, reads, filer);
            for (var call : setup) switch (call) {
                case "init" -> processor.init(replayEnvironment);
                case "options" -> processor.getSupportedOptions();
                case "annotations" -> supported = processor.getSupportedAnnotationTypes();
                case "version" -> processor.getSupportedSourceVersion();
                default -> throw new IllegalStateException("Unknown processor setup call: " + call);
            }
            boolean invoked = false;
            for (var round : rounds) {
                reads.phase(round.number);
                var annotations = new LinkedHashSet<TypeElement>();
                for (var annotation : round.annotations)
                    if (!filter(round.annotated.get(round.name(annotation)), origin, originOf).isEmpty()) annotations.add(annotation);
                if (!invoked && annotations.isEmpty() && !supported.contains("*")) continue;
                invoked = true;
                @SuppressWarnings("unchecked") var wrapped = (Set<? extends TypeElement>) reads.answer("round." + round.number + ".annotations", null, annotations);
                processor.process(wrapped, new ReplayRound(round, reads, origin, originOf));
            }
        }
        var actual = filer.bytes();
        var wanted = new TreeMap<String, byte[]>();
        for (var output : expected) {
            if (output.kind() != JavaFileObject.Kind.SOURCE) throw new ProcessorReads.ReplayUnavailable("non-source generated output");
            wanted.put(output.name(), output.bytes());
        }
        if (!actual.keySet().equals(wanted.keySet())) throw new ProcessorReads.ReplayUnavailable("isolated generated output paths differ from the native batch");
        for (var name : wanted.keySet()) if (!java.util.Arrays.equals(actual.get(name), wanted.get(name)))
            throw new ProcessorReads.ReplayUnavailable("isolated generated bytes differ from the native batch: " + name);
        return new Result(reads.proof(), List.copyOf(observations));
    }

    private static <T> Set<T> ordered(Set<? extends T> values) { return Collections.unmodifiableSet(new LinkedHashSet<>(values)); }
    private static Set<? extends Element> filter(Set<? extends Element> elements, URI origin, Function<Element, URI> originOf) {
        var selected = new LinkedHashSet<Element>();
        for (var element : elements) if (origin.equals(originOf.apply(element))) selected.add(element);
        return Collections.unmodifiableSet(selected);
    }

    private record ReplayRound(Round round, ProcessorReads reads, URI origin, Function<Element, URI> originOf) implements RoundEnvironment {
        private Object answer(String method, Object[] args, Object value) {
            return reads.answer("round." + round.number + "." + method, args, value);
        }
        @Override public boolean processingOver() { return (Boolean) answer("processingOver", null, round.over); }
        @Override public boolean errorRaised() { return (Boolean) answer("errorRaised", null, round.error); }
        @Override @SuppressWarnings("unchecked") public Set<? extends Element> getRootElements() {
            return (Set<? extends Element>) answer("getRootElements", null, filter(round.roots, origin, originOf));
        }
        @Override public Set<? extends Element> getElementsAnnotatedWith(TypeElement annotation) {
            return annotated(round.name((TypeElement) reads.unwrap(annotation)), annotation);
        }
        @Override public Set<? extends Element> getElementsAnnotatedWith(Class<? extends java.lang.annotation.Annotation> annotation) {
            return annotated(annotation.getCanonicalName(), annotation);
        }
        @SuppressWarnings("unchecked") private Set<? extends Element> annotated(String name, Object annotation) {
            var found = round.annotated.get(name);
            if (found == null) throw new ProcessorReads.ReplayUnavailable("unobserved round annotation query: " + name);
            return (Set<? extends Element>) answer("getElementsAnnotatedWith", new Object[] {annotation}, filter(found, origin, originOf));
        }
    }

    /** Diagnostics remain the native invocation's fresh PDIAG output; a replay never reports them twice. */
    private record ReplayEnvironment(Environment snapshot, ProcessorReads reads, Filer filer) implements ProcessingEnvironment {
        @Override public Map<String, String> getOptions() { return snapshot.options(); }
        @Override public Messager getMessager() { return MESSAGES; }
        @Override public Filer getFiler() { return filer; }
        @Override public Elements getElementUtils() { return reads.elements; }
        @Override public Types getTypeUtils() { return reads.types; }
        @Override public SourceVersion getSourceVersion() { return snapshot.sourceVersion(); }
        @Override public Locale getLocale() { return Locale.ROOT; }
        @Override public boolean isPreviewEnabled() { return snapshot.preview(); }
    }
    private static final Messager MESSAGES = new Messager() {
        @Override public void printMessage(Diagnostic.Kind kind, CharSequence text) { }
        @Override public void printMessage(Diagnostic.Kind kind, CharSequence text, Element element) { }
        @Override public void printMessage(Diagnostic.Kind kind, CharSequence text, Element element, AnnotationMirror annotation) { }
        @Override public void printMessage(Diagnostic.Kind kind, CharSequence text, Element element, AnnotationMirror annotation, AnnotationValue value) { }
    };

    private static final class MemoryFiler implements Filer {
        final URI origin;
        final Function<Element, URI> originOf;
        final Charset charset;
        final Map<String, MemoryFile> files = new TreeMap<>();
        MemoryFiler(URI origin, Function<Element, URI> originOf, Charset charset) {
            this.origin = origin; this.originOf = originOf; this.charset = charset;
        }
        @Override public JavaFileObject createSourceFile(CharSequence name, Element... origins) throws IOException {
            if (origins.length != 1 || !origin.equals(originOf.apply(ProcessorReads.nativeObject(origins[0]))))
                throw new ProcessorReads.ReplayUnavailable("isolated output does not have the selected originating file");
            String path = name.toString().replace('.', '/') + ".java";
            if (files.containsKey(path)) throw new FilerException("Source already created: " + name);
            var file = new MemoryFile(path, charset);
            files.put(path, file);
            return file;
        }
        Map<String, byte[]> bytes() {
            var out = new TreeMap<String, byte[]>();
            files.forEach((name, file) -> { if (file.closed) out.put(name, file.bytes.toByteArray()); });
            return out;
        }
        @Override public JavaFileObject createClassFile(CharSequence name, Element... origins) { throw unsupported("class output"); }
        @Override public FileObject createResource(JavaFileManager.Location location, CharSequence moduleAndPackage, CharSequence name, Element... origins) { throw unsupported("resource output"); }
        @Override public FileObject getResource(JavaFileManager.Location location, CharSequence moduleAndPackage, CharSequence name) { throw unsupported("resource read"); }
        private static ProcessorReads.ReplayUnavailable unsupported(String operation) { return new ProcessorReads.ReplayUnavailable("isolated " + operation + " requires its own proof"); }
    }

    private static final class MemoryFile extends SimpleJavaFileObject {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final Charset charset;
        boolean opened, closed;
        MemoryFile(String path, Charset charset) { super(URI.create("memory:///" + path), Kind.SOURCE); this.charset = charset; }
        @Override public OutputStream openOutputStream() throws IOException {
            if (opened) throw new IOException("Source already opened: " + getName());
            opened = true;
            return new java.io.FilterOutputStream(bytes) {
                @Override public void close() throws IOException { super.close(); closed = true; }
            };
        }
        @Override public Writer openWriter() throws IOException { return new OutputStreamWriter(openOutputStream(), charset); }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return bytes.toString(charset); }
    }
}
