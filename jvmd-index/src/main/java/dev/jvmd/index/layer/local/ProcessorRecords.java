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

    /** A reported javac diagnostic, after javac's filtering; offsets retain Diagnostic.NOPOS (-1). */
    public record Message(String processorClass, String path, javax.tools.Diagnostic.Kind kind, long position, long start, long end,
                          long line, long column, String code, String text) { }

    /** PDIAG is run output in LOCAL, never an input identity or a generated-file manifest. Order and duplicates are observable. */
    public record Diagnostics(List<Message> messages) {
        public Diagnostics { messages = List.copyOf(messages); }
        public byte[] encode() {
            var out = new Codec.Writer().u32(messages.size());
            for (var message : messages) {
                out.str(message.processorClass()).u8(message.path() == null ? 0 : 1);
                if (message.path() != null) out.zstr(message.path());
                out.u8(message.kind().ordinal()).i64(message.position()).i64(message.start()).i64(message.end())
                        .i64(message.line()).i64(message.column()).str(message.code()).str(message.text());
            }
            return out.toBytes();
        }
        public static Diagnostics decode(byte[] bytes) {
            var in = new Codec.Reader(bytes);
            int count = in.count();
            var messages = new ArrayList<Message>(count);
            for (int i = 0; i < count; i++) messages.add(new Message(in.str(), in.u8() == 1 ? in.zstr() : null,
                    javax.tools.Diagnostic.Kind.values()[in.u8()], in.i64(), in.i64(), in.i64(), in.i64(), in.i64(), in.str(), in.str()));
            return new Diagnostics(messages);
        }
    }

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
