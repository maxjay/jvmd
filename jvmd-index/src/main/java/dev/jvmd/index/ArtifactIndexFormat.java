package dev.jvmd.index;

import dev.jvmd.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Versioned, GAV-independent immutable artifact record format used by storage prototypes.
 * Paths and Maven coordinates are intentionally context outside this payload.
 */
public final class ArtifactIndexFormat {
    public static final int FORMAT_VERSION=1;
    public static final String INDEXER_VERSION="jvmd-index-v8";
    private static final byte[] MAGIC="JVIDX001".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_STRINGS=5_000_000,MAX_SYMBOLS=5_000_000,MAX_RELATIONSHIPS=20_000_000,MAX_STRING_BYTES=32*1024*1024;

    public record Key(String binarySha256,int formatVersion,String indexerVersion,int runtimeFeature,String mode) {
        public Key {
            Objects.requireNonNull(binarySha256);Objects.requireNonNull(indexerVersion);Objects.requireNonNull(mode);
            if(!binarySha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("binarySha256");
            if(formatVersion<1||runtimeFeature<1||indexerVersion.isBlank()||mode.isBlank())throw new IllegalArgumentException("artifact key");
        }
        public String cacheKey(){
            String canonical=binarySha256+"\0"+formatVersion+"\0"+indexerVersion+"\0"+runtimeFeature+"\0"+mode;
            return Hashing.sha256(canonical.getBytes(StandardCharsets.UTF_8));
        }
    }
    public record SymbolRecord(int id,int ownerId,String key,String fqn,String name,String kind,String signature,
                               String descriptor,int flags,String entry,List<String> parameters,String metadataJson,
                               ResolutionFact resolution) {
        public SymbolRecord(int id,int ownerId,String key,String fqn,String name,String kind,String signature,
                            String descriptor,int flags,String entry,List<String> parameters,String metadataJson){
            this(id,ownerId,key,fqn,name,kind,signature,descriptor,flags,entry,parameters,metadataJson,
                    ResolutionFact.legacy(key,fqn,name,kind,descriptor,flags));
        }
        public SymbolRecord{parameters=List.copyOf(parameters);Objects.requireNonNull(resolution);}
    }
    public record Relationship(int sourceId,String target,String kind) { }
    public record ArtifactData(Key key,List<SymbolRecord> symbols,List<Relationship> relationships) {
        public ArtifactData{symbols=List.copyOf(symbols);relationships=List.copyOf(relationships);}
    }

    private ArtifactIndexFormat(){}

    public static Key key(String binaryHash,String mode){
        return new Key(binaryHash,FORMAT_VERSION,INDEXER_VERSION,Runtime.version().feature(),mode);
    }

    public static String documentationKey(Key binary,String sourceSha256){
        if(sourceSha256==null||!sourceSha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("sourceSha256");
        return Hashing.sha256((binary.cacheKey()+"\0"+sourceSha256).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Java-resolution identity of one indexed artifact generation.
     *
     * Deliberately excludes the raw binary SHA, class-entry path, parameter display names and the
     * separately published documentation overlay. Two bytecode generations with the same indexed
     * Java semantic surface therefore retain the same resolution identity.
     */
    private static final Set<String> RESOLUTION_METADATA=Set.of(
            "generic_signature","binary_name","inner_classes","nest_host","nest_members",
            "permitted_subclasses","record_components","module_exports",
            "return_type","parameter_types","type_parameters","scip_return_disambiguated");

    /** Canonical Java-resolution identity shared with LIVE source facts. */
    public static Hash256 symbolResolutionIdentity(SymbolRecord symbol){
        return Objects.requireNonNull(symbol).resolution().identity();
    }

    /**
     * Normalized Java semantic identity of an artifact.
     *
     * Storage/index generation inputs in Key are deliberately excluded. A format/indexer/runtime
     * generation change with identical normalized facts and relationships retains this identity.
     */
    public static Hash256 resolutionIdentity(ArtifactData data){
        Objects.requireNonNull(data);
        var symbols=data.symbols().stream()
                .sorted(Comparator.comparing(symbol->symbol.resolution().symbolKey()))
                .map(symbol->new Object[]{symbol.resolution().symbolKey(),symbolResolutionIdentity(symbol)})
                .toList();
        var relationships=data.relationships().stream().map(edge->new Object[]{
                    data.symbols().get(edge.sourceId()).resolution().symbolKey(),edge.target(),edge.kind()
                }).sorted(Comparator.comparing(value->value[0].toString()+"\0"+value[1]+"\0"+value[2])).toList();
        return CanonicalDigestWriter.digest("artifact-java-resolution-v1",symbols,relationships);
    }

    private static List<Object> resolutionMetadata(String metadataJson){
        try{
            var node=Json.MAPPER.readTree(Objects.requireNonNullElse(metadataJson,"{}"));
            var result=new ArrayList<Object>();
            var names=new ArrayList<String>();node.fieldNames().forEachRemaining(names::add);names.sort(String::compareTo);
            for(String name:names)if(RESOLUTION_METADATA.contains(name))
                result.add(new Object[]{name,node.get(name).toString()});
            return List.copyOf(result);
        }catch(IOException invalid){
            throw new IllegalArgumentException("Invalid artifact symbol metadata",invalid);
        }
    }

    public static ArtifactData from(BinaryReader.Content content,Key key)throws Exception{
        return from(content,key,content.edges());
    }
    public static ArtifactData from(BinaryReader.Content content,Key key,Collection<BinaryReader.Edge> relationshipFacts)throws Exception{
        var ordered=new ArrayList<>(content.symbols());
        ordered.sort(Comparator.comparing(BinaryReader.Symbol::key)
                .thenComparing(BinaryReader.Symbol::kind)
                .thenComparing(symbol->Objects.toString(symbol.signature(),""))
                .thenComparing(symbol->Objects.toString(symbol.descriptor(),"")));
        var ids=new LinkedHashMap<String,Integer>();
        for(int i=0;i<ordered.size();i++){
            String symbolKey=ordered.get(i).key();
            if(ids.putIfAbsent(symbolKey,i)!=null)throw new IllegalArgumentException("Duplicate artifact-local symbol key: "+symbolKey);
        }
        var callableIdentities=new HashMap<String,Integer>();
        for(var symbol:ordered)if(symbol.kind().equals("method")||symbol.kind().equals("ctor"))
            callableIdentities.merge(callableIdentity(symbol),1,Integer::sum);
        var symbols=new ArrayList<SymbolRecord>();
        for(int i=0;i<ordered.size();i++){
            var symbol=ordered.get(i);int owner=symbol.owner()==null?-1:ids.getOrDefault(symbol.owner(),-1);
            Map<String,Object> metadata=symbol.metadata();
            if((symbol.kind().equals("method")||symbol.kind().equals("ctor"))&&callableIdentities.get(callableIdentity(symbol))>1){
                metadata=new LinkedHashMap<>(metadata);
                metadata.put("scip_return_disambiguated",true);
            }
            var resolution=ResolutionFact.canonical(symbol.key(),symbol.owner(),symbol.kind(),symbol.name(),symbol.descriptor(),
                    ResolutionFact.modifiers(symbol.flags()),ResolutionFact.packageName(symbol.fqn()),symbol.semanticType(),
                    symbol.typeParameters(),symbol.typeParameterBounds(),symbol.directSupertypes(),symbol.varargs());
            symbols.add(new SymbolRecord(i,owner,symbol.key(),symbol.fqn(),symbol.name(),symbol.kind(),symbol.signature(),
                    symbol.descriptor(),symbol.flags(),symbol.entry(),symbol.parameters(),canonicalJson(metadata),resolution));
        }
        var relationships=new LinkedHashSet<Relationship>();
        for(var edge:relationshipFacts){
            Integer source=ids.get(edge.src());if(source==null)throw new IllegalArgumentException("Unknown relationship source: "+edge.src());
            relationships.add(new Relationship(source,edge.target(),edge.kind()));
        }
        var sorted=new ArrayList<>(relationships);
        sorted.sort(Comparator.comparingInt(Relationship::sourceId).thenComparing(Relationship::target).thenComparing(Relationship::kind));
        return new ArtifactData(key,List.copyOf(symbols),List.copyOf(sorted));
    }

    private static String callableIdentity(BinaryReader.Symbol symbol){
        String descriptor=symbol.descriptor();
        return symbol.fqn()+"\0"+symbol.kind()+"\0"+symbol.name()+"\0"+descriptor.substring(0,descriptor.indexOf(')')+1);
    }

    public static byte[] encode(ArtifactData data)throws Exception{
        if(data.key().formatVersion()!=FORMAT_VERSION)throw new IllegalArgumentException("Unsupported format version: "+data.key().formatVersion());
        var strings=new TreeSet<String>();
        for(var symbol:data.symbols()){
            for(String value:List.of(symbol.key(),symbol.fqn(),symbol.name(),symbol.kind(),symbol.metadataJson(),symbol.resolution().encode()))strings.add(value);
            if(symbol.signature()!=null)strings.add(symbol.signature());
            if(symbol.descriptor()!=null)strings.add(symbol.descriptor());
            if(symbol.entry()!=null)strings.add(symbol.entry());
            strings.addAll(symbol.parameters());
        }
        for(var edge:data.relationships()){strings.add(edge.target());strings.add(edge.kind());}
        var table=new ArrayList<>(strings);var ids=new HashMap<String,Integer>();for(int i=0;i<table.size();i++)ids.put(table.get(i),i);

        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.writeInt(data.key().formatVersion());writeString(out,data.key().binarySha256());writeString(out,data.key().indexerVersion());
            out.writeInt(data.key().runtimeFeature());writeString(out,data.key().mode());
            out.writeInt(table.size());for(String value:table)writeString(out,value);
            out.writeInt(data.symbols().size());
            for(var symbol:data.symbols()){
                out.writeInt(symbol.id());out.writeInt(symbol.ownerId());out.writeInt(symbol.flags());
                out.writeInt(id(ids,symbol.key()));out.writeInt(id(ids,symbol.fqn()));out.writeInt(id(ids,symbol.name()));out.writeInt(id(ids,symbol.kind()));
                out.writeInt(id(ids,symbol.signature()));out.writeInt(id(ids,symbol.descriptor()));out.writeInt(id(ids,symbol.entry()));out.writeInt(id(ids,symbol.metadataJson()));
                out.writeInt(id(ids,symbol.resolution().encode()));
                out.writeInt(symbol.parameters().size());for(String parameter:symbol.parameters())out.writeInt(id(ids,parameter));
            }
            out.writeInt(data.relationships().size());
            for(var edge:data.relationships()){out.writeInt(edge.sourceId());out.writeInt(id(ids,edge.target()));out.writeInt(id(ids,edge.kind()));}
        }
        byte[] body=bytes.toByteArray(),checksum=MessageDigest.getInstance("SHA-256").digest(body);
        var result=new ByteArrayOutputStream(MAGIC.length+checksum.length+body.length);
        result.write(MAGIC);result.write(checksum);result.write(body);return result.toByteArray();
    }

    /** Individually addressable records keep a single lookup independent of artifact size. */
    public static byte[] encodeSymbol(SymbolRecord symbol)throws IOException{
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.writeInt(symbol.id());out.writeInt(symbol.ownerId());out.writeInt(symbol.flags());
            for(String value:new String[]{symbol.key(),symbol.fqn(),symbol.name(),symbol.kind(),symbol.signature(),
                    symbol.descriptor(),symbol.entry(),symbol.metadataJson(),symbol.resolution().encode()}){
                out.writeBoolean(value!=null);if(value!=null)writeString(out,value);
            }
            out.writeInt(symbol.parameters().size());for(String value:symbol.parameters())writeString(out,value);
        }
        return bytes.toByteArray();
    }

    public static SymbolRecord decodeSymbol(byte[] bytes)throws IOException{
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            int id=in.readInt(),owner=in.readInt(),flags=in.readInt();var fields=new String[9];
            for(int i=0;i<fields.length;i++)fields[i]=in.readBoolean()?readString(in):null;
            int count=bounded(in.readInt(),1_000_000,"parameter count");var parameters=new ArrayList<String>(count);
            for(int i=0;i<count;i++)parameters.add(readString(in));
            if(id<0||owner< -1||in.available()!=0||fields[0]==null||fields[1]==null||fields[2]==null||fields[3]==null||fields[7]==null||fields[8]==null)
                throw new IOException("Invalid symbol record");
            return new SymbolRecord(id,owner,fields[0],fields[1],fields[2],fields[3],fields[4],fields[5],flags,fields[6],parameters,fields[7],ResolutionFact.decode(fields[8]));
        }
    }

    /** Validate the same record shape without allocating decoded strings or a symbol model. */
    public static boolean validateSymbol(byte[] bytes,long expectedId,long symbolCount)throws IOException{
        var input=java.nio.ByteBuffer.wrap(bytes);
        try{
            int id=input.getInt(),owner=input.getInt();input.getInt();
            if(id<0||owner< -1)throw new IOException("Invalid symbol record");
            for(int field=0;field<9;field++){
                if(input.get()!=0)skipString(input);
                else if(field<4||field>=7)throw new IOException("Invalid symbol record");
            }
            int count=bounded(input.getInt(),1_000_000,"parameter count");
            for(int i=0;i<count;i++)skipString(input);
            if(input.hasRemaining())throw new IOException("Invalid symbol record");
            return id==expectedId&&owner<symbolCount;
        }catch(java.nio.BufferUnderflowException truncated){throw new EOFException("Truncated symbol record");}
    }
    private static void skipString(java.nio.ByteBuffer input)throws IOException{
        int size=bounded(input.getInt(),MAX_STRING_BYTES,"string size");
        if(size>input.remaining())throw new EOFException("Truncated symbol record");
        input.position(input.position()+size);
    }

    public static ArtifactData decode(byte[] encoded)throws Exception{
        if(encoded.length<MAGIC.length+32+4)throw new IOException("Truncated artifact index");
        for(int i=0;i<MAGIC.length;i++)if(encoded[i]!=MAGIC[i])throw new IOException("Invalid artifact index magic");
        byte[] expected=Arrays.copyOfRange(encoded,MAGIC.length,MAGIC.length+32);
        byte[] body=Arrays.copyOfRange(encoded,MAGIC.length+32,encoded.length);
        if(!MessageDigest.isEqual(expected,MessageDigest.getInstance("SHA-256").digest(body)))throw new IOException("Artifact index checksum mismatch");
        try(var in=new DataInputStream(new ByteArrayInputStream(body))){
            int version=in.readInt();if(version!=FORMAT_VERSION)throw new IOException("Unsupported artifact index version: "+version);
            String binaryHash=readString(in),indexer=readString(in);int runtime=in.readInt();String mode=readString(in);
            var key=new Key(binaryHash,version,indexer,runtime,mode);
            int stringCount=bounded(in.readInt(),MAX_STRINGS,"string count");var strings=new ArrayList<String>(stringCount);
            for(int i=0;i<stringCount;i++)strings.add(readString(in));
            int symbolCount=bounded(in.readInt(),MAX_SYMBOLS,"symbol count");var symbols=new ArrayList<SymbolRecord>(symbolCount);
            for(int i=0;i<symbolCount;i++){
                int id=in.readInt(),owner=in.readInt(),flags=in.readInt();
                String localKey=value(strings,in.readInt()),fqn=value(strings,in.readInt()),name=value(strings,in.readInt()),kind=value(strings,in.readInt());
                String signature=valueOrNull(strings,in.readInt()),descriptor=valueOrNull(strings,in.readInt()),entry=valueOrNull(strings,in.readInt()),metadata=value(strings,in.readInt());
                String resolution=value(strings,in.readInt());
                int parameterCount=bounded(in.readInt(),1_000_000,"parameter count");var parameters=new ArrayList<String>(parameterCount);
                for(int p=0;p<parameterCount;p++)parameters.add(value(strings,in.readInt()));
                if(id!=i)throw new IOException("Non-canonical local symbol id");
                symbols.add(new SymbolRecord(id,owner,localKey,fqn,name,kind,signature,descriptor,flags,entry,List.copyOf(parameters),metadata,ResolutionFact.decode(resolution)));
            }
            int relationCount=bounded(in.readInt(),MAX_RELATIONSHIPS,"relationship count");var relations=new ArrayList<Relationship>(relationCount);
            for(int i=0;i<relationCount;i++){
                int source=in.readInt();if(source<0||source>=symbolCount)throw new IOException("Invalid relationship source");
                relations.add(new Relationship(source,value(strings,in.readInt()),value(strings,in.readInt())));
            }
            if(in.available()!=0)throw new IOException("Trailing artifact index bytes");
            return new ArtifactData(key,List.copyOf(symbols),List.copyOf(relations));
        }
    }

    private static int id(Map<String,Integer> table,String value){return value==null?-1:table.get(value);}
    private static String value(List<String> table,int id)throws IOException{if(id<0||id>=table.size())throw new IOException("Invalid string id");return table.get(id);}
    private static String valueOrNull(List<String> table,int id)throws IOException{return id<0?null:value(table,id);}
    private static int bounded(int value,int max,String label)throws IOException{if(value<0||value>max)throw new IOException("Invalid "+label+": "+value);return value;}
    private static void writeString(DataOutputStream out,String value)throws IOException{
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_STRING_BYTES)throw new IOException("String too large");
        out.writeInt(bytes.length);out.write(bytes);
    }
    private static String readString(DataInputStream in)throws IOException{
        int length=bounded(in.readInt(),MAX_STRING_BYTES,"string length");byte[] bytes=in.readNBytes(length);
        if(bytes.length!=length)throw new EOFException("Truncated string");return new String(bytes,StandardCharsets.UTF_8);
    }
    private static String canonicalJson(Object value)throws Exception{return Json.MAPPER.writeValueAsString(canonical(value));}
    private static Object canonical(Object value){
        if(value instanceof Map<?,?> map){var result=new TreeMap<String,Object>();map.forEach((key,item)->result.put(String.valueOf(key),canonical(item)));return result;}
        if(value instanceof Collection<?> list)return list.stream().map(ArtifactIndexFormat::canonical).toList();
        return value;
    }
}
