package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.Stage3;
import dev.jvmd.core.hash.*;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.*;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Read-only, current-tree measurement; no candidate parser or replacement tree. Outside Maven source sets. */
public final class CurrentTreeDomains {
    static final com.sun.management.ThreadMXBean ALLOC = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static volatile Object observed;
    static int checks;
    static final class Memory implements NodeSink {
        final Map<Identity,byte[]> nodes;
        long reads, readBytes, writes, writeBytes;
        Memory(Map<Identity,byte[]> nodes) { this.nodes = new HashMap<>(nodes); }
        byte[] read(Identity id) { var b = Objects.requireNonNull(nodes.get(id)); reads++; readBytes += b.length; return b.clone(); }
        public void write(Node n) { writes++; writeBytes += n.bytes().length; nodes.putIfAbsent(n.hash(), n.bytes()); }
        public void flush() { }
        void reset() { reads=readBytes=writes=writeBytes=0; }
    }
    record Sample(String domain, Root root) { }
    static void check(boolean ok) { if (!ok) throw new AssertionError(); checks++; }
    static boolean tag(byte[] k,String t) { var p=(t+"|").getBytes(java.nio.charset.StandardCharsets.US_ASCII);return k.length>=p.length && Arrays.equals(k,0,p.length,p,0,p.length); }
    static void select(Map<String,Sample> samples, String domain, Identity hash, ContentTree tree, Function<Identity,byte[]> reader) {
        var root=tree.root(hash,reader);var prev=samples.get(domain);
        if(prev==null || root.count()>prev.root.count() || (root.count()==prev.root.count() && root.hash().compareTo(prev.root.hash())<0)) samples.put(domain,new Sample(domain,root));
    }
    public static void main(String[] args) throws Exception {
        Path projectPath=Path.of(args[0]).toAbsolutePath().normalize(), output=Path.of(args[1]);
        Files.createDirectories(output);
        var profiles=new StringBuilder("digest,domain,root,N,height,key_mean,key_p99,key_max,value_max,hash_cuts,no_hash_run_max,leaf_nodes,cap_only_leaf_nodes,node_bytes,leaf_bytes_max,interior_nodes,interior_cap_nodes,interior_unary_nodes\n");
        var costs=new StringBuilder("digest,domain,N,operation,reads,read_bytes,emissions,emitted_bytes,allocated_bytes\n");
        for(Digest digest:List.of(Sha256.INSTANCE,new Digests.Sha3())) {
            System.err.println("BOOT "+digest.name());
            var repository=MavenProjectTest.repository();var model=ProjectModel.parse(MavenModelHelper.build(projectPath,repository,Stage2Support.FEATURE).json());
            var tree=new ContentTree(digest);var store=Stage2Support.jdkOnly(digest).copy();
            var headers=new Stage2(digest,tree,Stage2Support.FEATURE,4,repository,ClassFacts::of).run(store,model);
            check(headers.faults().isEmpty());
            var bodies=new Stage3(digest,tree,Stage2Support.FEATURE,4,repository).run(store,model);
            check(bodies.faults().stream().allMatch(f->f.endsWith("unsupported for reuse: retained binary metadata reads have no exact proof projection")));
            System.err.println("BOOT complete sourceFiles="+headers.sourceFiles()+" nonreuse="+bodies.faults().size());
            var records=store.snapshot();var nodes=new HashMap<Identity,byte[]>();
            for(var e:records.entrySet())if(e.getKey().length==1+digest.width() && e.getKey()[0]=='N')nodes.put(Identity.of(Arrays.copyOfRange(e.getKey(),1,e.getKey().length)),e.getValue());
            var samples=new TreeMap<String,Sample>();Function<Identity,byte[]> read=nodes::get;
            var sourceLeaves=new HashSet<>(headers.leaves().values());
            for(var e:records.entrySet()) {
                var k=e.getKey();var v=e.getValue();
                if(k.length==1+digest.width() && k[0]=='L') {
                    var leaf=MachineLeaf.decode(v,digest.width());String kind=sourceLeaves.contains(leaf.k())?"source":"binary";
                    select(samples,"T-"+kind,leaf.k(),tree,read);select(samples,"N-"+kind,leaf.nHash(),tree,read);select(samples,"O-"+kind,leaf.oHash(),tree,read);
                } else if(tag(k,"AL")) {var a=AnnotationLeaf.decode(v,digest.width());select(samples,"A",a.annotations().hash(),tree,read);select(samples,"EA",a.edges().hash(),tree,read);}
                else if(tag(k,"DD") || tag(k,"DS") || tag(k,"DC")) select(samples,new String(k,0,2,java.nio.charset.StandardCharsets.US_ASCII),DefinerIndex.decodeRoot(v,digest.width()).hash(),tree,read);
                else if(tag(k,"DF")) select(samples,"DF-all",DefinerIndex.decodeRoot(v,digest.width()).hash(),tree,read);
                else if(tag(k,"RT")) {var route=Route.decode(v,digest.width());select(samples,"leaf-set",route.leafSetExt(),tree,read);select(samples,"leaf-set",route.leafSetSib(),tree,read);}
            }
            var project=Stage2.projectKey(digest,model);var local=LocalRoot.decode(digest,store.get(LocalStore.localRootKey(project)));
            select(samples,"LOCAL",local.local().hash(),tree,read);
            select(samples,"BODIES",bodies.bodies().bodiesRoot(),tree,read);
            tree.forEach(bodies.bodies().bodiesRoot(),read,e->{
                if(tag(e.key(),"CI")) select(samples,"CI-queries",ProofIndex.decode(BodyRecords.value(tree,store,e),digest.width()).queries(),tree,read);
                if(tag(e.key(),"OUT")) select(samples,"OUT",DefinerIndex.decodeRoot(BodyRecords.value(tree,store,e),digest.width()).hash(),tree,read);
            });
            // X is currently a raw current secondary index, not a standalone ContentTree.
            // Profile its actual selected LOCAL keys as a labelled slice; do not count it as a production root.
            var x=new ArrayList<Entry>();tree.forEach(local.local().hash(),read,e->{if(tag(e.key(),"X"))x.add(e);});
            var xm=new Memory(nodes);var xr=tree.build(x,xm);nodes.putAll(xm.nodes);select(samples,"X-header-slice",xr.hash(),tree,read);
            for(var sample:samples.values()) {
                var es=new ArrayList<Entry>();tree.forEach(sample.root.hash(),read,es::add);
                profile(digest,sample,es,nodes,profiles);
                measure(digest,sample,es,nodes,costs);
            }
            Stage2Support.release();
        }
        Files.writeString(output.resolve("pr62-current-domain-profile.csv"),profiles);
        Files.writeString(output.resolve("pr62-current-domain-costs.csv"),costs);
        System.err.println("PASS checks="+checks+"; production tree unchanged; no candidate implementation");
    }
    static void profile(Digest digest,Sample sample,List<Entry> es,Map<Identity,byte[]> nodes,StringBuilder out) {
        long keyBytes=0;int cuts=0,run=0,maxRun=0,valueMax=0;int[] sizes=new int[es.size()];
        for(int i=0;i<es.size();i++) {var e=es.get(i);keyBytes+=e.key().length;sizes[i]=e.key().length;valueMax=Math.max(valueMax,e.value().length);
            if((Hash64.of(e.key())&31)==0){cuts++;run=0;}else{run++;maxRun=Math.max(maxRun,run);}}
        Arrays.sort(sizes);long[] stats=new long[7];profileNodes(sample.root.hash(),nodes,digest.width(),stats);
        out.append(String.format(Locale.ROOT,"%s,%s,%s,%d,%d,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",digest.name(),sample.domain,HexFormat.of().formatHex(sample.root.hash().view()),es.size(),sample.root.level()+1,es.isEmpty()?0:(double)keyBytes/es.size(),es.isEmpty()?0:sizes[(int)Math.floor((sizes.length-1)*.99)],es.isEmpty()?0:sizes[sizes.length-1],valueMax,cuts,maxRun,stats[0],stats[1],stats[2],stats[3],stats[4],stats[5],stats[6]));
    }
    static void profileNodes(Identity id,Map<Identity,byte[]> nodes,int width,long[] s) {
        var b=nodes.get(id);s[2]+=b.length;
        if(Node.level(b)==0) {var es=Node.entries(b,width);s[0]++;s[3]=Math.max(s[3],b.length);if(es.size()==128 && (Hash64.of(es.getLast().key())&31)!=0)s[1]++;}
        else {var cs=Node.children(b,width);s[4]++;if(cs.size()==128)s[5]++;if(cs.size()==1)s[6]++;for(var c:cs)profileNodes(c.hash(),nodes,width,s);}
    }
    static void measure(Digest digest,Sample sample,List<Entry> es,Map<Identity,byte[]> nodes,StringBuilder out) {
        var tree=new ContentTree(digest);var rebuilt=new Memory(Map.of());check(tree.build(es,rebuilt).equals(sample.root));
        if(es.isEmpty())return;
        for(String op:List.of("delete-first","delete-middle","delete-last","replace-middle","get-middle","range-half")) {
            long[] allocations=new long[3];long[] counters=null;
            for(int trial=-2;trial<3;trial++) {
                var m=new Memory(nodes);int index=op.endsWith("first")?0:op.endsWith("last")?es.size()-1:es.size()/2;
                var old=es.get(index);var replacement=new Entry(old.key(),new byte[]{42},digest.hash(new byte[]{42}));
                // Edit construction, caller hashing, store-map copy and oracle are deliberately outside counters.
                var removed=op.startsWith("delete")?List.of(old.key()):List.<byte[]>of();var added=op.startsWith("replace")?List.of(replacement):List.<Entry>of();
                m.reset();long before=ALLOC.getCurrentThreadAllocatedBytes();Root edited=null;
                if(op.startsWith("delete") || op.startsWith("replace")) observed=edited=tree.apply(sample.root,removed,added,m::read,m);
                else if(op.startsWith("get")) observed=tree.get(sample.root.hash(),m::read,old.key());
                else observed=tree.rangeSum(sample.root,m::read,es.get(es.size()/4).key(),es.get(es.size()*3/4).key());
                long alloc=ALLOC.getCurrentThreadAllocatedBytes()-before;
                if(trial>=0) {allocations[trial]=alloc;var c=new long[]{m.reads,m.readBytes,m.writes,m.writeBytes};if(counters!=null)check(Arrays.equals(counters,c));counters=c;}
                if(edited!=null) {
                    var expected=new ArrayList<>(es);if(op.startsWith("delete"))expected.remove(index);else expected.set(index,replacement);
                    check(tree.build(expected,new Memory(Map.of())).equals(edited));
                    var diff=Diff.content(digest,sample.root,edited,m::read);
                    check(diff.removed().size()==1 && Arrays.equals(diff.removed().getFirst().key(),old.key()));
                    check(diff.added().size()==(op.startsWith("delete")?0:1));
                } else if(op.startsWith("get"))check(Arrays.equals(((Entry)observed).value(),old.value()));
                else {Identity expected=tree.sums().zero();for(int i=es.size()/4;i<es.size()*3/4;i++)expected=tree.sums().add(expected,es.get(i).h());check(expected.equals(observed));}
            }
            Arrays.sort(allocations);out.append(String.format(Locale.ROOT,"%s,%s,%d,%s,%d,%d,%d,%d,%d%n",digest.name(),sample.domain,es.size(),op,counters[0],counters[1],counters[2],counters[3],allocations[1]));
        }
    }
}
