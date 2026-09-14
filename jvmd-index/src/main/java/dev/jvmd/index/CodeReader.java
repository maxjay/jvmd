package dev.jvmd.index;

import java.lang.classfile.*;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.util.*;

/** Implements 4.4 pass 3: explicit, inter-class bytecode relationships, including dynamic handles. */
public final class CodeReader {
    private CodeReader() { }
    public static Set<String> classReferences(Collection<ClassModel> classes){
        var result=new LinkedHashSet<String>();
        for(var type:classes)for(var entry:type.constantPool())if(entry instanceof ClassEntry reference){String name=reference.asInternalName().replace('/','.');if(!name.startsWith("["))result.add(name);}
        return Set.copyOf(result);
    }
    public static List<BinaryReader.Edge> read(Collection<ClassModel> classes){
        var result=new LinkedHashSet<BinaryReader.Edge>();
        for(var type:classes){
            String owner=type.thisClass().asInternalName().replace('/','.');
            for(var method:type.methods()){
                String source=owner+"#"+method.methodName().stringValue()+method.methodType().stringValue();
                if(method.code().isEmpty())continue;
                for(var instruction:method.code().get()){
                    if(instruction instanceof InvokeInstruction invoke)add(result,source,owner,invoke.owner().asInternalName().replace('/','.')+"#"+invoke.name().stringValue()+invoke.type().stringValue(),"calls");
                    else if(instruction instanceof FieldInstruction field)add(result,source,owner,field.owner().asInternalName().replace('/','.')+"#"+field.name().stringValue(),field.opcode()==Opcode.GETFIELD||field.opcode()==Opcode.GETSTATIC?"reads":"writes");
                    else if(instruction instanceof NewObjectInstruction create)add(result,source,owner,create.className().asInternalName().replace('/','.'),"instantiates");
                    else if(instruction instanceof TypeCheckInstruction check){String target=check.type().asInternalName();if(target.startsWith("[")){int object=target.indexOf('L');if(object<0)continue;target=target.substring(object+1,target.length()-1);}add(result,source,owner,target.replace('/','.'),"reads");}
                    else if(instruction instanceof InvokeDynamicInstruction dynamic){
                        handle(result,source,owner,dynamic.bootstrapMethod());
                        for(var argument:dynamic.bootstrapArgs())constant(result,source,owner,argument,new HashSet<>(),0);
                    }
                }
            }
        }return List.copyOf(result);
    }
    private static void constant(Set<BinaryReader.Edge> result,String source,String owner,ConstantDesc value,Set<ConstantDesc> seen,int depth){
        if(depth>32||!seen.add(value))return;
        if(value instanceof DirectMethodHandleDesc method)handle(result,source,owner,method);
        else if(value instanceof DynamicConstantDesc<?> dynamic){handle(result,source,owner,dynamic.bootstrapMethod());for(var argument:dynamic.bootstrapArgs())constant(result,source,owner,argument,seen,depth+1);}
    }
    private static void handle(Set<BinaryReader.Edge> result,String source,String owner,DirectMethodHandleDesc handle){
        String type=handle.owner().descriptorString();if(!type.startsWith("L"))return;String target=type.substring(1,type.length()-1).replace('/','.')+"#"+handle.methodName();
        int kind=handle.refKind();String edge=kind<=2?"reads":kind<=4?"writes":"calls";
        if(kind>4)target+=handle.lookupDescriptor();add(result,source,owner,target,edge);
    }
    private static void add(Set<BinaryReader.Edge> result,String source,String owner,String target,String kind){
        String declaring=target.contains("#")?target.substring(0,target.indexOf('#')):target;
        if(!declaring.equals(owner))result.add(new BinaryReader.Edge(source,target,kind));
    }
}
