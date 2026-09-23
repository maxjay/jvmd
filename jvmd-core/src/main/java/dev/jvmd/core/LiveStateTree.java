package dev.jvmd.core;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Canonical live source-state identity. Mutations update compact semantic aggregates and a
 * deterministic Merkle hierarchy; readers consume already-maintained identities.
 *
 * <p>The algebraic accumulator is deliberately not XOR. Each path-bound contribution is a
 * domain-separated SHA-256 value interpreted in the secp256k1 prime field. Aggregates retain
 * both the modular sum and cardinality, then hash those fixed-size values into the exposed
 * identity. Controlled modular cancellation would require controlling SHA-256 field values;
 * the independent Merkle identity remains the authoritative structural identity.
 */
public final class LiveStateTree {
    private static final BigInteger FIELD=new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F",16);
    private static final Fingerprint PRESENT=fingerprint("membership-present-v1","present");
    public static final Fingerprint UNKNOWN=fingerprint("semantic-unknown-v1","unknown");
    private static final Fingerprint EMPTY_MERKLE_MAP=fingerprint("merkle-map-empty-v1","empty");

    public record Fingerprint(String value) {
        public Fingerprint { Objects.requireNonNull(value); }
    }
    public record AggregateIdentity(Fingerprint fingerprint,long cardinality) {
        public AggregateIdentity { Objects.requireNonNull(fingerprint);if(cardinality<0)throw new IllegalArgumentException("cardinality"); }
    }
    public record Leaf(Path path,Fingerprint content,Fingerprint api,Fingerprint namespace) {
        public Leaf {
            path=normalize(Objects.requireNonNull(path));
            Objects.requireNonNull(content);Objects.requireNonNull(api);Objects.requireNonNull(namespace);
        }
        public Leaf(Path path,String content,String api,String namespace){
            this(path,new Fingerprint(content),new Fingerprint(api),new Fingerprint(namespace));
        }
    }
    public record State(Fingerprint merkle,AggregateIdentity membership,AggregateIdentity content,
                        AggregateIdentity api,AggregateIdentity namespace,long epoch,int files) {
        public State {
            Objects.requireNonNull(merkle);Objects.requireNonNull(membership);Objects.requireNonNull(content);
            Objects.requireNonNull(api);Objects.requireNonNull(namespace);
            if(epoch<0||files<0)throw new IllegalArgumentException("negative state");
        }
    }
    public enum Domain { MEMBERSHIP, CONTENT, API, NAMESPACE, MERKLE }
    public record Transition(State before,State after,Set<Domain> changed) {
        public Transition { changed=Set.copyOf(changed); }
        public boolean changed(Domain domain){return changed.contains(domain);}
    }

    private final List<Path> roots;
    private final Node workspace=new Node("workspace",null,null,true);
    private final Map<Path,Leaf> leaves=new HashMap<>();
    private long epoch;

    public LiveStateTree(Collection<Path> sourceRoots) {
        var normalized=sourceRoots.stream().map(LiveStateTree::normalize).distinct().sorted(Comparator.comparing(Path::toString)).toList();
        roots=List.copyOf(normalized);
        for(Path root:roots){
            String key=rootKey(root);var child=new Node(root.toString(),root,key,true);
            workspace.directories.put(key,child);workspace.children.put(key,child.merkle);
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
        parent.children.put(fileKey(file),leafMerkle(next));parent.recompute();
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
        Node parent=chain.get(chain.size()-1);parent.children.remove(fileKey(file));parent.recompute();
        propagate(chain,true);leaves.remove(file);
        return new Transition(before,state(),domains(old,null));
    }

    /** Canonical source leaf from the existing source/API/exported-name semantic meanings. */
    public static Leaf source(Path path,String contentFingerprint,String apiFingerprint,Collection<String> exportedNames){
        return new Leaf(path,new Fingerprint(contentFingerprint),new Fingerprint(apiFingerprint),namespace(exportedNames));
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
            if(next==null){next=new Node(cursor.toString(),cursor,key,false);current.directories.put(key,next);}
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
                parent.directories.remove(child.keyInParent);parent.children.remove(child.keyInParent);
            }else parent.children.put(child.keyInParent,child.merkle);
            parent.recompute();
        }
    }
    private static void updateAggregates(List<Node> chain,Leaf old,Leaf next,long epoch){
        boolean membership=old==null||next==null;
        boolean content=old==null||next==null||!old.content().equals(next.content());
        boolean api=old==null||next==null||!old.api().equals(next.api());
        boolean namespace=old==null||next==null||!old.namespace().equals(next.namespace());
        int fileDelta=old==null?1:next==null?-1:0;
        for(Node node:chain){
            if(membership)node.membership.replace(old==null?null:old.path(),old==null?null:PRESENT,next==null?null:next.path(),next==null?null:PRESENT);
            if(content)node.content.replace(old==null?null:old.path(),old==null?null:old.content(),next==null?null:next.path(),next==null?null:next.content());
            if(api)node.api.replace(old==null?null:old.path(),old==null?null:old.api(),next==null?null:next.path(),next==null?null:next.api());
            if(namespace)node.namespace.replace(old==null?null:old.path(),old==null?null:old.namespace(),next==null?null:next.path(),next==null?null:next.namespace());
            node.files+=fileDelta;node.epoch=epoch;
        }
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
        return fingerprint("state-leaf-v1",leaf.path(),leaf.content().value(),leaf.api().value(),leaf.namespace().value());
    }

    private static final class Node {
        final String id;final Path directory;final String keyInParent;final boolean sourceRoot;
        final Map<String,Node> directories=new HashMap<>();
        final MerkleMap children=new MerkleMap();
        final Aggregate membership=new Aggregate("membership"),content=new Aggregate("content"),api=new Aggregate("api"),namespace=new Aggregate("namespace");
        Fingerprint merkle;long epoch;int files;
        Node(String id,Path directory,String keyInParent,boolean sourceRoot){
            this.id=id;this.directory=directory;this.keyInParent=keyInParent;this.sourceRoot=sourceRoot;recompute();
        }
        void recompute(){merkle=fingerprint("state-node-v1",id,children.rootHash().value());}
        State state(){return new State(merkle,membership.identity(),content.identity(),api.identity(),namespace.identity(),epoch,files);}
    }

    private static final class Aggregate {
        final String domain;BigInteger sum=BigInteger.ZERO;long count;AggregateIdentity identity;
        Aggregate(String domain){this.domain=domain;refresh();}
        void replace(Path oldPath,Fingerprint oldValue,Path newPath,Fingerprint newValue){
            if(oldValue!=null){sum=sum.subtract(contribution(domain,oldPath,oldValue)).mod(FIELD);count--;}
            if(newValue!=null){sum=sum.add(contribution(domain,newPath,newValue)).mod(FIELD);count++;}
            if(count<0)throw new IllegalStateException("Negative aggregate cardinality");refresh();
        }
        void refresh(){identity=new AggregateIdentity(fingerprint("aggregate-v1",domain,count,fixedHex(sum)),count);}
        AggregateIdentity identity(){return identity;}
    }

    /** Deterministic treap: priorities are derived from the key, so shape does not depend on mutation history. */
    private static final class MerkleMap {
        Entry root;
        Fingerprint rootHash(){return root==null?EMPTY_MERKLE_MAP:root.hash;}
        void put(String key,Fingerprint value){root=put(root,key,value,priority(key));}
        void remove(String key){root=remove(root,key);}
        private static Entry put(Entry node,String key,Fingerprint value,BigInteger priority){
            if(node==null)return new Entry(key,value,priority);
            int order=key.compareTo(node.key);
            if(order==0){node.value=value;node.update();return node;}
            if(order<0){node.left=put(node.left,key,value,priority);if(higher(node.left,node))node=rotateRight(node);}
            else{node.right=put(node.right,key,value,priority);if(higher(node.right,node))node=rotateLeft(node);}
            node.update();return node;
        }
        private static Entry remove(Entry node,String key){
            if(node==null)return null;int order=key.compareTo(node.key);
            if(order<0)node.left=remove(node.left,key);
            else if(order>0)node.right=remove(node.right,key);
            else return merge(node.left,node.right);
            node.update();return node;
        }
        private static Entry merge(Entry left,Entry right){
            if(left==null)return right;if(right==null)return left;
            if(higher(left,right)){left.right=merge(left.right,right);left.update();return left;}
            right.left=merge(left,right.left);right.update();return right;
        }
        private static boolean higher(Entry a,Entry b){
            int compared=a.priority.compareTo(b.priority);return compared>0||compared==0&&a.key.compareTo(b.key)>0;
        }
        private static Entry rotateRight(Entry node){Entry next=node.left;node.left=next.right;next.right=node;node.update();next.update();return next;}
        private static Entry rotateLeft(Entry node){Entry next=node.right;node.right=next.left;next.left=node;node.update();next.update();return next;}
        private static final class Entry {
            final String key;final BigInteger priority;Fingerprint value,hash;Entry left,right;
            Entry(String key,Fingerprint value,BigInteger priority){this.key=key;this.value=value;this.priority=priority;update();}
            void update(){hash=fingerprint("merkle-map-node-v1",left==null?EMPTY_MERKLE_MAP.value():left.hash.value(),key,value.value(),right==null?EMPTY_MERKLE_MAP.value():right.hash.value());}
        }
    }

    private static BigInteger priority(String key){return new BigInteger(1,digest("merkle-priority-v1",key));}
    private static BigInteger contribution(String domain,Path path,Fingerprint value){return new BigInteger(1,digest("aggregate-contribution-v1",domain,normalize(path),value.value())).mod(FIELD);}
    private static String fixedHex(BigInteger value){return String.format(Locale.ROOT,"%064x",value);}
    private static Fingerprint fingerprint(String domain,Object... parts){return new Fingerprint(HexFormat.of().formatHex(digest(domain,parts)));}
    private static byte[] digest(String domain,Object... parts){
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))){
                write(out,domain);for(Object part:parts)write(out,part);
            }
            return digest.digest();
        }catch(IOException|NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static void write(DataOutputStream out,Object value)throws IOException{
        if(value instanceof Object[] values){out.writeByte(1);out.writeInt(values.length);for(Object item:values)write(out,item);return;}
        if(value instanceof Collection<?> values){out.writeByte(2);out.writeInt(values.size());for(Object item:values)write(out,item);return;}
        byte[] bytes=Objects.toString(value,"").getBytes(StandardCharsets.UTF_8);out.writeByte(3);out.writeInt(bytes.length);out.write(bytes);
    }
}
