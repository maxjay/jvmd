package dev.jvmd.index.segment;

import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.Hash256;
import dev.jvmd.index.ArtifactIndexFormat;
import dev.jvmd.index.ResolutionFact;
import java.io.*;
import java.lang.foreign.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32C;

/**
 * Minimal native MACHINE segment (architecture §37–53, §98–105, Phase 11).
 *
 * One immutable, sealed file per artifact, read through a read-only memory mapping with bounded
 * primitive access. Nothing is eagerly deserialised into JVM objects, and there is no application
 * block cache: the OS page cache is the cache (§99).
 *
 * <h2>Layout (little-endian, explicit widths, UTF-8)</h2>
 * <pre>
 *   HEADER   magic "JVMDSEG1" u64 | format u32 | symbols u32 | sections u32 | reserved u32
 *   STRINGS          sorted unique UTF-8 bytes in deflate blocks of about 16 KiB raw; a string never
 *                    spans blocks (§42)
 *   STRING_OFFSETS   u32[strings+1] offsets into the uncompressed concatenation (StringId = ordinal, §40)
 *   STRING_BLOCKS    (u32 rawStart, u32 compressedStart)[blocks+1] block directory, sentinel last
 *   SYMBOLS          hot rows, 8 × u32 per symbol: key fqn name owner flags descriptor signature kind
 *                    (SymbolId = row ordinal; rows are in binary-key order, so exact lookup is a
 *                    binary search over the key column — no separate id column, §40, §53)
 *   COLD             4 × u32 per symbol: resolution-fact metadata entry parameters (cold columns, §43)
 *   MEMBER_ORDER     u32[] member ids ordered by (owner, name, key)
 *   OWNER_OFFSETS    u32[symbols+1] into MEMBER_ORDER    (§47)
 *   OWNER_AGGREGATES (u32 owner, 32-byte identity)[]     whole-owner member-range identity (§48)
 *   NAME_ORDER       u32[] ids ordered by (name, key)    simple-name and prefix lookup
 *   EDGE_OFFSETS     u32[symbols+1]                      CSR forward relationships (§51)
 *   EDGES            (u32 target, u32 kind)[]            targets stay symbolic binary names (§44)
 *   IDENTITY         32-byte semantic resolution identity of the artifact (base binding)
 *   FOOTER           section directory (id u32, offset u64, length u64, crc32c u32) × sections
 *                    | SHA-256 over every section (physical identity) | magic
 * </pre>
 * Everything workspace-effective (winning artifact, inherited members, accessibility) is absent:
 * it belongs above the immutable artifact (§46).
 *
 * <p>Benchmark code, not production: the strict task's W8 rule kept RocksDB as the MACHINE backend
 * ({@code docs/evidence/machine-decision.md}), so this proof of concept lives with its harness.
 */
public final class MachineSegment implements AutoCloseable {
    static final long MAGIC=0x31474553444d564aL;   // "JVMDSEG1"
    public static final int FORMAT_VERSION=2;
    /** Raw bytes per compressed string block; small enough that one inflate per cold probe is cheap. */
    static final int STRING_BLOCK=16*1024;
    static final int ROW=32,COLD=16;
    private static final String RANGE_DOMAIN="semantic-member-range-v1";
    private static final ValueLayout.OfInt INT=ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG=ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    enum Section { STRINGS,STRING_OFFSETS,SYMBOLS,COLD,MEMBER_ORDER,OWNER_OFFSETS,OWNER_AGGREGATES,NAME_ORDER,EDGE_OFFSETS,EDGES,IDENTITY,STRING_BLOCKS }

    /** Thrown when a segment fails validation; callers invalidate and rebuild (never partial authority). */
    public static final class CorruptSegment extends IOException {
        CorruptSegment(String message){super(message);}
    }

    // ------------------------------------------------------------------ writing

    /**
     * Write and publish a sealed segment crash-safely (§101): temp file, checksums, force, atomic
     * rename. After a crash a reader observes either the previous file or the complete new one.
     */
    public static Hash256 write(ArtifactIndexFormat.ArtifactData data,Path target)throws IOException{
        var symbols=data.symbols();int n=symbols.size();
        for(int i=0;i<n;i++)if(symbols.get(i).id()!=i)throw new IllegalArgumentException("Symbol ids must be row ordinals");
        for(int i=1;i<n;i++)if(symbols.get(i-1).key().compareTo(symbols.get(i).key())>=0)
            throw new IllegalArgumentException("Symbols must be strictly ordered by binary key");

        var strings=new TreeSet<String>();
        for(var symbol:symbols){
            for(String value:Arrays.asList(symbol.key(),symbol.fqn(),symbol.name(),symbol.descriptor(),symbol.signature(),symbol.kind(),
                    symbol.resolution().encode(),symbol.metadataJson(),symbol.entry(),String.join("\u0000",symbol.parameters())))
                if(value!=null)strings.add(value);
        }
        for(var edge:data.relationships()){strings.add(edge.target());strings.add(edge.kind());}
        var dictionary=new ArrayList<>(strings);var ids=new HashMap<String,Integer>(dictionary.size()*2);
        for(int i=0;i<dictionary.size();i++)ids.put(dictionary.get(i),i);

        var offsets=buffer(4L*(dictionary.size()+1));var compressed=new ByteArrayOutputStream();var blocks=new ByteArrayOutputStream();
        {
            var block=new ByteArrayOutputStream();int raw=0,blockStart=0;var deflater=new java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED,true);
            try{
                for(String value:dictionary){
                    byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
                    if(block.size()>0&&block.size()+bytes.length>STRING_BLOCK){
                        sealBlock(deflater,block,blockStart,compressed,blocks);blockStart=raw;
                    }
                    offsets.putInt(raw);block.writeBytes(bytes);raw+=bytes.length;
                }
                if(block.size()>0)sealBlock(deflater,block,blockStart,compressed,blocks);
            }finally{deflater.end();}
            offsets.putInt(raw);
            blocks.writeBytes(buffer(8).putInt(raw).putInt(compressed.size()).array());
        }

        var rows=buffer((long)ROW*n);var cold=buffer((long)COLD*n);
        // String id -1 encodes null: absence of a value is preserved, never collapsed to "".
        java.util.function.ToIntFunction<String> sid=value->value==null?-1:ids.get(value);
        for(var symbol:symbols){
            rows.putInt(sid.applyAsInt(symbol.key())).putInt(sid.applyAsInt(symbol.fqn())).putInt(sid.applyAsInt(symbol.name())).putInt(symbol.ownerId())
                    .putInt(symbol.flags()).putInt(sid.applyAsInt(symbol.descriptor())).putInt(sid.applyAsInt(symbol.signature())).putInt(sid.applyAsInt(symbol.kind()));
            cold.putInt(sid.applyAsInt(symbol.resolution().encode())).putInt(sid.applyAsInt(symbol.metadataJson()))
                    .putInt(sid.applyAsInt(symbol.entry())).putInt(sid.applyAsInt(String.join("\u0000",symbol.parameters())));
        }

        var members=new ArrayList<Integer>();
        for(var symbol:symbols)if(symbol.ownerId()>=0&&symbol.ownerId()<n)members.add(symbol.id());
        members.sort(Comparator.comparingInt((Integer id)->symbols.get(id).ownerId())
                .thenComparing(id->nz(symbols.get(id).name())).thenComparing(id->symbols.get(id).key()));
        var memberOrder=buffer(4L*members.size());members.forEach(memberOrder::putInt);
        var ownerOffsets=buffer(4L*(n+1));
        var aggregates=new ByteArrayOutputStream();
        {
            int cursor=0;
            for(int owner=0;owner<n;owner++){
                ownerOffsets.putInt(cursor);
                var accumulator=new AlgebraicAccumulator(RANGE_DOMAIN);int start=cursor;
                while(cursor<members.size()&&symbols.get(members.get(cursor)).ownerId()==owner){
                    var member=symbols.get(members.get(cursor));
                    accumulator.add(member.resolution().symbolKey(),member.resolution().identity());cursor++;
                }
                if(cursor>start){
                    var entry=buffer(36);entry.putInt(owner).put(accumulator.identity().bytes());aggregates.writeBytes(entry.array());
                }
            }
            ownerOffsets.putInt(cursor);
        }

        var byName=new ArrayList<Integer>(n);for(int i=0;i<n;i++)byName.add(i);
        byName.sort(Comparator.comparing((Integer id)->nz(symbols.get(id).name())).thenComparing(id->symbols.get(id).key()));
        var nameOrder=buffer(4L*n);byName.forEach(nameOrder::putInt);

        var outgoing=new ArrayList<List<ArtifactIndexFormat.Relationship>>(n);for(int i=0;i<n;i++)outgoing.add(new ArrayList<>());
        for(var edge:data.relationships())if(edge.sourceId()>=0&&edge.sourceId()<n)outgoing.get(edge.sourceId()).add(edge);
        var edgeOffsets=buffer(4L*(n+1));var edges=new ByteArrayOutputStream();int edgeCount=0;
        for(var list:outgoing){
            list.sort(Comparator.comparing(ArtifactIndexFormat.Relationship::kind).thenComparing(ArtifactIndexFormat.Relationship::target));
            edgeOffsets.putInt(edgeCount);
            for(var edge:list){var row=buffer(8);row.putInt(ids.get(edge.target())).putInt(ids.get(edge.kind()));edges.writeBytes(row.array());edgeCount++;}
        }
        edgeOffsets.putInt(edgeCount);

        var identity=ArtifactIndexFormat.resolutionIdentity(data);
        var sections=new LinkedHashMap<Section,byte[]>();
        sections.put(Section.STRINGS,compressed.toByteArray());sections.put(Section.STRING_OFFSETS,offsets.array());
        sections.put(Section.SYMBOLS,rows.array());sections.put(Section.COLD,cold.array());
        sections.put(Section.MEMBER_ORDER,memberOrder.array());sections.put(Section.OWNER_OFFSETS,ownerOffsets.array());
        sections.put(Section.OWNER_AGGREGATES,aggregates.toByteArray());sections.put(Section.NAME_ORDER,nameOrder.array());
        sections.put(Section.EDGE_OFFSETS,edgeOffsets.array());sections.put(Section.EDGES,edges.toByteArray());
        sections.put(Section.IDENTITY,identity.bytes());sections.put(Section.STRING_BLOCKS,blocks.toByteArray());
        return publish(target,n,sections);
    }
    private static void sealBlock(java.util.zip.Deflater deflater,ByteArrayOutputStream block,int rawStart,ByteArrayOutputStream compressed,ByteArrayOutputStream blocks){
        blocks.writeBytes(buffer(8).putInt(rawStart).putInt(compressed.size()).array());
        deflater.reset();deflater.setInput(block.toByteArray());deflater.finish();
        var out=new byte[Math.max(64,block.size()+64)];
        while(!deflater.finished()){int n=deflater.deflate(out);compressed.write(out,0,n);}
        block.reset();
    }

    static Hash256 publish(Path target,int count,Map<Section,byte[]> sections)throws IOException{
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary=target.resolveSibling(target.getFileName()+".tmp-"+ProcessHandle.current().pid()+"-"+System.nanoTime());
        MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        try(var channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var header=buffer(24).putLong(MAGIC).putInt(FORMAT_VERSION).putInt(count).putInt(sections.size()).putInt(0);
            write(channel,header.array());
            long offset=24;var directory=buffer(24L*sections.size());
            for(var entry:sections.entrySet()){
                byte[] bytes=entry.getValue();var crc=new CRC32C();crc.update(bytes);
                directory.putInt(entry.getKey().ordinal()).putLong(offset).putLong(bytes.length).putInt((int)crc.getValue());
                write(channel,bytes);digest.update(bytes);offset+=bytes.length;
            }
            byte[] physical=digest.digest();
            write(channel,directory.array());write(channel,physical);
            write(channel,buffer(8).putLong(MAGIC).array());
            channel.force(true);
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
            return new Hash256(physical);
        }catch(IOException|RuntimeException failure){
            Files.deleteIfExists(temporary);throw failure;
        }
    }
    private static void write(FileChannel channel,byte[] bytes)throws IOException{
        var value=ByteBuffer.wrap(bytes);while(value.hasRemaining())channel.write(value);
    }
    static ByteBuffer buffer(long size){return ByteBuffer.allocate(Math.toIntExact(size)).order(ByteOrder.LITTLE_ENDIAN);}
    private static String nz(String value){return value==null?"":value;}

    // ------------------------------------------------------------------ reading

    private final Arena arena;
    private final MemorySegment file;
    private final long[] offset=new long[Section.values().length],length=new long[Section.values().length];
    private final int symbols,strings,stringBlocks;
    /** Recently inflated string blocks; a tiny direct-mapped cache, the page cache holds the rest (§99). */
    private final int[] cachedBlock=new int[8];private final byte[][] cachedBytes=new byte[8][];
    private final Hash256 physicalIdentity,semanticIdentity;

    private MachineSegment(Arena arena,MemorySegment file,boolean verifyChecksums)throws CorruptSegment{
        this.arena=arena;this.file=file;
        long size=file.byteSize();
        if(size<24+8+32||file.get(LONG,0)!=MAGIC||file.get(LONG,size-8)!=MAGIC)throw new CorruptSegment("not a sealed segment");
        if(file.get(INT,8)!=FORMAT_VERSION)throw new CorruptSegment("unsupported segment format");
        symbols=file.get(INT,12);int sectionCount=file.get(INT,16);
        if(symbols<0||sectionCount!=Section.values().length)throw new CorruptSegment("section directory");
        long directory=size-8-32-24L*sectionCount;
        if(directory<24)throw new CorruptSegment("truncated directory");
        Arrays.fill(offset,-1);
        for(int i=0;i<sectionCount;i++){
            long at=directory+24L*i;int id=file.get(INT,at);long start=file.get(LONG,at+4),bytes=file.get(LONG,at+12);int crc=file.get(INT,at+20);
            if(id<0||id>=sectionCount||offset[id]>=0||start<24||bytes<0||start+bytes>directory)throw new CorruptSegment("section bounds");
            offset[id]=start;length[id]=bytes;
            if(verifyChecksums){
                var checksum=new CRC32C();checksum.update(file.asSlice(start,bytes).asByteBuffer());
                if((int)checksum.getValue()!=crc)throw new CorruptSegment("section checksum: "+Section.values()[id]);
            }
        }
        for(long value:offset)if(value<0)throw new CorruptSegment("missing section");
        byte[] physical=new byte[32];MemorySegment.copy(file,ValueLayout.JAVA_BYTE,directory+24L*sectionCount,physical,0,32);
        physicalIdentity=new Hash256(physical);
        strings=(int)(length[Section.STRING_OFFSETS.ordinal()]/4)-1;
        if(strings<0||length[Section.SYMBOLS.ordinal()]!=(long)ROW*symbols||length[Section.COLD.ordinal()]!=(long)COLD*symbols
                ||length[Section.OWNER_OFFSETS.ordinal()]!=4L*(symbols+1)||length[Section.NAME_ORDER.ordinal()]!=4L*symbols
                ||length[Section.EDGE_OFFSETS.ordinal()]!=4L*(symbols+1)||length[Section.IDENTITY.ordinal()]!=32)
            throw new CorruptSegment("section sizes");
        stringBlocks=(int)(length[Section.STRING_BLOCKS.ordinal()]/8)-1;
        if(stringBlocks<0||length[Section.STRING_BLOCKS.ordinal()]%8!=0)throw new CorruptSegment("string blocks");
        Arrays.fill(cachedBlock,-1);
        byte[] semantic=new byte[32];MemorySegment.copy(file,ValueLayout.JAVA_BYTE,offset[Section.IDENTITY.ordinal()],semantic,0,32);
        semanticIdentity=new Hash256(semantic);
    }

    /** Map and validate a sealed segment. Any failure is a cache invalidation, never partial data. */
    public static MachineSegment open(Path path,boolean verifyChecksums)throws IOException{
        var arena=Arena.ofShared();
        try(var channel=FileChannel.open(path,StandardOpenOption.READ)){
            var mapped=channel.map(FileChannel.MapMode.READ_ONLY,0,channel.size(),arena);
            return new MachineSegment(arena,mapped,verifyChecksums);
        }catch(IOException|RuntimeException failure){arena.close();throw failure;}
    }
    public static MachineSegment open(Path path)throws IOException{return open(path,true);}
    @Override public void close(){arena.close();}

    public int symbolCount(){return symbols;}
    /** Persisted bytes per section, for the materialisation table (§112–113). */
    public Map<String,Long> sectionBytes(){
        var result=new LinkedHashMap<String,Long>();
        for(var section:Section.values())result.put(section.name().toLowerCase(Locale.ROOT),length[section.ordinal()]);
        return result;
    }
    public long mappedBytes(){return file.byteSize();}
    /** Physical identity: SHA-256 of the exact persisted section bytes (§32). */
    public Hash256 physicalIdentity(){return physicalIdentity;}
    /** Semantic resolution projection identity; independent of layout and accelerators (§105). */
    public Hash256 semanticIdentity(){return semanticIdentity;}

    private int intAt(Section section,long index){return file.get(INT,offset[section.ordinal()]+4*index);}
    private int row(int id,int column){return file.get(INT,offset[Section.SYMBOLS.ordinal()]+(long)ROW*id+4L*column);}
    private int cold(int id,int column){return file.get(INT,offset[Section.COLD.ordinal()]+(long)COLD*id+4L*column);}
    String string(int id){
        if(id==-1)return null;
        if(id<0||id>=strings)throw new IndexOutOfBoundsException("string "+id);
        int start=intAt(Section.STRING_OFFSETS,id),end=intAt(Section.STRING_OFFSETS,id+1);
        if(start==end)return "";
        int low=0,high=stringBlocks-1;
        while(low<high){int middle=(low+high+1)>>>1;if(intAt(Section.STRING_BLOCKS,2L*middle)<=start)low=middle;else high=middle-1;}
        int rawStart=intAt(Section.STRING_BLOCKS,2L*low);
        return new String(block(low),start-rawStart,end-start,StandardCharsets.UTF_8);
    }
    private byte[] block(int index){
        int slot=index&(cachedBlock.length-1);
        synchronized(cachedBlock){if(cachedBlock[slot]==index)return cachedBytes[slot];}
        int rawStart=intAt(Section.STRING_BLOCKS,2L*index),rawEnd=intAt(Section.STRING_BLOCKS,2L*index+2);
        int from=intAt(Section.STRING_BLOCKS,2L*index+1),to=intAt(Section.STRING_BLOCKS,2L*index+3);
        if(rawEnd<rawStart||to<from||to>length[Section.STRINGS.ordinal()])throw new IllegalStateException("corrupt string block "+index);
        byte[] input=new byte[to-from];MemorySegment.copy(file,ValueLayout.JAVA_BYTE,offset[Section.STRINGS.ordinal()]+from,input,0,input.length);
        byte[] output=new byte[rawEnd-rawStart];var inflater=new java.util.zip.Inflater(true);
        try{
            inflater.setInput(input);int done=0;
            while(done<output.length){int n=inflater.inflate(output,done,output.length-done);if(n==0&&(inflater.finished()||inflater.needsInput()))break;done+=n;}
            if(done!=output.length)throw new IllegalStateException("corrupt string block "+index);
        }catch(java.util.zip.DataFormatException invalid){throw new IllegalStateException("corrupt string block "+index,invalid);}
        finally{inflater.end();}
        synchronized(cachedBlock){cachedBlock[slot]=index;cachedBytes[slot]=output;}
        return output;
    }
    public String key(int id){return string(row(id,0));}
    public String name(int id){return nz(string(row(id,2)));}
    public String kind(int id){return nz(string(row(id,7)));}
    public int owner(int id){return row(id,3);}

    public ArtifactIndexFormat.SymbolRecord symbol(int id){
        Objects.checkIndex(id,symbols);
        String parameters=string(cold(id,3));
        return new ArtifactIndexFormat.SymbolRecord(id,row(id,3),string(row(id,0)),string(row(id,1)),string(row(id,2)),string(row(id,7)),
                string(row(id,6)),string(row(id,5)),row(id,4),string(cold(id,2)),
                parameters.isEmpty()?List.of():List.of(parameters.split("\u0000",-1)),string(cold(id,1)),
                ResolutionFact.decode(string(cold(id,0))));
    }

    /** Exact binary-key lookup by binary search over the ordered key column (§53). */
    public int exact(String key){
        int low=0,high=symbols-1;
        while(low<=high){
            int middle=(low+high)>>>1;int compared=key(middle).compareTo(key);
            if(compared<0)low=middle+1;else if(compared>0)high=middle-1;else return middle;
        }
        return -1;
    }

    /** Ids whose simple name starts with {@code prefix}, in (name, key) order. */
    public List<Integer> namePrefix(String prefix,int limit){
        int low=lowerName(prefix);var result=new ArrayList<Integer>();
        for(int i=low;i<symbols&&result.size()<limit;i++){
            int id=intAt(Section.NAME_ORDER,i);if(!name(id).startsWith(prefix))break;result.add(id);
        }
        return result;
    }
    public List<Integer> exactName(String name,int limit){
        var result=new ArrayList<Integer>();
        for(int id:namePrefix(name,Integer.MAX_VALUE)){if(!name(id).equals(name))break;result.add(id);if(result.size()>=limit)break;}
        return result;
    }
    private int lowerName(String prefix){
        int low=0,high=symbols;
        while(low<high){int middle=(low+high)>>>1;if(name(intAt(Section.NAME_ORDER,middle)).compareTo(prefix)<0)low=middle+1;else high=middle;}
        return low;
    }

    /** Slice bounds [start, end) of one owner's members whose name starts with {@code prefix}. */
    long[] memberRange(int owner,String prefix){
        int start=intAt(Section.OWNER_OFFSETS,owner),end=intAt(Section.OWNER_OFFSETS,owner+1);
        if(prefix.isEmpty())return new long[]{start,end};
        int low=start,high=end;
        while(low<high){int middle=(low+high)>>>1;if(name(intAt(Section.MEMBER_ORDER,middle)).compareTo(prefix)<0)low=middle+1;else high=middle;}
        int first=low;int last=first;
        while(last<end&&name(intAt(Section.MEMBER_ORDER,last)).startsWith(prefix))last++;
        return new long[]{first,last};
    }
    /** Direct members of {@code owner} with a name prefix, bounded by {@code limit}. */
    public List<Integer> members(int owner,String prefix,int limit){
        Objects.checkIndex(owner,symbols);var range=memberRange(owner,prefix);var result=new ArrayList<Integer>();
        for(long i=range[0];i<range[1]&&result.size()<limit;i++)result.add(intAt(Section.MEMBER_ORDER,i));
        return result;
    }
    public int memberCount(int owner){return intAt(Section.OWNER_OFFSETS,owner+1)-intAt(Section.OWNER_OFFSETS,owner);}

    /**
     * Resolution identity of the complete matching member range (§49). The whole owner uses its
     * persisted aggregate in O(log owners); a prefix always aggregates the full range — a result
     * limit never weakens proof coverage.
     */
    public Hash256 memberRangeIdentity(int owner,String prefix){
        Objects.checkIndex(owner,symbols);
        if(prefix.isEmpty()){
            var whole=ownerAggregate(owner);if(whole.isPresent())return whole.get();
            return new AlgebraicAccumulator(RANGE_DOMAIN).identity();
        }
        var range=memberRange(owner,prefix);var accumulator=new AlgebraicAccumulator(RANGE_DOMAIN);
        for(long i=range[0];i<range[1];i++){
            var resolution=ResolutionFact.decode(string(cold(intAt(Section.MEMBER_ORDER,i),0)));
            accumulator.add(resolution.symbolKey(),resolution.identity());
        }
        return accumulator.identity();
    }
    Optional<Hash256> ownerAggregate(int owner){
        long base=offset[Section.OWNER_AGGREGATES.ordinal()];int count=(int)(length[Section.OWNER_AGGREGATES.ordinal()]/36);
        int low=0,high=count-1;
        while(low<=high){
            int middle=(low+high)>>>1;int value=file.get(INT,base+36L*middle);
            if(value<owner)low=middle+1;else if(value>owner)high=middle-1;
            else{byte[] bytes=new byte[32];MemorySegment.copy(file,ValueLayout.JAVA_BYTE,base+36L*middle+4,bytes,0,32);return Optional.of(new Hash256(bytes));}
        }
        return Optional.empty();
    }

    public record Edge(int source,String target,String kind) { }
    public List<Edge> outgoing(int id){
        Objects.checkIndex(id,symbols);var result=new ArrayList<Edge>();
        long base=offset[Section.EDGES.ordinal()];
        for(int i=intAt(Section.EDGE_OFFSETS,id),end=intAt(Section.EDGE_OFFSETS,id+1);i<end;i++)
            result.add(new Edge(id,string(file.get(INT,base+8L*i)),string(file.get(INT,base+8L*i+4))));
        return result;
    }
    /** Canonical slow path for incoming edges: a full scan (reverse CSR is an optional accelerator). */
    public List<Edge> incomingScan(String target,String kind){
        var result=new ArrayList<Edge>();long base=offset[Section.EDGES.ordinal()];
        for(int id=0;id<symbols;id++)for(int i=intAt(Section.EDGE_OFFSETS,id),end=intAt(Section.EDGE_OFFSETS,id+1);i<end;i++){
            if(string(file.get(INT,base+8L*i)).equals(target)&&(kind==null||string(file.get(INT,base+8L*i+4)).equals(kind)))
                result.add(new Edge(id,target,string(file.get(INT,base+8L*i+4))));
        }
        return result;
    }
    int edgeCount(){return intAt(Section.EDGE_OFFSETS,symbols);}
    int edgeTarget(int index){return file.get(INT,offset[Section.EDGES.ordinal()]+8L*index);}
    int edgeKind(int index){return file.get(INT,offset[Section.EDGES.ordinal()]+8L*index+4);}
    int edgeSourceStart(int id){return intAt(Section.EDGE_OFFSETS,id);}
    int stringId(String value){
        int low=0,high=strings-1;
        while(low<=high){int middle=(low+high)>>>1;int compared=string(middle).compareTo(value);
            if(compared<0)low=middle+1;else if(compared>0)high=middle-1;else return middle;}
        return -1;
    }
    int stringCount(){return strings;}

    /** Canonical slow path for substring search: scan names in (name, key) order (§56). */
    public List<Integer> substringScan(String query,int limit){
        String needle=query.toLowerCase(Locale.ROOT);var result=new ArrayList<Integer>();
        if(needle.isEmpty())return result;
        for(int i=0;i<symbols&&result.size()<limit;i++){
            int id=intAt(Section.NAME_ORDER,i);if(name(id).toLowerCase(Locale.ROOT).contains(needle))result.add(id);
        }
        return result;
    }
    int nameOrder(int index){return intAt(Section.NAME_ORDER,index);}
}
