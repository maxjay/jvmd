package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.*;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.*;
import java.util.function.Function;

/**
 * Persisted resolution-query index and an anchored validation receipt. Cold capture visits the proof once;
 * advance intersects a shared exact binding transition with the index without decoding the flat proof.
 * Processor admission is separate and still requires freshly verified current observations.
 */
public final class ProofIndex {
    public record Inputs(String basename, Identity source, Identity options, String compiler) {
        public Inputs(String basename, Identity source, Identity options) {
            this(basename, source, options, Runtime.version().toString());
        }
        void encode(Codec.Writer out) { out.str(basename).id(source).id(options).str(compiler); }
        static Inputs decode(Codec.Reader in, int width) { return new Inputs(in.str(), in.id(width), in.id(width), in.str()); }
    }

    /** Exact acceleration coordinates, never included in ACI. */
    public record Binding(Identity own, Identity route, Identity dd, Identity ds, Identity dc) {
        public static Binding capture(ContentTree tree, MachineLeaf own, Route route, Function<byte[],byte[]> records) {
            var read = new DefinerIndex.Reader(tree, own, route, records);
            return new Binding(own.k(), route.routeHash(), read.external().hash(), read.sibling().hash(), read.conflicts().hash());
        }
        void encode(Codec.Writer out) { out.id(own).id(route).id(dd).id(ds).id(dc); }
        static Binding decode(Codec.Reader in, int width) {
            return new Binding(in.id(width), in.id(width), in.id(width), in.id(width), in.id(width));
        }
    }

    private final Binding binding;
    private final Inputs inputs;
    private final Identity queries, aci;
    private final ProcessorRecords.Context processor;
    private final ProcessorRecords.Body body;

    private ProofIndex(Binding binding, Inputs inputs, Identity queries, Identity aci,
                       ProcessorRecords.Context processor, ProcessorRecords.Body body) {
        this.binding=binding; this.inputs=inputs; this.queries=queries; this.aci=aci; this.processor=processor; this.body=body;
    }
    public Binding binding() { return binding; }
    public Identity queries() { return queries; }
    public Identity aci() { return aci; }
    public Inputs inputs() { return inputs; }
    @Override public boolean equals(Object other) {
        return other instanceof ProofIndex that && binding.equals(that.binding) && inputs.equals(that.inputs)
                && queries.equals(that.queries) && aci.equals(that.aci)
                && Objects.equals(processor,that.processor) && Objects.equals(body,that.body);
    }
    @Override public int hashCode() { return Objects.hash(binding,inputs,queries,aci,processor,body); }

    public static ProofIndex capture(ContentTree tree, MachineLeaf own, Route route, Proof proof, Inputs inputs,
                                     Function<byte[],byte[]> records, NodeSink sink) {
        if (!proof.reusable() || proof.processorBody()!=null && !proof.processorBody().reusable())
            throw new IllegalArgumentException("Unsupported proof cannot have a reusable index");
        var read = new DefinerIndex.Reader(tree, own, route, records);
        var h=proof.header();
        if (!h.routeHash().equals(route.routeHash()) || !h.ownR().equals(own.r())
                || !h.ddSum().equals(read.external().sum()) || !h.dsSum().equals(read.sibling().sum())
                || !h.dcSum().equals(read.conflicts().sum()))
            throw new IllegalArgumentException("Proof was not captured against this binding");
        var entries = new TreeMap<byte[],Entry>(Arrays::compareUnsigned);
        for (var type:proof.types()) for (var entry:type.entries()) {
            var range=entry.range();
            add(tree,entries,new ReverseIndex.Dependency(range.form(),range.type(),range.kind(),range.name()),entry.sum());
        }
        for (var type:proof.absent()) add(tree,entries,new ReverseIndex.Dependency(ReverseIndex.D,type,Keys.TYPE,""),tree.sums().zero());
        var root=tree.build(entries.values(),sink); sink.flush();
        var binding=new Binding(own.k(),route.routeHash(),read.external().hash(),read.sibling().hash(),read.conflicts().hash());
        return new ProofIndex(binding,inputs,root.hash(),proof.aci(tree.digest(),inputs.basename(),inputs.source(),inputs.options(),inputs.compiler()),
                h.processor(),proof.processorBody());
    }
    private static byte[] key(ReverseIndex.Dependency q) {
        return new Codec.Writer().u8(q.form()).zstr(q.type()).u8(q.kind()).zstr(q.name()).toBytes();
    }
    private static void add(ContentTree tree, Map<byte[],Entry> entries, ReverseIndex.Dependency query, Identity sum) {
        var key=key(query); entries.put(key,new Entry(key,sum.bytes(),tree.digest().hash(key,sum.view())));
    }

    public byte[] encode() {
        var out=new Codec.Writer().u8(1); binding.encode(out); inputs.encode(out);
        out.id(queries).id(aci).u8(processor==null?0:1);
        if(processor!=null) { processor.encode(out); body.encode(out); }
        return out.toBytes();
    }
    public static ProofIndex decode(byte[] bytes,int width) {
        var in=new Codec.Reader(bytes);
        if(in.u8()!=1)throw new IllegalArgumentException("Unknown indexed proof format");
        var binding=Binding.decode(in,width);var inputs=Inputs.decode(in,width);var queries=in.id(width);var aci=in.id(width);
        int present=in.u8(); if(present>1)throw new IllegalArgumentException("Invalid processor presence");
        var processor=present==0?null:ProcessorRecords.Context.decode(in,width);
        var body=present==0?null:ProcessorRecords.Body.decode(in);
        if(in.remaining()!=0)throw new IllegalArgumentException("Trailing indexed proof bytes");
        return new ProofIndex(binding,inputs,queries,aci,processor,body);
    }

    /** Null means invalid. A returned receipt may be persisted at the new binding without rewriting its query tree or ACI. */
    public ProofIndex advance(Transition transition, Inputs current, ProcessorRecords.Context context, ProcessorRecords.Body observations) {
        if(!binding.equals(transition.before))throw new IllegalArgumentException("Transition does not start at the proof's validated binding");
        if(!inputs.equals(current) || !current.compiler().equals(Runtime.version().toString())
                || !Proof.processorValid(processor,body,context,observations,transition.records))return null;
        // Missing providers invalidate even expected-zero T/N questions whose spelling has no fact in either delta.
        for(var owner:transition.removedTypes)for(int form:new int[]{Proof.T,Proof.N}) {
            var prefix=new Codec.Writer().u8(form).zstr(owner).toBytes();
            boolean[] found={false};transition.work.proofQueries++;
            transition.tree.forEach(queries,id->{
                var bytes=transition.records.apply(MachineStore.nodeKey(id));
                transition.work.proofNodeReads++;transition.work.proofNodeBytes+=bytes.length;return bytes;
            },prefix,e->{found[0]=true;transition.work.proofEntries++;});
            if(found[0])return null;
        }
        for(var query:transition.changed) {
            transition.work.proofQueries++;
            var entry=transition.tree.get(queries,id->{
                var bytes=transition.records.apply(MachineStore.nodeKey(id));
                transition.work.proofNodeReads++;transition.work.proofNodeBytes+=bytes.length;return bytes;
            },key(query));
            if(entry==null)continue;
            transition.work.proofEntries++;
            if(!Identity.of(entry.value()).equals(transition.answer(query)))return null;
        }
        return new ProofIndex(transition.after,inputs,queries,aci,processor,body);
    }

    /** Counts include frontier preparation, current answer reads and proof intersection; no proof inventory scan is hidden. */
    public static final class Work {
        public long recordReads, recordBytes, nodeReads, nodeBytes, leafPairs, changedQueries;
        public long proofQueries, proofEntries, proofNodeReads, proofNodeBytes;
    }

    /**
     * A transition is derived from exact stored roots, not a caller-supplied possibly incomplete delta.
     * Same-position route substitutions compare leaf T/N trees. Other ordered edits conservatively name
     * owners in the moved/added/removed leaves. Exact effective answers then select the query frontier.
     * Preparation is once per binding transition, independently of every consumer's proof size.
     */
    public static final class Transition {
        private final ContentTree tree;
        private final Binding before,after;
        private final Function<byte[],byte[]> records;
        private final Function<Identity,byte[]> nodes;
        private final Work work;
        private final Map<Identity,MachineLeaf> leaves=new HashMap<>();
        private final Map<Identity,Root> roots=new HashMap<>();
        private final Set<ReverseIndex.Dependency> changed=new TreeSet<>();
        private final Set<String> removedTypes=new TreeSet<>();
        private final Map<ReverseIndex.Dependency,Identity> answers=new HashMap<>();
        private final View oldView,newView;
        private record Pair(Identity oldLeaf,Identity newLeaf) { }
        private final Map<Pair,Set<ReverseIndex.Dependency>> pairs=new HashMap<>();

        private Transition(ContentTree tree,Binding before,Binding after,Function<byte[],byte[]> records,Work work) {
            this.tree=tree;this.before=before;this.after=after;this.work=work;
            this.records=records;
            this.nodes=id->{
                var bytes=record(MachineStore.nodeKey(id));work.nodeReads++;work.nodeBytes+=bytes.length;return bytes;
            };
            oldView=new View(before);newView=new View(after);
            if(before.own().equals(after.own()) && before.route().equals(after.route()))return;
            var candidates=new TreeSet<ReverseIndex.Dependency>();
            candidates.addAll(difference(before.own(),after.own()));
            var owners=new TreeSet<String>();
            if(!before.route().equals(after.route())) {
                var routeDelta=Diff.lists(tree.digest(),listRoot(before.route()),listRoot(after.route()),nodes);
                boolean substitutions=routeDelta.removed().size()==routeDelta.added().size();
                for(int i=0;substitutions && i<routeDelta.removed().size();i++)
                    substitutions=routeDelta.removed().get(i).position()==routeDelta.added().get(i).position();
                if(substitutions) {
                    for(int i=0;i<routeDelta.removed().size();i++)
                        candidates.addAll(difference(Identity.of(routeDelta.removed().get(i).element().key()),
                                Identity.of(routeDelta.added().get(i).element().key())));
                } else {
                    var moved=new HashSet<Identity>();
                    for(var entries:List.of(routeDelta.removed(),routeDelta.added()))
                        for(var entry:entries)moved.add(Identity.of(entry.element().key()));
                    for(var k:moved)tree.forEach(leaf(k).oHash(),nodes,e->owners.add(Keys.ownerOf(e.key())));
                }
            }
            // Changed owner/presence can expose another provider; N's owner may be unchanged in T.
            for(var q:candidates)owners.add(q.type());
            for(var owner:owners) {
                var a=oldView.definer(owner);var b=newView.definer(owner);
                if(a!=null && b==null)removedTypes.add(owner);
                if(Objects.equals(a,b))continue;
                for(var q:difference(a,b))if(q.type().equals(owner))candidates.add(q);
            }
            // Remove shadowed changes and same-answer cancellations once, before touching any proof.
            for(var q:candidates) {
                var old=oldView.answer(q);var next=newView.answer(q);
                if(!Objects.equals(old,next)) {changed.add(q);answers.put(q,next);}
            }
            work.changedQueries+=changed.size();
        }
        public static Transition between(ContentTree tree,Binding before,Binding after,Function<byte[],byte[]> records,Work work) {
            return new Transition(tree,before,after,records,Objects.requireNonNull(work));
        }
        public Binding before() {return before;}
        public Binding after() {return after;}
        public Set<ReverseIndex.Dependency> changed() {return Collections.unmodifiableSet(changed);}
        public Set<String> removedTypes() {return Collections.unmodifiableSet(removedTypes);}
        private byte[] record(byte[] key) {
            var bytes=records.apply(key);
            if(bytes==null)throw new IllegalStateException("Missing immutable binding record");
            work.recordReads++;work.recordBytes+=bytes.length;return bytes;
        }
        private MachineLeaf leaf(Identity k) {
            return leaves.computeIfAbsent(k,id->MachineLeaf.decode(record(MachineStore.leafKey(id)),tree.digest().width()));
        }
        private Root root(Identity hash) {return roots.computeIfAbsent(hash,h->tree.root(h,nodes));}
        private Root listRoot(Identity hash) {
            var bytes=nodes.apply(hash);int level=Node.level(bytes),count=0;var sum=tree.sums().zero();
            if(level==0)for(var e:Node.elements(bytes,tree.digest().width())) {count++;sum=tree.sums().add(sum,e.h());}
            else for(var c:Node.children(bytes,tree.digest().width())) {count=Math.addExact(count,c.count());sum=tree.sums().add(sum,c.sum());}
            return new Root(hash,sum,count,level);
        }
        private Diff.Result diff(Identity a,Identity b) {
            if(Objects.equals(a,b))return new Diff.Result(List.of(),List.of());
            if(a!=null && b!=null)return Diff.trees(tree.digest(),root(a),root(b),nodes);
            var entries=new ArrayList<Entry>();tree.forEach(a==null?b:a,nodes,entries::add);
            return a==null?new Diff.Result(List.of(),entries):new Diff.Result(entries,List.of());
        }
        private Set<ReverseIndex.Dependency> difference(Identity a,Identity b) {
            if(Objects.equals(a,b))return Set.of();
            return pairs.computeIfAbsent(new Pair(a,b),ignored->{
                work.leafPairs++;
                var old=a==null?null:leaf(a);var next=b==null?null:leaf(b);
                var out=new TreeSet<ReverseIndex.Dependency>();
                var t=diff(a,b);
                for(var entries:List.of(t.removed(),t.added()))for(var e:entries) {
                    var m=Keys.Member.decode(e.key());
                    out.add(new ReverseIndex.Dependency(ReverseIndex.T,m.owner(),m.kind(),m.name()));
                    if(m.kind()!=Keys.TYPE)out.add(new ReverseIndex.Dependency(ReverseIndex.T,m.owner(),m.kind(),""));
                    // Only TYPE header insertion/removal can change effective type presence.
                    if(m.kind()==Keys.TYPE)out.add(new ReverseIndex.Dependency(ReverseIndex.D,m.owner(),Keys.TYPE,""));
                }
                var n=diff(old==null?null:old.nHash(),next==null?null:next.nHash());
                for(var entries:List.of(n.removed(),n.added()))for(var e:entries) {
                    var in=new Codec.Reader(e.key());String name=in.zstr();
                    if(in.u8()==Keys.TYPE) {
                        String outer=in.zstr();
                        if(!outer.isEmpty())out.add(new ReverseIndex.Dependency(ReverseIndex.N,outer,Keys.TYPE,name));
                    }
                }
                return out;
            });
        }
        private Identity answer(ReverseIndex.Dependency q) {return answers.get(q);}
        private final class View {
            final Binding binding;
            final Map<String,Identity> definers=new HashMap<>();
            final Map<ReverseIndex.Dependency,Identity> values=new HashMap<>();
            View(Binding binding) {this.binding=binding;}
            Identity definer(String owner) {
                if(definers.containsKey(owner))return definers.get(owner);
                var key=Keys.ownerKey(owner);Identity k=null;
                if(tree.get(leaf(binding.own()).oHash(),nodes,key)!=null)k=binding.own();
                else for(var root:List.of(binding.dc(),binding.ds(),binding.dd())) {
                    var e=tree.get(root,nodes,key);
                    if(e!=null) {k=new Codec.Reader(e.value()).id(tree.digest().width());break;}
                }
                definers.put(owner,k);return k;
            }
            Identity answer(ReverseIndex.Dependency q) {
                if(values.containsKey(q))return values.get(q);
                var k=definer(q.type());Identity result;
                // Null is presence for D, and a missing provider for T/N. A persisted D expects zero.
                if(q.form()==ReverseIndex.D)result=k==null?tree.sums().zero():null;
                else if(k==null)result=null;
                else result=tree.rangeSum(q.form()==ReverseIndex.T?k:leaf(k).nHash(),nodes,
                        q.form()==ReverseIndex.T?Keys.groupKey(q.type(),q.kind(),q.name()):Keys.memberTypesKey(q.type(),q.name()));
                values.put(q,result);return result;
            }
        }
    }
}
