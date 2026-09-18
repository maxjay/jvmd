package dev.jvmd.prototype;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

final class ImmutableSegmentStore implements PrototypeStore {
    private static final byte[] MAGIC="JVSEG001".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private record Slot(byte[] key,int valueOffset,int valueLength){}
    private final Path path;
    private FileChannel channel;
    private MappedByteBuffer mapped;
    private List<Slot> slots=List.of();

    ImmutableSegmentStore(Path path){this.path=path;}

    @Override public void publish(List<Entry> entries)throws Exception{
        var sorted=new ArrayList<>(entries);sorted.sort((a,b)->compare(a.key(),b.key()));
        for(int i=1;i<sorted.size();i++)if(compare(sorted.get(i-1).key(),sorted.get(i).key())==0)
            throw new IllegalArgumentException("duplicate prototype key");
        Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),"segment-",".tmp");
        try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))){
            out.write(MAGIC);out.writeInt(sorted.size());
            for(var entry:sorted){out.writeInt(entry.key().length);out.write(entry.key());out.writeInt(entry.value().length);out.write(entry.value());}
        }
        try(var file=FileChannel.open(temp,StandardOpenOption.WRITE)){file.force(true);}
        Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        reopen();
    }

    private void reopen()throws Exception{
        if(channel!=null)channel.close();channel=FileChannel.open(path,StandardOpenOption.READ);mapped=channel.map(FileChannel.MapMode.READ_ONLY,0,channel.size());
        byte[] magic=new byte[MAGIC.length];mapped.get(magic);if(!Arrays.equals(magic,MAGIC))throw new IOException("segment magic");
        int count=mapped.getInt();if(count<0||count>50_000_000)throw new IOException("segment count");
        var parsed=new ArrayList<Slot>(count);
        for(int i=0;i<count;i++){
            int keyLength=mapped.getInt();if(keyLength<0||keyLength>32*1024*1024)throw new IOException("key length");
            byte[] key=new byte[keyLength];mapped.get(key);int valueLength=mapped.getInt();if(valueLength<0||valueLength>256*1024*1024)throw new IOException("value length");
            int offset=mapped.position();mapped.position(offset+valueLength);parsed.add(new Slot(key,offset,valueLength));
        }
        slots=List.copyOf(parsed);
    }

    @Override public byte[] get(byte[] key){
        int index=lowerBound(key);if(index>=slots.size()||compare(slots.get(index).key(),key)!=0)return null;return value(slots.get(index));
    }
    @Override public List<Entry> prefix(byte[] prefix,int limit){
        var result=new ArrayList<Entry>();for(int i=lowerBound(prefix);i<slots.size()&&result.size()<limit;i++){
            var slot=slots.get(i);if(!startsWith(slot.key(),prefix))break;result.add(new Entry(slot.key(),value(slot)));
        }return List.copyOf(result);
    }
    private byte[] value(Slot slot){var view=mapped.duplicate();view.position(slot.valueOffset());byte[] value=new byte[slot.valueLength()];view.get(value);return value;}
    private int lowerBound(byte[] key){int low=0,high=slots.size();while(low<high){int mid=(low+high)>>>1;if(compare(slots.get(mid).key(),key)<0)low=mid+1;else high=mid;}return low;}
    @Override public long storageBytes()throws Exception{return Files.exists(path)?Files.size(path):0;}
    @Override public String name(){return "immutable-file";}
    @Override public void close()throws Exception{slots=List.of();mapped=null;if(channel!=null)channel.close();}
    static int compare(byte[] a,byte[] b){return Arrays.compareUnsigned(a,b);}
    static boolean startsWith(byte[] value,byte[] prefix){if(value.length<prefix.length)return false;for(int i=0;i<prefix.length;i++)if(value[i]!=prefix[i])return false;return true;}
}
