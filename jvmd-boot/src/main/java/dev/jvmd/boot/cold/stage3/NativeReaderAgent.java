package dev.jvmd.boot.cold.stage3;

import java.lang.classfile.*;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.*;
import java.lang.instrument.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;

/** Version-pinned startup bridge for the Stage 3 adapter; dormant outside its task-local capture. */
public final class NativeReaderAgent {
    private static final String TAP_NAME="dev.jvmd.boot.cold.stage3.NativeReaderTap";
    private static final ClassDesc TAP=ClassDesc.of(TAP_NAME);
    private static final ClassDesc DEPROXY=ClassDesc.of("com.sun.tools.javac.jvm.ClassReader$AnnotationDeproxy");
    private static final ClassDesc CLASS=ClassDesc.of("com.sun.tools.javac.code.Symbol$ClassSymbol");
    private static final MethodTypeDesc EVENT=MethodTypeDesc.ofDescriptor("(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V");
    private static final Map<String,String> HASHES=Map.of(
        "com/sun/tools/javac/jvm/ClassReader","1a609e6ae45b997a6cbbf44380adcd04deae78a2f53a61925817dbac5623bdef",
        "com/sun/tools/javac/jvm/ClassReader$AnnotationDeproxy","517dea796c4259395354bae40c519baa67a310456256838e3311aead1ab54ba7",
        "com/sun/tools/javac/code/Symbol$ClassSymbol","dfff4b4753e61c2a7e128e24b2b772732fbff79dc9024c9e13ff051e69e0c63f");
    private static Class<?> bootstrapTap;
    private NativeReaderAgent() { }
    public static void premain(String arguments,Instrumentation instrumentation) throws Exception {
        var jar=arguments==null || arguments.isEmpty()
                ?Path.of(NativeReaderAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI()):Path.of(arguments);
        var bootstrap=Files.createTempFile("jvmd-compiler-reader-", ".jar");bootstrap.toFile().deleteOnExit();
        try(var input=new JarFile(jar.toFile());var output=new JarOutputStream(Files.newOutputStream(bootstrap))) {
            for(var entry:input.stream().filter(e->e.getName().startsWith(TAP_NAME.replace('.','/'))).toList()) {
                output.putNextEntry(new JarEntry(entry.getName()));
                try(var bytes=input.getInputStream(entry)){bytes.transferTo(output);}output.closeEntry();
            }
        }
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(bootstrap.toFile()));
        bootstrapTap=Class.forName(TAP_NAME,true,null);
        var compiler=ModuleLayer.boot().findModule("jdk.compiler").orElseThrow();
        instrumentation.redefineModule(compiler,Set.of(bootstrapTap.getModule()),Map.of(),Map.of(),Set.of(),Map.of());
        for(var type:instrumentation.getAllLoadedClasses())
            if(HASHES.containsKey(type.getName().replace('.','/')))signal("drift","Compiler class loaded before startup bridge: "+type.getName());
        instrumentation.addTransformer(new Transformer());
    }
    private static void signal(String method,String value) {
        try {bootstrapTap.getMethod(method,String.class).invoke(null,value);}
        catch(ReflectiveOperationException failure) {throw new IllegalStateException("Cannot report reader bridge state",failure);}
    }
    private static final class Transformer implements ClassFileTransformer {
        @Override public byte[] transform(Module module,ClassLoader loader,String name,Class<?> redefining,ProtectionDomain domain,byte[] bytes) {
            if(!HASHES.containsKey(name))return null;
            try {
                if(!module.getName().equals("jdk.compiler"))throw new IllegalStateException("Unexpected compiler module");
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if(!HASHES.get(name).equals(hash))throw new IllegalStateException("Unsupported native reader bytes: "+name+" "+hash);
                var hooks=new TreeMap<String,Integer>();var cf=ClassFile.of();
                var result=cf.transformClass(cf.parse(bytes),ClassTransform.transformingMethods((builder,element)->{
                    if(!(element instanceof CodeModel code)){builder.with(element);return;}
                    var method=code.parent().orElseThrow();String methodName=method.methodName().stringValue();
                    boolean read=name.endsWith("/ClassReader") && methodName.equals("readClassFile");
                    boolean methodHeader=name.endsWith("/ClassReader") && methodName.equals("readMethod");
                    boolean methodQuery=name.endsWith("$AnnotationDeproxy") && methodName.equals("findAccessMethod");
                    boolean enumQuery=name.endsWith("$AnnotationDeproxy") && methodName.equals("visitEnumAttributeProxy");
                    boolean unsupported=name.endsWith("$AnnotationDeproxy") && methodName.equals("visitClassAttributeProxy");
                    boolean touch=name.endsWith("$ClassSymbol") && Set.of("flags","members","getRawAttributes","getRawTypeAttributes").contains(methodName);
                    if(!(read || methodHeader || methodQuery || enumQuery || unsupported || touch)){builder.with(code);return;}
                    hooks.merge(methodName,1,Integer::sum);
                    builder.transformCode(code,new CodeTransform() {
                        Label start;
                        @Override public void atStart(CodeBuilder out) {
                            start=out.newLabel();out.labelBinding(start);
                            if(touch)event(out,"touch",0,0,-1,-1);
                            if(unsupported)event(out,"unsupported",-1,0,1,-1);
                        }
                        @Override public void accept(CodeBuilder out,CodeElement instruction) {
                            if(methodHeader && instruction instanceof java.lang.classfile.instruction.InvokeInstruction invoke
                                    && invoke.name().equalsString("isInterface")) {
                                // This exact decoder branch consumes the type header. A class merely loaded
                                // without this operation does not acquire a blanket T dependency.
                                out.dup();int owner=out.allocateLocal(TypeKind.REFERENCE);out.astore(owner);
                                out.with(instruction);
                                event(out,"resolution-type",owner,owner,-1,-1);
                                return;
                            }
                            if(instruction instanceof ReturnInstruction) {
                                if(read)event(out,"read",1,1,0,-1);
                                else if(methodQuery) {
                                    out.dup();int value=out.allocateLocal(TypeKind.REFERENCE);out.astore(value);
                                    // Native local 3 is the caught CompletionFailure, including recovered returns.
                                    event(out,"method",value,0,2,3);
                                } else if(enumQuery) {
                                    out.aload(0).getfield(DEPROXY,"result",ClassDesc.of("com.sun.tools.javac.code.Attribute"));
                                    int value=out.allocateLocal(TypeKind.REFERENCE);out.astore(value);
                                    // Native local 5 retains the caught failure after native warning/recovery.
                                    event(out,"enum",value,0,1,5);
                                }
                            }
                            out.with(instruction);
                        }
                        @Override public void atEnd(CodeBuilder out) {
                            if(touch || unsupported || methodHeader)return;
                            var end=out.newLabel();var handler=out.newLabel();
                            out.labelBinding(end).exceptionCatchAll(start,end,handler).labelBinding(handler);
                            out.dup();int failure=out.allocateLocal(TypeKind.REFERENCE);out.astore(failure);
                            event(out,"failure",-1,read?1:0,-1,failure);out.athrow();
                        }
                        private void event(CodeBuilder out,String op,int value,int requester,int arg,int failure) {
                            out.ldc(op);load(out,value);
                            if((methodQuery || enumQuery || unsupported) && requester==0)out.aload(0).getfield(DEPROXY,"requestingOwner",CLASS);
                            else load(out,requester);
                            load(out,arg);load(out,failure);out.invokestatic(TAP,"event",EVENT);
                        }
                    });
                }));
                var expected=name.endsWith("/ClassReader")?Map.of("readClassFile",1,"readMethod",1)
                    :name.endsWith("$ClassSymbol")?Map.of("flags",1,"members",1,"getRawAttributes",1,"getRawTypeAttributes",1)
                    :Map.of("findAccessMethod",1,"visitEnumAttributeProxy",1,"visitClassAttributeProxy",1);
                if(!hooks.equals(expected))throw new IllegalStateException("Reader bridge hook drift: "+hooks);
                signal("installed",name);return result;
            } catch(Throwable failure) {
                signal("drift",failure.toString());return null;
            }
        }
        private static void load(CodeBuilder out,int slot) {if(slot<0)out.aconst_null();else out.aload(slot);}
    }
}
