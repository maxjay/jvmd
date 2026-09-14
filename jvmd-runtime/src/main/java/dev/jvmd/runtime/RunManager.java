package dev.jvmd.runtime;

import dev.jvmd.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Implements 4.7: workspace-owned process lifecycle and the frozen debug operation surface. */
public final class RunManager implements AutoCloseable {
    /** Implements 4.7: all launch/compile state needed to repeat the same run after hot swap. */
    public record Target(Path directory,List<Path> sources,List<Path> classpath,List<String> options,Path output) { }
    /** Implements 4.7: detached launch configuration and per-module compilation boundaries. */
    public record Request(DebugSession.Launch launch,SourceLookup sources,Path compilerHome,List<Path> sourceRoots,List<String> compilerOptions,Path output,List<Target> targets) { }
    private record Run(Request request,DebugSession debug,CompiledEvaluation evaluation) { }
    private final String workspace;
    private final LinkedHashMap<String,Run> runs=new LinkedHashMap<>();
    private long sequence;
    public RunManager(String workspace){this.workspace=workspace;}
    public Envelope start(Request request)throws Exception{
        if(runs.size()>=8)throw RpcException.invalid("At most eight application runs can be open in one workspace");
        String id=workspace+"r"+(++sequence);var debug=new DebugSession(id,request.launch(),request.sources());runs.put(id,new Run(request,debug,new CompiledEvaluation(debug,request)));return Envelope.of(2,"live",debug.status());
    }
    public Envelope operation(String id,String op,JsonNode args)throws Exception{
        var run=runs.get(id);if(run==null)throw new RpcException(-32001,"session_not_found",Map.of("run_session",id));var debug=run.debug();
        return switch(op){
            case "break"->{String type=args.hasNonNull("class")?args.get("class").asText():null;Path file=args.hasNonNull("path")?source(run,args.get("path").asText()):null;yield Envelope.of(2,"live",debug.breakpoint(type,file,Dispatcher.bounded(args,"line",0,Integer.MAX_VALUE)));}
            case "unbreak"->Envelope.of(2,"live",Map.of("removed",debug.unbreak(Dispatcher.required(args,"breakpoint"))));
            case "continue"->{debug.resume(thread(args));yield Envelope.of(2,"live",debug.status());}
            case "step_over","step_into","step_out"->{debug.step(thread(args),op);yield Envelope.of(2,"live",debug.status());}
            case "frames"->debug.frames(thread(args),offset(args),positive(args,"limit",20,200));
            case "locals"->debug.locals(args.path("frame").asText(null),offset(args),positive(args,"limit",20,200));
            case "inspect"->args.path("release").asBoolean()?Envelope.of(2,"live",Map.of("released",debug.handles().release(Dispatcher.required(args,"handle")))):debug.inspect(Dispatcher.required(args,"handle"),Dispatcher.bounded(args,"depth",2,4),positive(args,"breadth",20,20),args.path("cursor").asText(null));
            case "eval"->{
                int tier=args.path("tier").asInt(1);if(tier!=1&&tier!=2)throw RpcException.invalid("Evaluation tier must be 1 or 2");
                yield tier==2?run.evaluation().evaluate(Dispatcher.required(args,"expression"),args.path("frame").asText(null)):debug.eval(Dispatcher.required(args,"expression"),args.path("frame").asText(null));
            }
            case "histogram"->args.has("jfr")?profile(run,args.path("jfr")):debug.memory().histogram(positive(args,"limit",50,100),args.path("cursor").asText(null));
            case "instances"->debug.memory().instances(Dispatcher.required(args,"ref"),maximum(args),positive(args,"limit",50,100),args.path("cursor").asText(null));
            case "referrers"->debug.memory().referrers(Dispatcher.required(args,"handle"),maximum(args),positive(args,"limit",50,100),args.path("cursor").asText(null));
            case "hotswap"->{
                run.evaluation().clear();
                var changed=new ArrayList<Path>();for(var path:args.path("paths"))changed.add(source(run,path.asText()));if(changed.isEmpty()&&args.has("path"))changed.add(source(run,args.get("path").asText()));
                if(changed.isEmpty())throw RpcException.invalid("Hot swap requires changed source paths");
                var groups=new LinkedHashMap<Target,List<Path>>();
                for(Path file:changed){var target=run.request().targets().stream().filter(t->t.sources().stream().anyMatch(file::startsWith)).max(Comparator.comparingInt(t->t.directory().getNameCount())).orElseThrow(()->RpcException.invalid("No compilation target for "+file));groups.computeIfAbsent(target,_->new ArrayList<>()).add(file);}
                var results=new ArrayList<Envelope>();for(var entry:groups.entrySet()){var target=entry.getKey();results.add(new HotSwap(debug).apply(run.request().compilerHome(),entry.getValue(),target.sources(),target.options(),target.output(),target.classpath()));}
                if(results.size()==1)yield results.getFirst();
                yield Envelope.of(2,"live",Map.of("modules",results.stream().map(Envelope::result).toList(),"restart_required",results.stream().anyMatch(r->Boolean.TRUE.equals(((Map<?,?>)r.result()).get("restart_required")))));
            }
            case "restart"->{debug.close();runs.remove(id);yield start(run.request());}
            case "stop"->{debug.close();runs.remove(id);yield Envelope.of(2,"live",Map.of("stopped",id));}
            default->throw RpcException.invalid("Unknown debug operation: "+op);
        };
    }
    private Envelope profile(Run run,JsonNode args)throws Exception{
        var recorder=new FlightRecorder(run.request().compilerHome(),run.debug().pid());String name=args.path("name").asText("jvmd"),action=Dispatcher.required(args,"action");
        return switch(action){
            case "start"->Envelope.of(2,"live",recorder.start(name,Duration.ofSeconds(positive(args,"duration_seconds",60,3600))));
            case "dump"->{Path file=run.request().launch().directory().resolve(args.path("path").asText("target/"+name+".jfr")).normalize();if(!file.startsWith(run.request().launch().directory()))throw RpcException.invalid("Recording path must be inside the workspace");yield Envelope.of(2,"live",recorder.dump(name,file));}
            default->throw RpcException.invalid("JFR action must be start or dump");
        };
    }
    private static Long thread(JsonNode args){return args.hasNonNull("thread")?args.get("thread").longValue():null;}
    private static int maximum(JsonNode args){if(!args.has("max")||!args.get("max").isIntegralNumber())throw RpcException.invalid("Explicit max is required");return positive(args,"max",0,1000);}
    private static int positive(JsonNode args,String key,int fallback,int maximum){int value=Dispatcher.bounded(args,key,fallback,maximum);if(value<1)throw RpcException.invalid(key+" must be positive");return value;}
    private static int offset(JsonNode args){try{int offset=Integer.parseInt(args.path("cursor").asText("0"));if(offset<0)throw new NumberFormatException();return offset;}catch(NumberFormatException e){throw RpcException.invalid("Invalid cursor");}}
    private static Path source(Run run,String value){
        Path file=run.request().launch().directory().resolve(value).toAbsolutePath().normalize();
        if(!file.toString().endsWith(".java")||run.request().sourceRoots().stream().noneMatch(file::startsWith))throw RpcException.invalid("Path is outside the run's source roots");return file;
    }
    public List<Map<String,Object>> status(){return runs.values().stream().map(run->run.debug().status()).toList();}
    @Override public void close()throws Exception{Exception failure=null;for(var run:runs.values())try{run.debug().close();}catch(Exception e){failure=e;}runs.clear();if(failure!=null)throw failure;}
}
