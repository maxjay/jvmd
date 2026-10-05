package dev.jvmd.index.layer.local;

import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.hash.Identity;
import java.util.ArrayList;
import java.util.List;

/** Appendix F records shared by header compilation and body attribution. */
public final class ProcessorRecords {
    private ProcessorRecords() { }
    public static final int NONE = 0, ISOLATING = 1, AGGREGATING = 2;
    public static final int OVERLAY = 0, GENERATOR = 1, VIOLATED = 2;

    public record ConfigEntry(String path, Identity sum) {
        public void encode(Codec.Writer out) { out.zstr(path).id(sum); }
        public static ConfigEntry decode(Codec.Reader in, int width) { return new ConfigEntry(in.zstr(), in.id(width)); }
    }

    public record Context(Identity processorPathHash, Identity optionsHash, List<ConfigEntry> configuration) {
        public void encode(Codec.Writer out) {
            out.id(processorPathHash).id(optionsHash).u32(configuration.size());
            for (var entry : configuration) entry.encode(out);
        }
        public static Context decode(Codec.Reader in, int width) {
            var path = in.id(width);
            var options = in.id(width);
            int count = in.count();
            var configuration = new ArrayList<ConfigEntry>(count);
            for (int i = 0; i < count; i++) configuration.add(ConfigEntry.decode(in, width));
            return new Context(path, options, List.copyOf(configuration));
        }
    }

    public record Capability(int declared, int observed) {
        public boolean reusable() { return declared != NONE && observed != VIOLATED; }
        public byte[] encode() { return new Codec.Writer().u8(declared).u8(observed).toBytes(); }
        public static Capability decode(byte[] bytes) { var in = new Codec.Reader(bytes); return new Capability(in.u8(), in.u8()); }
    }
}
