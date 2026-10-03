package dev.jvmd.index;

import java.lang.classfile.*;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.constant.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Lazy class-file skeletons, with no Code traversal. */
public final class BinaryReader {
    /** Symbol metadata detached from class-file buffers. */
    public record Symbol(String key, String fqn, String name, String owner, String kind, String signature,
                         String descriptor, int flags, String entry, List<String> parameters, Map<String,Object> metadata,
                         SemanticType semanticType, List<String> typeParameters, List<List<SemanticType>> typeParameterBounds,
                         List<SemanticType> directSupertypes, boolean varargs) {
        public Symbol {
            parameters=List.copyOf(parameters);metadata=new LinkedHashMap<>(metadata);
            typeParameters=List.copyOf(typeParameters);
            typeParameterBounds=typeParameterBounds.stream().map(List::copyOf).toList();
            directSupertypes=List.copyOf(directSupertypes);
        }
    }
    private record Generic(List<String> ids,List<List<SemanticType>> bounds) {
        Generic { ids=List.copyOf(ids);bounds=bounds.stream().map(List::copyOf).toList(); }
    }
    /** Unresolved structural edges linked after each artifact transaction. */
    public record Edge(String src, String target, String kind) { }
    /** One artifact's detached skeleton, plus source-join models scoped to that read. */
    public record Content(List<Symbol> symbols, List<Edge> edges, Map<String,ClassModel> models, List<String> warnings) { }
    public Content read(Path path, boolean local) throws Exception {
        var files=new LinkedHashMap<String,byte[]>();
        if (Files.isDirectory(path)) {
            try (var walk=Files.walk(path)) { for(var file:walk.filter(p->p.toString().endsWith(".class")).toList()) files.put(path.relativize(file).toString(),Files.readAllBytes(file)); }
        } else if(path.toString().endsWith(".class")) {
            files.put(path.getFileName().toString(),Files.readAllBytes(path));
        } else files.putAll(entries(Files.readAllBytes(path),".class"));
        return read(files,local);
    }

    /**
     * Entries of an in-memory jar whose names end with {@code suffix}, keyed by real entry name, as
     * {@link JarFile#versionedStream()} selects them for this runtime: in a multi-release jar a
     * versioned entry replaces its base entry, and versions above this runtime are ignored.
     */
    public static Map<String,byte[]> entries(byte[] jar,String suffix) throws java.io.IOException {
        var order=new LinkedHashSet<String>();var base=new HashMap<String,byte[]>();var real=new LinkedHashMap<String,byte[]>();
        var versioned=new HashMap<String,TreeMap<Integer,String>>();
        boolean multiRelease=false;int feature=Runtime.version().feature();
        boolean any=false;
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jar))){
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                any=true;if(entry.isDirectory())continue;String name=entry.getName();
                if(name.equalsIgnoreCase(JarFile.MANIFEST_NAME)){
                    var manifest=new java.util.jar.Manifest(new java.io.ByteArrayInputStream(zip.readAllBytes()));
                    multiRelease="true".equalsIgnoreCase(manifest.getMainAttributes().getValue("Multi-Release"));continue;
                }
                if(!name.endsWith(suffix))continue;
                byte[] bytes=zip.readAllBytes();real.put(name,bytes);
                var matcher=VERSIONED.matcher(name);
                if(matcher.matches()){
                    versioned.computeIfAbsent(matcher.group(2),_->new TreeMap<>()).put(Integer.parseInt(matcher.group(1)),name);
                    order.add(matcher.group(2));
                }else{base.put(name,bytes);order.add(name);}
            }
        }
        // A stream reader yields nothing for bytes that are not a zip; only an empty archive may.
        if(!any&&!emptyArchive(jar))throw new java.util.zip.ZipException("Not a zip archive");
        if(!multiRelease)return real;
        var result=new LinkedHashMap<String,byte[]>();
        for(String name:order){
            var versions=versioned.get(name);var selected=versions==null?null:versions.floorEntry(feature);
            if(selected!=null)result.put(selected.getValue(),real.get(selected.getValue()));
            else if(base.containsKey(name))result.put(name,base.get(name));
        }
        return result;
    }
    /** The number and total uncompressed size of the entries of a zip whose names end with a suffix. */
    public record EntrySizes(int count,long bytes) { }

    /**
     * The sizes {@link #entries} would decompress, read from the zip's central directory without
     * decompressing anything.
     */
    public static EntrySizes entrySizes(byte[] zip,String suffix)throws java.util.zip.ZipException{
        var buffer=java.nio.ByteBuffer.wrap(zip).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int end=-1;
        for(int i=zip.length-22;i>=Math.max(0,zip.length-22-0xffff);i--)if(buffer.getInt(i)==0x06054b50){end=i;break;}
        if(end<0)throw new java.util.zip.ZipException("Not a zip archive");
        long entries=buffer.getShort(end+10)&0xffff,offset=buffer.getInt(end+16)&0xffffffffL;
        if((entries==0xffff||offset==0xffffffffL)&&end>=20&&buffer.getInt(end-20)==0x07064b50){
            long record=buffer.getLong(end-12);
            if(record<0||record>zip.length-56||buffer.getInt((int)record)!=0x06064b50)throw new java.util.zip.ZipException("Invalid zip64 end record");
            entries=buffer.getLong((int)record+32);offset=buffer.getLong((int)record+48);
        }
        int count=0;long bytes=0;long at=offset;
        for(long n=0;n<entries;n++){
            if(at<0||at>zip.length-46||buffer.getInt((int)at)!=0x02014b50)throw new java.util.zip.ZipException("Invalid central directory");
            int position=(int)at;long size=buffer.getInt(position+24)&0xffffffffL;
            int nameLength=buffer.getShort(position+28)&0xffff,extraLength=buffer.getShort(position+30)&0xffff,commentLength=buffer.getShort(position+32)&0xffff;
            if(position+46L+nameLength+extraLength>zip.length)throw new java.util.zip.ZipException("Invalid central directory");
            String name=new String(zip,position+46,nameLength,java.nio.charset.StandardCharsets.UTF_8);
            if(size==0xffffffffL)size=zip64Size(buffer,position+46+nameLength,extraLength);
            if(name.endsWith(suffix)&&!name.endsWith("/")){count++;bytes+=size;}
            at=position+46L+nameLength+extraLength+commentLength;
        }
        return new EntrySizes(count,bytes);
    }
    private static long zip64Size(java.nio.ByteBuffer buffer,int extra,int length)throws java.util.zip.ZipException{
        for(int at=extra;at+4<=extra+length;){
            int id=buffer.getShort(at)&0xffff,size=buffer.getShort(at+2)&0xffff;
            if(id==1&&size>=8)return buffer.getLong(at+4);
            at+=4+size;
        }
        throw new java.util.zip.ZipException("Missing zip64 size");
    }
    private static boolean emptyArchive(byte[] jar){
        return jar.length>=22&&jar[0]=='P'&&jar[1]=='K'&&jar[2]==5&&jar[3]==6;
    }
    private static final java.util.regex.Pattern VERSIONED=java.util.regex.Pattern.compile("META-INF/versions/([0-9]+)/(.+)");

    /** Skeletons from class-file bytes keyed by their entry names. */
    public Content read(Map<String,byte[]> files, boolean local) {
        var classes = new LinkedHashMap<String, ClassModel>(); var entries = new HashMap<String,String>(); var warnings = new ArrayList<String>();
        for(var file:files.entrySet()) parse(file.getValue(),file.getKey(),classes,entries,warnings);
        var symbols=new ArrayList<Symbol>();var edges=new ArrayList<Edge>();
        for(var model:classes.values()) {
            String owner=name(model.thisClass()), entry=entries.get(owner);
            if(model.isModuleInfo()) continue;
            int flags=model.flags().flagsMask();
            for(var inner:model.findAttribute(Attributes.innerClasses()).stream().flatMap(a->a.classes().stream()).toList())
                if(inner.innerClass().asInternalName().equals(model.thisClass().asInternalName())) flags=inner.flagsMask();
            String kind=(flags&ClassFile.ACC_ANNOTATION)!=0?"annotation":(flags&ClassFile.ACC_INTERFACE)!=0?"interface":(flags&ClassFile.ACC_ENUM)!=0?"enum":model.findAttribute(Attributes.record()).isPresent()?"record":"class";
            var metadata=metadata(model); metadata.put("binary_name",owner);
            model.findAttribute(Attributes.innerClasses()).ifPresent(a->metadata.put("inner_classes",a.classes().stream().map(i->name(i.innerClass())).toList()));
            model.findAttribute(Attributes.nestHost()).ifPresent(a->metadata.put("nest_host",name(a.nestHost())));
            model.findAttribute(Attributes.nestMembers()).ifPresent(a->metadata.put("nest_members",a.nestMembers().stream().map(BinaryReader::name).toList()));
            model.findAttribute(Attributes.permittedSubclasses()).ifPresent(a->metadata.put("permitted_subclasses",a.permittedSubclasses().stream().map(BinaryReader::name).toList()));
            model.findAttribute(Attributes.record()).ifPresent(a->metadata.put("record_components",a.components().stream().map(c->Map.of("name",c.name().stringValue(),"type",Signatures.type(c.findAttribute(Attributes.signature()).map(x->x.asTypeSignature()).orElseGet(()->Signature.of(c.descriptorSymbol()))))).toList()));
            String declaration=kind+" "+owner.replace('$','.');
            var generic=model.findAttribute(Attributes.signature()).map(a->a.asClassSignature());
            if(generic.isPresent()) {
                var sig=generic.get(); declaration+=Signatures.parameters(sig.typeParameters());
                if(!Signatures.type(sig.superclassSignature()).equals("java.lang.Object")) declaration+=" extends "+Signatures.type(sig.superclassSignature());
                if(!sig.superinterfaceSignatures().isEmpty()) declaration+=(kind.equals("interface")?" extends ":" implements ")+String.join(", ",sig.superinterfaceSignatures().stream().map(Signatures::type).toList());
            } else {
                if(model.superclass().isPresent()&&!name(model.superclass().get()).equals("java.lang.Object")) declaration+=" extends "+name(model.superclass().get());
                if(!model.interfaces().isEmpty()) declaration+=(kind.equals("interface")?" extends ":" implements ")+String.join(", ",model.interfaces().stream().map(BinaryReader::name).toList());
            }
            String outer=owner.contains("$")?owner.substring(0,owner.lastIndexOf('$')):null;
            Generic classParameters=generic.map(value->generic(value.typeParameters())).orElseGet(BinaryReader::emptyGeneric);
            var typeArguments=classParameters.ids().stream().map(id->(SemanticType)new SemanticType.Variable(id,id)).toList();
            String semanticName=owner.replace('$','.');
            var semanticType=new SemanticType.Declared(semanticName,semanticName,typeArguments);
            List<SemanticType> directSupertypes;
            if(generic.isPresent()){
                var value=generic.get();var parents=new ArrayList<SemanticType>();
                parents.add(semantic(value.superclassSignature()));
                value.superinterfaceSignatures().forEach(parent->parents.add(semantic(parent)));
                directSupertypes=List.copyOf(parents);
            }else{
                var parents=new ArrayList<SemanticType>();
                model.superclass().ifPresent(parent->parents.add(declared(name(parent))));
                model.interfaces().forEach(parent->parents.add(declared(name(parent))));
                directSupertypes=List.copyOf(parents);
            }
            symbols.add(new Symbol(owner,owner,simple(owner),outer,kind,declaration,null,flags,entry,List.of(),metadata,
                    semanticType,classParameters.ids(),classParameters.bounds(),directSupertypes,false));
            model.superclass().ifPresent(parent->edges.add(new Edge(owner,name(parent),"extends")));
            model.interfaces().forEach(parent->edges.add(new Edge(owner,name(parent),kind.equals("interface")?"extends":"implements")));
            annotations(model,owner,edges);
            for(var field:model.fields()) {
                String key=owner+"#"+field.fieldName().stringValue();
                Signature type=field.findAttribute(Attributes.signature()).map(a->a.asTypeSignature()).orElseGet(()->Signature.of(field.fieldTypeSymbol()));
                symbols.add(new Symbol(key,owner,field.fieldName().stringValue(),owner,(field.flags().flagsMask()&ClassFile.ACC_ENUM)!=0?"enumconst":"field",
                        Signatures.type(type)+" "+field.fieldName().stringValue(),field.fieldType().stringValue(),field.flags().flagsMask(),entry,List.of(),metadata(field),
                        semantic(type),List.of(),List.of(),List.of(),false));
                Signatures.referenced(type).forEach(t->edges.add(new Edge(key,t,"return_type"))); annotations(field,key,edges);
            }
            for(var method:model.methods()) {
                int mf=method.flags().flagsMask(); String methodName=method.methodName().stringValue();
                if(methodName.equals("<clinit>") || (mf & ClassFile.ACC_BRIDGE)!=0)continue;
                String key=owner+"#"+methodName+method.methodType().stringValue();
                var sig=method.findAttribute(Attributes.signature()).map(a->a.asMethodSignature()).orElseGet(()->MethodSignature.of(method.methodTypeSymbol()));
                var names=new ArrayList<String>();
                var stored=method.findAttribute(Attributes.methodParameters());
                for(int i=0;i<sig.arguments().size();i++)names.add(stored.isPresent()&&i<stored.get().parameters().size()?stored.get().parameters().get(i).name().map(n->n.stringValue()).orElse("arg"+i):"arg"+i);
                var parts=new ArrayList<String>(); for(int i=0;i<sig.arguments().size();i++)parts.add(Signatures.type(sig.arguments().get(i))+" "+names.get(i));
                boolean ctor=methodName.equals("<init>");
                String signature=Signatures.parameters(sig.typeParameters()); if(!signature.isEmpty())signature+=" ";
                signature+=(ctor?simple(owner):Signatures.type(sig.result())+" "+methodName)+"("+String.join(", ",parts)+")";
                var thrown=method.findAttribute(Attributes.exceptions()).stream().flatMap(a->a.exceptions().stream()).map(BinaryReader::name).toList();
                if(!sig.throwableSignatures().isEmpty())signature+=" throws "+String.join(", ",sig.throwableSignatures().stream().map(Signatures::type).toList());
                else if(!thrown.isEmpty())signature+=" throws "+String.join(", ",thrown);
                var data=metadata(method);data.put("parameter_names_from_class",stored.isPresent());data.put("return_type",Signatures.type(sig.result()));data.put("parameter_types",sig.arguments().stream().map(Signatures::type).toList());data.put("type_parameters",Signatures.parameters(sig.typeParameters()));
                Generic methodParameters=generic(sig.typeParameters());
                var semanticThrown=!sig.throwableSignatures().isEmpty()
                        ?sig.throwableSignatures().stream().map(BinaryReader::semantic).toList()
                        :thrown.stream().map(BinaryReader::declared).toList();
                var semanticMethod=new SemanticType.Executable(
                        sig.arguments().stream().map(BinaryReader::semantic).toList(),
                        semantic(sig.result()),semanticThrown);
                symbols.add(new Symbol(key,owner,ctor?simple(owner):methodName,owner,ctor?"ctor":"method",
                        signature,method.methodType().stringValue(),mf,entry,List.copyOf(names),data,
                        semanticMethod,methodParameters.ids(),methodParameters.bounds(),List.of(),(mf&ClassFile.ACC_VARARGS)!=0));
                for(var type:sig.arguments())Signatures.referenced(type).forEach(t->edges.add(new Edge(key,t,"param_type")));
                Signatures.referenced(sig.result()).forEach(t->edges.add(new Edge(key,t,"return_type")));
                for(var type:method.methodTypeSymbol().parameterArray())Signatures.referenced(Signature.of(type)).forEach(t->edges.add(new Edge(key,t,"param_type")));
                Signatures.referenced(Signature.of(method.methodTypeSymbol().returnType())).forEach(t->edges.add(new Edge(key,t,"return_type")));
                thrown.forEach(t->edges.add(new Edge(key,t,"throws")));annotations(method,key,edges);
            }
        }
        // Module exports are metadata, not a reason to discard a class on the class path.
        var exports=classes.values().stream().filter(ClassModel::isModuleInfo).flatMap(m->m.findAttribute(Attributes.module()).stream()).flatMap(m->m.exports().stream()).map(e->e.exportedPackage().name().stringValue().replace('/','.')).toList();
        if(!exports.isEmpty())for(var symbol:symbols)if(symbol.key().equals(symbol.fqn()))symbol.metadata().put("module_exports",exports);
        return new Content(List.copyOf(symbols),List.copyOf(edges),classes,List.copyOf(warnings));
    }
    private static Generic emptyGeneric(){return new Generic(List.of(),List.of());}
    private static Generic generic(List<Signature.TypeParam> parameters){
        if(parameters.isEmpty())return emptyGeneric();
        var ids=new ArrayList<String>(parameters.size());
        var bounds=new ArrayList<List<SemanticType>>(parameters.size());
        for(var parameter:parameters){
            ids.add(parameter.identifier());
            var values=new ArrayList<SemanticType>();
            parameter.classBound().ifPresent(bound->{
                var value=semantic(bound);
                if(!(value instanceof SemanticType.Declared declared&&declared.name().equals("java.lang.Object")))values.add(value);
            });
            parameter.interfaceBounds().forEach(bound->values.add(semantic(bound)));
            bounds.add(List.copyOf(values));
        }
        return new Generic(ids,bounds);
    }
    private static SemanticType declared(String binary){
        String value=binary.replace('$','.');
        return new SemanticType.Declared(value,value,List.of());
    }
    private static SemanticType semantic(Signature signature){
        return switch(signature){
            case Signature.BaseTypeSig base -> new SemanticType.Primitive(Signatures.type(base));
            case Signature.ArrayTypeSig array -> new SemanticType.Array(semantic(array.componentSignature()));
            case Signature.TypeVarSig variable -> new SemanticType.Variable(variable.identifier(),variable.identifier());
            case Signature.ClassTypeSig type -> {
                String name=rawName(type);
                var arguments=new ArrayList<SemanticType>();
                for(var argument:type.typeArgs())arguments.add(switch(argument){
                    case Signature.TypeArg.Unbounded _ -> new SemanticType.Wildcard(null,null);
                    case Signature.TypeArg.Bounded bounded -> switch(bounded.wildcardIndicator()){
                        case NONE -> semantic(bounded.boundType());
                        case EXTENDS -> new SemanticType.Wildcard(semantic(bounded.boundType()),null);
                        case SUPER -> new SemanticType.Wildcard(null,semantic(bounded.boundType()));
                    };
                });
                yield new SemanticType.Declared(name,name,List.copyOf(arguments));
            }
        };
    }
    private static String rawName(Signature.ClassTypeSig type){
        String current=type.className().replace('/','.').replace('$','.');
        return type.outerType().map(parent->rawName(parent)+"."+current).orElse(current);
    }

    private static void parse(byte[] bytes,String entry,Map<String,ClassModel> classes,Map<String,String> entries,List<String> warnings) {
        try {var model=ClassFile.of().parse(bytes);String name=name(model.thisClass());classes.put(name,model);entries.put(name,entry);}
        catch(IllegalArgumentException e){warnings.add("malformed_class: "+entry+": "+e.getClass().getSimpleName());}
    }
    private static boolean visible(int flags){return(flags&(ClassFile.ACC_PUBLIC|ClassFile.ACC_PROTECTED))!=0;}
    private static String name(ClassEntry entry){return entry.asInternalName().replace('/','.');}
    static String simple(String name){return name.substring(Math.max(name.lastIndexOf('.'),name.lastIndexOf('$'))+1);}
    private static Map<String,Object> metadata(AttributedElement element){var data=new LinkedHashMap<String,Object>();element.findAttribute(Attributes.signature()).ifPresent(a->data.put("generic_signature",a.signature().stringValue()));data.put("deprecated",element.findAttribute(Attributes.deprecated()).isPresent());return data;}
    private static void annotations(AttributedElement element,String key,List<Edge> edges){element.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(a->a.annotations().forEach(n->edges.add(new Edge(key,Signatures.qualified(n.classSymbol()),"annotated_by"))));}
}
