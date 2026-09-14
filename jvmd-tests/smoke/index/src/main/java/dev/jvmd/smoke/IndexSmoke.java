package dev.jvmd.smoke;
import java.lang.classfile.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
/** Implements 9.8 and 9.9: pinned multi-release selection and source signature join rate. */
public final class IndexSmoke {
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]), spring = repo.resolve("org/springframework/spring-core/7.0.8/spring-core-7.0.8.jar");
        var binaries = new LinkedHashMap<String, ClassModel>();
        try (var jar = new JarFile(spring.toFile(), false, JarFile.OPEN_READ, Runtime.version())) {
            for (var entry : jar.versionedStream().filter(e -> e.getName().endsWith(".class")).toList()) {
                var model = ClassFile.of().parse(jar.getInputStream(entry).readAllBytes());
                binaries.put(model.thisClass().asInternalName().replace('/', '.'), model);
                if (entry.getRealName().startsWith("META-INF/versions/")) {
                    int version = Integer.parseInt(entry.getRealName().split("/")[2]);
                    if (version > Runtime.version().feature()) throw new AssertionError("future class selected");
                    System.out.println("MULTI_RELEASE " + entry.getRealName() + " class_major=" + model.majorVersion());
                }
            }
        }
        var sources = new LinkedHashMap<String, String>();
        try (var jar = new JarFile(spring.resolveSibling("spring-core-7.0.8-sources.jar").toFile())) {
            for (var entry : jar.stream().filter(e -> e.getName().endsWith(".java") && !e.getName().startsWith("META-INF/")).toList())
                sources.put(entry.getName(), new String(jar.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        var result = new SourceJoin().join(binaries, sources);
        double rate = (result.eligible() - result.unmatched().size()) * 100.0 / result.eligible();
        System.out.printf(Locale.ROOT, "SOURCE_JOIN eligible=%d matched=%d rate=%.5f%% unmatched=%s%n", result.eligible(), result.eligible()-result.unmatched().size(), rate, result.unmatched());
        if (rate <= 98) throw new AssertionError("source join rate below 98%");
    }
}
