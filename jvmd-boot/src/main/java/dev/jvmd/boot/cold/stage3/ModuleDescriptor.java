package dev.jvmd.boot.cold.stage3;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Diff;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.HeaderDiagnostics;
import dev.jvmd.index.layer.local.Proof;
import dev.jvmd.index.layer.local.ResultRecord;
import dev.jvmd.index.layer.local.Route;
import dev.jvmd.index.layer.local.SourceLeaf;
import dev.jvmd.index.layer.local.UsesRecord;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.Ann;
import dev.jvmd.index.layer.machine.AnnotationLeaf;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.Res;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** A zero-body output of the exact module-info T/A entries. No source task or module resolution occurs here. */
public final class ModuleDescriptor {
    private ModuleDescriptor() { }
    public record Derived(Attribute.Computed computed, boolean emitted) { }

    /** The only descriptor emission options in the current classpath compiler policy. */
    public record Options(int majorVersion, boolean sourceFile) {
        public static Options of(List<String> javac) {
            boolean source = true; int target = Runtime.version().feature();
            for (int i = 0; i < javac.size(); i++) {
                String option = javac.get(i);
                if (option.equals("-g")) source = true;
                else if (option.startsWith("-g:")) source = List.of(option.substring(3).split(",")).contains("source");
                else if (option.equals("--release") || option.equals("--target") || option.equals("-target")) target = Integer.parseInt(javac.get(++i));
                else if (option.startsWith("--release=") || option.startsWith("--target=")) target = Integer.parseInt(option.substring(option.indexOf('=') + 1));
            }
            return new Options(target + 44, source);
        }
        /** moduleEmitFormat: emitter version, target classfile version, and SourceFile emission only. */
        byte[] encode() { return new Codec.Writer().u16(2).u16(majorVersion).u8(sourceFile ? 1 : 0).toBytes(); }
    }

    /** A caller schedules emission directly from the descriptor key's T/A delta, without consulting X. */
    public static boolean changed(Diff.Result resolution, Diff.Result annotations) {
        var key = Keys.typeKey("module-info");
        return java.util.stream.Stream.of(resolution.removed(), resolution.added(), annotations.removed(), annotations.added())
                .flatMap(List::stream).anyMatch(entry -> Arrays.equals(entry.key(), key));
    }

    public static Derived derive(ContentTree tree, LocalStore store, SourceLeaf own, Options options) {
        var fact = tree.get(own.k(), id -> store.get(MachineStore.nodeKey(id)), Keys.typeKey("module-info"));
        if (fact == null) throw new IllegalStateException("Missing canonical module-info fact");
        var annotations = AnnotationLeaf.decode(store.get(MachineStore.annotationLeafKey(own.a())), tree.digest().width());
        var annotation = tree.get(annotations.annotations().hash(), id -> store.get(MachineStore.nodeKey(id)), Keys.typeKey("module-info"));
        var type = Res.Type.decode(fact.value());
        if (type.kind() != Res.Type.MODULE || type.module() == null) throw new IllegalStateException("Invalid module-info fact");
        var digest = tree.digest();
        var aci = identity(digest, fact.h(), annotation == null ? null : annotation.h(), options);
        var uses = new UsesRecord(List.of());
        var cached = store.get(LocalStore.resultKey(aci));
        if (cached != null) {
            var result = ResultRecord.decode(cached, digest.width());
            if (!result.attributed() || !result.diagnostics().isEmpty() || result.classFiles().size() != 1
                    || !result.classFiles().getFirst().internalName().equals("module-info"))
                throw new IllegalStateException("Invalid cached module descriptor result");
            var cf = result.classFiles().getFirst().contentHash();
            var bytes = store.get(LocalStore.classFileKey(cf));
            if (bytes != null && digest.hash(bytes).equals(cf))
                return new Derived(new Attribute.Computed(aci, result, null, uses, List.of()), false);
        }
        var bytes = emit(type, annotation == null ? Entry.NONE : annotation.value(), options);
        var cf = digest.hash(bytes);
        var result = new ResultRecord(true, List.of(new ResultRecord.ClassFile("module-info", cf)), List.of());
        store.put(LocalStore.classFileKey(cf), bytes);
        store.put(LocalStore.resultKey(aci), result.encode());
        return new Derived(new Attribute.Computed(aci, result, null, uses, List.of()), true);
    }

    /** Failed header results consume the source and its exact header observations; they never enter the body compiler. */
    public static Derived failed(ContentTree tree, LocalStore store, MachineLeaf own, Route route, FileRow row,
                                 HeaderDiagnostics diagnostics, Identity headerOptions) {
        if (!diagnostics.failed()) throw new IllegalArgumentException("A failed descriptor needs a native header error");
        var ranges = new ArrayList<Proof.Range>(); var absent = new ArrayList<String>();
        for (var entry : row.headerProof()) ranges.add(new Proof.Range(Proof.T, entry.typeKey(), entry.kind(), entry.name()));
        for (var entry : row.absences()) {
            if (entry.form() == 0) absent.add(entry.type());
            else ranges.add(new Proof.Range(Proof.N, entry.type(), Keys.TYPE, entry.name()));
        }
        var proof = Arrange.proof(tree, own, route, null, ranges, absent, store::get);
        var aci = tree.digest().hash(new Codec.Writer().str("module-info header error v1")
                .id(proof.aci(tree.digest(), "module-info.java", row.kappa(), headerOptions)).toBytes());
        var result = new ResultRecord(false, List.of(), diagnostics.messages());
        var uses = new UsesRecord(List.of());
        store.put(LocalStore.resultKey(aci), result.encode()); store.put(LocalStore.usesKey(aci), uses.encode());
        return new Derived(new Attribute.Computed(aci, result, proof, uses, List.of()), false);
    }

    /** Domain separated from source ACI; no source bytes, route, own leaf or unrelated javac options enter this key. */
    public static Identity identity(Digest digest, Identity fact, Identity annotation, Options options) {
        return digest.hash(new Codec.Writer().str("module-info derivation v2")
                .id(fact).optId(annotation).raw(options.encode()).toBytes());
    }

    /** javac's descriptor attribute and constant-pool insertion order, applied to the already resolved fact. */
    public static byte[] emit(Res.Type type, byte[] tail, Options options) {
        var module = type.module();
        var retained = annotations(tail);
        var pool = new Pool();
        var body = new Codec.Writer().u16(0x8000).u16(pool.reference(7, "module-info")).u16(0).u16(0).u16(0).u16(0);
        body.u16((options.sourceFile ? 2 : 1) + (module.innerClasses().isEmpty() ? 0 : 1)
                + (type.warnings().deprecatedAttribute() ? 1 : 0) + (int) retained.stream().filter(a -> !a.isEmpty()).count());
        if (options.sourceFile) body.u16(pool.utf("SourceFile")).u32(2).u16(pool.utf("module-info.java"));
        if (type.warnings().deprecatedAttribute()) body.u16(pool.utf("Deprecated")).u32(0);
        for (int i = 0; i < retained.size(); i++) if (!retained.get(i).isEmpty()) {
            int name = pool.utf(i == 0 ? "RuntimeVisibleAnnotations" : "RuntimeInvisibleAnnotations");
            var value = new Codec.Writer().u16(retained.get(i).size());
            for (var annotation : retained.get(i)) annotation(pool, value, annotation);
            body.u16(name).lenBytes(value.toBytes());
        }
        int name = pool.utf("Module");
        var value = new Codec.Writer().u16(pool.reference(19, module.name())).u16(module.flags()).u16(pool.optional(module.version()));
        value.u16(module.requires().size());
        for (var r : module.requires()) value.u16(pool.reference(19, r.module())).u16(r.flags()).u16(pool.optional(r.version()));
        directives(pool, value, module.exports()); directives(pool, value, module.opens());
        value.u16(module.uses().size());
        for (var use : module.uses()) value.u16(pool.reference(7, use));
        value.u16(module.provides().size());
        for (var provide : module.provides()) {
            value.u16(pool.reference(7, provide.service())).u16(provide.with().size());
            for (var implementation : provide.with()) value.u16(pool.reference(7, implementation));
        }
        body.u16(name).lenBytes(value.toBytes());
        if (!module.innerClasses().isEmpty()) {
            int innerName = pool.utf("InnerClasses");
            var inners = new Codec.Writer().u16(module.innerClasses().size());
            for (var inner : module.innerClasses()) inners.u16(pool.reference(7, inner.name()))
                    .u16(inner.outer() == null ? 0 : pool.reference(7, inner.outer())).u16(pool.optional(inner.simpleName())).u16(inner.flags());
            body.u16(innerName).lenBytes(inners.toBytes());
        }
        return new Codec.Writer().u32(0xcafebabeL).u16(0).u16(options.majorVersion).raw(pool.bytes()).raw(body.toBytes()).toBytes();
    }

    private static List<List<Ann>> annotations(byte[] tail) {
        if (tail.length == 0) return List.of(List.of(), List.of());
        var in = new Codec.Reader(tail); var lists = new ArrayList<List<Ann>>();
        for (int i = 0; i < 2; i++) {
            var list = new ArrayList<Ann>();
            for (int j = 0, count = in.count(); j < count; j++) list.add(Ann.decode(in));
            lists.add(List.copyOf(list));
        }
        if (in.count() != 0 || in.remaining() != 0) throw new IllegalArgumentException("Unexpected module annotation tail");
        return List.copyOf(lists);
    }

    private static void annotation(Pool pool, Codec.Writer out, Ann annotation) {
        out.u16(pool.utf(annotation.descriptor())).u16(annotation.elements().size());
        for (var element : annotation.elements()) {
            out.u16(pool.utf(element.name())); value(pool, out, element.value());
        }
    }

    private static void value(Pool pool, Codec.Writer out, Ann.Val value) {
        switch (value) {
            case Ann.Val.Prim p -> out.u8(p.tag()).u16(pool.constant(p));
            case Ann.Val.Str s -> out.u8('s').u16(pool.utf(s.value()));
            case Ann.Val.Enum e -> out.u8('e').u16(pool.utf(e.descriptor())).u16(pool.utf(e.constant()));
            case Ann.Val.Cls c -> out.u8('c').u16(pool.utf(c.descriptor()));
            case Ann.Val.Nested n -> { out.u8('@'); annotation(pool, out, n.annotation()); }
            case Ann.Val.Array a -> { out.u8('[').u16(a.values().size()); for (var v : a.values()) value(pool, out, v); }
        }
    }

    private static void directives(Pool pool, Codec.Writer out, List<Res.Directive> directives) {
        out.u16(directives.size());
        for (var directive : directives) {
            out.u16(pool.reference(20, directive.packageName())).u16(directive.flags()).u16(directive.to().size());
            for (var target : directive.to()) out.u16(pool.reference(19, target));
        }
    }

    /** javac reserves the reference before its UTF8 payload; classfile UTF8 is modified UTF8, not the tree string codec. */
    private static final class Pool {
        private record Key(int tag, Object value) { }
        private final LinkedHashMap<Key, Integer> indexes = new LinkedHashMap<>();
        private final ArrayList<byte[]> entries = new ArrayList<>();
        int optional(String text) { return text == null ? 0 : utf(text); }
        int utf(String text) { return add(new Key(1, text)); }
        int reference(int tag, String text) { return add(new Key(tag, text)); }
        int constant(Ann.Val.Prim p) {
            int tag = switch (p.tag()) { case 'F' -> 4; case 'J' -> 5; case 'D' -> 6; default -> 3; };
            return add(new Key(tag, tag == 5 || tag == 6 ? p.bits() : p.bits() & 0xffffffffL));
        }
        int add(Key key) {
            var previous = indexes.get(key); if (previous != null) return previous;
            int index = entries.size() + 1;
            boolean wide = key.tag == 5 || key.tag == 6;
            if (index + (wide ? 1 : 0) >= 65535) throw new IllegalArgumentException("Module descriptor constant pool overflow");
            indexes.put(key, index); entries.add(null);
            if (wide) entries.add(Entry.NONE);
            byte[] bytes;
            if (key.tag == 1) {
                var output = new ByteArrayOutputStream();
                try (var writer = new DataOutputStream(output)) { writer.writeByte(1); writer.writeUTF((String) key.value); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
                bytes = output.toByteArray();
            } else if (key.tag >= 3 && key.tag <= 6) {
                var out = new Codec.Writer().u8(key.tag);
                if (wide) out.u64((Long) key.value); else out.u32((Long) key.value);
                bytes = out.toBytes();
            } else bytes = new Codec.Writer().u8(key.tag).u16(utf((String) key.value)).toBytes();
            entries.set(index - 1, bytes); return index;
        }
        byte[] bytes() {
            var out = new Codec.Writer().u16(entries.size() + 1);
            for (var entry : entries) out.raw(entry);
            return out.toBytes();
        }
    }
}
