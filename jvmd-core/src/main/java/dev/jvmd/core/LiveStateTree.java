package dev.jvmd.core;

import dev.jvmd.core.tree.Aggregate;
import dev.jvmd.core.tree.KeyedTree;
import java.nio.file.Path;
import java.util.*;

/**
 * Live source-state identity of a set of source roots. Each directory is a Merkle node over a
 * {@link KeyedTree} of its children, and carries one {@link Aggregate} per projection (membership,
 * content, api, namespace) over the files beneath it. A file mutation updates the aggregates and
 * the Merkle hashes on its path only; readers consume the maintained identities.
 */
public final class LiveStateTree {
    private static final Fingerprint PRESENT=fingerprint("membership-present-v1","present");
    public static final Fingerprint UNKNOWN=fingerprint("semantic-unknown-v1","unknown");
    public static final Fingerprint UNATTRIBUTED_CONTENT=fingerprint("semantic-content-unattributed-v1","unknown");
    private static final KeyedTree.Spec<String,Fingerprint> CHILDREN=new KeyedTree.StringKeys<>("live-state-children-v1"){
        @Override public byte[] encodeValue(Fingerprint value){return value.value().getBytes(java.nio.charset.StandardCharsets.UTF_8);}
        @Override public Fingerprint decodeValue(byte[] bytes){return new Fingerprint(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));}
        @Override public Hash256 identity(Fingerprint value){return Hash256.fromHex(value.value());}
    };

    public record Fingerprint(String value) {
        public Fingerprint { Objects.requireNonNull(value); }
    }
    public record AggregateIdentity(Fingerprint fingerprint,long cardinality) {
        public AggregateIdentity { Objects.requireNonNull(fingerprint);if(cardinality<0)throw new IllegalArgumentException("cardinality"); }
    }
    /**
     * API/namespace are the last accepted semantic identities. semanticContent records the exact
     * source content from which they were derived; semanticsCurrent() is the proof boundary.
     */
    public record Leaf(Path path,Fingerprint content,Fingerprint api,Fingerprint namespace,Fingerprint semanticContent) {
        public Leaf {
            path=normalize(Objects.requireNonNull(path));
            Objects.requireNonNull(content);Objects.requireNonNull(api);Objects.requireNonNull(namespace);Objects.requireNonNull(semanticContent);
        }
        /** Convenience for already-attributed leaves. */
        public Leaf(Path path,Fingerprint content,Fingerprint api,Fingerprint namespace){this(path,content,api,namespace,content);}
        public Leaf(Path path,String content,String api,String namespace){
            this(path,new Fingerprint(content),new Fingerprint(api),new Fingerprint(namespace));
        }
        public boolean semanticsCurrent(){return content.equals(semanticContent);}
    }
    /**
     * api()/namespace() are accepted aggregates; they are proven current for all source content
     * only when semanticsCurrent() is true.
     */
    public record State(Fingerprint merkle,AggregateIdentity membership,AggregateIdentity content,
                        AggregateIdentity api,AggregateIdentity namespace,long epoch,int files,int pendingSemanticFiles) {
        public State {
            Objects.requireNonNull(merkle);Objects.requireNonNull(membership);Objects.requireNonNull(content);
            Objects.requireNonNull(api);Objects.requireNonNull(namespace);
            if(epoch<0||files<0||pendingSemanticFiles<0)throw new IllegalArgumentException("negative state");
        }
        public boolean semanticsCurrent(){return pendingSemanticFiles==0;}
    }
    public enum Domain { MEMBERSHIP, CONTENT, API, NAMESPACE, MERKLE }
    public record Transition(State before,State after,Set<Domain> changed) {
        public Transition { changed=Set.copyOf(changed); }
        public boolean changed(Domain domain){return changed.contains(domain);}
    }

    private final List<Path> roots;
    private final Node workspace=new Node("workspace",null,true);
    private final Map<Path,Leaf> leaves=new HashMap<>();
    private long epoch;

    public LiveStateTree(Collection<Path> sourceRoots) {
        var normalized=sourceRoots.stream().map(LiveStateTree::normalize).distinct().sorted(Comparator.comparing(Path::toString)).toList();
        roots=List.copyOf(normalized);
        for(Path root:roots){
            String key=rootKey(root);var child=new Node(root.toString(),key,true);
            workspace.directories.put(key,child);workspace.putChild(key,child.merkle);
        }
        workspace.recompute();
    }

    public synchronized List<Path> roots(){return roots;}
    public synchronized State state(){return workspace.state();}
    /** Expensive membership materialization for reconciliation/persistence boundaries, never request identity. */
    public synchronized Set<Path> paths(){return Set.copyOf(leaves.keySet());}
    /** Record an observed transition whose intermediate content may be unavailable (for example watcher overflow). */
    public synchronized State uncertainTransition(){long next=++epoch;markEpoch(workspace,next);return state();}
    public synchronized Optional<State> branch(Path directory){
        directory=normalize(directory);Path root=rootFor(directory);
        if(root==null)return Optional.empty();
        Node node=workspace.directories.get(rootKey(root));
        if(directory.equals(root))return Optional.of(node.state());
        Path relative=root.relativize(directory);
        for(Path part:relative){
            node=node.directories.get(directoryKey(part.toString()));
            if(node==null)return Optional.empty();
        }
        return Optional.of(node.state());
    }
    public synchronized Optional<Leaf> leaf(Path file){return Optional.ofNullable(leaves.get(normalize(file)));}

    public synchronized Transition put(Leaf next){
        Objects.requireNonNull(next);Path file=next.path();Path root=requireRoot(file);
        Leaf beforeLeaf=leaves.get(file);State before=state();
        if(next.equals(beforeLeaf))return new Transition(before,before,Set.of());
        long nextEpoch=++epoch;
        var chain=ensureChain(root,file.getParent());
        var changed=domains(beforeLeaf,next);
        updateAggregates(chain,beforeLeaf,next,nextEpoch);
        Node parent=chain.get(chain.size()-1);
        parent.putChild(fileKey(file),leafMerkle(next));parent.recompute();
        propagate(chain,false);
        leaves.put(file,next);
        return new Transition(before,state(),changed);
    }

    public synchronized Transition remove(Path requested){
        Path file=normalize(requested);Leaf old=leaves.get(file);State before=state();
        if(old==null)return new Transition(before,before,Set.of());
        Path root=requireRoot(file);var chain=findChain(root,file.getParent());
        if(chain==null)throw new IllegalStateException("Missing live-state branch for "+file);
        long nextEpoch=++epoch;
        updateAggregates(chain,old,null,nextEpoch);
        Node parent=chain.get(chain.size()-1);parent.removeChild(fileKey(file));parent.recompute();
        propagate(chain,true);leaves.remove(file);
        return new Transition(before,state(),domains(old,null));
    }

    /** Canonical source leaf from the existing source/API/exported-name semantic meanings. */
    public static Leaf source(Path path,String contentFingerprint,String apiFingerprint,Collection<String> exportedNames){
        var content=new Fingerprint(contentFingerprint);
        return new Leaf(path,content,new Fingerprint(apiFingerprint),namespace(exportedNames),content);
    }
    /** Source content observed before semantic attribution catches up. */
    public static Leaf unattributed(Path path,String contentFingerprint,Fingerprint acceptedApi,Fingerprint acceptedNamespace,Fingerprint semanticContent){
        return new Leaf(path,new Fingerprint(contentFingerprint),acceptedApi,acceptedNamespace,semanticContent);
    }

    public static Fingerprint namespace(Collection<String> exportedNames){
        var names=exportedNames==null?List.<String>of():exportedNames.stream().filter(Objects::nonNull).distinct().sorted().toList();
        return fingerprint("namespace-v1",names.toArray());
    }

    private List<Node> ensureChain(Path root,Path parentDirectory){
        var chain=new ArrayList<Node>();chain.add(workspace);
        Node current=workspace.directories.get(rootKey(root));chain.add(current);
        Path cursor=root;
        if(parentDirectory!=null&&!parentDirectory.equals(root))for(Path part:root.relativize(parentDirectory)){
            cursor=cursor.resolve(part);String key=directoryKey(part.toString());
            Node next=current.directories.get(key);
            if(next==null){next=new Node(cursor.toString(),key,false);current.directories.put(key,next);}
            current=next;chain.add(current);
        }
        return chain;
    }
    private List<Node> findChain(Path root,Path parentDirectory){
        var chain=new ArrayList<Node>();chain.add(workspace);
        Node current=workspace.directories.get(rootKey(root));if(current==null)return null;chain.add(current);
        if(parentDirectory!=null&&!parentDirectory.equals(root))for(Path part:root.relativize(parentDirectory)){
            current=current.directories.get(directoryKey(part.toString()));if(current==null)return null;chain.add(current);
        }
        return chain;
    }
    private void propagate(List<Node> chain,boolean prune){
        for(int i=chain.size()-1;i>0;i--){
            Node child=chain.get(i),parent=chain.get(i-1);
            if(prune&&!child.sourceRoot&&child.files==0){
                parent.directories.remove(child.keyInParent);parent.removeChild(child.keyInParent);
            }else parent.putChild(child.keyInParent,child.merkle);
            parent.recompute();
        }
    }
    private static void updateAggregates(List<Node> chain,Leaf old,Leaf next,long epoch){
        boolean membership=old==null||next==null;
        boolean content=old==null||next==null||!old.content().equals(next.content());
        boolean api=old==null||next==null||!old.api().equals(next.api());
        boolean namespace=old==null||next==null||!old.namespace().equals(next.namespace());
        int fileDelta=old==null?1:next==null?-1:0;
        int pendingDelta=(next!=null&&!next.semanticsCurrent()?1:0)-(old!=null&&!old.semanticsCurrent()?1:0);
        for(Node node:chain){
            if(membership)node.membership=replace(node.membership,old,old==null?null:PRESENT,next,next==null?null:PRESENT);
            if(content)node.content=replace(node.content,old,old==null?null:old.content(),next,next==null?null:next.content());
            if(api)node.api=replace(node.api,old,old==null?null:old.api(),next,next==null?null:next.api());
            if(namespace)node.namespace=replace(node.namespace,old,old==null?null:old.namespace(),next,next==null?null:next.namespace());
            node.files+=fileDelta;node.pendingSemanticFiles+=pendingDelta;node.epoch=epoch;
        }
    }
    private static Aggregate replace(Aggregate aggregate,Leaf old,Fingerprint oldValue,Leaf next,Fingerprint nextValue){
        return aggregate.replace(old==null?null:old.path(),oldValue==null?null:oldValue.value(),next==null?null:next.path(),nextValue==null?null:nextValue.value());
    }
    private static void markEpoch(Node node,long value){node.epoch=value;for(Node child:node.directories.values())markEpoch(child,value);}
    private static Set<Domain> domains(Leaf old,Leaf next){
        var changed=EnumSet.of(Domain.MERKLE);
        if(old==null||next==null)changed.add(Domain.MEMBERSHIP);
        if(old==null||next==null||!old.content().equals(next.content()))changed.add(Domain.CONTENT);
        if(old==null||next==null||!old.api().equals(next.api()))changed.add(Domain.API);
        if(old==null||next==null||!old.namespace().equals(next.namespace()))changed.add(Domain.NAMESPACE);
        return changed;
    }
    private Path requireRoot(Path file){Path root=rootFor(file);if(root==null)throw new IllegalArgumentException("Source outside roots: "+file);return root;}
    private Path rootFor(Path value){
        return roots.stream().filter(value::startsWith).max(Comparator.comparingInt(Path::getNameCount)).orElse(null);
    }
    private static String rootKey(Path root){return "R|"+root;}
    private static String directoryKey(String name){return "D|"+name;}
    private static String fileKey(Path file){return "F|"+file.getFileName();}
    private static Path normalize(Path path){return path.toAbsolutePath().normalize();}
    private static Fingerprint leafMerkle(Leaf leaf){
        return fingerprint("state-leaf-v2",leaf.path(),leaf.content().value(),leaf.api().value(),leaf.namespace().value(),leaf.semanticContent().value());
    }

    private static final class Node {
        final String id;final String keyInParent;final boolean sourceRoot;
        final Map<String,Node> directories=new HashMap<>();
        KeyedTree<String,Fingerprint> children=KeyedTree.empty(CHILDREN);
        Aggregate membership=Aggregate.empty("membership"),content=Aggregate.empty("content"),api=Aggregate.empty("api"),namespace=Aggregate.empty("namespace");
        Fingerprint merkle;long epoch;int files,pendingSemanticFiles;
        Node(String id,String keyInParent,boolean sourceRoot){
            this.id=id;this.keyInParent=keyInParent;this.sourceRoot=sourceRoot;recompute();
        }
        void putChild(String key,Fingerprint value){children=children.put(key,value);}
        void removeChild(String key){children=children.remove(key);}
        void recompute(){merkle=fingerprint("state-node-v1",id,children.rootHash().hex());}
        State state(){return new State(merkle,identity(membership),identity(content),identity(api),identity(namespace),epoch,files,pendingSemanticFiles);}
        private static AggregateIdentity identity(Aggregate aggregate){
            return new AggregateIdentity(new Fingerprint(aggregate.identity().hex()),aggregate.cardinality());
        }
    }

    private static Fingerprint fingerprint(String domain,Object... parts){return new Fingerprint(CanonicalDigestWriter.digest(domain,parts).hex());}
}
