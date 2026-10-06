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
                        .i64(message.line()).i64(message.column()).str(message.code()).utf16(message.text());
            }
            return out.toBytes();
        }
        public static Diagnostics decode(byte[] bytes) {
            var in = new Codec.Reader(bytes);
            int count = in.count();
            var messages = new ArrayList<Message>(count);
            for (int i = 0; i < count; i++) messages.add(new Message(in.str(), in.u8() == 1 ? in.zstr() : null,
                    javax.tools.Diagnostic.Kind.values()[in.u8()], in.i64(), in.i64(), in.i64(), in.i64(), in.i64(), in.str(), in.utf16()));
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

    /** Actual public-model answers from one processor in a body task, never a header/API approximation.
     * Null answers mean a tested native overlay or a processor javac did not initialize. */
    public record Observation(String processorClass, Capability capability, byte[] answers) {
        public Observation {
            if (processorClass.isEmpty()) throw new IllegalArgumentException("Empty processor class");
            java.util.Objects.requireNonNull(capability);
            answers = answers == null ? null : answers.clone();
        }
        @Override public byte[] answers() { return answers == null ? null : answers.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Observation o && processorClass.equals(o.processorClass) && capability.equals(o.capability)
                    && java.util.Arrays.equals(answers, o.answers);
        }
        @Override public int hashCode() { return java.util.Objects.hash(processorClass, capability, java.util.Arrays.hashCode(answers)); }
        private void inputs(Codec.Writer out) {
            out.str(processorClass).u8(answers == null ? 0 : 1);
            if (answers != null) out.lenBytes(answers);
        }
    }

    /** Ordered, detached observations. Output conservation and current-input admission are separate obligations. */
    public record Body(List<Observation> processors, boolean rejected, List<String> configuredProcessors) {
        public Body(List<Observation> processors) { this(processors, false); }
        public Body(List<Observation> processors, boolean rejected) {
            this(processors, rejected, processors.stream().map(Observation::processorClass).toList());
        }
        public Body {
            processors = List.copyOf(processors);
            configuredProcessors = List.copyOf(configuredProcessors);
            var names = new java.util.HashSet<String>();
            for (var processor : processors) if (!names.add(processor.processorClass()))
                throw new IllegalArgumentException("Duplicate body processor");
            var configured = new java.util.HashSet<>(configuredProcessors);
            if (configured.size() != configuredProcessors.size() || configured.contains("") || !configured.containsAll(names))
                throw new IllegalArgumentException("Invalid configured processor set");
        }
        public boolean reusable() {
            return !rejected && processors.stream().allMatch(p -> p.capability().declared() == ISOLATING && p.capability().reusable());
        }
        /** Scope admission or generated-output conservation can fail even when no body processor was invoked. */
        public Body rejectReuse() { return new Body(processors, true, configuredProcessors); }
        public Body withConfiguredProcessors(List<String> names) { return new Body(processors, rejected, names); }
        /** Admission metadata is not an ACI input. It includes configured aggregates that do not run in the body task. */
        public List<String> violations(Context context, java.util.function.Function<byte[], byte[]> records) {
            var violations = new ArrayList<String>();
            for (var name : configuredProcessors) {
                var bytes = records.apply(LocalStore.processorKey(context.processorPathHash(), name));
                if (bytes != null && Capability.decode(bytes).observed() == VIOLATED) violations.add(name);
            }
            return List.copyOf(violations);
        }
        /** Classification controls admission; only execution order and observed answers are result inputs. */
        public boolean sameInputs(Body other) {
            if (other == null || processors.size() != other.processors.size()) return false;
            for (int i = 0; i < processors.size(); i++) {
                var a = processors.get(i); var b = other.processors.get(i);
                if (!a.processorClass.equals(b.processorClass) || !java.util.Arrays.equals(a.answers, b.answers)) return false;
            }
            return true;
        }
        public void inputs(Codec.Writer out) {
            out.u32(processors.size());
            for (var processor : processors) processor.inputs(out);
        }
        public void encode(Codec.Writer out) {
            out.u8(rejected ? 1 : 0).u32(processors.size());
            for (var processor : processors) { processor.inputs(out); out.raw(processor.capability().encode()); }
            out.u32(configuredProcessors.size());
            for (var name : configuredProcessors) out.str(name);
        }
        public static Body decode(Codec.Reader in) {
            int rejected = in.u8();
            if (rejected > 1) throw new IllegalArgumentException("Invalid processor rejection flag");
            var processors = new ArrayList<Observation>();
            for (int i = 0, n = in.count(); i < n; i++) {
                var name = in.str(); int presence = in.u8();
                if (presence > 1) throw new IllegalArgumentException("Invalid processor answers presence");
                var answers = presence == 0 ? null : in.lenBytes();
                processors.add(new Observation(name, new Capability(in.u8(), in.u8()), answers));
            }
            var configured = new ArrayList<String>();
            for (int i = 0, n = in.count(); i < n; i++) configured.add(in.str());
            return new Body(processors, rejected == 1, configured);
        }
    }

    /** The ordered processor set and classifications actually observed in one scope under these options. */
    public record Invocation(String processorClass, Capability capability) { }

    public record Scope(Identity processorPathHash, Identity optionsHash, List<Invocation> processors) {
        public Scope {
            java.util.Objects.requireNonNull(processorPathHash); java.util.Objects.requireNonNull(optionsHash);
            processors = List.copyOf(processors);
            var names = new java.util.HashSet<String>();
            for (var processor : processors) {
                if (processor.processorClass().isEmpty() || !names.add(processor.processorClass()))
                    throw new IllegalArgumentException("Empty or duplicate processor class");
                java.util.Objects.requireNonNull(processor.capability());
            }
        }
        public List<String> names() { return processors.stream().map(Invocation::processorClass).toList(); }
        public Capability capability(String name) {
            return processors.stream().filter(p -> p.processorClass().equals(name)).map(Invocation::capability).findFirst().orElse(null);
        }
        public byte[] encode() {
            var out = new Codec.Writer().id(processorPathHash).id(optionsHash).u32(processors.size());
            for (var processor : processors) out.str(processor.processorClass()).raw(processor.capability().encode());
            return out.toBytes();
        }
        public static Scope decode(byte[] bytes, int width) {
            var in = new Codec.Reader(bytes);
            var path = in.id(width); var options = in.id(width); var processors = new ArrayList<Invocation>();
            for (int i = 0, n = in.count(); i < n; i++) processors.add(new Invocation(in.str(), new Capability(in.u8(), in.u8())));
            if (in.remaining() != 0) throw new IllegalArgumentException("Trailing processor scope bytes");
            return new Scope(path, options, processors);
        }
    }

    public record Capability(int declared, int observed) {
        public Capability {
            if (declared < NONE || declared > AGGREGATING || observed < OVERLAY || observed > VIOLATED)
                throw new IllegalArgumentException("Invalid processor capability");
        }
        /** Global history is classification consensus plus monotone violation history, never a scope's execution plan. */
        public Capability merge(Capability other) {
            return new Capability(declared == other.declared ? declared : NONE, Math.max(observed, other.observed));
        }
        public boolean reusable() { return declared != NONE && observed != VIOLATED; }
        public byte[] encode() { return new Codec.Writer().u8(declared).u8(observed).toBytes(); }
        public static Capability decode(byte[] bytes) { var in = new Codec.Reader(bytes); return new Capability(in.u8(), in.u8()); }
    }
}
