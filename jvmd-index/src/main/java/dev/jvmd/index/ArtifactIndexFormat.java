package dev.jvmd.index;

import dev.jvmd.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Versioned, GAV-independent immutable artifact record format used by storage prototypes.
 * Paths and Maven coordinates are intentionally context outside this payload.
 */
public final class ArtifactIndexFormat {
    public static final int FORMAT_VERSION=1;
    public static final String INDEXER_VERSION="jvmd-index-v9";
    private static final int MAX_STRING_BYTES=32*1024*1024;

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
                    ResolutionFact.legacy(Objects.toString(key,"<invalid>"),Objects.toString(fqn,""),
                            Objects.toString(name,""),Objects.toString(kind,"unknown"),descriptor,flags));
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
        var byId=new HashMap<Integer,SymbolRecord>();for(var symbol:data.symbols())byId.put(symbol.id(),symbol);
        var relationships=data.relationships().stream().map(edge->{
                    var source=byId.get(edge.sourceId());
                    if(source==null)throw new IllegalArgumentException("Unknown relationship source: "+edge.sourceId());
                    return new Object[]{source.resolution().symbolKey(),edge.target(),edge.kind()};
                }).sorted(Comparator.comparing(value->value[0].toString()+"\0"+value[1]+"\0"+value[2])).toList();
        return CanonicalDigestWriter.digest("artifact-java-resolution-v1",symbols,relationships);
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
                    ResolutionFact.modifiers(symbol.flags(),symbol.kind()),ResolutionFact.packageName(symbol.fqn()),symbol.semanticType(),
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

    /** Individually addressable records keep one typed lookup independent of artifact size. */
    public static byte[] encodeSymbol(SymbolRecord symbol)throws IOException{
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.writeInt(symbol.id());out.writeInt(symbol.ownerId());out.writeInt(symbol.flags());
            for(String value:new String[]{symbol.key(),symbol.fqn(),symbol.name(),symbol.kind(),symbol.signature(),
                    symbol.descriptor(),symbol.entry(),symbol.metadataJson()}){
                out.writeBoolean(value!=null);if(value!=null)writeString(out,value);
            }
            writeResolution(out,symbol.resolution());
            out.writeInt(symbol.parameters().size());for(String value:symbol.parameters())writeString(out,value);
        }
        return bytes.toByteArray();
    }

    public static SymbolRecord decodeSymbol(byte[] bytes)throws IOException{
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            int id=in.readInt(),owner=in.readInt(),flags=in.readInt();var fields=new String[8];
            for(int i=0;i<fields.length;i++)fields[i]=in.readBoolean()?readString(in):null;
            if(id<0||owner< -1||fields[0]==null||fields[1]==null||fields[2]==null||fields[3]==null||fields[7]==null)
                throw new IOException("Invalid symbol record");
            ResolutionFact resolution=readResolution(in);
            int count=bounded(in.readInt(),1_000_000,"parameter count");var parameters=new ArrayList<String>(count);
            for(int i=0;i<count;i++)parameters.add(readString(in));
            if(in.available()!=0)throw new IOException("Trailing symbol record bytes");
            return new SymbolRecord(id,owner,fields[0],fields[1],fields[2],fields[3],fields[4],fields[5],flags,fields[6],parameters,fields[7],resolution);
        }
    }

    /** Validate the same compact record shape without constructing semantic objects. */
    public static boolean validateSymbol(byte[] bytes,long expectedId,long symbolCount)throws IOException{
        var input=java.nio.ByteBuffer.wrap(bytes);
        try{
            int id=input.getInt(),owner=input.getInt();input.getInt();
            if(id<0||owner< -1)throw new IOException("Invalid symbol record");
            for(int field=0;field<8;field++){
                if(input.get()!=0)skipString(input);
                else if(field<4||field==7)throw new IOException("Invalid symbol record");
            }
            skipResolution(input);
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

    /** A declaration fact as tree node bytes; {@link #decodeFact} reads back an equal fact. */
    public static byte[] encodeFact(SemanticFact fact)throws IOException{
        var bytes=new ByteArrayOutputStream(256);
        try(var out=new DataOutputStream(bytes)){
            writeString(out,fact.id());writeNullable(out,fact.ownerId());writeString(out,fact.name());writeString(out,fact.kind());
            writeString(out,fact.structuralSignature());writeNullable(out,fact.erasedDescriptor());
            out.writeInt(fact.modifiers().size());for(String modifier:fact.modifiers().stream().sorted().toList())writeString(out,modifier);
            writeNullable(out,fact.sourceFile());writeString(out,fact.packageName());writeString(out,fact.namePath());writeNullable(out,fact.fqn());
            writeType(out,fact.type());
            out.writeInt(fact.typeParameters().size());
            for(int i=0;i<fact.typeParameters().size();i++){
                writeString(out,fact.typeParameters().get(i));var bounds=fact.typeParameterBounds().get(i);
                out.writeInt(bounds.size());for(var bound:bounds)writeType(out,bound);
            }
            out.writeInt(fact.directSupertypes().size());for(var parent:fact.directSupertypes())writeType(out,parent);
            out.writeInt(fact.parameterNames().size());for(String name:fact.parameterNames())writeString(out,name);
            out.writeBoolean(fact.varargs());
            writeString(out,fact.apiIdentity());writeString(out,fact.namespaceIdentity());writeString(out,fact.documentationIdentity());
            out.write(fact.factIdentity().bytes());writeResolution(out,fact.resolutionFact());
        }
        return bytes.toByteArray();
    }

    public static SemanticFact decodeFact(byte[] bytes)throws IOException{
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            String id=readString(in),owner=readNullable(in),name=readString(in),kind=readString(in),signature=readString(in),descriptor=readNullable(in);
            int modifierCount=bounded(in.readInt(),64,"fact modifier count");var modifiers=new LinkedHashSet<String>();
            for(int i=0;i<modifierCount;i++)modifiers.add(readString(in));
            String source=readNullable(in),pkg=readString(in),namePath=readString(in),fqn=readNullable(in);
            SemanticType type=readType(in);
            int parameterCount=bounded(in.readInt(),1024,"fact type parameter count");
            var parameters=new ArrayList<String>(parameterCount);var bounds=new ArrayList<List<SemanticType>>(parameterCount);
            for(int i=0;i<parameterCount;i++){
                parameters.add(readString(in));int boundCount=bounded(in.readInt(),1024,"fact bound count");var values=new ArrayList<SemanticType>(boundCount);
                for(int j=0;j<boundCount;j++)values.add(readType(in));bounds.add(values);
            }
            int parentCount=bounded(in.readInt(),4096,"fact supertype count");var parents=new ArrayList<SemanticType>(parentCount);
            for(int i=0;i<parentCount;i++)parents.add(readType(in));
            int nameCount=bounded(in.readInt(),4096,"fact parameter name count");var names=new ArrayList<String>(nameCount);
            for(int i=0;i<nameCount;i++)names.add(readString(in));
            boolean varargs=in.readBoolean();
            String api=readString(in),namespace=readString(in),documentation=readString(in);
            byte[] identity=in.readNBytes(Hash256.BYTES);if(identity.length!=Hash256.BYTES)throw new EOFException("Truncated fact identity");
            return new SemanticFact(id,owner,name,kind,signature,descriptor,modifiers,source,pkg,namePath,fqn,type,parameters,bounds,parents,names,varargs,
                    api,namespace,documentation,new Hash256(identity),readResolution(in));
        }
    }
    private static void writeNullable(DataOutputStream out,String value)throws IOException{out.writeBoolean(value!=null);if(value!=null)writeString(out,value);}
    private static String readNullable(DataInputStream in)throws IOException{return in.readBoolean()?readString(in):null;}

    private static void writeResolution(DataOutputStream out,ResolutionFact value)throws IOException{
        writeString(out,value.symbolKey());
        out.writeBoolean(value.ownerKey()!=null);if(value.ownerKey()!=null)writeString(out,value.ownerKey());
        writeString(out,value.kind());writeString(out,value.name());writeString(out,value.erasedDescriptor());
        out.writeInt(value.modifiers().size());for(String modifier:value.modifiers().stream().sorted().toList())writeString(out,modifier);
        writeString(out,value.packageName());writeType(out,value.type());
        out.writeInt(value.typeParameters().size());
        for(var parameter:value.typeParameters()){
            out.writeInt(parameter.bounds().size());for(var bound:parameter.bounds())writeType(out,bound);
        }
        out.writeInt(value.directSupertypes().size());for(var parent:value.directSupertypes())writeType(out,parent);
        out.writeBoolean(value.varargs());out.write(value.identity().bytes());
    }

    private static ResolutionFact readResolution(DataInputStream in)throws IOException{
        String symbol=readString(in),owner=in.readBoolean()?readString(in):null;
        String kind=readString(in),name=readString(in),descriptor=readString(in);
        int modifierCount=bounded(in.readInt(),64,"resolution modifier count");var modifiers=new LinkedHashSet<String>();
        for(int i=0;i<modifierCount;i++)modifiers.add(readString(in));
        String pkg=readString(in);SemanticType type=readType(in);
        int parameterCount=bounded(in.readInt(),1024,"resolution type parameter count");
        var parameters=new ArrayList<ResolutionFact.TypeParameter>(parameterCount);
        for(int i=0;i<parameterCount;i++){
            int boundCount=bounded(in.readInt(),1024,"resolution bound count");var bounds=new ArrayList<SemanticType>(boundCount);
            for(int j=0;j<boundCount;j++)bounds.add(readType(in));parameters.add(new ResolutionFact.TypeParameter(bounds));
        }
        int parentCount=bounded(in.readInt(),4096,"resolution supertype count");var parents=new ArrayList<SemanticType>(parentCount);
        for(int i=0;i<parentCount;i++)parents.add(readType(in));
        boolean varargs=in.readBoolean();byte[] identity=in.readNBytes(Hash256.BYTES);
        if(identity.length!=Hash256.BYTES)throw new EOFException("Truncated resolution identity");
        return new ResolutionFact(symbol,owner,kind,name,descriptor,modifiers,pkg,type,parameters,parents,varargs,new Hash256(identity));
    }

    private static void writeType(DataOutputStream out,SemanticType type)throws IOException{
        switch(type){
            case SemanticType.Primitive value -> {out.writeByte(1);writeString(out,value.name());}
            case SemanticType.Declared value -> {
                out.writeByte(2);writeString(out,value.symbolId());writeString(out,value.name());
                out.writeInt(value.arguments().size());for(var argument:value.arguments())writeType(out,argument);
            }
            case SemanticType.Variable value -> {out.writeByte(3);writeString(out,value.symbolId());writeString(out,value.name());}
            case SemanticType.Array value -> {out.writeByte(4);writeType(out,value.component());}
            case SemanticType.Executable value -> {
                out.writeByte(5);out.writeInt(value.parameters().size());for(var parameter:value.parameters())writeType(out,parameter);
                writeType(out,value.returns());out.writeInt(value.thrown().size());for(var thrown:value.thrown())writeType(out,thrown);
            }
            case SemanticType.Wildcard value -> {
                out.writeByte(6);out.writeBoolean(value.extendsBound()!=null);if(value.extendsBound()!=null)writeType(out,value.extendsBound());
                out.writeBoolean(value.superBound()!=null);if(value.superBound()!=null)writeType(out,value.superBound());
            }
            case SemanticType.Intersection value -> {
                out.writeByte(7);out.writeInt(value.bounds().size());for(var bound:value.bounds())writeType(out,bound);
            }
            case SemanticType.Unknown value -> {out.writeByte(8);writeString(out,value.text());}
        }
    }

    private static SemanticType readType(DataInputStream in)throws IOException{
        return switch(in.readUnsignedByte()){
            case 1 -> new SemanticType.Primitive(readString(in));
            case 2 -> {
                String id=readString(in),name=readString(in);int count=bounded(in.readInt(),4096,"type argument count");
                var args=new ArrayList<SemanticType>(count);for(int i=0;i<count;i++)args.add(readType(in));
                yield new SemanticType.Declared(id,name,args);
            }
            case 3 -> new SemanticType.Variable(readString(in),readString(in));
            case 4 -> new SemanticType.Array(readType(in));
            case 5 -> {
                int count=bounded(in.readInt(),4096,"parameter type count");var params=new ArrayList<SemanticType>(count);
                for(int i=0;i<count;i++)params.add(readType(in));SemanticType returns=readType(in);
                int thrownCount=bounded(in.readInt(),4096,"thrown type count");var thrown=new ArrayList<SemanticType>(thrownCount);
                for(int i=0;i<thrownCount;i++)thrown.add(readType(in));
                yield new SemanticType.Executable(params,returns,thrown);
            }
            case 6 -> {
                SemanticType ext=in.readBoolean()?readType(in):null;SemanticType sup=in.readBoolean()?readType(in):null;
                yield new SemanticType.Wildcard(ext,sup);
            }
            case 7 -> {
                int count=bounded(in.readInt(),4096,"intersection bound count");var bounds=new ArrayList<SemanticType>(count);
                for(int i=0;i<count;i++)bounds.add(readType(in));yield new SemanticType.Intersection(bounds);
            }
            case 8 -> new SemanticType.Unknown(readString(in));
            default -> throw new IOException("Invalid semantic type tag");
        };
    }

    private static void skipResolution(java.nio.ByteBuffer input)throws IOException{
        skipString(input);if(input.get()!=0)skipString(input);skipString(input);skipString(input);skipString(input);
        int modifiers=bounded(input.getInt(),64,"resolution modifier count");for(int i=0;i<modifiers;i++)skipString(input);
        skipString(input);skipType(input);
        int parameters=bounded(input.getInt(),1024,"resolution type parameter count");
        for(int i=0;i<parameters;i++){int bounds=bounded(input.getInt(),1024,"resolution bound count");for(int j=0;j<bounds;j++)skipType(input);}
        int parents=bounded(input.getInt(),4096,"resolution supertype count");for(int i=0;i<parents;i++)skipType(input);
        input.get();if(input.remaining()<Hash256.BYTES)throw new EOFException("Truncated resolution identity");input.position(input.position()+Hash256.BYTES);
    }

    private static void skipType(java.nio.ByteBuffer input)throws IOException{
        switch(Byte.toUnsignedInt(input.get())){
            case 1,8 -> skipString(input);
            case 2 -> {skipString(input);skipString(input);int count=bounded(input.getInt(),4096,"type argument count");for(int i=0;i<count;i++)skipType(input);}
            case 3 -> {skipString(input);skipString(input);}
            case 4 -> skipType(input);
            case 5 -> {
                int count=bounded(input.getInt(),4096,"parameter type count");for(int i=0;i<count;i++)skipType(input);
                skipType(input);int thrown=bounded(input.getInt(),4096,"thrown type count");for(int i=0;i<thrown;i++)skipType(input);
            }
            case 6 -> {if(input.get()!=0)skipType(input);if(input.get()!=0)skipType(input);}
            case 7 -> {int count=bounded(input.getInt(),4096,"intersection bound count");for(int i=0;i<count;i++)skipType(input);}
            default -> throw new IOException("Invalid semantic type tag");
        }
    }

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
