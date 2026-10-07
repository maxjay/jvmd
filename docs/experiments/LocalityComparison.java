import dev.jvmd.core.hash.*;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.*;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.*;

/**
 * Standalone F09 experiment, deliberately outside every Maven source set.
 * Uses the production ContentTree unchanged. Radix is an experimental comparison,
 * not a production format proposal or an approved replacement.
 *
 * javac -cp jvmd-core/target/classes -d jvmd-tests/target/locality docs/experiments/LocalityComparison.java
 * java -Xmx2g -Xss16m -cp "jvmd-core/target/classes;jvmd-tests/target/locality" LocalityComparison
 */
public final class LocalityComparison {
    static final int CAP = ContentTree.CAP;
    static final Comparator<byte[]> ORDER = Arrays::compareUnsigned;
    static final com.sun.management.ThreadMXBean ALLOC =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static volatile Object observed;
    static boolean printing = true;
    static int checks;

    static final class Memory implements NodeSink {
        final Map<Identity, byte[]> nodes = new HashMap<>();
        long reads, readBytes, emits, emitBytes, newNodes, newBytes;
        Memory() {}
        Memory(Memory other) { nodes.putAll(other.nodes); }
        byte[] read(Identity h) {
            byte[] b = Objects.requireNonNull(nodes.get(h), "missing node " + h);
            reads++; readBytes += b.length;
            // Both implementations pay for a fresh encoded-node buffer, as Rocks does.
            return b.clone();
        }
        void put(Identity h, byte[] b) {
            emits++; emitBytes += b.length;
            if (nodes.putIfAbsent(h, b) == null) { newNodes++; newBytes += b.length; }
        }
        public void write(Node n) { put(n.hash(), n.bytes()); }
        public void flush() {}
        void reset() { reads = readBytes = emits = emitBytes = newNodes = newBytes = 0; }
        long[] counters() { return new long[]{reads, readBytes, emits, emitBytes, newNodes, newBytes}; }
    }

    /** Ref has the same fields as the production child summary; no descendant objects. */
    record Ref(Identity hash, Identity sum, int count, byte[] first) {}
    record Branch(byte[] prefix, List<Ref> children) {}

    /** Canonical, ordered, compressed nibble radix with CAP-entry leaves. */
    static final class Radix {
        final Digest digest;
        final Sum sum;
        Radix(Digest digest) { this.digest = digest; this.sum = Sum.forWidth(digest.width()); }

        // End-of-key 0 sorts before every nibble (1..16), including an actual zero byte.
        static int digit(byte[] key, int at) {
            if (at == key.length * 2) return 0;
            if (at > key.length * 2) throw new AssertionError("past key terminator");
            return 1 + ((key[at / 2] >>> ((at & 1) == 0 ? 4 : 0)) & 15);
        }
        static byte[] common(byte[] a, byte[] b) {
            int n = 0;
            while (digit(a, n) == digit(b, n)) {
                if (digit(a, n) == 0) throw new AssertionError("duplicate partition keys");
                n++;
            }
            byte[] p = new byte[n];
            for (int i = 0; i < n; i++) p[i] = (byte) digit(a, i);
            return p;
        }
        static boolean matches(byte[] key, byte[] prefix) {
            for (int i = 0; i < prefix.length; i++) {
                if (i >= key.length * 2 || digit(key, i) != prefix[i]) return false;
            }
            return true;
        }
        Ref leaf(List<Entry> es, Memory m) {
            Node n = Node.leaf(digest, sum, es); m.write(n);
            return new Ref(n.hash(), n.sum(), n.count(), n.first());
        }
        Ref branch(List<Ref> children, Memory m) {
            if (children.size() == 1) return children.getFirst();
            if (children.size() < 2 || children.size() > 17) throw new AssertionError("fanout");
            byte[] prefix = common(children.getFirst().first, children.getLast().first);
            var w = new Codec.Writer();
            // 0x52 distinguishes the experimental interior format; leaves reuse Node.leaf.
            w.u8(0x52).lenBytes(prefix).u32(children.size());
            Identity s = sum.zero(); int count = 0, prev = -1;
            for (Ref c : children) {
                int d = digit(c.first, prefix.length);
                if (d <= prev) throw new AssertionError("noncanonical child order"); prev = d;
                w.id(c.hash).id(c.sum).u32(c.count).lenBytes(c.first);
                s = sum.add(s, c.sum); count = Math.addExact(count, c.count);
            }
            byte[] b = w.toBytes(); Identity h = digest.hash(b); m.put(h, b);
            return new Ref(h, s, count, children.getFirst().first);
        }
        Branch decode(byte[] b) {
            var r = new Codec.Reader(b);
            if (r.u8() != 0x52) throw new AssertionError("not radix branch");
            byte[] p = r.lenBytes(); int size = r.count();
            var children = new ArrayList<Ref>(size);
            for (int i = 0; i < size; i++)
                children.add(new Ref(r.id(digest.width()), r.id(digest.width()), r.count(), r.lenBytes()));
            if (r.remaining() != 0) throw new AssertionError("trailing bytes");
            return new Branch(p, children);
        }
        Ref build(List<Entry> es, Memory m) {
            if (es.size() <= CAP) return leaf(es, m);
            byte[] p = common(es.getFirst().key(), es.getLast().key());
            var children = new ArrayList<Ref>();
            for (int start = 0; start < es.size();) {
                int end = start + 1, d = digit(es.get(start).key(), p.length);
                while (end < es.size() && digit(es.get(end).key(), p.length) == d) end++;
                children.add(build(es.subList(start, end), m)); start = end;
            }
            return branch(children, m);
        }
        Ref edit(Ref r, byte[] key, Entry value, Memory m) {
            Ref result = edit0(r, key, value, m);
            return result == null ? leaf(List.of(), m) : result;
        }
        Ref edit0(Ref r, byte[] key, Entry value, Memory m) {
            byte[] b = m.read(r.hash);
            if (b[0] == 0) {
                var es = Node.entries(b, digest.width());
                int index = Collections.binarySearch(es, new Entry(key, Entry.NONE, sum.zero()),
                        (a, c) -> ORDER.compare(a.key(), c.key()));
                if (index >= 0) {
                    if (value == null) es.remove(index);
                    else if (equal(es.get(index), value)) return r;
                    else es.set(index, value);
                } else {
                    if (value == null) return r;
                    es.add(-index - 1, value);
                }
                return es.isEmpty() ? null : build(es, m);
            }
            Branch br = decode(b);
            if (!matches(key, br.prefix)) {
                if (value == null) return r;
                Ref added = leaf(List.of(value), m);
                return branch(ORDER.compare(key, r.first) < 0 ? List.of(added, r) : List.of(r, added), m);
            }
            var cs = new ArrayList<>(br.children);
            int d = digit(key, br.prefix.length), i = 0;
            while (i < cs.size() && digit(cs.get(i).first, br.prefix.length) < d) i++;
            if (i < cs.size() && digit(cs.get(i).first, br.prefix.length) == d) {
                Ref old = cs.get(i), next = edit0(old, key, value, m);
                if (next != null && next.hash.equals(old.hash)) return r;
                if (next == null) cs.remove(i); else cs.set(i, next);
            } else {
                if (value == null) return r;
                cs.add(i, leaf(List.of(value), m));
            }
            if (cs.size() == 1) return cs.getFirst();
            int count = 0; for (Ref c : cs) count += c.count;
            if (count <= CAP) {
                var es = new ArrayList<Entry>(count);
                for (Ref c : cs) entries(c, m, es::add);
                return leaf(es, m);
            }
            return branch(cs, m);
        }
        void entries(Ref r, Memory m, Consumer<Entry> out) {
            byte[] b = m.read(r.hash);
            if (b[0] == 0) { Node.entries(b, digest.width()).forEach(out); return; }
            for (Ref c : decode(b).children) entries(c, m, out);
        }
        Entry get(Ref r, byte[] key, Memory m) {
            byte[] b = m.read(r.hash);
            if (b[0] == 0) {
                Entry[] found = {null};
                Node.entries(b, digest.width(), key, Arrays.copyOf(key, key.length + 1), true, e -> found[0] = e);
                return found[0];
            }
            Branch br = decode(b);
            if (!matches(key, br.prefix)) return null;
            int d = digit(key, br.prefix.length);
            for (Ref c : br.children) if (digit(c.first, br.prefix.length) == d) return get(c, key, m);
            return null;
        }
        Identity range(Ref r, byte[] from, byte[] to, byte[] end, Memory m) {
            byte[] b = m.read(r.hash);
            Identity[] result = {sum.zero()};
            if (b[0] == 0) {
                Node.entries(b, digest.width(), from, to, false, e -> result[0] = sum.add(result[0], e.h()));
                return result[0];
            }
            var cs = decode(b).children;
            for (int i = 0; i < cs.size(); i++) {
                Ref c = cs.get(i); byte[] hi = i + 1 < cs.size() ? cs.get(i + 1).first : end;
                if (from != null && hi != null && ORDER.compare(hi, from) <= 0
                        || to != null && ORDER.compare(c.first, to) >= 0) continue;
                if ((from == null || ORDER.compare(c.first, from) >= 0)
                        && (to == null || hi != null && ORDER.compare(hi, to) <= 0))
                    result[0] = sum.add(result[0], c.sum);
                else result[0] = sum.add(result[0], range(c, from, to, hi, m));
            }
            return result[0];
        }
        void expand(ArrayDeque<Object> q, Memory m) {
            Ref r = (Ref) q.removeFirst(); byte[] b = m.read(r.hash);
            List<?> items = b[0] == 0 ? Node.entries(b, digest.width()) : decode(b).children;
            for (int i = items.size() - 1; i >= 0; i--) q.addFirst(items.get(i));
        }
        static byte[] first(Object o) { return o instanceof Ref r ? r.first : ((Entry)o).key(); }
        Diff.Result diff(Ref a, Ref b, Memory m) {
            var x = new ArrayDeque<Object>(); var y = new ArrayDeque<Object>();
            x.add(a); y.add(b);
            var removed = new ArrayList<Entry>(); var added = new ArrayList<Entry>();
            while (!x.isEmpty() || !y.isEmpty()) {
                if (x.isEmpty()) { drainOne(y, added, m); continue; }
                if (y.isEmpty()) { drainOne(x, removed, m); continue; }
                Object u = x.getFirst(), v = y.getFirst();
                if (u instanceof Ref p && v instanceof Ref q && p.hash.equals(q.hash)) {
                    x.removeFirst(); y.removeFirst(); continue;
                }
                int c = ORDER.compare(first(u), first(v));
                if (u instanceof Ref p && v instanceof Ref q && c == 0) {
                    expand(p.count >= q.count ? x : y, m); continue;
                }
                if (u instanceof Ref && c <= 0) { expand(x, m); continue; }
                if (v instanceof Ref && c >= 0) { expand(y, m); continue; }
                if (c < 0) removed.add((Entry)x.removeFirst());
                else if (c > 0) added.add((Entry)y.removeFirst());
                else {
                    Entry p = (Entry)x.removeFirst(), q = (Entry)y.removeFirst();
                    if (!equal(p, q)) { removed.add(p); added.add(q); }
                }
            }
            return new Diff.Result(removed, added);
        }
        void drainOne(ArrayDeque<Object> q, List<Entry> out, Memory m) {
            if (q.getFirst() instanceof Ref) expand(q, m); else out.add((Entry)q.removeFirst());
        }
        void verify(Ref r, Memory m) {
            byte[] b = m.read(r.hash);
            require(digest.hash(b).equals(r.hash), "radix hash");
            Ref rebuilt;
            if (b[0] == 0) rebuilt = leaf(Node.entries(b, digest.width()), new Memory());
            else {
                var cs = decode(b).children; cs.forEach(c -> verify(c, m));
                rebuilt = branch(cs, new Memory());
            }
            require(r.hash.equals(rebuilt.hash) && r.sum.equals(rebuilt.sum) && r.count == rebuilt.count,
                    "radix summary");
        }
    }

    record Row(Object result, long[] io, long allocation) {}
    static Row measure(Memory base, Function<Memory, Object> work) {
        long[] counters = null; Object result = null;
        long[] allocations = new long[3];
        for (int i = 0; i < allocations.length; i++) {
            Memory m = new Memory(base); // Existing store setup is outside the operation.
            long start = ALLOC.getCurrentThreadAllocatedBytes();
            result = work.apply(m); observed = result;
            allocations[i] = ALLOC.getCurrentThreadAllocatedBytes() - start;
            long[] actual = m.counters();
            if (counters != null) require(Arrays.equals(counters, actual), "repeat I/O");
            counters = actual;
        }
        Arrays.sort(allocations);
        return new Row(result, counters, allocations[1]);
    }
    static void print(Digest d, String fixture, int n, String implementation, String operation, Row row) {
        if (!printing) return;
        long[] c = row.io;
        System.out.printf(Locale.ROOT, "%s,%s,%d,%s,%s,%d,%d,%d,%d,%d,%d,%d%n",
                d.name(), fixture, n, implementation, operation,
                c[0], c[1], c[2], c[3], c[4], c[5], row.allocation);
    }
    static byte[] key(int n) { return ByteBuffer.allocate(4).putInt(n).array(); }
    static Entry entry(Digest d, byte[] k, byte[] v) { return new Entry(k, v, d.hash(k, v)); }
    static boolean equal(Entry a, Entry b) {
        return Arrays.equals(a.key(), b.key()) && Arrays.equals(a.value(), b.value()) && a.h().equals(b.h());
    }
    static void require(boolean b, String why) { checks++; if (!b) throw new AssertionError(why); }
    static void sameEntries(List<Entry> a, List<Entry> b, String why) {
        require(a.size() == b.size(), why + " size");
        for (int i = 0; i < a.size(); i++) require(equal(a.get(i), b.get(i)), why + " entry " + i);
    }

    static List<Entry> fixture(Digest d, String name, int n) {
        var entries = new ArrayList<Entry>();
        if (name.equals("chain")) {
            for (int i = 0; i < n; i++) {
                byte[] k = new byte[i + 1]; k[i] = 1;
                entries.add(entry(d, k, Entry.NONE));
            }
        } else {
            int prefix = name.startsWith("prefix-") ? Integer.parseInt(name.substring(7)) : 0;
            int payload = name.startsWith("payload-") ? Integer.parseInt(name.substring(8)) : 0;
            for (int i = name.equals("cap") ? 0 : 1; entries.size() < n; i++) {
                byte[] k = key(i);
                if (name.equals("cap") && (Hash64.of(k) & (ContentTree.B - 1)) == 0) continue;
                if (prefix > 0) { byte[] longer = new byte[prefix + 4]; System.arraycopy(k, 0, longer, prefix, 4); k = longer; }
                byte[] v = new byte[payload]; if (payload > 0) v[0] = (byte)i;
                entries.add(entry(d, k, v));
            }
        }
        entries.sort(Comparator.comparing(Entry::key, ORDER)); return entries;
    }

    static void compare(Digest d, String name, int n) {
        List<Entry> es = fixture(d, name, n);
        var current = new ContentTree(d); var alternative = new Radix(d);
        var baseC = new Memory(); var baseR = new Memory();
        Root c = current.build(es, baseC); Ref r = alternative.build(es, baseR);
        print(d, name, n, "current", "fresh-build", measure(new Memory(), m -> current.build(es, m)));
        print(d, name, n, "radix", "fresh-build", measure(new Memory(), m -> alternative.build(es, m)));
        Identity expectedSum = current.sums().zero();
        for (Entry e : es) expectedSum = current.sums().add(expectedSum, e.h());
        require(c.sum().equals(expectedSum) && r.sum.equals(expectedSum), "initial sum");
        current.verify(c, baseC::read); alternative.verify(r, baseR);

        for (String operation : List.of("delete-first", "delete-middle", "delete-last", "replace-middle", "insert-first")) {
            int at = operation.endsWith("first") ? 0 : operation.endsWith("last") ? n - 1 : n / 2;
            byte[] k = operation.equals("insert-first") ? new byte[0] : es.get(at).key();
            Entry value = operation.startsWith("delete") ? null : entry(d, k, new byte[]{42});
            List<byte[]> removed = value == null ? List.of(k) : List.of();
            List<Entry> added = value == null ? List.of() : List.of(value);
            Row cr = measure(baseC, m -> current.apply(c, removed, added, m::read, m));
            Row rr = measure(baseR, m -> alternative.edit(r, k, value, m));
            print(d, name, n, "current", operation, cr); print(d, name, n, "radix", operation, rr);

            var expected = new ArrayList<>(es);
            if (value == null) expected.remove(at);
            else if (operation.equals("insert-first")) expected.addFirst(value);
            else expected.set(at, value);
            Root freshC = current.build(expected, new Memory()); Ref freshR = alternative.build(expected, new Memory());
            require(cr.result.equals(freshC), "current incremental/fresh");
            Ref changedR = (Ref)rr.result;
            require(changedR.hash.equals(freshR.hash) && changedR.sum.equals(freshR.sum), "radix incremental/fresh");

            Memory mc = new Memory(baseC), mr = new Memory(baseR);
            Root nextC = current.apply(c, removed, added, mc::read, mc);
            Ref nextR = alternative.edit(r, k, value, mr);
            // A separately measured diff includes the complete consumer operation, not just edit emission.
            Row cd = measure(mc, m -> Diff.content(d, c, nextC, m::read));
            Row rd = measure(mr, m -> alternative.diff(r, nextR, m));
            print(d, name, n, "current", "diff-" + operation, cd);
            print(d, name, n, "radix", "diff-" + operation, rd);
            Diff.Result actualC = (Diff.Result)cd.result, actualR = (Diff.Result)rd.result;
            List<Entry> wantRemoved = operation.equals("insert-first") ? List.of() : List.of(es.get(at));
            List<Entry> wantAdded = value == null ? List.of() : List.of(value);
            sameEntries(actualC.removed(), wantRemoved, "current diff removed");
            sameEntries(actualC.added(), wantAdded, "current diff added");
            sameEntries(actualR.removed(), wantRemoved, "radix diff removed");
            sameEntries(actualR.added(), wantAdded, "radix diff added");
            Ref restored = alternative.edit(nextR, k, operation.equals("insert-first") ? null : es.get(at), mr);
            require(restored.hash.equals(r.hash), "restore canonical root");
            // Enumerated paths, not an edit-script echo, verify byte order and all values.
            var enumeration = new ArrayList<Entry>(); alternative.entries(nextR, mr, enumeration::add);
            sameEntries(enumeration, expected, "radix enumeration");
            alternative.verify(nextR, mr);
        }
        byte[] lo = es.get(n / 2).key(), hi = es.get(n / 2 + 1).key();
        Row cg = measure(baseC, m -> current.get(c.hash(), m::read, lo));
        Row rg = measure(baseR, m -> alternative.get(r, lo, m));
        require(equal((Entry)cg.result, es.get(n / 2)) && equal((Entry)rg.result, es.get(n / 2)), "get");
        print(d, name, n, "current", "get-middle", cg); print(d, name, n, "radix", "get-middle", rg);
        Row cq = measure(baseC, m -> current.rangeSum(c, m::read, lo, hi));
        Row rq = measure(baseR, m -> alternative.range(r, lo, hi, null, m));
        require(cq.result.equals(es.get(n / 2).h()) && rq.result.equals(cq.result), "narrow sum");
        print(d, name, n, "current", "range-one", cq); print(d, name, n, "radix", "range-one", rq);
        byte[] from = es.get(n / 4).key(), to = es.get(n * 3 / 4).key();
        cq = measure(baseC, m -> current.rangeSum(c, m::read, from, to));
        rq = measure(baseR, m -> alternative.range(r, from, to, null, m));
        Identity middleSum = current.sums().zero();
        for (int i = n / 4; i < n * 3 / 4; i++) middleSum = current.sums().add(middleSum, es.get(i).h());
        require(cq.result.equals(middleSum) && rq.result.equals(cq.result), "wide sum");
        print(d, name, n, "current", "range-half", cq); print(d, name, n, "radix", "range-half", rq);
    }

    static void history(Digest d) {
        Radix radix = new Radix(d);
        var expected = new TreeMap<byte[], Entry>(ORDER); Memory m = new Memory();
        Ref root = radix.build(List.of(), m); var random = new Random(6209);
        var keys = new ArrayList<byte[]>();
        keys.add(new byte[0]); keys.add(new byte[]{0}); keys.add(new byte[]{-1}); keys.add(new byte[]{-1,-1});
        for (int i = 0; i < 700; i++) { byte[] k = new byte[random.nextInt(16) + 1]; random.nextBytes(k); keys.add(k); }
        for (int step = 0; step < 2000; step++) {
            byte[] k = keys.get(step < keys.size() ? step : random.nextInt(keys.size()));
            Entry value = step >= keys.size() && random.nextBoolean() ? null : entry(d, k, key(step));
            Ref before = root;
            List<Entry> old = new ArrayList<>(expected.values());
            if (value == null) expected.remove(k); else expected.put(k, value);
            root = radix.edit(root, k, value, m);
            Ref fresh = radix.build(new ArrayList<>(expected.values()), new Memory());
            require(root.hash.equals(fresh.hash) && root.sum.equals(fresh.sum), "history-independent root");
            var delta = radix.diff(before, root, m);
            var oracleRemoved = new ArrayList<Entry>(); var oracleAdded = new ArrayList<Entry>();
            TreeMap<byte[], Entry> oldMap = new TreeMap<>(ORDER); old.forEach(e -> oldMap.put(e.key(), e));
            for (Entry e : old) {
                Entry v = expected.get(e.key());
                if (v == null || !equal(e, v)) oracleRemoved.add(e);
            }
            for (Entry e : expected.values()) {
                Entry v = oldMap.get(e.key());
                if (v == null || !equal(e, v)) oracleAdded.add(e);
            }
            sameEntries(delta.removed(), oracleRemoved, "random diff removed");
            sameEntries(delta.added(), oracleAdded, "random diff added");
            if (step % 20 == 0) {
                for (int q = 0; q < 12; q++) {
                    byte[] prefix = keys.get(random.nextInt(keys.size()));
                    prefix = Arrays.copyOf(prefix, random.nextInt(prefix.length + 1));
                    Identity sum = radix.sum.zero();
                    for (Entry e : expected.values())
                        if (Arrays.equals(prefix, Arrays.copyOf(e.key(), Math.min(prefix.length, e.key().length))))
                            sum = radix.sum.add(sum, e.h());
                    require(radix.range(root, prefix, ContentTree.prefixEnd(prefix), null, m).equals(sum), "prefix sum");
                }
            }
        }
        // Insertion orders differ, but the final entry map alone determines the root.
        List<Entry> shuffled = new ArrayList<>(expected.values()); Collections.shuffle(shuffled, random);
        Memory other = new Memory(); Ref reverse = radix.build(List.of(), other);
        for (Entry e : shuffled) reverse = radix.edit(reverse, e.key(), e, other);
        require(root.hash.equals(reverse.hash), "different construction history");
    }

    static void unrelatedShapes(Digest d) {
        Radix radix = new Radix(d); var random = new Random(6210);
        for (int trial = 0; trial < 100; trial++) {
            var a = new TreeMap<byte[], Entry>(ORDER); var b = new TreeMap<byte[], Entry>(ORDER);
            for (int i = 0; i < 600; i++) {
                byte[] k = key(i);
                if (random.nextInt(4) != 0) a.put(k, entry(d, k, key(i)));
                if (random.nextInt(4) != 0) b.put(k, entry(d, k, key(random.nextInt(5) == 0 ? -i : i)));
            }
            Memory m = new Memory(); Ref ar = radix.build(new ArrayList<>(a.values()), m);
            Ref br = radix.build(new ArrayList<>(b.values()), m);
            Diff.Result actual = radix.diff(ar, br, m);
            var removed = new ArrayList<Entry>(); var added = new ArrayList<Entry>();
            for (Entry e : a.values()) if (!b.containsKey(e.key()) || !equal(e, b.get(e.key()))) removed.add(e);
            for (Entry e : b.values()) if (!a.containsKey(e.key()) || !equal(e, a.get(e.key()))) added.add(e);
            sameEntries(actual.removed(), removed, "unrelated shapes removed");
            sameEntries(actual.added(), added, "unrelated shapes added");
            require(radix.diff(ar, ar, m).isEmpty(), "equal roots diff");
        }
    }

    static Digest sha3() {
        return new Digest() {
            public String name() { return "SHA3-256"; }
            public int width() { return 32; }
            public Hasher hasher() {
                try {
                    MessageDigest md = MessageDigest.getInstance(name());
                    return new Hasher() {
                        public void update(byte[] b, int from, int n) { md.update(b, from, n); }
                        public Identity finish() { return Identity.of(md.digest()); }
                    };
                } catch (Exception e) { throw new IllegalStateException(e); }
            }
        };
    }
    public static void main(String[] args) {
        if (!ALLOC.isThreadAllocatedMemorySupported()) throw new IllegalStateException("thread allocation unavailable");
        ALLOC.setThreadAllocatedMemoryEnabled(true);
        System.err.println("Java=" + System.getProperty("java.runtime.version") + "; CAP=" + CAP
                + "; B=" + ContentTree.B + "; cloned reads; median of 3 allocations; no Rocks I/O");
        System.out.println("digest,fixture,entries,implementation,operation,node_reads,read_bytes,node_emissions,emitted_bytes,new_nodes,new_bytes,allocated_bytes");
        for (Digest d : List.of(Sha256.INSTANCE, sha3())) {
            printing = false;
            for (int i = 0; i < 5; i++) compare(d, "ordinary", 256);
            history(d);
            unrelatedShapes(d);
            printing = true;
            for (String fixture : List.of("ordinary", "cap"))
                for (int n : new int[]{4096, 16384, 65536}) compare(d, fixture, n);
            for (int n : new int[]{127,128,129,255,256,257}) compare(d, "ordinary", n);
            for (int size : new int[]{64,512,4096}) compare(d, "prefix-" + size, 4096);
            for (int size : new int[]{256,1024,4096}) compare(d, "payload-" + size, 4096);
            for (int n : new int[]{256,512,1024}) compare(d, "chain", n);
        }
        System.err.println("PASS checks=" + checks + "; production source unchanged; comparison only");
    }
}
