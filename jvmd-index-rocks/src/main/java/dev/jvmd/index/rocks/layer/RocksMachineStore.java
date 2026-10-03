package dev.jvmd.index.rocks.layer;

import dev.jvmd.core.Hash256;
import dev.jvmd.core.tree.Commit;
import dev.jvmd.core.tree.KeyedTree;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineTree;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.rocksdb.*;

/**
 * MACHINE storage: leaves ({@code L|cacheKey}), tree nodes ({@code N|hash}), the path table
 * ({@code P|location}) and the root ({@code ROOT}). A commit stages leaves, nodes and paths in
 * bounded batches, syncs once, then writes the root. A recommit is the difference from the committed
 * tree: it writes the leaves and paths that changed, deletes the ones that went, and writes only the
 * nodes that are not stored, so its writes grow with the change and not with the layer.
 */
public final class RocksMachineStore implements AutoCloseable {
    static {RocksDB.loadLibrary();}
    private static final byte[] ROOT="ROOT".getBytes(StandardCharsets.UTF_8);
    public static final long BATCH_BYTES=4L*1024*1024;

    private final RocksDB db;
    private final Options options;
    private final Set<Hash256> written=new HashSet<>();
    private volatile Map<String,Long> lastCommit=Map.of();

    /** Opens the store; {@code options} decide whether a missing database is created. */
    public RocksMachineStore(Path directory,Options options)throws RocksDBException{
        this.options=options;db=RocksDB.open(options,directory.toString());
    }

    /** The committed root of the MACHINE store in {@code directory}, or empty when absent or unreadable. */
    public static Optional<Root> committedRoot(Path directory){
        if(!Files.isDirectory(directory))return Optional.empty();
        try(var options=new Options();var db=RocksDB.openReadOnly(options,directory.toString())){
            byte[] value=db.get(ROOT);return value==null?Optional.empty():Optional.of(Root.decode(value));
        }catch(RocksDBException|IOException|RuntimeException unreadable){return Optional.empty();}
    }

    /** This store's committed root, or empty before its first commit. */
    public Optional<Root> committedRoot()throws IOException{
        try{byte[] value=db.get(ROOT);return value==null?Optional.empty():Optional.of(Root.decode(value));}
        catch(RocksDBException unreadable){throw new IOException(unreadable);}
    }

    /**
     * Commit {@code tree} over {@code previous}, the tree whose root is committed in this store
     * ({@link MachineTree#EMPTY} before the first commit): the changed leaves, the nodes not stored,
     * the changed paths, then the root.
     */
    public synchronized void commit(MachineTree previous,MachineTree tree)throws Exception{
        var newlyWritten=new ArrayList<Hash256>();
        try(var sync=new WriteOptions().setSync(true);var staged=new WriteOptions()){
            var commit=new Commit(new Commit.Store(){
                @Override public void stage(List<Commit.Write> batch)throws Exception{
                    try(var write=new WriteBatch()){
                        for(var record:batch)if(record.delete())write.delete(record.key());else write.put(record.key(),record.value());
                        db.write(staged,write);
                    }
                }
                @Override public void sync()throws Exception{db.flushWal(true);}
                @Override public void root(byte[] root)throws Exception{db.put(sync,ROOT,root);}
            },BATCH_BYTES);
            long leaves=0,paths=0,deleted=0;
            for(var leaf:tree.leaves())if(!leaf.equals(previous.leaf(leaf.cacheKey()))){commit.put(bytes("L|"+leaf.cacheKey()),leaf.encode());leaves++;}
            for(var leaf:previous.leaves())if(tree.leaf(leaf.cacheKey())==null){commit.delete(bytes("L|"+leaf.cacheKey()));deleted++;}
            // Every node of a committed tree is stored, so a subtree whose root is stored is skipped whole.
            boolean committed=!previous.tree().isEmpty();
            tree.tree().writeNodes((hash,node)->{commit.put(bytes("N|"+hash.hex()),node);newlyWritten.add(hash);},
                    hash->written.contains(hash)||committed&&stored(hash));
            for(var path:tree.paths().entrySet())if(!path.getValue().equals(previous.paths().get(path.getKey()))){commit.put(bytes("P|"+path.getKey()),bytes(path.getValue()));paths++;}
            for(var path:previous.paths().keySet())if(!tree.paths().containsKey(path)){commit.delete(bytes("P|"+path));deleted++;}
            commit.root(tree.root());
            written.addAll(newlyWritten);
            lastCommit=Map.of("leaves",leaves,"nodes",(long)newlyWritten.size(),"paths",paths,"deleted",deleted);
        }
    }

    /** What the last commit wrote: leaves, nodes and paths put, and leaves and paths deleted. */
    public Map<String,Long> lastCommit(){return lastCommit;}

    private boolean stored(Hash256 hash){
        try{return db.get(bytes("N|"+hash.hex()))!=null;}catch(RocksDBException e){throw new IllegalStateException(e);}
    }

    /** The committed artifact tree, read from its stored nodes. */
    public KeyedTree<String,MachineLeaf> tree(Root root)throws IOException{
        return KeyedTree.read(MachineTree.LEAVES,root.tree(),hash->{
            try{return db.get(bytes("N|"+hash.hex()));}catch(RocksDBException e){throw new IOException(e);}
        });
    }

    /** The committed MACHINE tree: every stored leaf, bulk-built. */
    public MachineTree committedTree()throws IOException{
        var leaves=new ArrayList<MachineLeaf>();byte[] prefix=bytes("L|");
        try(var iterator=db.newIterator()){
            for(iterator.seek(prefix);iterator.isValid()&&startsWith(iterator.key(),prefix);iterator.next())leaves.add(MachineLeaf.decode(iterator.value()));
        }
        return MachineTree.build(leaves);
    }
    private static boolean startsWith(byte[] value,byte[] prefix){
        if(value.length<prefix.length)return false;
        for(int i=0;i<prefix.length;i++)if(value[i]!=prefix[i])return false;
        return true;
    }

    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}

    @Override public void close(){db.close();options.close();}
}
