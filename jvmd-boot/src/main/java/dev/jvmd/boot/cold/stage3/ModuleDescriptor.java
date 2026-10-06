package dev.jvmd.boot.cold.stage3;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.Proof;
import dev.jvmd.index.layer.local.ResultRecord;
import dev.jvmd.index.layer.local.Route;
import dev.jvmd.index.layer.local.UsesRecord;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.Res;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** A zero-body output of the canonical module-info fact. No source task or module resolution occurs here. */
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
        byte[] encode() { return new Codec.Writer().u16(majorVersion).u8(sourceFile ? 1 : 0).toBytes(); }
    }

    public static Derived derive(ContentTree tree, LocalStore store, MachineLeaf own, Route route, Options options) {
        var fact = tree.get(own.k(), id -> store.get(MachineStore.nodeKey(id)), Keys.typeKey("module-info"));
        if (fact == null) throw new IllegalStateException("Missing canonical module-info fact");
        var type = Res.Type.decode(fact.value());
        if (type.kind() != Res.Type.MODULE || type.module() == null) throw new IllegalStateException("Invalid module-info fact");
        var digest = tree.digest();
        var aci = identity(digest, fact.h(), options);
        var proof = Arrange.proof(tree, own, route, null,
                List.of(new Proof.Range(Proof.T, "module-info", Keys.TYPE, "")), List.of(), store::get);
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
                return new Derived(new Attribute.Computed(aci, result, proof, uses, List.of()), false);
        }
        var bytes = emit(type.module(), options);
        var cf = digest.hash(bytes);
        var result = new ResultRecord(true, List.of(new ResultRecord.ClassFile("module-info", cf)), List.of());
        store.put(LocalStore.classFileKey(cf), bytes);
        store.put(LocalStore.resultKey(aci), result.encode());
        store.put(LocalStore.usesKey(aci), uses.encode());
        return new Derived(new Attribute.Computed(aci, result, proof, uses, List.of()), true);
    }

    /** Domain separated from source ACI; no source bytes, route, own leaf or unrelated javac options enter this key. */
    public static Identity identity(Digest digest, Identity fact, Options options) {
        return digest.hash(new Codec.Writer().str("module-info derivation v1").raw(Keys.typeKey("module-info"))
                .id(fact).raw(options.encode()).toBytes());
    }

    /** javac's descriptor attribute and constant-pool insertion order, applied to the already resolved fact. */
    public static byte[] emit(Res.Module module, Options options) {
        var pool = new Pool();
        var body = new Codec.Writer().u16(0x8000).u16(pool.reference(7, "module-info")).u16(0).u16(0).u16(0).u16(0);
        body.u16(options.sourceFile ? 2 : 1);
        if (options.sourceFile) body.u16(pool.utf("SourceFile")).u32(2).u16(pool.utf("module-info.java"));
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
        return new Codec.Writer().u32(0xcafebabeL).u16(0).u16(options.majorVersion).raw(pool.bytes()).raw(body.toBytes()).toBytes();
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
        private record Key(int tag, String text) { }
        private final LinkedHashMap<Key, Integer> indexes = new LinkedHashMap<>();
        private final ArrayList<byte[]> entries = new ArrayList<>();
        int optional(String text) { return text == null ? 0 : utf(text); }
        int utf(String text) { return add(new Key(1, text)); }
        int reference(int tag, String text) { return add(new Key(tag, text)); }
        int add(Key key) {
            var previous = indexes.get(key); if (previous != null) return previous;
            int index = entries.size() + 1;
            if (index >= 65535) throw new IllegalArgumentException("Module descriptor constant pool overflow");
            indexes.put(key, index); entries.add(null);
            byte[] bytes;
            if (key.tag == 1) {
                var output = new ByteArrayOutputStream();
                try (var writer = new DataOutputStream(output)) { writer.writeByte(1); writer.writeUTF(key.text); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
                bytes = output.toByteArray();
            } else bytes = new Codec.Writer().u8(key.tag).u16(utf(key.text)).toBytes();
            entries.set(index - 1, bytes); return index;
        }
        byte[] bytes() {
            var out = new Codec.Writer().u16(entries.size() + 1);
            for (var entry : entries) out.raw(entry);
            return out.toBytes();
        }
    }
}
