package dev.jvmd.index.rocks;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import java.util.zip.*;
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
    private byte[] namespace;
    private long bytes,peakBytes,spillBytes,spillNanos,inputRecords,runRecords;

    SstSorter(Path directory,long budget){
        if(budget<65536)throw new IllegalArgumentException("sort budget must be at least 64 KiB");
        this.directory=directory;this.budget=budget;
    }
    void add(byte[] key,byte[] value)throws Exception{
        if(key.length<66||key[64]!='|')throw new IOException("Invalid artifact namespace");
        if(namespace==null)namespace=Arrays.copyOf(key,65);
        else if(Arrays.mismatch(namespace,0,65,key,0,65)>=0)throw new IOException("Mixed artifact namespaces");
        // A sort contains exactly one artifact. Keep its repeated SHA out of every spill row.
        var entry=new Entry(Arrays.copyOfRange(key,65,key.length),value);
        inputRecords++;
        if(!buffered.isEmpty()&&bytes+entry.bytes()>budget)spill();
        buffered.add(entry);bytes+=entry.bytes();peakBytes=Math.max(peakBytes,bytes);
        if(bytes>=budget)spill();
    }
    long peakBytes(){return peakBytes;}
    long spillBytes(){return spillBytes;}
    long spillNanos(){return spillNanos;}
    long inputRecords(){return inputRecords;}
    long runRecords(){return runRecords;}
    String writeTo(SstFileWriter writer)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");
        var postings=new PostingWriter(entry->{
            byte[] full=Arrays.copyOf(namespace,namespace.length+entry.key().length);
            System.arraycopy(entry.key(),0,full,namespace.length,entry.key().length);
            hash(digest,full,entry.value());writer.put(full,entry.value());
        });
        Consumer write=postings::accept;
        if(runs.isEmpty()){
            buffered.sort(ORDER);byte[] previous=null;
            for(var entry:buffered){check(previous,entry.key());write.accept(entry);previous=entry.key();}
            postings.flush();return HexFormat.of().formatHex(digest.digest());
        }
        spill();
        while(runs.size()>FAN_IN){
            var next=new ArrayList<Path>();
            for(int i=0;i<runs.size();i+=FAN_IN){
                var group=List.copyOf(runs.subList(i,Math.min(runs.size(),i+FAN_IN)));Path output=newRun();
                try(var out=output(output)){
                    var packed=new PostingWriter(entry->{write(out,entry);runRecords++;});merge(group,packed::accept);packed.flush();
                }
                spillBytes+=Files.size(output);next.add(output);
                for(Path path:group){Files.delete(path);temporary.remove(path);}
            }
            runs=next;
        }
        merge(runs,write);
        postings.flush();
        return HexFormat.of().formatHex(digest.digest());
    }
    static void hash(MessageDigest digest,byte[] key,byte[] value){
        digest.update(ByteBuffer.allocate(8).putInt(key.length).putInt(value.length).array());
        digest.update(key);digest.update(value);
    }
    private void spill()throws Exception{
        if(buffered.isEmpty())return;long started=System.nanoTime();buffered.sort(ORDER);Path path=newRun();
        try(var out=output(path)){
            var packed=new PostingWriter(entry->{write(out,entry);runRecords++;});byte[] previous=null;
            for(var entry:buffered){check(previous,entry.key());packed.accept(entry);previous=entry.key();}packed.flush();
        }
        spillBytes+=Files.size(path);runs.add(path);buffered.clear();bytes=0;
        spillNanos+=System.nanoTime()-started;
    }
    private Path newRun()throws IOException{
        Path path=Files.createTempFile(directory,"sort-",".run.tmp");temporary.add(path);return path;
    }
    private static DataOutputStream output(Path path)throws IOException{
        var compressed=new GZIPOutputStream(Files.newOutputStream(path),65536){{def.setLevel(Deflater.BEST_SPEED);}};
        return new DataOutputStream(new BufferedOutputStream(compressed,65536));
    }
    /** Bound each posting block to 256 sorted IDs. The key carries the block's maximum ID. */
    private static final class PostingWriter {
        private final Consumer consumer;
        private final ByteArrayOutputStream ids=new ByteArrayOutputStream(1024);
        private byte[] last;private int count,previous;
        PostingWriter(Consumer consumer){this.consumer=consumer;}
        void accept(Entry entry)throws Exception{
            byte[] key=entry.key();
            boolean posting=entry.value().length==0&&isPosting(key);
            if(!posting){flush();put(key,entry.value());return;}
            int prefixLength=key.length-8;
            if(last!=null&&(count==256||last.length!=key.length||Arrays.mismatch(last,0,prefixLength,key,0,prefixLength)>=0))flush();
            int id=0;for(int i=prefixLength;i<key.length;i++)id=(id<<4)|Character.digit(key[i],16);
            if(count==0)ids.write(0x7f);
            int delta=id-previous;
            do{int part=delta&0x7f;delta>>>=7;ids.write(part|(delta==0?0:0x80));}while(delta!=0);
            count++;previous=id;last=key;
        }
        void flush()throws Exception{
            if(last==null)return;put(last,ids.toByteArray());last=null;count=0;previous=0;ids.reset();
        }
        private void put(byte[] key,byte[] value)throws Exception{consumer.accept(new Entry(key,value));}
    }
    private static boolean isPosting(byte[] key){return key.length>11&&
            (key[0]=='3'||key[0]=='5'||key[0]=='7'||key[0]=='8'||(key[0]=='2'&&key[2]=='s'));}
    @FunctionalInterface private interface Consumer {void accept(Entry entry)throws Exception;}
    private static void merge(List<Path> paths,Consumer consumer)throws Exception{
        var inputs=new ArrayList<Run>();
        var queue=new PriorityQueue<Run>((a,b)->ORDER.compare(a.current,b.current));
        try{
            for(Path path:paths){var run=new Run(path);inputs.add(run);if(run.current!=null)queue.add(run);}
            byte[] previous=null;
            while(!queue.isEmpty()){
                var run=queue.remove();var entry=run.current;check(previous,entry.key());
                // A packed range can pass through intact when no other run interleaves it.
                // Overlapping ranges still expand one ID at a time, retaining duplicate checks.
                if(run.postingIds!=null&&run.postingOffset==1&&
                        (queue.isEmpty()||Arrays.compareUnsigned(run.postingKey,queue.peek().current.key())<0)){
                    entry=new Entry(run.postingKey,run.postingValue);run.postingOffset=run.postingIds.length;
                }
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
        private byte[] postingKey,postingValue;private int[] postingIds;private int postingOffset;
        private static final byte[] EMPTY=new byte[0];
        private static final byte[] HEX="0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Run(Path path)throws IOException{
            input=new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path),65536),65536));
            try{advance();}catch(IOException e){input.close();throw e;}
        }
        void advance()throws IOException{
            if(postingIds!=null&&postingOffset<postingIds.length){nextPosting();return;}
            postingIds=null;postingKey=null;postingValue=null;
            int first=input.read();if(first<0){current=null;return;}
            int keyLength=(first<<24)|(input.readUnsignedByte()<<16)|(input.readUnsignedByte()<<8)|input.readUnsignedByte();
            int valueLength=input.readInt();
            if(keyLength<1||valueLength<0)throw new IOException("Corrupt sort run");
            byte[] key=input.readNBytes(keyLength),value=input.readNBytes(valueLength);
            if(key.length!=keyLength||value.length!=valueLength)throw new EOFException("Truncated sort run");
            if(isPosting(key)&&value.length>0){
                try{postingIds=PostingCodec.decode(key,value);}catch(IllegalStateException invalid){throw new IOException("Corrupt sort posting",invalid);}
                postingKey=key;postingValue=value;postingOffset=0;nextPosting();
            }else current=new Entry(key,value);
        }
        private void nextPosting(){
            byte[] key=postingKey.clone();int id=postingIds[postingOffset++];
            for(int i=key.length-1;i>=key.length-8;i--){key[i]=HEX[id&15];id>>>=4;}
            current=new Entry(key,EMPTY);
        }
        @Override public void close()throws IOException{input.close();}
    }
    @Override public void close()throws IOException{
        IOException failure=null;
        for(Path path:temporary)try{Files.deleteIfExists(path);}catch(IOException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        if(failure!=null)throw failure;
    }
}
