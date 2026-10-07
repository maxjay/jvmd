package dev.jvmd.index.layer.local;

import dev.jvmd.core.tree.Codec;
import java.util.List;

/** The native package-enumeration question in a processor phase; init is phase -1. Not module resolution state. */
public record ProcessorModuleQuery(String processor, int phase, String module) {
    public ProcessorModuleQuery {
        java.util.Objects.requireNonNull(processor);
        java.util.Objects.requireNonNull(module);
        if (phase < -1) throw new IllegalArgumentException("Invalid processor phase");
    }

    public byte[] key() { return new Codec.Writer().str(processor).u32((long) phase + 1).str(module).toBytes(); }

    public static byte[] encode(List<String> packages) {
        var out = new Codec.Writer().u32(packages.size());
        packages.forEach(out::str);
        return out.toBytes();
    }

    public static List<String> decode(byte[] bytes) {
        var in = new Codec.Reader(bytes);
        var names = new java.util.ArrayList<String>();
        for (long n = in.u32(); n > 0; n--) names.add(in.str());
        if (in.remaining() != 0) throw new IllegalStateException("Trailing processor module query bytes");
        return List.copyOf(names);
    }
}
