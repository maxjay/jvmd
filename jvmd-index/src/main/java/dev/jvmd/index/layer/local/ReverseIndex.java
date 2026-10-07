package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Diff;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.Arrays;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Header reverse lookup: one empty record per exact dependency, project and source path. */
public final class ReverseIndex {
    private ReverseIndex() { }
    public static final int T = 0, N = 1, D = 2;
    private static final byte[] HEADER = ("X|H" + LocalFormat.LAYOUT + "|").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] BODY = ("X|B" + BodiesRoot.VERSION + "L" + LocalFormat.LAYOUT + "|").getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public static boolean isBodyKey(byte[] key) {
        return key.length >= BODY.length && Arrays.equals(key, 0, BODY.length, BODY, 0, BODY.length);
    }

    /** Versioned current index: earlier raw X history is never searched by this reader. */
    public static boolean isHeaderKey(byte[] key) {
        return key.length >= HEADER.length && Arrays.equals(key, 0, HEADER.length, HEADER, 0, HEADER.length);
    }

    /** Form selects T, N or the definer universe. Empty member name selects a complete kind. */
    public record Dependency(int form, String type, int kind, String name) implements Comparable<Dependency> {
        public Dependency {
            if (form < T || form > D || kind < Keys.TYPE || kind > Keys.METHOD
                    || form != T && kind != Keys.TYPE || form == D && !name.isEmpty())
                throw new IllegalArgumentException("Invalid header reverse dependency");
        }
        public byte[] prefix() { return new Codec.Writer().raw(HEADER).u8(form).zstr(type).u8(kind).zstr(name).toBytes(); }
        public byte[] key(Identity project, SourceUnit unit) { var out = new Codec.Writer().raw(prefix()).id(project); unit.encode(out); return out.toBytes(); }
        public byte[] key(Identity project, String module, int scope, String path) { return key(project, new SourceUnit(module, scope, path)); }
        @Override public int compareTo(Dependency other) {
            int c = Integer.compare(form, other.form);
            if (c == 0) c = type.compareTo(other.type);
            if (c == 0) c = Integer.compare(kind, other.kind);
            return c == 0 ? name.compareTo(other.name) : c;
        }
    }

    public record Consumer(Identity project, SourceUnit source) implements Comparable<Consumer> {
        public Consumer(Identity project, String module, int scope, String path) { this(project, new SourceUnit(module, scope, path)); }
        public String path() { return source.path(); }
        public String module() { return source.module(); }
        public int scope() { return source.scope(); }
        @Override public int compareTo(Consumer other) {
            int c = project.compareTo(other.project);
            return c == 0 ? source.compareTo(other.source) : c;
        }
    }

    public static Dependency range(HeaderProof.Range range) { return new Dependency(T, range.type(), range.kind(), range.name()); }
    public static Dependency absence(HeaderProof.Absence absence) {
        return new Dependency(absence.form() == 0 ? D : N, absence.type(), Keys.TYPE, absence.name());
    }

    public static Set<Dependency> dependencies(FileRow row) {
        var out = new TreeSet<Dependency>();
        for (var proof : row.headerProof()) out.add(range(proof.range()));
        for (var absence : row.absences()) out.add(absence(absence));
        return out;
    }

    /** All current consumers of one prefix, atomically maintained with the project root. */
    public static Set<Consumer> consumers(Digest digest, LocalStore store, Dependency dependency) {
        return new Reader(digest, store).read(List.of(dependency.prefix()));
    }

    /**
     * A semantic delta from T, N and O/DD/DS/DC to candidate paths. Only changed keys and their reverse prefixes are read;
     * no F row is read here. The caller validates just these candidates against each file's current own leaf and route.
     */
    public record Delta(Diff.Result types, Diff.Result memberTypes, Diff.Result presence, Diff.Result definers) { }

    public static Set<Consumer> candidates(Digest digest, LocalStore store, Delta delta) {
        var changed = new TreeSet<Dependency>();
        for (var entries : List.of(delta.types().removed(), delta.types().added())) for (var entry : entries) {
            var m = Keys.Member.decode(entry.key());
            changed.add(new Dependency(T, m.owner(), m.kind(), m.name()));
            if (m.kind() != Keys.TYPE) changed.add(new Dependency(T, m.owner(), m.kind(), ""));
        }
        for (var entry : presenceChanges(delta.memberTypes())) {
            var in = new Codec.Reader(entry.key());
            String name = in.zstr();
            if (in.u8() == Keys.TYPE) {
                String outer = in.zstr();
                if (!outer.isEmpty()) changed.add(new Dependency(N, outer, Keys.TYPE, name));
            }
        }
        for (var entry : presenceChanges(delta.presence()))
            changed.add(new Dependency(D, Keys.ownerOf(entry.key()), Keys.TYPE, ""));
        var prefixes = new TreeSet<byte[]>(Arrays::compareUnsigned);
        for (var dependency : changed) prefixes.add(dependency.prefix());
        for (var entry : definerChanges(digest, delta.definers())) {
            String type = Keys.ownerOf(entry.key());
            for (int form : new int[]{T, N})
                prefixes.add(new Codec.Writer().raw(HEADER).u8(form).zstr(type).toBytes());
        }
        return new Reader(digest, store).read(prefixes);
    }

    /** Exact DC value changes only matter here when the first provider or its resolution projection changed. */
    private static List<dev.jvmd.core.tree.Entry> definerChanges(Digest digest, Diff.Result delta) {
        var old = new java.util.TreeMap<byte[], dev.jvmd.core.tree.Entry>(Arrays::compareUnsigned);
        var next = new java.util.TreeMap<byte[], dev.jvmd.core.tree.Entry>(Arrays::compareUnsigned);
        for (var entry : delta.removed()) old.put(entry.key(), entry);
        for (var entry : delta.added()) next.put(entry.key(), entry);
        var result = new ArrayList<>(presenceChanges(delta));
        next.forEach((key, entry) -> {
            var prior = old.get(key);
            if (prior != null && (!prior.h().equals(entry.h()) ||
                    !new Codec.Reader(prior.value()).id(digest.width()).equals(new Codec.Reader(entry.value()).id(digest.width()))))
                result.add(entry);
        });
        return result;
    }

    /** A same-key replacement cannot toggle an indexed zero/presence predicate. */
    private static List<dev.jvmd.core.tree.Entry> presenceChanges(Diff.Result delta) {
        var removed = new java.util.TreeMap<byte[], dev.jvmd.core.tree.Entry>(Arrays::compareUnsigned);
        var added = new java.util.TreeMap<byte[], dev.jvmd.core.tree.Entry>(Arrays::compareUnsigned);
        for (var entry : delta.removed()) removed.put(entry.key(), entry);
        for (var entry : delta.added()) added.put(entry.key(), entry);
        var result = new ArrayList<dev.jvmd.core.tree.Entry>();
        removed.forEach((key, entry) -> { if (!added.containsKey(key)) result.add(entry); });
        added.forEach((key, entry) -> { if (!removed.containsKey(key)) result.add(entry); });
        return result;
    }

    /** Root publication's only reverse mutations; historical LOCAL nodes already retain the empty values. */
    public static List<byte[][]> publication(Digest digest, LocalStore store, byte[] previous, byte[] next) {
        var tree = new ContentTree(digest);
        var root = LocalRoot.decode(digest, next).local();
        var mutations = new ArrayList<byte[][]>();
        java.util.function.Function<Identity, byte[]> nodes = h -> store.get(MachineStore.nodeKey(h));
        if (previous == null) tree.forEach(root.hash(), nodes, e -> {
            if (isHeaderKey(e.key())) mutations.add(new byte[][]{e.key(), new byte[0]});
        });
        else {
            var diff = Diff.trees(digest, LocalRoot.decode(digest, previous).local(), root, nodes);
            for (var entry : diff.removed()) if (isHeaderKey(entry.key())) mutations.add(new byte[][]{entry.key(), null});
            for (var entry : diff.added()) if (isHeaderKey(entry.key())) mutations.add(new byte[][]{entry.key(), new byte[0]});
        }
        return mutations;
    }

    private static final class Reader {
        private final Digest digest;
        private final LocalStore store;
        private final int namespace;
        Reader(Digest digest, LocalStore store) { this(digest, store, HEADER.length); }
        Reader(Digest digest, LocalStore store, int namespace) { this.digest = digest; this.store = store; this.namespace = namespace; }

        Set<Consumer> read(java.util.Collection<byte[]> prefixes) {
            var consumers = new TreeSet<Consumer>();
            byte[] previous = null;
            for (var prefix : prefixes) {
                if (previous != null && prefix.length >= previous.length
                        && Arrays.equals(prefix, 0, previous.length, previous, 0, previous.length)) continue;
                previous = prefix;
                store.forEachKey(prefix, key -> {
                    var in = new Codec.Reader(key);
                    in.raw(namespace); in.u8(); in.zstr(); in.u8(); in.zstr();
                    var project = in.id(digest.width());
                    var unit = SourceUnit.decode(in);
                    if (in.remaining() != 0) throw new IllegalStateException("Trailing reverse consumer bytes");
                    consumers.add(new Consumer(project, unit));
                });
            }
            return consumers;
        }
    }
    public static byte[] bodyPrefix(Dependency dependency) {
        return new Codec.Writer().raw(BODY).u8(dependency.form()).zstr(dependency.type()).u8(dependency.kind()).zstr(dependency.name()).toBytes();
    }

    public static byte[] bodyKey(Dependency dependency, Identity project, SourceUnit unit) {
        var out = new Codec.Writer().raw(bodyPrefix(dependency)).id(project); unit.encode(out); return out.toBytes();
    }
    public static byte[] bodyKey(Dependency dependency, Identity project, String module, int scope, String path) {
        return bodyKey(dependency, project, new SourceUnit(module, scope, path));
    }

    public static Set<Dependency> dependencies(Proof proof) {
        var out = new TreeSet<Dependency>();
        for (var type : proof.types()) for (var entry : type.entries()) {
            var range = entry.range();
            out.add(new Dependency(range.form(), range.type(), range.kind(), range.name()));
        }
        for (var type : proof.absent()) out.add(new Dependency(D, type, Keys.TYPE, ""));
        return out;
    }

    public static Set<Consumer> bodyConsumers(Digest digest, LocalStore store, Dependency dependency) {
        return new Reader(digest, store, BODY.length).read(List.of(bodyPrefix(dependency)));
    }

    /** Body N stores actual sums, unlike header N's zero predicates: same-key h changes are observable here. */
    public static Set<Consumer> bodyCandidates(Digest digest, LocalStore store, Delta delta) {
        var prefixes = new TreeSet<byte[]>(Arrays::compareUnsigned);
        for (var dependency : changes(delta.types(), delta.memberTypes(), delta.presence())) prefixes.add(bodyPrefix(dependency));
        for (var entry : definerChanges(digest, delta.definers())) for (int form : new int[]{T, N})
            prefixes.add(new Codec.Writer().raw(BODY).u8(form).zstr(Keys.ownerOf(entry.key())).toBytes());
        return new Reader(digest, store, BODY.length).read(prefixes);
    }

    private static Set<Dependency> changes(Diff.Result t, Diff.Result n, Diff.Result d) {
        var changed = new TreeSet<Dependency>();
        for (var entries : List.of(t.removed(), t.added())) for (var entry : entries) {
            var m = Keys.Member.decode(entry.key());
            changed.add(new Dependency(T, m.owner(), m.kind(), m.name()));
            if (m.kind() != Keys.TYPE) changed.add(new Dependency(T, m.owner(), m.kind(), ""));
        }
        for (var entries : List.of(n.removed(), n.added())) for (var entry : entries) {
            var in = new Codec.Reader(entry.key());
            String name = in.zstr();
            if (in.u8() == Keys.TYPE) {
                String outer = in.zstr();
                if (!outer.isEmpty()) changed.add(new Dependency(N, outer, Keys.TYPE, name));
            }
        }
        for (var entry : presenceChanges(d))
            changed.add(new Dependency(D, Keys.ownerOf(entry.key()), Keys.TYPE, ""));
        return changed;
    }

    /** Current body X changes follow exact rooted membership, independent of retained historical keys. */
    public static List<byte[][]> bodyPublication(Digest digest, LocalStore store, byte[] previous, byte[] next) {
        var tree = new ContentTree(digest);
        java.util.function.Function<Identity, byte[]> nodes = h -> store.get(MachineStore.nodeKey(h));
        var current = BodiesRoot.decode(next, digest.width());
        var mutations = new ArrayList<byte[][]>();
        if (previous == null || !LocalRoot.formatOf(previous).equals(current.format())) {
            tree.forEach(current.bodiesRoot(), nodes, BODY, e -> mutations.add(new byte[][]{e.key(), new byte[0]}));
        } else {
            var before = BodiesRoot.decode(previous, digest.width());
            var diff = Diff.trees(digest, tree.root(before.bodiesRoot(), nodes), tree.root(current.bodiesRoot(), nodes), nodes);
            for (var entry : diff.removed()) if (isBodyKey(entry.key())) mutations.add(new byte[][]{entry.key(), null});
            for (var entry : diff.added()) if (isBodyKey(entry.key())) mutations.add(new byte[][]{entry.key(), new byte[0]});
        }
        return mutations;
    }
}
