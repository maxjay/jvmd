package dev.jvmd.index.layer.local;

import dev.jvmd.core.tree.Codec;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code MOD|} record (stage 2, B.3): a module's descriptor. {@code release} is the version javac was run at, which is the
 * running feature version when the module compiles with {@code --enable-preview} (C.1); the model's own value is not kept.
 */
public record ModuleRecord(String coordinate, int release, boolean moduleInfo, List<String> javacOptions, List<String> mainRoots, List<String> testRoots) {
    public byte[] encode() {
        var out = new Codec.Writer(256).str(coordinate).u8(release).u8(moduleInfo ? 1 : 0);
        strings(out, javacOptions);
        strings(out, mainRoots);
        strings(out, testRoots);
        return out.toBytes();
    }

    public static ModuleRecord decode(byte[] bytes) {
        var in = new Codec.Reader(bytes);
        return new ModuleRecord(in.str(), in.u8(), in.u8() == 1, strings(in), strings(in), strings(in));
    }

    private static void strings(Codec.Writer out, List<String> list) {
        out.u32(list.size());
        for (var s : list) out.str(s);
    }

    private static List<String> strings(Codec.Reader in) {
        int n = in.count();
        var out = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) out.add(in.str());
        return List.copyOf(out);
    }
}
