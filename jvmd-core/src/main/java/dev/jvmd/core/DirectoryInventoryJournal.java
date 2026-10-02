package dev.jvmd.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32C;

/**
 * Durable, validated cache of directory observations, the sibling of
 * {@link FileObservationJournal}.
 *
 * A record holds a directory's stamp (size, mtime, ctime, inode), when it was observed, its entry
 * list (name and kind) and the SHA-256 of that list. It is not authority: {@link FileStateRegistry}
 * reuses the entries instead of enumerating the directory only when the directory's current stamp
 * equals the recorded one and the observation was taken outside the racy timestamp window. A
 * missing, torn or corrupt record is a miss, and the directory is enumerated.
 *
 * Format (little-endian): 8-byte magic/version, then records {@code u32 length | u32 crc32c | payload}.
 * Payload: {@code u16 pathLen, path, i64 size, i64 mtimeNanos, i64 ctimeNanos, i64 inode,
 * i64 observedAtNanos, u32 entries, (u8 kind, u16 nameLen, name)*, 32-byte SHA-256 of the entries}.
 * Later records for a path supersede earlier ones; loading stops at the first invalid record.
 */
public final class DirectoryInventoryJournal {
    /** "JVMDDIR1". */
    static final long MAGIC=0x31524944444d564aL;
    private static final int MAX_PAYLOAD=8*1024*1024;

    public enum Kind { FILE, DIRECTORY, LINK }
    public record Entry(String name,Kind kind) {
        public Entry { Objects.requireNonNull(name);Objects.requireNonNull(kind);if(name.isEmpty()||name.contains("/"))throw new IllegalArgumentException("entry name"); }
    }
    public record Record(String path,long size,long modifiedNanos,long changedNanos,long inode,long observedAtNanos,List<Entry> entries) {
        public Record { Objects.requireNonNull(path);entries=List.copyOf(entries); }
        /** Entry list hash: the directory's membership evidence. */
        public byte[] entriesHash(){
            try{
                var digest=MessageDigest.getInstance("SHA-256");
                for(var entry:entries){digest.update((byte)entry.kind().ordinal());digest.update(entry.name().getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}
                return digest.digest();
            }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        }
    }

    private final Path file;
    private final Map<String,Record> pending=new LinkedHashMap<>();
    private long appended,flushes,bytesWritten,compactions,totalRecords,liveRecords;

    public DirectoryInventoryJournal(Path file){this.file=Objects.requireNonNull(file).toAbsolutePath().normalize();}
    public Path file(){return file;}

    /** Every valid record; an unreadable, foreign or corrupt file loads what precedes the damage. */
    public synchronized Map<String,Record> load(){
        var records=new LinkedHashMap<String,Record>();long read=0;
        try(var channel=FileChannel.open(file,StandardOpenOption.READ)){
            long size=channel.size();if(size<8)return Map.of();
            var header=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);readFully(channel,header,0);
            if(header.flip().getLong()!=MAGIC)return Map.of();
            long position=8;var prefix=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            while(position+8<=size){
                prefix.clear();readFully(channel,prefix,position);prefix.flip();
                int length=prefix.getInt(),crc=prefix.getInt();
                if(length<=0||length>MAX_PAYLOAD||position+8+length>size)break;
                var payload=ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);readFully(channel,payload,position+8);
                var checksum=new CRC32C();checksum.update(payload.array(),0,length);
                if((int)checksum.getValue()!=crc)break;
                Record record;try{record=decode(payload.flip());}catch(RuntimeException invalid){break;}
                records.put(record.path(),record);read++;position+=8+length;
            }
        }catch(IOException missingOrUnreadable){return Map.of();}
        totalRecords=read;liveRecords=records.size();
        return Collections.unmodifiableMap(records);
    }

    public synchronized void append(Record record){pending.put(record.path(),Objects.requireNonNull(record));appended++;}
    public synchronized int pending(){return pending.size();}

    /** Append queued records, or rewrite from {@code live} when the file is new, damaged or mostly dead. */
    public synchronized void flush(Map<String,Record> live)throws IOException{
        if(pending.isEmpty())return;
        Files.createDirectories(file.getParent());
        boolean fresh=!Files.isRegularFile(file)||Files.size(file)<8||!headerValid();
        if(fresh||totalRecords+pending.size()>Math.max(1024,4L*Math.max(liveRecords,live.size()))){compact(live);pending.clear();return;}
        var out=new ByteArrayOutputStream();for(var record:pending.values())writeRecord(out,record);
        try(var channel=FileChannel.open(file,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){
            var buffer=ByteBuffer.wrap(out.toByteArray());while(buffer.hasRemaining())channel.write(buffer);
        }
        bytesWritten+=out.size();totalRecords+=pending.size();liveRecords=live.size();flushes++;pending.clear();
    }
    public synchronized void compact(Map<String,Record> live)throws IOException{
        Files.createDirectories(file.getParent());
        var out=new ByteArrayOutputStream();out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(MAGIC).array());
        var ordered=new TreeMap<>(live);for(var record:ordered.values())writeRecord(out,record);
        Path temporary=file.resolveSibling(file.getFileName()+".tmp-"+ProcessHandle.current().pid());
        try(var channel=FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
            var buffer=ByteBuffer.wrap(out.toByteArray());while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        bytesWritten+=out.size();liveRecords=ordered.size();totalRecords=ordered.size();compactions++;flushes++;
    }
    public synchronized Map<String,Object> status(){
        return Map.of("file",file.toString(),"appended",appended,"pending",pending.size(),"flushes",flushes,
                "bytes_written",bytesWritten,"compactions",compactions,"records",totalRecords,"live_records",liveRecords);
    }

    private boolean headerValid(){
        try(var channel=FileChannel.open(file,StandardOpenOption.READ)){
            var header=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);readFully(channel,header,0);return header.flip().getLong()==MAGIC;
        }catch(IOException invalid){return false;}
    }
    private static void writeRecord(ByteArrayOutputStream out,Record record){
        byte[] path=record.path().getBytes(StandardCharsets.UTF_8);if(path.length>Short.MAX_VALUE)return;
        var names=new ArrayList<byte[]>();int size=2+path.length+8*5+4+32;
        for(var entry:record.entries()){byte[] name=entry.name().getBytes(StandardCharsets.UTF_8);if(name.length>Short.MAX_VALUE)return;names.add(name);size+=3+name.length;}
        if(size>MAX_PAYLOAD)return;
        var payload=ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        payload.putShort((short)path.length).put(path).putLong(record.size()).putLong(record.modifiedNanos()).putLong(record.changedNanos())
                .putLong(record.inode()).putLong(record.observedAtNanos()).putInt(record.entries().size());
        for(int i=0;i<names.size();i++)payload.put((byte)record.entries().get(i).kind().ordinal()).putShort((short)names.get(i).length).put(names.get(i));
        payload.put(record.entriesHash());
        var checksum=new CRC32C();checksum.update(payload.array(),0,size);
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(size).putInt((int)checksum.getValue()).array(),0,8);
        out.write(payload.array(),0,size);
    }
    private static Record decode(ByteBuffer payload){
        int pathLength=Short.toUnsignedInt(payload.getShort());if(pathLength==0)throw new IllegalArgumentException("path");
        byte[] path=new byte[pathLength];payload.get(path);
        long size=payload.getLong(),modified=payload.getLong(),changed=payload.getLong(),inode=payload.getLong(),observed=payload.getLong();
        int count=payload.getInt();if(count<0||count>payload.remaining()/3)throw new IllegalArgumentException("entries");
        var entries=new ArrayList<Entry>(count);var kinds=Kind.values();
        for(int i=0;i<count;i++){
            int kind=Byte.toUnsignedInt(payload.get());if(kind>=kinds.length)throw new IllegalArgumentException("kind");
            byte[] name=new byte[Short.toUnsignedInt(payload.getShort())];payload.get(name);
            entries.add(new Entry(new String(name,StandardCharsets.UTF_8),kinds[kind]));
        }
        byte[] hash=new byte[32];payload.get(hash);if(payload.hasRemaining())throw new IllegalArgumentException("trailing");
        var record=new Record(new String(path,StandardCharsets.UTF_8),size,modified,changed,inode,observed,entries);
        if(!Arrays.equals(hash,record.entriesHash()))throw new IllegalArgumentException("entries hash");
        return record;
    }
    private static void readFully(FileChannel channel,ByteBuffer buffer,long position)throws IOException{
        while(buffer.hasRemaining())if(channel.read(buffer,position+buffer.position())<0)throw new EOFException();
    }
}
