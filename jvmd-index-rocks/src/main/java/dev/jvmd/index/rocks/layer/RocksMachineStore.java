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
 * bounded batches, syncs once, then writes the root. A node already written by this store is not
 * written again, so a recommit writes only the nodes on changed paths.
 */
public final class RocksMachineStore implements AutoCloseable {
    static {RocksDB.loadLibrary();}
    private static final byte[] ROOT="ROOT".getBytes(StandardCharsets.UTF_8);
    public static final long BATCH_BYTES=4L*1024*1024;

    private final RocksDB db;
    private final Options options;
    private final Set<Hash256> written=new HashSet<>();
    private long commits,batches,records,peakStagedBytes;

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

    /** Commit a tree: its leaves, every node not yet written, its path table, then its root. */
    public synchronized void commit(MachineTree tree)throws Exception{
        var newlyWritten=new ArrayList<Hash256>();
        try(var sync=new WriteOptions().setSync(true);var staged=new WriteOptions()){
            var commit=new Commit(new Commit.Store(){
                @Override public void stage(List<Map.Entry<byte[],byte[]>> batch)throws Exception{
                    try(var write=new WriteBatch()){for(var record:batch)write.put(record.getKey(),record.getValue());db.write(staged,write);}
                }
                @Override public void sync()throws Exception{db.flushWal(true);}
                @Override public void root(byte[] root)throws Exception{db.put(sync,ROOT,root);}
            },BATCH_BYTES);
            for(var leaf:tree.leaves())commit.put(bytes("L|"+leaf.cacheKey()),leaf.encode());
            tree.tree().writeNodes((hash,node)->{commit.put(bytes("N|"+hash.hex()),node);newlyWritten.add(hash);},written::contains);
            for(var path:tree.paths().entrySet())commit.put(bytes("P|"+path.getKey()),bytes(path.getValue()));
            commit.root(tree.root());
            written.addAll(newlyWritten);commits++;batches+=commit.batches();records+=commit.records();
            peakStagedBytes=Math.max(peakStagedBytes,commit.peakBytes());
        }
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

    public synchronized Map<String,Object> status(){
        return Map.of("commits",commits,"commit_batches",batches,"commit_records",records,"peak_staged_bytes",peakStagedBytes);
    }

    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}

    @Override public void close(){db.close();options.close();}
}
