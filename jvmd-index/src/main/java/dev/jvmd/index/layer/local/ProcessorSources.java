package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.function.Function;

/** A scope's source declarations, reached through the committed LOCAL root independently of its T/A identities. */
public final class ProcessorSources {
    private final ContentTree tree;
    private final Root root;
    private final Function<byte[], byte[]> records;

    private ProcessorSources(ContentTree tree, Root root, Function<byte[], byte[]> records) {
        this.tree = tree; this.root = root; this.records = records;
    }

    public static ProcessorSources load(ContentTree tree, LocalRoot local, Identity project, String module, int scope,
                                        Function<byte[], byte[]> records) {
        var key = LocalStore.processorSourcesKey(project, module, scope);
        var entry = tree.get(local.local().hash(), id -> records.apply(MachineStore.nodeKey(id)), key);
        if (entry == null) throw new IllegalStateException("Processor sources are not in the committed LOCAL tree");
        var value = records.apply(key);
        if (value == null || !tree.digest().hash(value).equals(entry.h()))
            throw new IllegalStateException("Processor sources differ from the committed LOCAL tree");
        return new ProcessorSources(tree, DefinerIndex.decodeRoot(value, tree.digest().width()), records);
    }

    /** Bind source metadata by the first defining origin, not by a T identity shared by distinct source scopes. */
    public static Function<String, ProcessorDeclaration.Source> bind(ContentTree tree, LocalRoot committed, Identity project,
                                                                    String module, int scope, Function<byte[], byte[]> records) {
        Function<byte[], byte[]> required = key -> {
            var entry = tree.get(committed.local().hash(), id -> records.apply(MachineStore.nodeKey(id)), key);
            if (entry == null) throw new IllegalStateException("Source binding record is not in the committed LOCAL tree");
            var value = records.apply(key);
            if (value == null || !tree.digest().hash(value).equals(entry.h()))
                throw new IllegalStateException("Source binding record differs from the committed LOCAL tree");
            return value;
        };
        record Origin(Identity types, ProcessorSources sources) { }
        var origins = new java.util.ArrayList<Origin>();
        var own = SourceLeaf.decode(required.apply(LocalStore.sourceLeafKey(project, module, scope)), tree.digest().width());
        origins.add(new Origin(own.k(), ProcessorSources.load(tree, committed, project, module, scope, records)));
        var route = Route.decode(required.apply(LocalStore.routeKey(project, module, scope)), tree.digest().width());
        for (var entry : route.entries()) switch (entry) {
            case RouteEntry.Sibling sibling -> {
                var leaf = SourceLeaf.decode(required.apply(LocalStore.sourceLeafKey(project, sibling.module(), LocalStore.MAIN)), tree.digest().width());
                origins.add(new Origin(leaf.k(), ProcessorSources.load(tree, committed, project, sibling.module(), LocalStore.MAIN, records)));
            }
            case RouteEntry.Jar jar -> { if (jar.defaultK() != null) origins.add(new Origin(jar.defaultK(), null)); }
            case RouteEntry.Jrt jrt -> origins.add(new Origin(jrt.k(), null));
        }
        return name -> {
            var key = dev.jvmd.index.layer.machine.Keys.typeKey(name);
            for (var origin : origins) if (tree.get(origin.types(), id -> records.apply(MachineStore.nodeKey(id)), key) != null) {
                if (origin.sources() == null) return null;
                var source = origin.sources().type(name);
                if (source == null) throw new IllegalStateException("Source type has no committed processor declaration: " + name);
                return source;
            }
            return null;
        };
    }

    public Root root() { return root; }

    /** Exact binary name lookup, including nested names with literal '$'; null is a proved absence from this scope. */
    public ProcessorDeclaration.Source type(String internalName) {
        var key = Keys.typeKey(internalName);
        var entry = tree.get(root.hash(), id -> records.apply(MachineStore.nodeKey(id)), key);
        if (entry == null) return null;
        if (!tree.digest().hash(key, entry.value()).equals(entry.h()))
            throw new IllegalStateException("Processor declaration entry digest mismatch");
        var in = new Codec.Reader(entry.value());
        int tag = in.u8();
        if (tag == 0) {
            var source = ProcessorDeclaration.decode(in.raw(in.remaining()));
            if (!(source.declaration().detail() instanceof ProcessorDeclaration.TypeDeclaration type)
                    || !type.binaryName().replace('.', '/').equals(internalName)
                    || !java.util.Arrays.equals(source.declaration().key().bytes(), key))
                throw new IllegalStateException("Processor declaration key differs from its binary name");
            return source;
        }
        if (tag == 1) throw new Unavailable(internalName, in.str(), in.str());
        throw new IllegalStateException("Invalid processor declaration entry tag");
    }

    public static Entry available(ContentTree tree, byte[] key, byte[] projection) {
        return entry(tree, key, new Codec.Writer().u8(0).raw(projection).toBytes());
    }
    public static Entry unavailable(ContentTree tree, byte[] key, String path, String reason) {
        return entry(tree, key, new Codec.Writer().u8(1).str(path).str(reason).toBytes());
    }
    private static Entry entry(ContentTree tree, byte[] key, byte[] value) { return new Entry(key, value, tree.digest().hash(key, value)); }

    /** A source declaration fault is not absence and must not fall through to a binary stub or an older source model. */
    public static final class Unavailable extends IllegalStateException {
        public Unavailable(String type, String path, String reason) { super("Processor declaration unavailable: " + type + " at " + path + ": " + reason); }
    }
}
