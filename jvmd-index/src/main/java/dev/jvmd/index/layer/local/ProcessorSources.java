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
    private final ProcessorDeclaration.Graph declarations;

    private ProcessorSources(ContentTree tree, Root root, Function<byte[], byte[]> records) {
        this.tree = tree; this.root = root; this.records = records;
        this.declarations=new ProcessorDeclaration.Graph(tree.digest(),id->records.apply(LocalStore.processorDeclarationKey(id)));
    }

    public static ProcessorSources load(ContentTree tree, LocalRoot local, Identity project, String module, int scope,
                                        Function<byte[], byte[]> records) {
        var key = LocalStore.processorSourcesKey(project, module, scope);
        var entry = tree.get(local.local().hash(), id -> records.apply(MachineStore.nodeKey(id)), key);
        if (entry == null) throw new IllegalStateException("Processor sources are not in the committed LOCAL tree");
        var value = RootedRecords.value(tree,records,entry);
        return new ProcessorSources(tree, DefinerIndex.decodeRoot(value, tree.digest().width()), records);
    }

    /** Bind source metadata by the first defining origin, not by a T identity shared by distinct source scopes. */
    public static Binding bind(ContentTree tree, LocalRoot committed, Identity project,
                                                                    String module, int scope, Function<byte[], byte[]> records) {
        Function<byte[], byte[]> required = key -> {
            var entry = tree.get(committed.local().hash(), id -> records.apply(MachineStore.nodeKey(id)), key);
            if (entry == null) throw new IllegalStateException("Source binding record is not in the committed LOCAL tree");
            return RootedRecords.value(tree,records,entry);
        };
        var origins=origins(tree,project,module,scope,required,records);
        var route=Route.decode(required.apply(LocalStore.routeKey(project,module,scope)),tree.digest().width());
        var own=dev.jvmd.index.layer.machine.MachineLeaf.decode(records.apply(MachineStore.leafKey(origins.getFirst().types())),tree.digest().width());
        Function<byte[],byte[]> rooted=key->BodyRecords.tag(key,"DD") || BodyRecords.tag(key,"DS") || BodyRecords.tag(key,"DC")
                ? required.apply(key):records.apply(key);
        var resolver=new DefinerIndex.Reader(tree,own,route,rooted);
        var packages=DefinerIndex.decodeRoot(required.apply(LocalStore.processorBindingKey(project,module,scope)),tree.digest().width());
        return new Binding(tree,records,origins,resolver,packages);
    }

    private static java.util.List<Origin> origins(ContentTree tree,Identity project,String module,int scope,
                                                  Function<byte[],byte[]> required,Function<byte[],byte[]> records) {
        var origins=new java.util.ArrayList<Origin>();
        var own=SourceLeaf.decode(required.apply(LocalStore.sourceLeafKey(project,module,scope)),tree.digest().width());
        origins.add(new Origin(own.k(),new ProcessorSources(tree,DefinerIndex.decodeRoot(
                required.apply(LocalStore.processorSourcesKey(project,module,scope)),tree.digest().width()),records)));
        var route=Route.decode(required.apply(LocalStore.routeKey(project,module,scope)),tree.digest().width());
        for(var entry:route.entries())switch(entry) {
            case RouteEntry.Sibling sibling -> {
                var leaf=SourceLeaf.decode(required.apply(LocalStore.sourceLeafKey(project,sibling.module(),LocalStore.MAIN)),tree.digest().width());
                origins.add(new Origin(leaf.k(),new ProcessorSources(tree,DefinerIndex.decodeRoot(
                        required.apply(LocalStore.processorSourcesKey(project,sibling.module(),LocalStore.MAIN)),tree.digest().width()),records)));
            }
            case RouteEntry.Jar jar -> {if(jar.defaultK()!=null)origins.add(new Origin(jar.defaultK(),null));}
            case RouteEntry.Jrt jrt -> origins.add(new Origin(jrt.k(),null));
        }
        return java.util.List.copyOf(origins);
    }

    /** Cold construction visits package entries only, once per exact PM root, shared across scope bindings. */
    public static Root packageIndex(ContentTree tree,Identity project,String module,int scope,Function<byte[],byte[]> records,
                                    dev.jvmd.core.tree.NodeSink sink,java.util.Map<Identity,java.util.List<Entry>> catalog) {
        var entries=new java.util.TreeMap<byte[],Entry>(java.util.Arrays::compareUnsigned);
        var origins=origins(tree,project,module,scope,records,records);
        byte[] prefix=new Codec.Writer().u8(0).lenBytes(new byte[0]).zstr("PACKAGE").toBytes();
        for(int index=0;index<origins.size();index++) {
            var origin=origins.get(index);if(origin.sources()==null)continue;
            int ordinal=index;
            for(var entry:catalog.computeIfAbsent(origin.sources().root.hash(),hash->{
                var packages=new java.util.ArrayList<Entry>();
                tree.forEach(hash,id->records.apply(MachineStore.nodeKey(id)),prefix,packages::add);
                return java.util.List.copyOf(packages);
            })) {
                var prior=entries.get(entry.key());
                if(prior!=null && new Codec.Reader(prior.value()).count()!=0)continue;
                int selected=entry.value()[0]==2?0:ordinal+1;
                var value=new Codec.Writer().u32(selected).toBytes();
                entries.put(entry.key(),new Entry(entry.key(),value,tree.digest().hash(entry.key(),value)));
            }
        }
        return tree.build(entries.values(),sink);
    }

    private record Origin(Identity types,ProcessorSources sources) { }

    /** Resolution uses exact indexes; the first occurrence of a winning k chooses its origin in this ordered binding only. */
    public static final class Binding implements Function<String,ProcessorDeclaration.Source> {
        private final ContentTree tree;
        private final Function<byte[],byte[]> records;
        private final java.util.List<Origin> origins;
        private final java.util.Map<Identity,Integer> first=new java.util.HashMap<>();
        private final DefinerIndex.Reader resolver;
        private final Root packages;
        private Binding(ContentTree tree,Function<byte[],byte[]> records,java.util.List<Origin> origins,
                        DefinerIndex.Reader resolver,Root packages) {
            this.tree=tree;this.records=records;this.origins=origins;this.resolver=resolver;this.packages=packages;
            for(int i=0;i<origins.size();i++)first.putIfAbsent(origins.get(i).types(),i);
        }
        private Integer definingOrigin(String name) {
            var leaf=resolver.definer(name);if(leaf==null)return null;
            var index=first.get(leaf.k());
            if(index==null)throw new IllegalStateException("Definer is outside the selected origin binding");
            return index;
        }
        @Override public ProcessorDeclaration.Source apply(String name) {
            var index=definingOrigin(name);if(index==null)return null;
            var origin=origins.get(index);
            if(origin.sources()==null)return null;
            var source=origin.sources().type(name);
            if(source==null)throw new IllegalStateException("Source type has no committed processor declaration: "+name);
            return source;
        }
        public ProcessorDeclaration.Source packageHeader(String qualifiedName) {
            var entry=packageEntry(qualifiedName);if(entry==null)return null;
            int ordinal=new Codec.Reader(entry.value()).count()-1;if(ordinal<0)return null;
            var binary=qualifiedName.isEmpty()?"package-info":qualifiedName.replace('.','/')+"/package-info";
            var defining=definingOrigin(binary);
            if(defining!=null && defining<ordinal)return null;
            return origins.get(ordinal).sources().packageHeader(qualifiedName);
        }
        public boolean packageExists(String qualifiedName) {return packageEntry(qualifiedName)!=null;}
        private Entry packageEntry(String qualifiedName) {
            return tree.get(packages.hash(),id->records.apply(MachineStore.nodeKey(id)),Keys.packageElementKey(qualifiedName));
        }
        public java.util.List<String> packageMembers(String qualifiedName) {return origins.getFirst().sources().packageMembers(qualifiedName);}
    }

    public Root root() { return root; }

    /** Exact binary name lookup, including nested names with literal '$'; null is a proved absence from this scope. */
    public ProcessorDeclaration.Source type(String internalName) {
        var source = declaration(Keys.typeKey(internalName), internalName, false);
        if (source != null && (!(source.declaration().detail() instanceof ProcessorDeclaration.TypeDeclaration type)
                || !type.binaryName().replace('.', '/').equals(internalName)))
            throw new IllegalStateException("Processor declaration key differs from its binary name");
        return source;
    }

    public ProcessorDeclaration.Source packageHeader(String qualifiedName) {
        var source = declaration(Keys.packageElementKey(qualifiedName), qualifiedName, true);
        if (source != null && (!(source.declaration().detail() instanceof ProcessorDeclaration.PackageHeader pkg)
                || !pkg.qualifiedName().equals(qualifiedName)))
            throw new IllegalStateException("Processor package key differs from its qualified name");
        return source;
    }

    private boolean packageExists(String qualifiedName) {
        return entry(Keys.packageElementKey(qualifiedName)) != null;
    }

    public java.util.List<String> packageMembers(String qualifiedName) {
        var entry = entry(packageMembersKey(qualifiedName));
        if (entry == null) return null;
        var in = new Codec.Reader(entry.value());
        int tag = in.u8();
        if (tag == 1) throw new Unavailable(qualifiedName, in.str(), in.str());
        if (tag != 0) throw new IllegalStateException("Invalid package member entry tag");
        var members = new java.util.ArrayList<String>();
        for (long n = in.u32(); n > 0; n--) members.add(in.str());
        if (in.remaining() != 0) throw new IllegalStateException("Trailing package member bytes");
        return java.util.List.copyOf(members);
    }

    public static byte[] packageMembersKey(String qualifiedName) {
        return Keys.processorElementKey(new byte[0], "PACKAGE_MEMBERS", qualifiedName);
    }
    public static Entry packageMembers(ContentTree tree, String qualifiedName, java.util.List<String> members) {
        var out = new Codec.Writer().u8(0).u32(members.size());
        members.forEach(out::str);
        return entry(tree, packageMembersKey(qualifiedName), out.toBytes());
    }

    private Entry entry(byte[] key) {
        var entry = tree.get(root.hash(), id -> records.apply(MachineStore.nodeKey(id)), key);
        if (entry != null && !tree.digest().hash(key, entry.value()).equals(entry.h()))
            throw new IllegalStateException("Processor declaration entry digest mismatch");
        return entry;
    }

    private ProcessorDeclaration.Source declaration(byte[] key, String name, boolean packageHeader) {
        var entry = entry(key);
        if (entry == null) return null;
        var in = new Codec.Reader(entry.value());
        int tag = in.u8();
        if (tag == 0) {
            var source = ProcessorDeclaration.decode(in.raw(in.remaining()));
            if (!java.util.Arrays.equals(source.declaration().key().bytes(), key))
                throw new IllegalStateException("Processor declaration key differs from its stored key");
            return source;
        }
        if(tag==3) {
            var path=in.zstr();var id=in.id(tree.digest().width());
            if(in.remaining()!=0)throw new IllegalStateException("Trailing processor declaration reference");
            var declaration=declarations.get(id);
            if(!java.util.Arrays.equals(declaration.key().bytes(),key))
                throw new IllegalStateException("Processor declaration key differs from its stored key");
            return new ProcessorDeclaration.Source(path,declaration);
        }
        if (tag == 1) throw new Unavailable(name, in.str(), in.str());
        if (tag == 2 && packageHeader && in.remaining() == 0) return null;
        throw new IllegalStateException("Invalid processor declaration entry tag");
    }

    /** Exact package existence, including ancestors, without claiming a package-info declaration or metadata origin. */
    public static Entry packagePresence(ContentTree tree, String qualifiedName) {
        return entry(tree, Keys.packageElementKey(qualifiedName), new byte[] {2});
    }

    public static Entry available(ContentTree tree, byte[] key, byte[] projection) {
        return entry(tree, key, new Codec.Writer().u8(0).raw(projection).toBytes());
    }
    public static Entry available(ContentTree tree,byte[] key,String path,Identity declaration) {
        return entry(tree,key,new Codec.Writer().u8(3).zstr(path).id(declaration).toBytes());
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
