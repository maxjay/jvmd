package dev.jvmd.index.rocks;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.rocksdb.*;

/** Persistent path/stamp inventory referencing immutable content-addressed artifact generations. */
public final class RocksArtifactInventory implements AutoCloseable {
    public record Stamp(long size,long modifiedNanos,long changedNanos,String fileKey) {
        public Stamp { fileKey=fileKey==null?"":fileKey; }
        public static Stamp read(Path path)throws Exception{
            var basic=Files.readAttributes(path,BasicFileAttributes.class);
            long modified=basic.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS),changed=modified;
            try{
                Object ctime=Files.getAttribute(path,"unix:ctime");
                if(ctime instanceof java.nio.file.attribute.FileTime value)changed=value.to(java.util.concurrent.TimeUnit.NANOSECONDS);
            }catch(UnsupportedOperationException|IllegalArgumentException ignored){}
            return new Stamp(basic.size(),modified,changed,Objects.toString(basic.fileKey(),""));
        }
    }

    public record Entry(String path,String gav,String kind,String cacheKey,String binarySha256,Stamp stamp,long generation) {
        public Entry {
            Objects.requireNonNull(path);Objects.requireNonNull(gav);Objects.requireNonNull(kind);
            Objects.requireNonNull(cacheKey);Objects.requireNonNull(binarySha256);Objects.requireNonNull(stamp);
        }
    }

    private static final byte[] GENERATION="M|generation".getBytes(StandardCharsets.UTF_8);
    static {RocksDB.loadLibrary();}
    private final Options options;
    private final RocksDB db;

    public RocksArtifactInventory(Path root)throws Exception{
        Path path=root.toAbsolutePath().normalize();Files.createDirectories(path);
        options=new Options().setCreateIfMissing(true).setMaxOpenFiles(64);
        db=RocksDB.open(options,path.toString());
    }

    public synchronized long beginScan()throws Exception{
        long generation=readLong(db.get(GENERATION))+1;
        db.put(GENERATION,longBytes(generation));return generation;
    }

    public synchronized Optional<Entry> reusable(Path path,String gav,String kind,Stamp stamp,boolean forceHash)throws Exception{
        if(forceHash)return Optional.empty();
        byte[] value=db.get(pathKey(path));if(value==null)return Optional.empty();
        Entry entry=decode(value);
        if(!entry.gav().equals(gav)||!entry.kind().equals(kind)||!entry.stamp().equals(stamp))return Optional.empty();
        return Optional.of(entry);
    }

    public synchronized void observe(long generation,Path path,String gav,String kind,String cacheKey,String binarySha256,Stamp stamp)throws Exception{
        if(generation<=0)throw new IllegalArgumentException("generation");
        String location=normalize(path);byte[] key=pathKey(path),oldBytes=db.get(key);
        Entry old=oldBytes==null?null:decode(oldBytes);
        var next=new Entry(location,gav,kind,cacheKey,binarySha256,stamp,generation);
        try(var batch=new WriteBatch();var write=new WriteOptions()){
            if(old==null||!old.cacheKey().equals(cacheKey)){
                if(old!=null)adjustRefcount(batch,old.cacheKey(),-1);
                adjustRefcount(batch,cacheKey,1);
            }
            batch.put(key,encode(next));db.write(write,batch);
        }
    }

    public synchronized Set<String> completeScan(long generation)throws Exception{
        var stale=new ArrayList<Map.Entry<byte[],Entry>>();byte[] prefix="P|".getBytes(StandardCharsets.UTF_8);
        try(var read=new ReadOptions();var iterator=db.newIterator(read)){
            for(iterator.seek(prefix);iterator.isValid();iterator.next()){
                if(!startsWith(iterator.key(),prefix))break;
                Entry entry=decode(iterator.value());if(entry.generation()!=generation)
                    stale.add(Map.entry(Arrays.copyOf(iterator.key(),iterator.key().length),entry));
            }
        }
        var candidates=new LinkedHashSet<String>();
        try(var batch=new WriteBatch();var write=new WriteOptions()){
            for(var item:stale){
                batch.delete(item.getKey());long next=refcount(item.getValue().cacheKey())-1;
                if(next<=0){batch.delete(refKey(item.getValue().cacheKey()));candidates.add(item.getValue().cacheKey());}
                else batch.put(refKey(item.getValue().cacheKey()),longBytes(next));
            }
            db.write(write,batch);
        }
        var unreferenced=new LinkedHashSet<String>();
        for(String key:candidates)if(refcount(key)==0)unreferenced.add(key);
        return Set.copyOf(unreferenced);
    }

    public synchronized long refcount(String cacheKey)throws Exception{return readLong(db.get(refKey(cacheKey)));}

    public synchronized List<Entry> entries(){
        var result=new ArrayList<Entry>();byte[] prefix="P|".getBytes(StandardCharsets.UTF_8);
        try(var read=new ReadOptions();var iterator=db.newIterator(read)){
            for(iterator.seek(prefix);iterator.isValid();iterator.next()){
                if(!startsWith(iterator.key(),prefix))break;
                try{result.add(decode(iterator.value()));}catch(IOException e){throw new UncheckedIOException(e);}
            }
        }
        result.sort(Comparator.comparing(Entry::path));return List.copyOf(result);
    }

    private void adjustRefcount(WriteBatch batch,String cacheKey,long delta)throws Exception{
        long next=refcount(cacheKey)+delta;
        if(next<=0)batch.delete(refKey(cacheKey));else batch.put(refKey(cacheKey),longBytes(next));
    }

    private static byte[] encode(Entry entry)throws IOException{
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            writeString(out,entry.path());writeString(out,entry.gav());writeString(out,entry.kind());
            writeString(out,entry.cacheKey());writeString(out,entry.binarySha256());
            out.writeLong(entry.stamp().size());out.writeLong(entry.stamp().modifiedNanos());out.writeLong(entry.stamp().changedNanos());
            writeString(out,entry.stamp().fileKey());out.writeLong(entry.generation());
        }
        return bytes.toByteArray();
    }
    private static Entry decode(byte[] bytes)throws IOException{
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            String path=readString(in),gav=readString(in),kind=readString(in),cacheKey=readString(in),sha=readString(in);
            var stamp=new Stamp(in.readLong(),in.readLong(),in.readLong(),readString(in));long generation=in.readLong();
            if(in.available()!=0)throw new IOException("Trailing inventory bytes");
            return new Entry(path,gav,kind,cacheKey,sha,stamp,generation);
        }
    }

    private static byte[] pathKey(Path path){return ("P|"+normalize(path)).getBytes(StandardCharsets.UTF_8);}
    private static byte[] refKey(String cacheKey){return ("R|"+cacheKey).getBytes(StandardCharsets.UTF_8);}
    private static String normalize(Path path){return path.toAbsolutePath().normalize().toString();}
    private static byte[] longBytes(long value){return java.nio.ByteBuffer.allocate(Long.BYTES).putLong(value).array();}
    private static long readLong(byte[] value){return value==null?0:java.nio.ByteBuffer.wrap(value).getLong();}
    private static void writeString(DataOutputStream out,String value)throws IOException{
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
    }
    private static String readString(DataInputStream in)throws IOException{
        int length=in.readInt();if(length<0||length>16*1024*1024)throw new IOException("Invalid inventory string");
        byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw new EOFException();return new String(bytes,StandardCharsets.UTF_8);
    }
    private static boolean startsWith(byte[] value,byte[] prefix){
        if(value.length<prefix.length)return false;for(int i=0;i<prefix.length;i++)if(value[i]!=prefix[i])return false;return true;
    }

    @Override public void close(){db.close();options.close();}
}
