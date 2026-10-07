package dev.jvmd.boot.cold.stage3;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.lang.reflect.Method;
import java.util.*;

/** Detaches native reader answers. It never completes a symbol, walks a scope or repeats a compiler lookup. */
final class NativeReaderCapture {
    private final Pool.ReaderInputs inputs;
    private final java.util.function.Predicate<Symbol.ClassSymbol> fixed;
    private final Map<Symbol.ClassSymbol,Map<ReverseIndex.Dependency,Identity>> retained=new IdentityHashMap<>();
    private final Map<Symbol.ClassSymbol,Set<Symbol.ClassSymbol>> dependencies=new IdentityHashMap<>();
    private final Map<Symbol.ClassSymbol,Set<Proof.Range>> retainedResolution=new IdentityHashMap<>();
    private final Set<Proof.Range> resolution=new LinkedHashSet<>();
    private final Set<Symbol.ClassSymbol> imported=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<ReverseIndex.Dependency,byte[]> answers=new HashMap<>();
    long events,physicalReads,queries,nodeReads,nodeBytes;
    private final Map<ReverseIndex.Dependency,Identity> current=new TreeMap<>();
    private final Set<Symbol.ClassSymbol> decoded=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> faults=new LinkedHashSet<>();
    private boolean active,effects;
    private static final Method BEGIN,FINISH,STATUS;
    static {
        Method begin=null,finish=null,status=null;
        try {
            var tap=Class.forName("dev.jvmd.boot.cold.stage3.NativeReaderTap",false,null);
            begin=tap.getMethod("begin",java.util.function.Consumer.class);finish=tap.getMethod("finish");status=tap.getMethod("status");
            // Delay loading until task/validation startup, after every independent test agent's premain.
            for(var name:List.of("com.sun.tools.javac.jvm.ClassReader","com.sun.tools.javac.jvm.ClassReader$AnnotationDeproxy","com.sun.tools.javac.code.Symbol$ClassSymbol"))
                Class.forName(name,false,ModuleLayer.boot().findLoader("jdk.compiler"));
        } catch(ReflectiveOperationException absent) { /* Startup agent is an admission requirement, not a compiler replacement. */ }
        BEGIN=begin;FINISH=finish;STATUS=status;
    }
    NativeReaderCapture(Pool.ReaderInputs inputs,java.util.function.Predicate<Symbol.ClassSymbol> fixed) {this.inputs=inputs;this.fixed=fixed;}
    static String bridgeStatus() {
        if(STATUS==null)return "Stage 3 compiler reader bridge is not installed";
        try {return (String)STATUS.invoke(null);}
        catch(ReflectiveOperationException failure) {return "Cannot verify compiler reader bridge: "+failure;}
    }
    void clear() {retained.clear();retainedResolution.clear();dependencies.clear();decoded.clear();}
    void begin() {
        current.clear();resolution.clear();faults.clear();imported.clear();effects=false;
        if(inputs==null || BEGIN==null)return;
        try {active=true;if(!(boolean)BEGIN.invoke(null,(java.util.function.Consumer<Object[]>)this::event))faults.add("Compiler bridge drift");}
        catch(ReflectiveOperationException ex) {active=false;faults.add("Cannot enter compiler reader bridge: "+ex);}
    }
    List<Proof.ReaderRead> finish() {
        if(active)try {var failure=(String)FINISH.invoke(null);if(failure!=null)faults.add(failure);}
        catch(ReflectiveOperationException ex) {faults.add("Cannot leave compiler reader bridge: "+ex);}
        finally {active=false;}
        return current.entrySet().stream().map(e->new Proof.ReaderRead(e.getKey(),e.getValue())).toList();
    }
    boolean supported() {return inputs!=null && BEGIN!=null && faults.isEmpty();}
    boolean admitted(Symbol.ClassSymbol symbol) {return active && decoded.contains(symbol) && faults.isEmpty();}
    boolean effects() {return effects;}
    List<String> faults() {return List.copyOf(faults);}
    List<Proof.Range> resolutionReads() {return List.copyOf(resolution);}
    private static String name(Symbol.ClassSymbol symbol) {return symbol.flatname.toString().replace('.','/');}
    private static boolean platform(Symbol.ClassSymbol symbol) {
        return symbol.classfile!=null && "jrt".equals(symbol.classfile.toUri().getScheme());
    }
    private void event(Object[] event) {
        events++;String operation=(String)event[0];var requesting=event[2] instanceof Symbol.ClassSymbol s?s:null;
        if(operation.equals("touch")) {if(requesting!=null && retained.containsKey(requesting))reuse(requesting);return;}
        if(operation.equals("failure") || event[4]!=null) {
            // The native Throwable is captured independently of its bottom-type recovery value.
            faults.add("Native reader completion failure: "+(event[4]==null?"unknown":event[4].getClass().getName()));
            effects=true;
            if(operation.equals("failure")) {
                // A thrown class read still consumed this precise input. It cannot be admitted,
                // but its question must survive publication for discovery after repair.
                if(requesting!=null && !platform(requesting) && !fixed.test(requesting)
                        && event[3] instanceof com.sun.tools.javac.jvm.ClassReader reader)
                    query(requesting,ReaderImage.RECIPE,reader.saveParameterNames?"parameters":"",requesting);
                return;
            }
        }
        if(requesting==null) {faults.add("Missing native reader requester");return;}
        if(operation.equals("unsupported")) {
            if(!platform(requesting))faults.add("Native class-literal proxy requires an additional observation");
            return;
        }
        if(platform(requesting))return;
        if(operation.equals("resolution-type")) {
            if(fixed.test(requesting))return;
            var read=new Proof.Range(Proof.T,name(requesting),Keys.TYPE,"");
            resolution.add(read);retainedResolution.computeIfAbsent(requesting,k->new LinkedHashSet<>()).add(read);
            return;
        }
        if(operation.equals("read")) {
            physicalReads++;if(fixed.test(requesting))return;
            var local=query(requesting,ReaderImage.RECIPE,((com.sun.tools.javac.jvm.ClassReader)event[3]).saveParameterNames?"parameters":"",requesting);
            if(local!=null && local[0]==ReaderBinding.PRESENT)decoded.add(requesting);
            return;
        }
        Symbol value=event[0].equals("enum")?((com.sun.tools.javac.code.Attribute.Enum)event[1]).value:(Symbol)event[1];
        if(!(value.owner instanceof Symbol.ClassSymbol owner)) {faults.add("Reader answer has no class owner");return;}
        if(platform(owner))return;
        // Named-module binary resolution is outside this initial classpath slice.
        if(owner.packge().modle!=null && !owner.packge().modle.isUnnamed()) {faults.add("Named-module reader answer");return;}
        int op=operation.equals("method")?ReaderImage.METHOD:ReaderImage.VARIABLE;
        String member=value.name.toString();
        byte[] local=query(owner,op,member,requesting);if(local==null)return;
        Type type=value.type;
        if(op==ReaderImage.METHOD) {
            if(!(type instanceof Type.MethodType method)) {faults.add("Generic native annotation method");return;}
            type=method.restype;
        }
        if(type.hasTag(TypeTag.BOT)) {
            effects=true;
            if(local[0]!=ReaderBinding.ABSENT_MEMBER)faults.add("Native recovery differs from indexed member absence");
            return;
        }
        if(local[0]!=ReaderBinding.PRESENT) {faults.add("Native member differs from indexed reader image");return;}
        var in=new Codec.Reader(Arrays.copyOfRange(local,1,local.length));
        String descriptor=in.utf16(),signature=in.optStr();
        String nativeDescriptor=descriptor(type);
        if(nativeDescriptor==null || signature!=null || !descriptor.equals((op==ReaderImage.METHOD?"()":"")+nativeDescriptor))
            faults.add("Native member type requires a richer reader answer");
        dependencies.computeIfAbsent(requesting,k->Collections.newSetFromMap(new IdentityHashMap<>())).add(owner);
        reuse(owner);
    }
    private byte[] query(Symbol.ClassSymbol owner,int operation,String member,Symbol.ClassSymbol requester) {
        if(inputs==null)return null;
        var q=new ReverseIndex.Dependency(ReverseIndex.M,name(owner),operation,member);
        queries++;
        var local=answers.computeIfAbsent(q,key->ReaderBinding.local(inputs.tree(),inputs.binding(),q.type(),q.kind(),q.name(),id->{
            var bytes=inputs.records().apply(MachineStore.nodeKey(id));nodeReads++;nodeBytes+=bytes.length;return bytes;
        }));
        var answer=inputs.tree().digest().hash(local);
        current.put(q,answer);retained.computeIfAbsent(requester,k->new TreeMap<>()).put(q,answer);
        // An unsupported answer is retained only in rejected C/current X. It cannot produce CI/ACI.
        if(local[0]==ReaderBinding.UNSUPPORTED) {faults.add("No supported reader view for "+q.type());return null;}
        return local;
    }
    private void reuse(Symbol.ClassSymbol owner) {
        if(!imported.add(owner))return;
        var answers=retained.get(owner);if(answers!=null)current.putAll(answers);
        resolution.addAll(retainedResolution.getOrDefault(owner,Set.of()));
        for(var dependency:dependencies.getOrDefault(owner,Set.of()))reuse(dependency);
    }
    private static String descriptor(Type type) {
        return switch(type.getTag()) {
            case BYTE -> "B";case CHAR -> "C";case SHORT -> "S";case INT -> "I";case LONG -> "J";
            case FLOAT -> "F";case DOUBLE -> "D";case BOOLEAN -> "Z";case VOID -> "V";
            case CLASS -> type.tsym instanceof Symbol.ClassSymbol s?"L"+name(s)+";":null;
            case ARRAY -> {String element=descriptor(((Type.ArrayType)type).elemtype);yield element==null?null:"["+element;}
            default -> null;
        };
    }
}
