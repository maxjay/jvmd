package dev.jvmd.index.layer.local;

import dev.jvmd.core.tree.Codec;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code MOD|} record (stage 2, B.3): a module's descriptor. {@code release} is the version javac was run at, which is the
 * running feature version when the module compiles with {@code --enable-preview} (C.1); the model's own value is not kept.
 */
public record ModuleRecord(String coordinate, int release, boolean moduleInfo, List<String> javacOptions, List<String> mainRoots, List<String> testRoots,
                           ProjectModel.Processing processing) {
    public ModuleRecord(String coordinate, int release, boolean moduleInfo, List<String> javacOptions, List<String> mainRoots, List<String> testRoots) {
        this(coordinate, release, moduleInfo, javacOptions, mainRoots, testRoots, ProjectModel.Processing.NONE);
    }
    public byte[] encode() {
        var out = new Codec.Writer(256).str(coordinate).u8(release).u8(moduleInfo ? 1 : 0);
        strings(out, javacOptions);
        strings(out, mainRoots);
        strings(out, testRoots);
        out.u32(processing.path().size());
        for (var jar : processing.path()) out.str(jar.coordinate()).str(jar.location());
        strings(out, processing.processors());
        strings(out, processing.options());
        strings(out, processing.configurationFiles());
        return out.toBytes();
    }

    public static ModuleRecord decode(byte[] bytes) {
        var in = new Codec.Reader(bytes);
        var coordinate = in.str();
        int release = in.u8();
        boolean moduleInfo = in.u8() == 1;
        var options = strings(in);
        var main = strings(in);
        var test = strings(in);
        int count = in.count();
        var path = new ArrayList<ProjectModel.Dependency>(count);
        for (int i = 0; i < count; i++) path.add(new ProjectModel.Dependency(in.str(), in.str(), null));
        var processing = new ProjectModel.Processing(List.copyOf(path), strings(in), strings(in), strings(in));
        return new ModuleRecord(coordinate, release, moduleInfo, options, main, test, processing);
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
