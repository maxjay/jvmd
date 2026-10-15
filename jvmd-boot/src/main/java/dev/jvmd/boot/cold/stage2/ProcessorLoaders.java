package dev.jvmd.boot.cold.stage2;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleFinder;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.StandardJavaFileManager;

/**
 * Annotation processors see their own path and the JDK, and nothing of the process that runs javac (amendments 4.8). javac's own
 * processor class loader delegates to the class loader of javac's caller, so in the daemon a processor would load jvmd's jars (Jackson,
 * RocksDB, jvmd itself) before its own, and in a test the test class path. One version of Lombok was found that way instead of the
 * project's. The file manager here gives javac the same loader over the same locations, with a parent that only knows the packages of the
 * runtime's own modules. {@code jdk.compiler} is one of them, and Lombok needs it; the classes it finds are the ones javac itself uses.
 */
final class ProcessorLoaders {
    private ProcessorLoaders() { }

    /** {@code files} as javac sees it, except that processors are loaded in isolation. */
    static JavaFileManager isolating(StandardJavaFileManager files) {
        return new ForwardingJavaFileManager<>(files) {
            @Override public ClassLoader getClassLoader(Location location) {
                var paths = fileManager.getLocationAsPaths(location);
                if (paths == null) return null;
                var urls = new ArrayList<URL>();
                for (var path : paths) {
                    try { urls.add(path.toUri().toURL()); } catch (MalformedURLException e) { throw new UncheckedIOException(new IOException(e)); }
                }
                return new URLClassLoader("jvmd-processors", urls.toArray(URL[]::new), JdkOnly.INSTANCE);
            }
        };
    }

    /** The packages of the runtime's own modules, through the system class loader that defines them; nothing else. */
    static final class JdkOnly extends ClassLoader {
        static final JdkOnly INSTANCE = new JdkOnly();
        private final Set<String> packages = new HashSet<>();

        private JdkOnly() {
            super("jvmd-jdk-only", null);
            var system = ModuleFinder.ofSystem();
            for (var module : ModuleLayer.boot().modules())
                if (system.find(module.getName()).isPresent()) packages.addAll(module.getPackages());
        }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            int dot = name.lastIndexOf('.');
            if (dot < 0 || !packages.contains(name.substring(0, dot))) throw new ClassNotFoundException(name);
            return Class.forName(name, false, ClassLoader.getSystemClassLoader());
        }
    }
}
