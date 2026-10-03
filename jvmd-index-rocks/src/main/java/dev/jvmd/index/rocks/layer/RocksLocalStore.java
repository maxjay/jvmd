package dev.jvmd.index.rocks.layer;

import dev.jvmd.core.Hash256;
import dev.jvmd.core.Hashing;
import dev.jvmd.core.tree.Commit;
import dev.jvmd.core.tree.KeyedTree;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.ResidentSemanticState;
import dev.jvmd.index.SemanticFact;
import dev.jvmd.index.layer.local.LocalFile;
import dev.jvmd.index.layer.local.LocalFileTree;
import dev.jvmd.index.layer.local.LocalTree;
import dev.jvmd.index.layer.local.Route;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.rocksdb.*;

/**
 * One project's LOCAL storage, keyed by canonical project root within a generation: file leaves
 * ({@code F|path}), tree nodes ({@code N|hash}), the tree heads ({@code H|files}, {@code H|semantic}),
 * the module graph ({@code G|gav}), routes ({@code R|gav:scope}) and the root ({@code ROOT}). A
 * commit stages everything in bounded batches, syncs once, then writes the root.
 */
public final class RocksLocalStore implements AutoCloseable {
    static {RocksDB.loadLibrary();}
    private static final byte[] ROOT=bytes("ROOT");
    public static final long BATCH_BYTES=4L*1024*1024;

    private final RocksDB db;
    private final Options options;

    private RocksLocalStore(Path directory,Options options)throws RocksDBException{
        this.options=options;db=RocksDB.open(options,directory.toString());
    }

    /** The LOCAL directory of the project at {@code projectRoot} within a generation. */
    public static Path directory(Path generation,Path projectRoot)throws IOException{
        String canonical=projectRoot.toRealPath().toString();
        return generation.resolve("local").resolve(Hashing.sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    /** The committed LOCAL root in {@code directory}, or empty when absent or unreadable. */
    public static Optional<Root> committedRoot(Path directory){
        if(!Files.isDirectory(directory))return Optional.empty();
        try(var options=new Options();var db=RocksDB.openReadOnly(options,directory.toString())){
            byte[] value=db.get(ROOT);return value==null?Optional.empty():Optional.of(Root.decode(value));
        }catch(RocksDBException|IOException|RuntimeException unreadable){return Optional.empty();}
    }

    /**
     * Create a project's LOCAL storage. Only the LOCAL cold boot's create stage calls this. It fails
     * when the directory holds a committed root; anything else there was left by an interrupted cold
     * boot, covers nothing committed, and is deleted.
     */
    public static RocksLocalStore create(Path directory)throws Exception{
        if(committedRoot(directory).isPresent())throw new IllegalStateException("Project already has a committed LOCAL root: "+directory);
        if(Files.exists(directory))try(var paths=Files.walk(directory)){
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
        }
        Files.createDirectories(directory);
        return new RocksLocalStore(directory,new Options().setCreateIfMissing(true).setMaxOpenFiles(64));
    }

    /** Open a project's committed LOCAL storage to read what its cold boot committed. */
    public static RocksLocalStore open(Path directory)throws RocksDBException{
        return new RocksLocalStore(directory,new Options().setMaxOpenFiles(64));
    }

    /** Commit a LOCAL tree: file leaves, nodes, module graph and routes, then the root. */
    public synchronized Root commit(LocalTree tree)throws Exception{
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
            var written=new HashSet<Hash256>();
            for(var file:tree.files().files())commit.put(bytes("F|"+file.path()),file.encode());
            tree.files().tree().writeNodes((hash,node)->{if(written.add(hash))commit.put(bytes("N|"+hash.hex()),node);});
            tree.semantic().writeNodes((hash,node)->{if(written.add(hash))commit.put(bytes("N|"+hash.hex()),node);});
            commit.put(bytes("H|files"),tree.files().tree().rootHash().bytes());
            commit.put(bytes("H|semantic"),tree.semantic().rootHash().bytes());
            for(var module:tree.modules().entrySet())commit.put(bytes("G|"+module.getKey()),bytes(String.join("\n",module.getValue())));
            for(var route:tree.routes().values())commit.put(bytes("R|"+route.key()),route.encode());
            var root=tree.root();commit.root(root);
            return root;
        }
    }

    /** The committed file tree, read from its stored nodes. */
    public KeyedTree<String,LocalFile> files()throws IOException{return KeyedTree.read(LocalFileTree.FILES,head("files"),this::node);}
    /** The committed semantic tree, read from its stored nodes. */
    public KeyedTree<String,SemanticFact> semantic()throws IOException{return KeyedTree.read(ResidentSemanticState.FACTS,head("semantic"),this::node);}
    /** The committed route of {@code gav:scope}, or null. */
    public Route route(String key)throws IOException{byte[] value=get("R|"+key);return value==null?null:Route.decode(value);}

    private Hash256 head(String name)throws IOException{
        byte[] value=get("H|"+name);if(value==null)throw new IOException("No committed "+name+" tree");return new Hash256(value);
    }
    private byte[] node(Hash256 hash)throws IOException{return get("N|"+hash.hex());}
    private byte[] get(String key)throws IOException{try{return db.get(bytes(key));}catch(RocksDBException e){throw new IOException(e);}}

    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}

    @Override public void close(){db.close();options.close();}
}
