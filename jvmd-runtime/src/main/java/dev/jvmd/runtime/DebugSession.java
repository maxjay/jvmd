package dev.jvmd.runtime;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import dev.jvmd.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Implements 4.7: one launched application, JDI event loop, line breakpoints and stopped frames. */
public final class DebugSession implements AutoCloseable {
    /** Implements 4.7: launch configuration reused on restart. */
    public record Launch(Path javaHome,Path directory,List<Path> classpath,String main,List<String> args,boolean debug,List<String> vmOptions) {
        public Launch(Path javaHome,Path directory,List<Path> classpath,String main,List<String> args,boolean debug){this(javaHome,directory,classpath,main,args,debug,List.of());}
        public Launch {classpath=List.copyOf(classpath);args=List.copyOf(args);vmOptions=List.copyOf(vmOptions);}
    }
    private record Stop(ThreadReference thread,EventSet events,long epoch) { }
    private static final class Break {
        final String id,className;final Path file;final int line;final List<EventRequest> requests=new ArrayList<>();final Set<String> locations=new HashSet<>();
        Break(String id,String className,Path file,int line){this.id=id;this.className=className;this.file=file;this.line=line;}
    }
    private final String id;
    private final Launch launch;
    private final SourceLookup sources;
    private final Process process;
    private final VirtualMachine vm;
    private final ObjectHandles handles;
    private final Map<String,Break> breaks=new LinkedHashMap<>();
    private final Map<Long,Stop> stopped=new LinkedHashMap<>();
    private final List<String> warnings=new CopyOnWriteArrayList<>();
    private final StringBuilder output=new StringBuilder();
    private final Thread reader,eventReader;
    private CompletableFuture<Void> nextStop=new CompletableFuture<>();
    private volatile boolean closed,disconnected,invoking;
    private volatile String debugInfo="pending";
    private long sequence,epoch;
    private final double attachMillis;
    private final Integer jdwpPort;
    public DebugSession(String id,Launch launch,SourceLookup sources)throws Exception{this(id,launch,sources,new ObjectHandles(id));}
    public DebugSession(String id,Launch launch,SourceLookup sources,ObjectHandles handles)throws Exception{
        this.id=id;this.launch=launch;this.sources=sources;this.handles=handles;
        var command=new ArrayList<String>();command.add(launch.javaHome().resolve("bin/java").toString());
        command.addAll(launch.vmOptions());
        if(launch.debug())command.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0");
        var targetClasspath=new ArrayList<>(launch.classpath());if(launch.debug())targetClasspath.add(EvaluationSupport.bridge());
        command.addAll(List.of("-cp",targetClasspath.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(File.pathSeparator)),launch.main()));command.addAll(launch.args());
        process=new ProcessBuilder(command).directory(launch.directory().toFile()).redirectErrorStream(true).start();
        var port=new CompletableFuture<Integer>();
        reader=Thread.ofVirtual().name("jvmd-run-output-"+id).start(()->{
            try(var input=new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8)){
                char[] buffer=new char[4096];int count;var line=new StringBuilder();
                while((count=input.read(buffer))!=-1){
                    synchronized(output){output.append(buffer,0,count);if(output.length()>65536)output.delete(0,output.length()-65536);}
                    for(int i=0;i<count;i++){char c=buffer[i];if(c=='\n'){
                        String value=line.toString().strip();if(value.startsWith("Listening for transport dt_socket at address: ")){
                            try{port.complete(Integer.parseInt(value.substring(value.lastIndexOf(' ')+1)));}catch(NumberFormatException ignored){}
                        }line.setLength(0);
                    }else if(line.length()<4096)line.append(c);}
                }
                port.completeExceptionally(new IOException("Application exited before JDWP readiness: "+output()));
            }catch(Exception e){port.completeExceptionally(e);}
        });
        VirtualMachine connected=null;double elapsed=0;Integer listening=null;
        try{
            if(launch.debug()){
                int address=port.get(10,TimeUnit.SECONDS);listening=address;
                var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
                var arguments=connector.defaultArguments();arguments.get("hostname").setValue("127.0.0.1");arguments.get("port").setValue(Integer.toString(address));arguments.get("timeout").setValue("5000");
                long before=System.nanoTime();connected=connector.attach(arguments);elapsed=(System.nanoTime()-before)/1e6;
            }
        }catch(Exception e){handles.close();process.destroyForcibly();throw e;}
        vm=connected;attachMillis=elapsed;jdwpPort=listening;
        if(vm!=null){
            var prepare=vm.eventRequestManager().createClassPrepareRequest();prepare.addClassFilter(launch.main());prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);prepare.enable();
            for(var main:vm.classesByName(launch.main()))if(main.isPrepared())debugInfo(main);
            eventReader=Thread.ofVirtual().name("jvmd-debug-events-"+id).start(this::events);
        }else{eventReader=null;debugInfo="disabled";}
    }
    private void requireDebug(){if(vm==null)throw new RpcException(-32003,"unsupported_capability",Map.of("capability","debug","reason","Launch with debug=true"));if(disconnected||closed)throw RpcException.invalid("Debuggee is disconnected");}
    boolean hasLocalInformation(ReferenceType type){
        for(Method method:type.methods())if(!method.isNative()&&!method.isAbstract())try{method.variables();return true;}catch(AbsentInformationException ignored){}
        return false;
    }
    private void debugInfo(ReferenceType main){
        main.availableStrata();debugInfo=hasLocalInformation(main)?"available":"no_local_variables";
        if(debugInfo.equals("no_local_variables")&&!warnings.contains("debug: no_local_variables"))warnings.add("debug: no_local_variables");
    }
    private void events(){
        try{
            while(!closed){
                EventSet events=vm.eventQueue().remove(250);if(events==null)continue;boolean suspend=false;
                synchronized(this){
                    for(var event:events){
                        if(event instanceof ClassPrepareEvent prepared){if(prepared.referenceType().name().equals(launch.main()))debugInfo(prepared.referenceType());for(var breakpoint:breaks.values())bind(breakpoint,prepared.referenceType());}
                        else if(event instanceof BreakpointEvent breakpoint){stopped.put(breakpoint.thread().uniqueID(),new Stop(breakpoint.thread(),events,++epoch));suspend=true;}
                        else if(event instanceof StepEvent step){vm.eventRequestManager().deleteEventRequest(step.request());stopped.put(step.thread().uniqueID(),new Stop(step.thread(),events,++epoch));suspend=true;}
                        else if(event instanceof VMDeathEvent||event instanceof VMDisconnectEvent){disconnected=true;nextStop.completeExceptionally(new IOException("Application disconnected"));}
                    }
                    if(invoking){if(suspend){stopped.entrySet().removeIf(e->e.getValue().events()==events);}events.resume();}else if(suspend)nextStop.complete(null);else events.resume();
                }
            }
        }catch(VMDisconnectedException ignored){disconnected=true;nextStop.completeExceptionally(new IOException("Application disconnected"));}
        catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(Exception e){warnings.add("debug_event_fault: "+e);nextStop.completeExceptionally(e);}
    }
    public synchronized Map<String,Object> breakpoint(String className,Path file,int line)throws Exception{
        requireDebug();if(line<1)throw RpcException.invalid("Breakpoint line must be positive");
        if((className==null||className.isBlank())&&file==null)throw RpcException.invalid("A class or source path is required");
        if(breaks.size()>=1000)throw RpcException.invalid("Breakpoint limit is 1000");
        var breakpoint=new Break("break:"+id+":"+(++sequence),className,file==null?null:file.toAbsolutePath().normalize(),line);
        var prepare=vm.eventRequestManager().createClassPrepareRequest();
        if(className!=null&&!className.isBlank())prepare.addClassFilter(className+"*");
        else if(vm.canUseSourceNameFilters())prepare.addSourceNameFilter(file.getFileName().toString());
        prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);prepare.enable();breakpoint.requests.add(prepare);breaks.put(breakpoint.id,breakpoint);
        for(var type:vm.allClasses())if(type.isPrepared())bind(breakpoint,type);
        return Map.of("breakpoint",breakpoint.id,"bound",breakpoint.locations.size(),"pending",breakpoint.locations.isEmpty());
    }
    private void bind(Break breakpoint,ReferenceType type)throws Exception{
        if(breakpoint.className!=null&&!breakpoint.className.isBlank()&&!type.name().equals(breakpoint.className)&&!type.name().startsWith(breakpoint.className+"$"))return;
        if(breakpoint.file!=null&&!breakpoint.file.equals(sources.find(type)))return;
        try{
            for(Location location:type.locationsOfLine(breakpoint.line)){
                String key=type.name()+":"+type.classLoader()+":"+location.method().name()+location.method().signature()+":"+location.codeIndex();
                if(!breakpoint.locations.add(key))continue;
                var request=vm.eventRequestManager().createBreakpointRequest(location);request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);request.enable();breakpoint.requests.add(request);
            }
        }catch(AbsentInformationException e){String warning="debug: no line information for "+type.name();if(!warnings.contains(warning))warnings.add(warning);}
    }
    public synchronized boolean unbreak(String id){
        requireDebug();var breakpoint=breaks.remove(id);if(breakpoint==null)return false;vm.eventRequestManager().deleteEventRequests(breakpoint.requests);return true;
    }
    private Stop stop(Long thread){
        requireDebug();Stop stop=thread==null?stopped.values().stream().findFirst().orElse(null):stopped.get(thread);
        if(stop==null)throw RpcException.invalid("Thread is not stopped at a debugger event");return stop;
    }
    public synchronized void resume(Long thread){
        requireDebug();var selected=thread==null?new ArrayList<>(stopped.values()):List.of(stop(thread));var events=new HashSet<EventSet>();
        for(var stop:selected){stopped.remove(stop.thread().uniqueID());events.add(stop.events());}
        nextStop=new CompletableFuture<>();events.forEach(EventSet::resume);
    }
    public synchronized void step(Long thread,String op){
        var stop=stop(thread);int depth=switch(op){case "step_into"->StepRequest.STEP_INTO;case "step_out"->StepRequest.STEP_OUT;case "step_over"->StepRequest.STEP_OVER;default->throw RpcException.invalid("Unknown step");};
        for(var request:new ArrayList<>(vm.eventRequestManager().stepRequests()))if(request.thread().equals(stop.thread()))vm.eventRequestManager().deleteEventRequest(request);
        var request=vm.eventRequestManager().createStepRequest(stop.thread(),StepRequest.STEP_LINE,depth);request.addCountFilter(1);request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);request.enable();resume(stop.thread().uniqueID());
    }
    public void awaitStop(Duration timeout)throws Exception{
        CompletableFuture<Void> future;synchronized(this){if(!stopped.isEmpty())return;future=nextStop;}future.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
    }
    public synchronized Envelope frames(Long thread,int offset,int limit)throws Exception{
        if(offset<0||limit<1||limit>200)throw RpcException.invalid("Frame page limit must be 1..200");
        var stop=stop(thread);int size=stop.thread().frameCount(),from=Math.min(offset,size),to=Math.min(size,from+limit);var values=new ArrayList<Map<String,Object>>();int number=from;
        for(var frame:stop.thread().frames(from,to-from)){
            var location=frame.location();var value=new LinkedHashMap<String,Object>();value.put("frame",frameId(stop,number));value.put("index",number++);value.put("thread",stop.thread().uniqueID());value.put("class",location.declaringType().name());value.put("method",location.method().name());value.put("scip",sources.symbol(location.declaringType(),location.method()));value.put("descriptor",location.method().signature());value.put("obsolete",location.method().isObsolete());value.put("line",location.lineNumber());var source=sources.find(location.declaringType());value.put("source_file",source==null?null:source.toString());values.add(value);
        }
        return new Envelope(2,"live",to<size,to<size?Integer.toString(to):null,List.copyOf(warnings),Map.of("frames",values));
    }
    private String frameId(Stop stop,int number){return "frame:"+id+":"+stop.epoch()+":"+stop.thread().uniqueID()+":"+number;}
    synchronized StackFrame frame(String ref)throws Exception{
        if(ref==null||ref.isBlank())return stop(null).thread().frame(0);
        String[] parts=ref.split(":");if(parts.length!=5||!parts[0].equals("frame")||!parts[1].equals(id))throw RpcException.invalid("Invalid frame");
        try{var stop=stop(Long.parseLong(parts[3]));if(stop.epoch()!=Long.parseLong(parts[2]))throw RpcException.invalid("Frame is stale after resume");return stop.thread().frame(Integer.parseInt(parts[4]));}
        catch(NumberFormatException|IndexOutOfBoundsException e){throw RpcException.invalid("Invalid frame");}
    }
    public synchronized Envelope locals(String ref,int offset,int limit)throws Exception{
        if(offset<0||limit<1||limit>200)throw RpcException.invalid("Local page limit must be 1..200");
        var frame=frame(ref);List<LocalVariable> variables;
        try{variables=frame.visibleVariables();}catch(AbsentInformationException e){throw missingLocals();}
        int from=Math.min(offset,variables.size()),to=Math.min(variables.size(),from+limit);var selected=variables.subList(from,to);var values=frame.getValues(selected);var result=new ArrayList<Map<String,Object>>();
        for(var variable:selected)result.add(Map.of("name",variable.name(),"type",variable.typeName(),"scip",variable.isArgument()?sources.symbol(frame.location().declaringType(),frame.location().method())+"("+variable.name()+")":"local "+id+"_"+Integer.toUnsignedString(Objects.hash(ref,variable.name(),variable.signature())),"value",value(values.get(variable))));
        return new Envelope(2,"live",to<variables.size(),to<variables.size()?Integer.toString(to):null,List.copyOf(warnings),Map.of("locals",result,"this",value(frame.thisObject())));
    }
    <T> T invocation(java.util.concurrent.Callable<T> action)throws Exception{return invocation(action,Duration.ofSeconds(5));}
    <T> T invocation(java.util.concurrent.Callable<T> action,Duration timeout)throws Exception{
        var enabled=new ArrayList<EventRequest>();
        synchronized(this){
            requireDebug();if(invoking)throw RpcException.invalid("An evaluation is already running");invoking=true;
            var requests=new ArrayList<EventRequest>();requests.addAll(vm.eventRequestManager().breakpointRequests());requests.addAll(vm.eventRequestManager().stepRequests());requests.addAll(vm.eventRequestManager().classPrepareRequests());
            for(var request:requests)if(request.isEnabled()){request.disable();enabled.add(request);}
        }
        var evaluation=new FutureTask<T>(action);Thread.ofVirtual().name("jvmd-eval-"+id).start(evaluation);
        try{return evaluation.get(Math.max(1,timeout.toNanos()),TimeUnit.NANOSECONDS);}
        catch(TimeoutException|InterruptedException failure){
            // JDI invocation has no safe cancellation. This daemon owns the launched process;
            // terminate it rather than leave an unknown mutation running behind a usable frame.
            disconnected=true;process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();evaluation.cancel(true);
            try{process.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            synchronized(this){stopped.clear();++epoch;nextStop.completeExceptionally(new IOException("Evaluation aborted"));}
            warnings.add("evaluation_aborted: application terminated; restart is required and external side effects may remain");
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new RpcException(-32003,"evaluation_timeout",Map.of("timeout_ms",timeout.toMillis(),"run_session",id,"restart_required",true,"process_terminated",true,"side_effects_may_remain",true));
        }catch(ExecutionException failure){if(failure.getCause() instanceof Exception e)throw e;if(failure.getCause() instanceof Error e)throw e;throw new IllegalStateException(failure.getCause());}
        finally{synchronized(this){
            invoking=false;
            if(!disconnected&&!closed){
                for(var request:enabled)try{request.enable();}catch(InvalidRequestStateException ignored){}
                for(var entry:new ArrayList<>(stopped.entrySet()))stopped.put(entry.getKey(),new Stop(entry.getValue().thread(),entry.getValue().events(),++epoch));
                for(var breakpoint:breaks.values())for(var type:vm.classesByName(breakpoint.className==null?"":breakpoint.className))if(type.isPrepared())bind(breakpoint,type);
            }
        }}
    }
    public Envelope eval(String expression,String frame)throws Exception{if(debugInfo.equals("no_local_variables"))throw missingLocals();return new ExpressionEvaluator(this,frame).evaluate(expression);}
    public Envelope inspect(String handle,int depth,int breadth,String cursor)throws Exception{return new Inspection(this).inspect(handle,depth,breadth,cursor);}
    private final MemoryView memory=new MemoryView(this);
    public MemoryView memory(){return memory;}
    RpcException missingLocals(){return new RpcException(-32003,"unsupported_capability",Map.of("capability","eval","reason","debug: no_local_variables; compile with -g"));}
    public Map<String,Object> value(Value value){
        if(value==null)return Map.of("kind","null");
        if(value instanceof StringReference string){String text=string.value();return Map.of("kind","string","handle",handles.pin(string),"value",text.substring(0,Math.min(500,text.length())),"truncated",text.length()>500);}
        if(value instanceof ObjectReference object){var row=new LinkedHashMap<String,Object>();row.put("kind","object");row.put("type",object.referenceType().name());row.put("scip",object instanceof ArrayReference?null:sources.symbol(object.referenceType(),null));row.put("handle",handles.pin(object));return row;}
        return Map.of("kind","primitive","type",value.type().name(),"value",value.toString());
    }
    public synchronized Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();result.put("run_session",id);result.put("pid",process.pid());result.put("port",jdwpPort);result.put("alive",process.isAlive());result.put("debug",debugInfo);result.put("attach_ms",attachMillis);result.put("stopped_threads",stopped.keySet().stream().limit(100).toList());result.put("handles",handles.size());result.put("warnings",List.copyOf(warnings));result.put("source_roots",sources.roots());
        if(vm!=null&&!disconnected){boolean enhanced=launch.vmOptions().contains("-XX:+AllowEnhancedClassRedefinition");
            result.put("hotswap",vm.canRedefineClasses()?(enhanced?"enhanced":"bodies_only"):"unsupported");result.put("instance_info",vm.canGetInstanceInfo());
            result.put("jdi_redefinition",Map.of("redefine_classes",vm.canRedefineClasses(),"add_method",vm.canAddMethod(),"unrestricted",vm.canUnrestrictedlyRedefineClasses()));
            result.put("hotswap_detection",enhanced?"configured_jbr_flag":"jdi");result.put("java_home",launch.javaHome().toString());result.put("vm_version",vm.version());
            result.put("framework_reload",launch.vmOptions().contains("-XX:HotswapAgent=external")?"hotswap_agent":"disabled");}
        return result;
    }
    synchronized void redefined()throws Exception{
        requireDebug();
        for(var breakpoint:breaks.values()){
            var old=breakpoint.requests.stream().filter(r->r instanceof BreakpointRequest).toList();for(var request:old)try{vm.eventRequestManager().deleteEventRequest(request);}catch(InvalidRequestStateException ignored){}breakpoint.requests.removeAll(old);breakpoint.locations.clear();
            var types=breakpoint.className==null?vm.allClasses():vm.classesByName(breakpoint.className);for(var type:types)if(type.isPrepared())bind(breakpoint,type);
        }
        for(var entry:new ArrayList<>(stopped.entrySet()))stopped.put(entry.getKey(),new Stop(entry.getValue().thread(),entry.getValue().events(),++epoch));
    }
    public long pid(){return process.pid();}
    public String output(){synchronized(output){return output.toString();}}
    public String id(){return id;}
    public Launch launch(){return launch;}
    public ObjectHandles handles(){return handles;}
    VirtualMachine vm(){requireDebug();return vm;}
    SourceLookup sources(){return sources;}
    public void input(String value)throws IOException{process.getOutputStream().write(value.getBytes(StandardCharsets.UTF_8));process.getOutputStream().flush();}
    @Override public void close()throws Exception{
        synchronized(this){if(closed)return;closed=true;handles.close();if(vm!=null)try{vm.dispose();}catch(VMDisconnectedException ignored){}nextStop.completeExceptionally(new IOException("Run session closed"));}
        if(eventReader!=null)eventReader.interrupt();process.getOutputStream().close();if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}reader.join(2000);
    }
}
