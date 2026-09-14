package dev.jvmd.runtime;

import com.sun.jdi.*;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;

/** Implements 4.7: bounded compiled evaluation against real frame locals with assignment copyback. */
public final class CompiledEvaluation {
    private static final long TTL=java.time.Duration.ofMinutes(5).toNanos(),MAX_BYTES=32L*1024*1024;
    private record Cached(EvaluationCompiler.Plan plan,long expires,long bytes) { }
    private final DebugSession debug;
    private final RunManager.Request request;
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>(16,.75f,true);
    private long cacheBytes;
    public CompiledEvaluation(DebugSession debug,RunManager.Request request){this.debug=debug;this.request=request;}
    public synchronized void clear(){cache.clear();cacheBytes=0;}
    public synchronized Envelope evaluate(String expression,String reference)throws Exception{
        if(expression==null||expression.isBlank()||expression.length()>8192)throw RpcException.invalid("An expression of 1..8192 characters is required");
        StackFrame frame=debug.frame(reference);ThreadReference thread=frame.thread();var location=frame.location();
        if(location.method().isObsolete())throw unsupported("The selected frame is obsolete after class redefinition; resume to a fresh frame");
        List<LocalVariable> variables;try{variables=frame.visibleVariables();}catch(AbsentInformationException e){throw debug.missingLocals();}
        var metadata=variables.stream().map(local->new EvaluationCompiler.Local(local.name(),local.signature(),local.genericSignature())).toList();
        var values=frame.getValues(variables);ObjectReference self=frame.thisObject();ReferenceType host=location.declaringType();
        int depth=reference==null||reference.isBlank()?0:Integer.parseInt(reference.substring(reference.lastIndexOf(':')+1));
        Path source=debug.sources().find(host);if(source==null||!Files.isRegularFile(source))throw unsupported("Declaring source is unavailable for "+host.name());
        var target=request.targets().stream().filter(t->t.sources().stream().anyMatch(source::startsWith)).max(Comparator.comparingInt(t->t.directory().getNameCount())).orElseThrow(()->unsupported("Declaring source is outside the compilation targets"));
        String hash=Hashing.sha256(source),key=Json.MAPPER.writeValueAsString(List.of(expression,host.name(),host.classObject().uniqueID(),location.method().name(),location.method().signature(),location.lineNumber(),self!=null,metadata,hash,target.classpath(),target.options()));
        long now=System.nanoTime();var iterator=cache.entrySet().iterator();
        while(iterator.hasNext()){var item=iterator.next().getValue();if(now-item.expires()>=0){cacheBytes-=item.bytes();iterator.remove();}}
        var entry=cache.get(key);boolean cached=entry!=null;EvaluationCompiler.Plan plan;
        if(cached)plan=entry.plan();
        else{
            plan=EvaluationCompiler.compile(request.compilerHome(),source,host.name(),location.method().name(),location.lineNumber(),self!=null,metadata,expression,target.classpath(),target.sources(),target.options());
            long bytes=2L*(plan.evaluator().length()+plan.bootstrap().length());
            while(!cache.isEmpty()&&(cache.size()>=32||cacheBytes+bytes>MAX_BYTES)){var removed=cache.remove(cache.keySet().iterator().next());cacheBytes-=removed.bytes();}
            cache.put(key,new Cached(plan,System.nanoTime()+TTL,bytes));cacheBytes+=bytes;
        }
        if(!Hashing.sha256(source).equals(plan.sourceHash()))throw RpcException.invalid("Declaring source changed before evaluation");
        var pins=new ObjectHandles(debug.id()+"-eval",java.time.Duration.ofSeconds(60),System::nanoTime);long started=System.nanoTime();
        try{
            EvaluationCompiler.Plan selected=plan;
            var result=debug.invocation(()->{
                var vm=debug.vm();ClassType classes=(ClassType)vm.classesByName("java.lang.Class").getFirst();
                StringReference name=vm.mirrorOf(EvaluationBridge.class.getName());pins.pin(name);
                var forName=classes.concreteMethodByName("forName","(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
                var loaders=(ClassType)vm.classesByName("java.lang.ClassLoader").getFirst();
                var systemLoader=loaders.invokeMethod(thread,loaders.concreteMethodByName("getSystemClassLoader","()Ljava/lang/ClassLoader;"),List.of(),ClassType.INVOKE_SINGLE_THREADED);
                var classObject=(ClassObjectReference)classes.invokeMethod(thread,forName,Arrays.asList(name,vm.mirrorOf(true),systemLoader),ClassType.INVOKE_SINGLE_THREADED);
                pins.pin(classObject);var helper=(ClassType)classObject.reflectedType();
                var allocator=helper.concreteMethodByName("arguments","(I)[Ljava/lang/Object;");
                var arguments=(ArrayReference)helper.invokeMethod(thread,allocator,List.of(vm.mirrorOf(variables.size())),ClassType.INVOKE_SINGLE_THREADED);pins.pin(arguments);
                var primitives=new StringBuilder();
                for(int i=0;i<variables.size();i++){
                    Value value=values.get(variables.get(i));
                    if(value instanceof PrimitiveValue primitive)primitives.append(i).append(':').append(value.type().name()).append(':').append(value instanceof CharValue character?Integer.toString(character.value()):primitive.toString()).append('\n');
                    else arguments.setValue(i,value);
                }
                StringReference evaluator=vm.mirrorOf(selected.evaluator()),bootstrap=vm.mirrorOf(selected.bootstrap()),encoded=vm.mirrorOf(primitives.toString());pins.pin(evaluator);pins.pin(bootstrap);pins.pin(encoded);
                var method=helper.concreteMethodByName("evaluate","(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/String;)[Ljava/lang/Object;");
                var returned=(ArrayReference)helper.invokeMethod(thread,method,Arrays.asList(host.classObject(),evaluator,bootstrap,self,arguments,encoded),ClassType.INVOKE_SINGLE_THREADED);pins.pin(returned);
                if(returned.length()!=variables.size()+2)throw unsupported("Evaluation bridge returned an invalid local snapshot");
                var all=returned.getValues();var unboxed=new HashMap<Integer,Value>();
                for(String line:((StringReference)all.getLast()).value().split("\n"))if(!line.isEmpty()){
                    String[] parts=line.split(":",3);unboxed.put(Integer.parseInt(parts[0]),primitive(vm,parts[1],parts[2]));
                }
                // Every invoke invalidates StackFrame objects. Reacquire this depth before writing locals.
                StackFrame fresh=thread.frame(depth);
                if(fresh.location().method().isObsolete())throw unsupported("Frame became obsolete while evaluating; local assignments were not copied back");
                var changed=new ArrayList<String>();
                for(int i=0;i<variables.size();i++){
                    var local=variables.get(i);Value updated=local.signature().length()==1?unboxed.get(i+1):all.get(i+1);
                    if(!Objects.equals(values.get(local),updated)){
                        var current=fresh.visibleVariableByName(local.name());
                        if(current==null||!current.signature().equals(local.signature()))throw unsupported("Frame local changed while evaluating: "+local.name());
                        fresh.setValue(current,updated);changed.add(local.name());
                    }
                }
                return Map.of("value",debug.value(unboxed.getOrDefault(0,all.getFirst())),"updated_locals",changed);
            });
            var output=new LinkedHashMap<String,Object>(result);output.put("eval_tier",2);output.put("compiled_cache_hit",cached);output.put("compile_ms",cached?0:plan.compileMillis());output.put("invoke_ms",(System.nanoTime()-started)/1e6);output.put("source_hash",plan.sourceHash());
            return Envelope.of(2,"live",output);
        }catch(InvocationException error){
            return new Envelope(2,"live",false,null,List.of("evaluation_threw; target side effects before the exception are retained"),Map.of("eval_tier",2,"threw",true,"exception",debug.value(error.exception()),"local_assignments_applied",false));
        }finally{pins.close();}
    }
    private static Value primitive(VirtualMachine vm,String type,String value){
        return switch(type){case "boolean"->vm.mirrorOf(Boolean.parseBoolean(value));case "byte"->vm.mirrorOf(Byte.parseByte(value));case "short"->vm.mirrorOf(Short.parseShort(value));case "char"->vm.mirrorOf((char)Integer.parseInt(value));case "int"->vm.mirrorOf(Integer.parseInt(value));case "long"->vm.mirrorOf(Long.parseLong(value));case "float"->vm.mirrorOf(Float.parseFloat(value));case "double"->vm.mirrorOf(Double.parseDouble(value));default->throw RpcException.invalid("Invalid primitive evaluation result");};
    }
    private static RpcException unsupported(String reason){return new RpcException(-32003,"unsupported_capability",Map.of("capability","eval_tier_2","reason",reason));}
}
