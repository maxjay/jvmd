package dev.jvmd.index.segment;

import dev.jvmd.core.Hash256;
import java.io.*;
import java.lang.foreign.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/**
 * Derived MACHINE accelerators (architecture §30–31, §52, §54–56, §65–66, §102, Phase 12).
 *
 * Accelerators are regenerable from the sealed base segment, so they live in a separate file and
 * never enter semantic proof identity. Each file binds to the base segment's semantic identity and
 * physical identity. A negative answer is authoritative only when that binding and the integrity
 * checks pass; otherwise the accelerator reports UNKNOWN and callers use the canonical slow path.
 *
 * <ul>
 *   <li>membership filter over type binary names and packages (no false negatives for valid data);</li>
 *   <li>trigram postings for substring search;</li>
 *   <li>reverse CSR for incoming relationships;</li>
 *   <li>sparse member checkpoints for large owners only.</li>
 * </ul>
 *
 * <p>Benchmark code, not production: the strict task's W8 rule kept RocksDB as the MACHINE backend
 * ({@code docs/evidence/machine-decision.md}), so this proof of concept lives with its harness.
 */
public final class MachineAccelerators implements AutoCloseable {
    static final long MAGIC=0x31434341444d564aL; // "JVMDACC1"
    static final int VERSION=1;
    private static final ValueLayout.OfInt INT=ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG=ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final int FILTER_HASHES=7;
    private static final Set<String> TYPES=Set.of("class","interface","enum","record","annotation");

    /** Answer of a negative-authoritative accelerator. */
    public enum Membership { ABSENT, MAYBE, UNKNOWN }

    // ------------------------------------------------------------------ writing

    public static void write(MachineSegment base,Path target,int largeOwnerThreshold)throws IOException{
        int n=base.symbolCount();
        // Membership filter: binary names of types and their packages. ~10 bits per element.
        var elements=new ArrayList<String>();var packages=new TreeSet<String>();
        for(int id=0;id<n;id++)if(TYPES.contains(base.kind(id))){
            String key=base.key(id);elements.add("t:"+key);
            int dot=key.lastIndexOf('.');packages.add(dot<0?"":key.substring(0,dot));
        }
        for(String pkg:packages)elements.add("p:"+pkg);
        long bits=Math.max(64,((long)elements.size()*10+63)/64*64);
        var filter=new long[(int)(bits/64)];
        for(String element:elements)for(long index:positions(element,bits))filter[(int)(index>>>6)]|=1L<<(index&63);
        var filterBytes=MachineSegment.buffer(8+8L*filter.length).putLong(bits);for(long word:filter)filterBytes.putLong(word);

        // Trigram postings over lower-cased names: sorted (gram, ids[]) table.
        var grams=new TreeMap<String,TreeSet<Integer>>();
        for(int id=0;id<n;id++){
            String name=base.name(id).toLowerCase(Locale.ROOT);
            for(int i=0;i+3<=name.length();i++)grams.computeIfAbsent(name.substring(i,i+3),ignored->new TreeSet<>()).add(id);
        }
        var gramDirectory=new ByteArrayOutputStream();var gramPostings=new ByteArrayOutputStream();int postingCount=0;
        for(var entry:grams.entrySet()){
            byte[] gram=entry.getKey().getBytes(StandardCharsets.UTF_8);if(gram.length>12)continue;
            var row=MachineSegment.buffer(20);byte[] padded=Arrays.copyOf(gram,12);
            row.put(padded).putInt(postingCount).putInt(entry.getValue().size());gramDirectory.writeBytes(row.array());
            for(int id:entry.getValue()){gramPostings.writeBytes(MachineSegment.buffer(4).putInt(id).array());postingCount++;}
        }

        // Reverse CSR keyed by target string id: offsets over sources in source order.
        int strings=base.stringCount();int edges=base.edgeCount();
        var counts=new int[strings+1];
        for(int i=0;i<edges;i++)counts[base.edgeTarget(i)+1]++;
        for(int i=0;i<strings;i++)counts[i+1]+=counts[i];
        var reverse=new int[edges];var fill=Arrays.copyOf(counts,strings);
        for(int id=0;id<n;id++)for(int i=base.edgeSourceStart(id),end=base.edgeSourceStart(id+1);i<end;i++){
            reverse[fill[base.edgeTarget(i)]++]=i;
        }
        var reverseOffsets=MachineSegment.buffer(4L*(strings+1));for(int value:counts)reverseOffsets.putInt(value);
        var reverseEdges=MachineSegment.buffer(4L*edges);for(int value:reverse)reverseEdges.putInt(value);

        // Large-owner checkpoints: (owner, member count) for owners above the threshold. The range
        // identity itself is recomputed over the bounded checkpoint interval; recorded only for
        // the measured experiment.
        var checkpoints=new ByteArrayOutputStream();
        for(int owner=0;owner<n;owner++)if(base.memberCount(owner)>=largeOwnerThreshold)
            checkpoints.writeBytes(MachineSegment.buffer(8).putInt(owner).putInt(base.memberCount(owner)).array());

        var sections=List.of(filterBytes.array(),gramDirectory.toByteArray(),gramPostings.toByteArray(),
                reverseOffsets.array(),reverseEdges.array(),checkpoints.toByteArray());
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary=target.resolveSibling(target.getFileName()+".tmp-"+ProcessHandle.current().pid()+"-"+System.nanoTime());
        try(var channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var header=MachineSegment.buffer(8+4+4+32+32);
            header.putLong(MAGIC).putInt(VERSION).putInt(sections.size()).put(base.semanticIdentity().bytes()).put(base.physicalIdentity().bytes());
            write(channel,header.array());
            var directory=MachineSegment.buffer(20L*sections.size());long offset=header.capacity();
            for(byte[] section:sections){
                var crc=new CRC32C();crc.update(section);
                directory.putLong(offset).putLong(section.length).putInt((int)crc.getValue());
                write(channel,section);offset+=section.length;
            }
            write(channel,directory.array());write(channel,MachineSegment.buffer(8).putLong(MAGIC).array());
            channel.force(true);
        }
        try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
    }
    private static void write(FileChannel channel,byte[] bytes)throws IOException{
        var value=ByteBuffer.wrap(bytes);while(value.hasRemaining())channel.write(value);
    }
    private static long[] positions(String element,long bits){
        long h1=murmur(element,0x9747b28cL),h2=murmur(element,0x5bd1e995L)|1;
        var result=new long[FILTER_HASHES];
        for(int i=0;i<FILTER_HASHES;i++)result[i]=Math.floorMod(h1+i*h2,bits);
        return result;
    }
    private static long murmur(String value,long seed){
        long h=seed^value.length();
        for(byte b:value.getBytes(StandardCharsets.UTF_8)){h^=b&0xff;h*=0xff51afd7ed558ccdL;h^=h>>>33;}
        h*=0xc4ceb9fe1a85ec53L;h^=h>>>33;return h;
    }

    // ------------------------------------------------------------------ reading

    private final Arena arena;
    private final MemorySegment file;
    private final MachineSegment base;
    private final long[] offset,length;
    private final boolean valid;
    private final String invalidReason;

    private MachineAccelerators(MachineSegment base,Arena arena,MemorySegment file){
        this.base=base;this.arena=arena;this.file=file;
        long[] offsets=new long[6],lengths=new long[6];String reason=null;
        try{
            long size=file==null?0:file.byteSize();
            if(file==null)reason="missing";
            else if(size<8+4+4+64+8||file.get(LONG,0)!=MAGIC||file.get(LONG,size-8)!=MAGIC||file.get(INT,8)!=VERSION||file.get(INT,12)!=6)reason="foreign";
            else{
                byte[] semantic=new byte[32],physical=new byte[32];
                MemorySegment.copy(file,ValueLayout.JAVA_BYTE,16,semantic,0,32);MemorySegment.copy(file,ValueLayout.JAVA_BYTE,48,physical,0,32);
                if(!new Hash256(semantic).equals(base.semanticIdentity())||!new Hash256(physical).equals(base.physicalIdentity()))reason="binding";
                long directory=size-8-20L*6;
                for(int i=0;reason==null&&i<6;i++){
                    long at=directory+20L*i;offsets[i]=file.get(LONG,at);lengths[i]=file.get(LONG,at+8);int crc=file.get(INT,at+16);
                    if(offsets[i]<80||lengths[i]<0||offsets[i]+lengths[i]>directory){reason="bounds";break;}
                    var checksum=new CRC32C();checksum.update(file.asSlice(offsets[i],lengths[i]).asByteBuffer());
                    if((int)checksum.getValue()!=crc)reason="checksum";
                }
            }
        }catch(RuntimeException invalid){reason="corrupt";}
        offset=offsets;length=lengths;valid=reason==null;invalidReason=reason==null?"":reason;
    }

    /** Open accelerators for {@code base}. Missing or invalid files yield an invalid (UNKNOWN) view. */
    public static MachineAccelerators open(MachineSegment base,Path path){
        var arena=Arena.ofShared();
        try(var channel=FileChannel.open(path,StandardOpenOption.READ)){
            return new MachineAccelerators(base,arena,channel.map(FileChannel.MapMode.READ_ONLY,0,channel.size(),arena));
        }catch(IOException|RuntimeException missing){
            return new MachineAccelerators(base,arena,null);
        }
    }
    @Override public void close(){arena.close();}
    public boolean valid(){return valid;}
    public String invalidReason(){return invalidReason;}
    public long mappedBytes(){return file==null?0:file.byteSize();}

    /**
     * Negative-authoritative type membership. ABSENT only when integrity and the base binding hold;
     * an invalid accelerator answers UNKNOWN, never ABSENT (§66).
     */
    public Membership type(String binaryName){return membership("t:"+binaryName);}
    public Membership packageName(String name){return membership("p:"+name);}
    private Membership membership(String element){
        if(!valid)return Membership.UNKNOWN;
        long bits=file.get(LONG,offset[0]);
        for(long index:positions(element,bits)){
            long word=file.get(LONG,offset[0]+8+8*(index>>>6));
            if((word&(1L<<(index&63)))==0)return Membership.ABSENT;
        }
        return Membership.MAYBE;
    }

    /** Substring search; falls back to the canonical scan when the accelerator is unavailable. */
    public List<Integer> substring(String query,int limit){
        String needle=query.toLowerCase(Locale.ROOT);
        if(!valid||needle.length()<3)return base.substringScan(query,limit);
        List<Integer> candidates=null;
        for(int i=0;i+3<=needle.length();i++){
            var posting=posting(needle.substring(i,i+3));
            if(posting.isEmpty())return List.of();
            if(candidates==null||posting.size()<candidates.size())candidates=posting;
        }
        var matches=new TreeMap<String,Integer>();
        for(int id:candidates)if(base.name(id).toLowerCase(Locale.ROOT).contains(needle))matches.put(base.name(id)+"\u0000"+base.key(id),id);
        return matches.values().stream().limit(limit).toList();
    }
    private List<Integer> posting(String gram){
        int count=(int)(length[1]/20);int low=0,high=count-1;byte[] wanted=Arrays.copyOf(gram.getBytes(StandardCharsets.UTF_8),12);
        while(low<=high){
            int middle=(low+high)>>>1;long at=offset[1]+20L*middle;
            int compared=compare(at,wanted);
            if(compared<0)low=middle+1;else if(compared>0)high=middle-1;
            else{
                int start=file.get(INT,at+12),size=file.get(INT,at+16);var result=new ArrayList<Integer>(size);
                for(int i=0;i<size;i++)result.add(file.get(INT,offset[2]+4L*(start+i)));
                return result;
            }
        }
        return List.of();
    }
    private int compare(long at,byte[] wanted){
        for(int i=0;i<12;i++){
            int a=Byte.toUnsignedInt(file.get(ValueLayout.JAVA_BYTE,at+i)),b=Byte.toUnsignedInt(wanted[i]);
            if(a!=b)return Integer.compare(a,b);
        }
        return 0;
    }

    /** Incoming relationships through the reverse CSR, or the canonical scan when unavailable. */
    public List<MachineSegment.Edge> incoming(String target,String kind){
        if(!valid)return base.incomingScan(target,kind);
        int targetId=base.stringId(target);if(targetId<0)return List.of();
        int start=file.get(INT,offset[3]+4L*targetId),end=file.get(INT,offset[3]+4L*(targetId+1));
        var result=new ArrayList<MachineSegment.Edge>();
        for(int i=start;i<end;i++){
            int edge=file.get(INT,offset[4]+4L*i);String edgeKind=base.string(base.edgeKind(edge));
            if(kind!=null&&!kind.equals(edgeKind))continue;
            result.add(new MachineSegment.Edge(sourceOf(edge),target,edgeKind));
        }
        result.sort(Comparator.comparingInt(MachineSegment.Edge::source));
        return result;
    }
    private int sourceOf(int edge){
        int low=0,high=base.symbolCount()-1;
        // The largest id whose CSR range starts at or before the edge owns it.
        while(low<high){int middle=(low+high+1)>>>1;if(base.edgeSourceStart(middle)<=edge)low=middle;else high=middle-1;}
        return low;
    }
    public int largeOwners(){return valid?(int)(length[5]/8):0;}
}
