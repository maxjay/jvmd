package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The common source/options policy for Stage 2 headers and Stage 3 bodies (C.1). */
public final class JavacOptions {
    private JavacOptions() { }

    /** JDK 25 accepts source 8 and later; preview and unspecified releases use the running feature version. */
    public static int effectiveRelease(int release, boolean preview, int feature) {
        return preview || release <= 0 ? feature : Math.max(8,Math.min(release,feature));
    }

    public static Charset charset(List<String> options) {
        var charset = StandardCharsets.UTF_8;
        for (int i = 0; i + 1 < options.size(); i++) if (options.get(i).equals("-encoding")) charset = Charset.forName(options.get(++i));
        return charset;
    }

    public static Identity optionsHash(Digest digest, List<String> options, int release, Identity processorPathHash, List<String> processors) {
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
    public static List<String> filtered(List<String> options) {
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
