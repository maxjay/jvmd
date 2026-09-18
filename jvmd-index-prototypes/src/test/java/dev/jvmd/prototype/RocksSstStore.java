package dev.jvmd.prototype;

import java.nio.file.*;
import java.util.*;
import org.rocksdb.*;

final class RocksSstStore implements PrototypeStore {
    static {RocksDB.loadLibrary();}
    private final Path root;
    private final Options options;
    private final RocksDB db;

    RocksSstStore(Path root)throws Exception{
        this.root=root;Files.createDirectories(root);
        options=new Options().setCreateIfMissing(true);
        db=RocksDB.open(options,root.toString());
    }

    @Override public void publish(List<Entry> entries)throws Exception{
        var sorted=new ArrayList<>(entries);sorted.sort((a,b)->ImmutableSegmentStore.compare(a.key(),b.key()));
        for(int i=1;i<sorted.size();i++)if(ImmutableSegmentStore.compare(sorted.get(i-1).key(),sorted.get(i).key())==0)
            throw new IllegalArgumentException("duplicate key");
        Path sst=Files.createTempFile(root.getParent(),"prototype-",".sst");
        try(var env=new EnvOptions();var writer=new SstFileWriter(env,options)){
            writer.open(sst.toString());
            for(var entry:sorted)writer.put(entry.key(),entry.value());
            writer.finish();
        }
        try(var ingest=new IngestExternalFileOptions().setMoveFiles(false)){
            db.ingestExternalFile(List.of(sst.toString()),ingest);
        }finally{Files.deleteIfExists(sst);}
    }

    @Override public byte[] get(byte[] key)throws Exception{return db.get(key);}
    @Override public List<Entry> prefix(byte[] prefix,int limit){
        var result=new ArrayList<Entry>();
        try(var iterator=db.newIterator()){
            for(iterator.seek(prefix);iterator.isValid()&&result.size()<limit;iterator.next()){
                byte[] key=iterator.key();if(!ImmutableSegmentStore.startsWith(key,prefix))break;
                result.add(new Entry(key,iterator.value()));
            }
        }
        return List.copyOf(result);
    }
    @Override public long storageBytes()throws Exception{
        try(var files=Files.walk(root)){
            return files.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(Exception e){return 0;}}).sum();
        }
    }
    @Override public String name(){return "rocksdb-sst";}
    @Override public void close(){db.close();options.close();}
}
