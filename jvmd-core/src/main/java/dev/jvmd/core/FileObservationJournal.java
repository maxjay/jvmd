package dev.jvmd.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/**
 * Durable, validated cache of file content observations.
 *
 * The journal is not authority. A restored record only lets {@link FileStateRegistry} skip hashing
 * when the file's current reliable stamp equals the persisted stamp and the observation was not
 * taken inside the racy timestamp window. A missing, torn or corrupt record is merely a miss.
 *
 * Format (little-endian, explicit widths): an 8-byte magic/version header followed by records
 * {@code u32 length | u32 crc32c(payload) | payload}. Payload: {@code u16 pathLen, path UTF-8,
 * i64 size, i64 mtimeNanos, i64 ctimeNanos, i64 inode, i64 observedAtNanos, 32-byte SHA-256}.
 * Later records for the same path supersede earlier ones. Loading stops at the first record that
 * fails validation, which bounds the effect of a torn append to that suffix.
 */
public final class FileObservationJournal implements Closeable {
    /** Format magic and version ("JVMDOBS1"); a different value is a foreign or obsolete journal. */
    static final long MAGIC=0x3153424f444d564aL;
    private static final int MAX_PATH_BYTES=16*1024;

    /** One persisted observation. Paths are physical location metadata, never semantic identity. */
    public record Record(String path,long size,long modifiedNanos,long changedNanos,long inode,long observedAtNanos,byte[] sha256) {
        public Record {
            Objects.requireNonNull(path);Objects.requireNonNull(sha256);
            if(sha256.length!=32)throw new IllegalArgumentException("sha256");
            sha256=sha256.clone();
        }
        @Override public byte[] sha256(){return sha256.clone();}
        public String hex(){return HexFormat.of().formatHex(sha256);}
        @Override public boolean equals(Object other){
            return other instanceof Record value&&path.equals(value.path)&&size==value.size&&modifiedNanos==value.modifiedNanos
                    &&changedNanos==value.changedNanos&&inode==value.inode&&observedAtNanos==value.observedAtNanos
                    &&Arrays.equals(sha256,value.sha256);
        }
        @Override public int hashCode(){return Objects.hash(path,size,modifiedNanos,changedNanos,inode,observedAtNanos,Arrays.hashCode(sha256));}
    }

    public record Loaded(Map<String,Record> records,long recordsRead,long corruptTail,long bytesRead) { }

    private final Path file;
    private final List<Record> pending=new ArrayList<>();
    private long appended,flushes,bytesWritten,compactions;
    private long liveRecords,totalRecords;

    public FileObservationJournal(Path file){this.file=Objects.requireNonNull(file).toAbsolutePath().normalize();}
    public Path file(){return file;}

    /** Read every valid record. Unreadable or foreign files load as empty; they are a cache miss. */
    public synchronized Loaded load(){
        var records=new LinkedHashMap<String,Record>();long read=0,corrupt=0,bytes=0;
        try(var channel=FileChannel.open(file,StandardOpenOption.READ)){
            long size=channel.size();bytes=size;
            if(size<8)return new Loaded(Map.of(),0,size>0?1:0,bytes);
            var header=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);readFully(channel,header,0);
            if(header.flip().getLong()!=MAGIC)return new Loaded(Map.of(),0,1,bytes);
            long position=8;
            var prefix=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            while(position+8<=size){
                prefix.clear();readFully(channel,prefix,position);prefix.flip();
                int length=prefix.getInt();int crc=prefix.getInt();
                if(length<=0||length>MAX_PATH_BYTES+128||position+8+length>size){corrupt++;break;}
                var payload=ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);readFully(channel,payload,position+8);
                var checksum=new CRC32C();checksum.update(payload.array(),0,length);
                if((int)checksum.getValue()!=crc){corrupt++;break;}
                payload.flip();
                Record record;
                try{record=decode(payload);}catch(RuntimeException invalid){corrupt++;break;}
                records.put(record.path(),record);read++;position+=8+length;
            }
            if(position<size&&corrupt==0)corrupt++;
        }catch(NoSuchFileException missing){
            return new Loaded(Map.of(),0,0,0);
        }catch(IOException unreadable){
            return new Loaded(Map.of(),0,1,bytes);
        }
        liveRecords=records.size();totalRecords=read;
        return new Loaded(Collections.unmodifiableMap(records),read,corrupt,bytes);
    }

    /** Queue a record; durability is best effort and batched. */
    public synchronized void append(Record record){pending.add(Objects.requireNonNull(record));appended++;}
    public synchronized int pending(){return pending.size();}

    /**
     * Append queued records. A journal that is missing, too short or has an invalid header, or whose
     * dead records dominate, is rewritten from {@code live} with an atomic rename instead.
     */
    public synchronized void flush(Map<String,Record> live)throws IOException{
        if(pending.isEmpty())return;
        Files.createDirectories(file.getParent());
        boolean fresh=!Files.isRegularFile(file)||Files.size(file)<8||!headerValid();
        if(fresh||totalRecords+pending.size()>Math.max(4096,4L*Math.max(liveRecords,live.size()))){
            compact(live);pending.clear();return;
        }
        var out=new ByteArrayOutputStream();
        for(var record:pending)writeRecord(out,record);
        try(var channel=FileChannel.open(file,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){
            var buffer=ByteBuffer.wrap(out.toByteArray());while(buffer.hasRemaining())channel.write(buffer);
        }
        bytesWritten+=out.size();totalRecords+=pending.size();liveRecords=live.size();flushes++;pending.clear();
    }

    /** Rewrite the journal with exactly the live observations, publishing by atomic rename. */
    public synchronized void compact(Map<String,Record> live)throws IOException{
        Files.createDirectories(file.getParent());
        var out=new ByteArrayOutputStream();
        var header=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(MAGIC);out.write(header.array());
        var ordered=new TreeMap<>(live);
        for(var record:ordered.values())writeRecord(out,record);
        Path temporary=file.resolveSibling(file.getFileName()+".tmp-"+ProcessHandle.current().pid());
        try(var channel=FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
            var buffer=ByteBuffer.wrap(out.toByteArray());while(buffer.hasRemaining())channel.write(buffer);
            channel.force(true);
        }
        try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        bytesWritten+=out.size();liveRecords=ordered.size();totalRecords=ordered.size();compactions++;flushes++;
    }

    public synchronized Map<String,Object> status(){
        return Map.of("file",file.toString(),"appended",appended,"pending",pending.size(),"flushes",flushes,
                "bytes_written",bytesWritten,"compactions",compactions,"records",totalRecords,"live_records",liveRecords);
    }
    @Override public void close(){}

    private boolean headerValid(){
        try(var channel=FileChannel.open(file,StandardOpenOption.READ)){
            var header=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);readFully(channel,header,0);
            return header.flip().getLong()==MAGIC;
        }catch(IOException invalid){return false;}
    }
    private static void writeRecord(ByteArrayOutputStream out,Record record){
        byte[] path=record.path().getBytes(StandardCharsets.UTF_8);
        if(path.length>MAX_PATH_BYTES)return;
        var payload=ByteBuffer.allocate(2+path.length+8*5+32).order(ByteOrder.LITTLE_ENDIAN);
        payload.putShort((short)path.length).put(path).putLong(record.size()).putLong(record.modifiedNanos())
                .putLong(record.changedNanos()).putLong(record.inode()).putLong(record.observedAtNanos()).put(record.sha256);
        var checksum=new CRC32C();checksum.update(payload.array(),0,payload.position());
        var prefix=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.position()).putInt((int)checksum.getValue());
        out.write(prefix.array(),0,8);out.write(payload.array(),0,payload.position());
    }
    private static Record decode(ByteBuffer payload){
        int pathLength=Short.toUnsignedInt(payload.getShort());
        if(pathLength==0||pathLength>MAX_PATH_BYTES||payload.remaining()!=pathLength+8*5+32)throw new IllegalArgumentException("record");
        byte[] path=new byte[pathLength];payload.get(path);
        long size=payload.getLong(),modified=payload.getLong(),changed=payload.getLong(),inode=payload.getLong(),observed=payload.getLong();
        byte[] sha=new byte[32];payload.get(sha);
        return new Record(new String(path,StandardCharsets.UTF_8),size,modified,changed,inode,observed,sha);
    }
    private static void readFully(FileChannel channel,ByteBuffer buffer,long position)throws IOException{
        while(buffer.hasRemaining()){
            int n=channel.read(buffer,position+buffer.position());
            if(n<0)throw new EOFException();
        }
    }
}
