package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/** Stage 3 B.1: a resolution proof grouped by type, with independent T/N ranges and exact type absences. */
public record Proof(Header header, List<Type> types, List<String> absent, ProcessorRecords.Body processorBody) {
    public static final int T = 0, N = 1;

    public record Header(Identity routeHash, Identity ddSum, Identity dsSum, Identity dcSum, Identity ownR,
                         ProcessorRecords.Context processor) {
        public Header {
            if (processor != null) processor = new ProcessorRecords.Context(processor.processorPathHash(), processor.optionsHash(),
                    List.copyOf(processor.configuration()));
        }
    }

    /** Storage/consumer key. The T and N keys retain their namespace even when their spelling coincides. */
    public record Range(int form, String type, int kind, String name) implements Comparable<Range> {
        public Range {
            if (form == T) {
                if (kind < Keys.TYPE || kind > Keys.METHOD || kind == Keys.TYPE && !name.isEmpty())
                    throw new IllegalArgumentException("Invalid T proof range");
            } else if (form == N) {
                if (kind != Keys.TYPE || name.isEmpty()) throw new IllegalArgumentException("Invalid N proof range");
            } else throw new IllegalArgumentException("Unknown proof range form " + form);
        }
        public byte[] key() { return new Codec.Writer().u8(form).zstr(type).u8(kind).zstr(name).toBytes(); }
        public byte[] prefix() { return form == T ? Keys.groupKey(type, kind, name) : Keys.memberTypesKey(type, name); }
        @Override public int compareTo(Range other) { return Arrays.compareUnsigned(key(), other.key()); }
    }

    public record Entry(Range range, Identity sum) { }

    public record Type(String key, Identity oSum, List<Entry> entries) {
        public Type {
            entries = entries.stream().sorted(Comparator.comparing(Entry::range)).toList();
            if (entries.isEmpty()) throw new IllegalArgumentException("A proof type must have a consumed range");
            Range previous = null;
            for (var entry : entries) {
                if (!key.equals(entry.range().type())) throw new IllegalArgumentException("Proof range has a different owner");
                if (entry.range().equals(previous)) throw new IllegalArgumentException("Duplicate proof range");
                previous = entry.range();
            }
        }
    }

    public Proof(Header header, List<Type> types, List<String> absent) { this(header, types, absent, null); }

    public Proof withProcessorBody(ProcessorRecords.Body body) { return new Proof(header, types, absent, body); }

    public Proof {
        Objects.requireNonNull(header);
        if (processorBody != null && header.processor() == null)
            throw new IllegalArgumentException("Processor observations require a processor context");
        types = types.stream().sorted((a, b) -> compareNames(a.key(), b.key())).toList();
        absent = absent.stream().sorted(Proof::compareNames).toList();
        String previous = null;
        for (var type : types) {
            if (type.key().equals(previous)) throw new IllegalArgumentException("Duplicate proof type");
            previous = type.key();
        }
        previous = null;
        for (var type : absent) {
            if (type.equals(previous)) throw new IllegalArgumentException("Duplicate type absence");
            previous = type;
        }
    }

    private static int compareNames(String a, String b) { return Arrays.compareUnsigned(Keys.ownerKey(a), Keys.ownerKey(b)); }

    public byte[] encode() {
        var out = new Codec.Writer().id(header.routeHash()).id(header.ddSum()).id(header.dsSum()).id(header.dcSum()).id(header.ownR());
        processor(out, header.processor());
        out.u8(processorBody == null ? 0 : 1);
        if (processorBody != null) processorBody.encode(out);
        out.u32(types.size());
        for (var type : types) {
            out.zstr(type.key()).id(type.oSum()).u32(type.entries().size());
            for (var entry : type.entries()) out.raw(entry.range().key()).id(entry.sum());
        }
        out.u32(absent.size());
        for (var type : absent) out.zstr(type);
        return out.toBytes();
    }

    public static Proof decode(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        var route = in.id(width); var dd = in.id(width); var ds = in.id(width); var dc = in.id(width); var own = in.id(width);
        int presence = in.u8();
        if (presence > 1) throw new IllegalArgumentException("Invalid processor context presence");
        var context = presence == 0 ? null : ProcessorRecords.Context.decode(in, width);
        int bodyPresence = in.u8();
        if (bodyPresence > 1) throw new IllegalArgumentException("Invalid processor observations presence");
        var body = bodyPresence == 0 ? null : ProcessorRecords.Body.decode(in);
        var types = new ArrayList<Type>();
        for (int i = 0, n = in.count(); i < n; i++) {
            String key = in.zstr();
            var sum = in.id(width);
            var entries = new ArrayList<Entry>();
            for (int j = 0, m = in.count(); j < m; j++) entries.add(new Entry(new Range(in.u8(), in.zstr(), in.u8(), in.zstr()), in.id(width)));
            types.add(new Type(key, sum, entries));
        }
        var absent = new ArrayList<String>();
        for (int i = 0, n = in.count(); i < n; i++) absent.add(in.zstr());
        if (in.remaining() != 0) throw new IllegalArgumentException("Trailing proof bytes");
        return new Proof(new Header(route, dd, ds, dc, own, context), types, absent, body);
    }

    /**
     * Resolution descent without current processor observations. A processed proof cannot pass this overload.
     */
    public boolean valid(ContentTree tree, MachineLeaf own, Route route, ProcessorRecords.Context processor,
                         Function<byte[], byte[]> records) {
        return valid(tree, own, route, processor, null, records);
    }

    /** Current observations must come from freshly admitted execution or verified model queries, never from this proof.
     * Generated-output conservation is checked separately. Both processor checks precede every resolution shortcut. */
    public boolean valid(ContentTree tree, MachineLeaf own, Route route, ProcessorRecords.Context processor,
                         ProcessorRecords.Body currentBody, Function<byte[], byte[]> records) {
        if (!Objects.equals(header.processor(), processor)) return false;
        if (processor != null) {
            if (processorBody == null || currentBody == null || !processorBody.reusable() || !currentBody.reusable()
                    || !processorBody.sameInputs(currentBody)) return false;
            if (!processorBody.configuredProcessors().equals(currentBody.configuredProcessors())
                    || !processorBody.violations(processor, records).isEmpty()) return false;
        } else if (currentBody != null) return false;
        boolean sameOwn = own.r().equals(header.ownR());
        if (sameOwn && route.routeHash().equals(header.routeHash())) return true;
        var read = new DefinerIndex.Reader(tree, own, route, records);
        if (sameOwn && read.external().sum().equals(header.ddSum()) && read.sibling().sum().equals(header.dsSum())
                && read.conflicts().sum().equals(header.dcSum())) return true;
        for (var type : types) {
            var leaf = read.definer(type.key());
            if (leaf == null) return false;
            boolean moved = !tree.rangeSum(leaf.oHash(), read.nodes(), Keys.ownerKey(type.key())).equals(type.oSum());
            for (var entry : type.entries()) {
                var range = entry.range();
                if (range.form() == T && !moved) continue;
                var root = range.form() == T ? leaf.k() : leaf.nHash();
                if (!tree.rangeSum(root, read.nodes(), range.prefix()).equals(entry.sum())) return false;
            }
        }
        for (var type : absent) if (!read.absent(type)) return false;
        return true;
    }

    /** B.2: only actual entry identities and processor inputs; no route, definer-sum or own-leaf shortcut identity. */
    public Identity aci(Digest digest, String basename, Identity kappa, Identity options) {
        if (header.processor() != null && (processorBody == null || !processorBody.reusable()))
            throw new IllegalStateException("Processor result has no reusable model observations");
        var out = new Codec.Writer().str(basename).id(kappa).id(options);
        processor(out, header.processor());
        out.u8(processorBody == null ? 0 : 1);
        if (processorBody != null) processorBody.inputs(out);
        var ordered = new TreeMap<byte[], Identity>(Arrays::compareUnsigned);
        for (var type : types) for (var entry : type.entries()) ordered.put(entry.range().key(), entry.sum());
        for (var type : absent) ordered.put(new Codec.Writer().u8(2).zstr(type).toBytes(), Identity.zero(digest.width()));
        for (var entry : ordered.entrySet()) out.raw(entry.getKey()).id(entry.getValue());
        return digest.hash(out.toBytes());
    }

    private static void processor(Codec.Writer out, ProcessorRecords.Context context) {
        out.u8(context == null ? 0 : 1);
        if (context != null) context.encode(out);
    }
}
