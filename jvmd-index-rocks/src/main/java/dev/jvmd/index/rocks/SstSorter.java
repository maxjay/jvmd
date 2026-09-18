package dev.jvmd.index.rocks;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import org.rocksdb.SstFileWriter;

/** Bounded external merge sort. All temporary runs remain outside the published namespace. */
final class SstSorter implements AutoCloseable {
    private record Entry(byte[] key,byte[] value) { long bytes(){return 48L+key.length+value.length;} }
    private static final Comparator<Entry> ORDER=(a,b)->Arrays.compareUnsigned(a.key(),b.key());
    private static final int FAN_IN=32;
    private final Path directory;
    private final long budget;
    private final List<Entry> buffered=new ArrayList<>();
    private final Set<Path> temporary=new LinkedHashSet<>();
    private List<Path> runs=new ArrayList<>();
    private long bytes,peakBytes,spillBytes;

    SstSorter(Path directory,long budget){
        if(budget<65536)throw new IllegalArgumentException("sort budget must be at least 64 KiB");
        this.directory=directory;this.budget=budget;
    }
    void add(byte[] key,byte[] value)throws IOException{
        var entry=new Entry(key,value);
        if(!buffered.isEmpty()&&bytes+entry.bytes()>budget)spill();
        buffered.add(entry);bytes+=entry.bytes();peakBytes=Math.max(peakBytes,bytes);
        if(bytes>=budget)spill();
    }
    long peakBytes(){return peakBytes;}
    long spillBytes(){return spillBytes;}
    String writeTo(SstFileWriter writer)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");
        Consumer write=entry->{hash(digest,entry.key(),entry.value());writer.put(entry.key(),entry.value());};
        if(runs.isEmpty()){
            buffered.sort(ORDER);byte[] previous=null;
            for(var entry:buffered){check(previous,entry.key());write.accept(entry);previous=entry.key();}
            return HexFormat.of().formatHex(digest.digest());
        }
        spill();
        while(runs.size()>FAN_IN){
            var next=new ArrayList<Path>();
            for(int i=0;i<runs.size();i+=FAN_IN){
                var group=List.copyOf(runs.subList(i,Math.min(runs.size(),i+FAN_IN)));Path output=newRun();
                try(var out=output(output)){merge(group,entry->write(out,entry));}
                spillBytes+=Files.size(output);next.add(output);
                for(Path path:group){Files.delete(path);temporary.remove(path);}
            }
            runs=next;
        }
        merge(runs,write);
        return HexFormat.of().formatHex(digest.digest());
    }
    static void hash(MessageDigest digest,byte[] key,byte[] value){
        digest.update(ByteBuffer.allocate(8).putInt(key.length).putInt(value.length).array());
        digest.update(key);digest.update(value);
    }
    private void spill()throws IOException{
        if(buffered.isEmpty())return;buffered.sort(ORDER);Path path=newRun();
        try(var out=output(path)){for(var entry:buffered)write(out,entry);}
        spillBytes+=Files.size(path);runs.add(path);buffered.clear();bytes=0;
    }
    private Path newRun()throws IOException{
        Path path=Files.createTempFile(directory,"sort-",".run.tmp");temporary.add(path);return path;
    }
    private static DataOutputStream output(Path path)throws IOException{
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path),65536));
    }
    @FunctionalInterface private interface Consumer {void accept(Entry entry)throws Exception;}
    private static void merge(List<Path> paths,Consumer consumer)throws Exception{
        var inputs=new ArrayList<Run>();
        var queue=new PriorityQueue<Run>((a,b)->ORDER.compare(a.current,b.current));
        try{
            for(Path path:paths){var run=new Run(path);inputs.add(run);if(run.current!=null)queue.add(run);}
            byte[] previous=null;
            while(!queue.isEmpty()){
                var run=queue.remove();var entry=run.current;check(previous,entry.key());
                consumer.accept(entry);previous=entry.key();run.advance();if(run.current!=null)queue.add(run);
            }
        }finally{for(var run:inputs)run.close();}
    }
    private static void check(byte[] previous,byte[] current)throws IOException{
        if(previous!=null&&Arrays.compareUnsigned(previous,current)>=0)throw new IOException("Duplicate or unsorted artifact index key");
    }
    private static void write(DataOutputStream out,Entry entry)throws IOException{
        out.writeInt(entry.key().length);out.writeInt(entry.value().length);out.write(entry.key());out.write(entry.value());
    }
    private static final class Run implements AutoCloseable {
        private final DataInputStream input;private Entry current;
        Run(Path path)throws IOException{
            input=new DataInputStream(new BufferedInputStream(Files.newInputStream(path),65536));
            try{advance();}catch(IOException e){input.close();throw e;}
        }
        void advance()throws IOException{
            int first=input.read();if(first<0){current=null;return;}
            int keyLength=(first<<24)|(input.readUnsignedByte()<<16)|(input.readUnsignedByte()<<8)|input.readUnsignedByte();
            int valueLength=input.readInt();
            if(keyLength<1||valueLength<0)throw new IOException("Corrupt sort run");
            byte[] key=input.readNBytes(keyLength),value=input.readNBytes(valueLength);
            if(key.length!=keyLength||value.length!=valueLength)throw new EOFException("Truncated sort run");
            current=new Entry(key,value);
        }
        @Override public void close()throws IOException{input.close();}
    }
    @Override public void close()throws IOException{
        IOException failure=null;
        for(Path path:temporary)try{Files.deleteIfExists(path);}catch(IOException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        if(failure!=null)throw failure;
    }
}
