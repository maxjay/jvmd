package dev.jvmd.index.layer.machine;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.*;
import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.util.*;

/** Local symbolic ClassReader inputs. No cross-class name is resolved during extraction. */
public final class ReaderImage {
    public static final int RECIPE=0, METHOD=1, VARIABLE=2, PRESENT=3;
    public record Named(int operation,String name,byte[] declaration) { }
    public record Parsed(String owner,List<byte[]> recipe,List<Named> names,boolean supported,List<byte[]> parameterRecipe) {
        public Parsed(String owner,List<byte[]> recipe,List<Named> names,boolean supported) {this(owner,recipe,names,supported,recipe);}
        public Parsed {recipe=List.copyOf(recipe);names=List.copyOf(names);parameterRecipe=List.copyOf(parameterRecipe);}
    }
    private ReaderImage() { }
    public static byte[] key(int operation,String name) {return new Codec.Writer().u8(operation).utf16(name).toBytes();}
    public static byte[] owner(String owner) {return new Codec.Writer().utf16(owner).toBytes();}
    /** Descriptor/signature are symbolic; an actual native returned type is checked separately. */
    public static byte[] declaration(String descriptor,String signature) {
        return new Codec.Writer().utf16(descriptor).optStr(signature).toBytes();
    }
    private static String signature(AttributedElement element) {
        return element.findAttribute(Attributes.signature()).map(a->a.signature().stringValue()).orElse(null);
    }
    public static Parsed extract(ClassModel model) {
        var recipe=new ArrayList<byte[]>();var parameters=new ArrayList<byte[]>();var names=new TreeMap<byte[],Named>(Arrays::compareUnsigned);
        boolean[] supported={true};
        // javac deliberately jumps to the class attributes before reading fields and methods.
        attributes(model,new Codec.Writer().u8(0),recipe,supported,false,0);
        attributes(model,new Codec.Writer().u8(0),parameters,supported,true,0);
        for(var field:model.fields()) {
            String name=field.fieldName().stringValue(),desc=field.fieldType().stringValue();
            attributes(field,new Codec.Writer().u8(1).utf16(name).utf16(desc),recipe,supported,false,0);
            attributes(field,new Codec.Writer().u8(1).utf16(name).utf16(desc),parameters,supported,true,0);
            if(entered(flags(field,field.flags().flagsMask()),name))names.put(key(VARIABLE,name),new Named(VARIABLE,name,declaration(desc,signature(field))));
        }
        for(var method:model.methods()) {
            String name=method.methodName().stringValue(),desc=method.methodType().stringValue();
            int slots=(method.flags().flagsMask() & ClassFile.ACC_STATIC)==0?1:0;
            for(var parameter:java.lang.constant.MethodTypeDesc.ofDescriptor(desc).parameterList())
                slots+=parameter.descriptorString().equals("J") || parameter.descriptorString().equals("D")?2:1;
            attributes(method,new Codec.Writer().u8(2).utf16(name).utf16(desc),recipe,supported,false,slots);
            attributes(method,new Codec.Writer().u8(2).utf16(name).utf16(desc),parameters,supported,true,slots);
            if(entered(flags(method,method.flags().flagsMask()),name) && desc.startsWith("()"))
                names.put(key(METHOD,name),new Named(METHOD,name,declaration(desc,signature(method))));
        }
        return new Parsed(model.thisClass().asInternalName(),recipe,new ArrayList<>(names.values()),supported[0],parameters);
    }
    private static int flags(AttributedElement element,int flags) {
        for(var a:element.attributes())switch(a.attributeName().stringValue()) {
            case "Synthetic" -> flags|=ClassFile.ACC_SYNTHETIC;
            case "Bridge" -> flags|=ClassFile.ACC_BRIDGE;
            default -> { }
        }
        return flags;
    }
    private static boolean entered(int flags,String name) {
        return (flags & (ClassFile.ACC_SYNTHETIC|ClassFile.ACC_BRIDGE))!=ClassFile.ACC_SYNTHETIC || name.startsWith("lambda$");
    }
    private static void attributes(AttributedElement element,Codec.Writer site,List<byte[]> recipe,boolean[] supported,boolean parameterNames,int slots) {
        byte[] prefix=site.toBytes();
        for(var attribute:element.attributes()) {
            var out=new Codec.Writer().lenBytes(prefix);
            switch(attribute) {
                case SourceFileAttribute a -> out.u8(7).utf16(a.sourceFile().stringValue());
                case MethodParametersAttribute a -> {
                    if(!parameterNames)continue;
                    out.u8(8).u32(a.parameters().size());
                    for(var p:a.parameters()) {out.u8(p.name().isPresent()?1:0);p.name().ifPresent(n->out.utf16(n.stringValue()));out.u16(p.flagsMask());}
                }
                case CodeAttribute a -> {
                    // javac skips instructions, exceptions and non-name Code attributes. Never hash body bytes.
                    if(!parameterNames)continue;
                    boolean any=false;
                    for(var nested:a.attributes())if(nested instanceof LocalVariableTableAttribute table)
                        any|=parameterTable(out,table,slots);
                    if(!any)continue;
                }
                case RuntimeVisibleAnnotationsAttribute a -> {out.u8(0);annotations(out,a.annotations(),supported);}
                case RuntimeInvisibleAnnotationsAttribute a -> {out.u8(1);annotations(out,a.annotations(),supported);}
                case RuntimeVisibleTypeAnnotationsAttribute a -> {out.u8(2).u32(a.annotations().size());for(var t:a.annotations()){ClassFacts.writeTypeAnnotation(out,t);if(unsupported(ClassFacts.annotation(t.annotation())))supported[0]=false;}}
                case RuntimeInvisibleTypeAnnotationsAttribute a -> {out.u8(3).u32(a.annotations().size());for(var t:a.annotations()){ClassFacts.writeTypeAnnotation(out,t);if(unsupported(ClassFacts.annotation(t.annotation())))supported[0]=false;}}
                case RuntimeVisibleParameterAnnotationsAttribute a -> {out.u8(4).u32(a.parameterAnnotations().size());for(var p:a.parameterAnnotations())annotations(out,p,supported);supported[0]=false;}
                case RuntimeInvisibleParameterAnnotationsAttribute a -> {out.u8(5).u32(a.parameterAnnotations().size());for(var p:a.parameterAnnotations())annotations(out,p,supported);supported[0]=false;}
                case AnnotationDefaultAttribute a -> {out.u8(6);var value=ClassFacts.value(a.defaultValue());Ann.encode(out,value);if(unsupported(value))supported[0]=false;}
                case RecordAttribute a -> {
                    for(var component:a.components())attributes(component,new Codec.Writer().u8(3).utf16(component.name().stringValue())
                            .utf16(component.descriptor().stringValue()),recipe,supported,parameterNames,slots);
                    supported[0]=false;continue;
                }
                default -> {continue;}
            }
            recipe.add(out.toBytes());
        }
    }
    private static boolean parameterTable(Codec.Writer out,LocalVariableTableAttribute table,int slots) {
        var selected=table.localVariables().stream().filter(v->v.startPc()==0 && v.slot()<slots).toList();
        if(selected.isEmpty())return false;
        out.u8(9).u32(selected.size());for(var variable:selected)out.u16(variable.slot()).utf16(variable.name().stringValue());
        return true;
    }
    private static void annotations(Codec.Writer out,List<Annotation> annotations,boolean[] supported) {
        var values=annotations.stream().map(ClassFacts::annotation).toList();Ann.encodeList(out,values);
        if(values.stream().anyMatch(ReaderImage::unsupported))supported[0]=false;
    }
    private static boolean unsupported(Ann annotation) {return annotation.elements().stream().anyMatch(e->unsupported(e.value()));}
    private static boolean unsupported(Ann.Val value) {
        return switch(value) {
            case Ann.Val.Cls ignored -> true; // Native class-literal interpretation is not yet a proved reader operation.
            case Ann.Val.Nested nested -> unsupported(nested.annotation());
            case Ann.Val.Array array -> array.values().stream().anyMatch(ReaderImage::unsupported);
            default -> false;
        };
    }
    /** Materialise one parsed class; the outer map preserves classes even when T deliberately filters them. */
    public static Entry seal(ContentTree tree,NodeSink sink,Parsed parsed) {
        var entries=new ArrayList<Entry>();
        var list=new ContentList(tree.digest());
        for(var recipe:List.of(Map.entry("",parsed.recipe()),Map.entry("parameters",parsed.parameterRecipe()))) {
            var elements=recipe.getValue().stream().map(bytes->new Entry(bytes,Entry.NONE,tree.digest().hash(bytes))).toList();
            var sequence=list.build(elements,sink);
            add(tree,entries,RECIPE,recipe.getKey(),new Codec.Writer().u8(parsed.supported()?1:0).id(sequence.hash()).toBytes());
        }
        add(tree,entries,PRESENT,"",Entry.NONE);
        for(var name:parsed.names())add(tree,entries,name.operation(),name.name(),name.declaration());
        entries.sort((a,b)->Arrays.compareUnsigned(a.key(),b.key()));
        var root=tree.build(entries,sink);var key=owner(parsed.owner());
        return new Entry(key,root.hash().bytes(),tree.digest().hash(key,root.hash().view()));
    }
    private static void add(ContentTree tree,List<Entry> out,int op,String name,byte[] value) {
        var key=key(op,name);out.add(new Entry(key,value,tree.digest().hash(key,value)));
    }
}
