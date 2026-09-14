package dev.jvmd.index;

import java.lang.classfile.*;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.constant.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Implements 4.4 pass 1: lazy class-file skeletons, with no Code traversal. */
public final class BinaryReader {
    /** Implements 4.4: symbol metadata detached from class-file buffers. */
    public record Symbol(String key, String fqn, String name, String owner, String kind, String signature,
                         String descriptor, int flags, String entry, List<String> parameters, Map<String,Object> metadata) { }
    /** Implements 4.4: unresolved structural edges linked after each artifact transaction. */
    public record Edge(String src, String target, String kind) { }
    /** Implements 4.4: one artifact's detached skeleton, plus source-join models scoped to that read. */
    public record Content(List<Symbol> symbols, List<Edge> edges, Map<String,ClassModel> models, List<String> warnings) { }
    public Content read(Path path, boolean local) throws Exception {
        var classes = new LinkedHashMap<String, ClassModel>(); var entries = new HashMap<String,String>(); var warnings = new ArrayList<String>();
        if (Files.isDirectory(path)) {
            try (var files=Files.walk(path)) { for(var file:files.filter(p->p.toString().endsWith(".class")).toList()) parse(Files.readAllBytes(file),path.relativize(file).toString(),classes,entries,warnings); }
        } else try (var jar=new JarFile(path.toFile(),false,JarFile.OPEN_READ,Runtime.version())) {
            for(var entry:jar.versionedStream().filter(e->e.getName().endsWith(".class")).toList()) {
                try(var stream=jar.getInputStream(entry)){parse(stream.readAllBytes(),entry.getRealName(),classes,entries,warnings);}
            }
        }
        var symbols=new ArrayList<Symbol>();var edges=new ArrayList<Edge>();
        for(var model:classes.values()) {
            String owner=name(model.thisClass()), entry=entries.get(owner);
            if(model.isModuleInfo()) continue;
            int flags=model.flags().flagsMask();
            for(var inner:model.findAttribute(Attributes.innerClasses()).stream().flatMap(a->a.classes().stream()).toList())
                if(inner.innerClass().asInternalName().equals(model.thisClass().asInternalName())) flags=inner.flagsMask();
            if(!local && !visible(flags)) continue;
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
            symbols.add(new Symbol(owner,owner,simple(owner),outer,kind,declaration,null,flags,entry,List.of(),metadata));
            model.superclass().ifPresent(c->edges.add(new Edge(owner,name(c),"extends")));
            model.interfaces().forEach(c->edges.add(new Edge(owner,name(c),kind.equals("interface")?"extends":"implements")));
            annotations(model,owner,edges);
            for(var field:model.fields()) {
                if(!local&&!visible(field.flags().flagsMask()))continue;
                String key=owner+"#"+field.fieldName().stringValue();
                Signature type=field.findAttribute(Attributes.signature()).map(a->a.asTypeSignature()).orElseGet(()->Signature.of(field.fieldTypeSymbol()));
                symbols.add(new Symbol(key,owner,field.fieldName().stringValue(),owner,(field.flags().flagsMask()&ClassFile.ACC_ENUM)!=0?"enumconst":"field",Signatures.type(type)+" "+field.fieldName().stringValue(),field.fieldType().stringValue(),field.flags().flagsMask(),entry,List.of(),metadata(field)));
                Signatures.referenced(type).forEach(t->edges.add(new Edge(key,t,"return_type"))); annotations(field,key,edges);
            }
            for(var method:model.methods()) {
                int mf=method.flags().flagsMask(); String methodName=method.methodName().stringValue();
                if(methodName.equals("<clinit>") || (mf & ClassFile.ACC_BRIDGE)!=0 || !local&&!visible(mf))continue;
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
                symbols.add(new Symbol(key,owner,ctor?simple(owner):methodName,owner,ctor?"ctor":"method",signature,method.methodType().stringValue(),mf,entry,List.copyOf(names),data));
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
