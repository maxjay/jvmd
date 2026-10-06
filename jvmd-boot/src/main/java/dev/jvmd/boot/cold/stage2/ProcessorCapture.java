package dev.jvmd.boot.cold.stage2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.annotation.processing.FilerException;
import javax.lang.model.SourceVersion;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;

/** A task-local Filer namespace. Closing an output captures bytes; it never registers a new javac input. */
final class ProcessorCapture {
    private final Path directory;
    private final Charset charset;
    private final Set<String> types = new HashSet<>();
    private final Set<URI> files = new HashSet<>();
    private final Map<URI, String> unclosed = new LinkedHashMap<>();

    ProcessorCapture(Path directory, Charset charset) { this.directory = directory; this.charset = charset; }

    JavaFileObject javaFile(String processor, String name, JavaFileObject.Kind kind,
                            BiConsumer<JavaFileObject, byte[]> output) throws IOException {
        boolean packageInfo = name.equals("package-info") && kind == JavaFileObject.Kind.SOURCE
                || name.endsWith(".package-info") && SourceVersion.isName(name.substring(0, name.length() - 13));
        if (!SourceVersion.isName(name) && !packageInfo) throw new FilerException("Illegal name " + name);
        if (types.contains(name)) throw new FilerException("Attempt to recreate a file for type " + name);
        var location = kind == JavaFileObject.Kind.SOURCE ? StandardLocation.SOURCE_OUTPUT : StandardLocation.CLASS_OUTPUT;
        var uri = uri(location, name.replace('.', '/') + kind.extension);
        var file = create(processor, uri, kind, output);
        types.add(name);
        return file;
    }

    JavaFileObject resource(String processor, JavaFileManager.Location location, String pkg, String name,
                            BiConsumer<JavaFileObject, byte[]> output) throws IOException {
        if (!location.isOutputLocation()) throw new IllegalArgumentException("Resource creation not supported in location " + location);
        return create(processor, resourceUri(location, pkg, name), JavaFileObject.Kind.OTHER, output);
    }

    void checkRead(JavaFileManager.Location location, String pkg, String name) throws IOException {
        var uri = resourceUri(location, pkg, name);
        if (files.contains(uri)) throw new FilerException("Attempt to reopen a file for path " + uri);
    }

    private URI resourceUri(JavaFileManager.Location location, String pkg, String name) throws IOException {
        if (!pkg.isEmpty() && !SourceVersion.isName(pkg)) throw new FilerException("Illegal name " + pkg);
        // Filer relative names are slash-separated, rootless paths, with no dot or empty segments.
        if (name.isEmpty() || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || java.util.Arrays.stream(name.split("/", -1)).anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")))
            throw new IllegalArgumentException("Invalid relative name: " + name);
        return uri(location, (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/") + name);
    }

    private URI uri(JavaFileManager.Location location, String name) {
        // The path is only a stable output name. Neither the directory nor any output is created on disk.
        return directory.resolve(location.getName()).resolve(name).toUri();
    }

    private JavaFileObject create(String processor, URI uri, JavaFileObject.Kind kind,
                                  BiConsumer<JavaFileObject, byte[]> output) throws IOException {
        if (!files.add(uri)) throw new FilerException("Attempt to reopen a file for path " + uri);
        unclosed.put(uri, processor);
        return new SimpleJavaFileObject(uri, kind) {
            private boolean opened;
            @Override public synchronized OutputStream openOutputStream() throws IOException {
                if (opened) throw new IOException("Output stream or writer has already been opened.");
                opened = true;
                var file = this;
                return new OutputStream() {
                    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    private boolean closed;
                    private void check() throws IOException { if (closed) throw new IOException("Stream closed"); }
                    @Override public void write(int b) throws IOException { check(); bytes.write(b); }
                    @Override public void write(byte[] b, int off, int len) throws IOException { check(); bytes.write(b, off, len); }
                    @Override public void close() {
                        if (closed) return;
                        closed = true; unclosed.remove(uri); output.accept(file, bytes.toByteArray());
                    }
                };
            }
            @Override public Writer openWriter() throws IOException { return new OutputStreamWriter(openOutputStream(), charset); }
            @Override public java.io.InputStream openInputStream() { throw new IllegalStateException("FileObject was not opened for reading."); }
            @Override public java.io.Reader openReader(boolean ignore) { throw new IllegalStateException("FileObject was not opened for reading."); }
            @Override public CharSequence getCharContent(boolean ignore) { throw new IllegalStateException("FileObject was not opened for reading."); }
            @Override public boolean delete() { return false; }
        };
    }

    void finish(BiConsumer<String, String> unsupported) {
        unclosed.forEach((uri, processor) -> unsupported.accept(processor, "unclosed Filer output " + uri));
        unclosed.clear();
    }
}
