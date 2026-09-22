package dev.jvmd.benchmark;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;

/** Pipe-only benchmark transport: the production Application, Dispatcher and Framing are unchanged. */
public final class StdioApplication {
    private static Application start()throws Exception {
        if(!RequestScope.TRACING)return new Application(Config.load());
        String workflow=System.getProperty("jvmd.benchmark.workflow","");
        var cause=new RequestScope.Context(0,"server.start",new java.util.concurrent.ConcurrentHashMap<>(),workflow,workflow+":startup","fixture:initial",null,0);
        return RequestScope.with(cause,()->{try(var span=RequestScope.stage("server.start")){return new Application(Config.load());}});
    }
    public static void main(String[] args)throws Exception {
        try(var app=start()){
            var retained=new java.util.ArrayList<dev.jvmd.dist.WorkspaceBindings.Snapshot>();
            // Diagnostic-only leases on the actual workspace fact revisions. The cap and
            // explicit release keep this benchmark holder out of ordinary server lifetimes.
            if(Boolean.getBoolean("jvmd.benchmark.retention"))app.dispatcher().register("benchmark.retention",(session,params)->{
                String operation=params.path("operation").asText("snapshot");
                if(operation.equals("hold")){
                    if(retained.size()>=32)throw RpcException.invalid("Benchmark lease limit is 32");
                    var method=Application.class.getDeclaredMethod("workspaceBindings",Session.class,boolean.class);
                    method.setAccessible(true);
                    retained.add((dev.jvmd.dist.WorkspaceBindings.Snapshot)method.invoke(app,session,true));
                }else if(operation.equals("release")){
                    for(var view:retained)view.close();retained.clear();
                }else if(!operation.equals("snapshot"))throw RpcException.invalid("Unknown retention operation");
                System.gc(); // This executable enables the handler only in separate retention runs.
                var memory=java.lang.management.ManagementFactory.getMemoryMXBean();
                var result=new java.util.LinkedHashMap<String,Object>();
                result.put("held_views",retained.size());result.put("heap_used_bytes",memory.getHeapMemoryUsage().getUsed());
                result.put("heap_committed_bytes",memory.getHeapMemoryUsage().getCommitted());
                result.put("nonheap_used_bytes",memory.getNonHeapMemoryUsage().getUsed());
                result.put("gc",java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream().map(gc->java.util.Map.of("name",gc.getName(),"count",gc.getCollectionCount(),"milliseconds",gc.getCollectionTime())).toList());
                result.put("measurement","Heap after explicit GC request; not allocation or object retained-size analysis");
                result.put("workspace_bindings",session.state("workspace_bindings")==null?java.util.Map.of():((dev.jvmd.dist.WorkspaceBindings)session.state("workspace_bindings")).status());
                if(params.path("inspect_heap").asBoolean()){
                    var server=java.lang.management.ManagementFactory.getPlatformMBeanServer();
                    var command=new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand");
                    String histogram=(String)server.invoke(command,"gcClassHistogram",new Object[]{new String[]{}},new String[]{"[Ljava.lang.String;"});
                    var lines=histogram.lines().toList();var selected=new java.util.LinkedHashSet<String>(lines.subList(0,Math.min(33,lines.size())));
                    lines.stream().filter(line->line.contains("dev.jvmd.")||line.startsWith("Total")).forEach(selected::add);
                    result.put("live_object_histogram",java.util.List.copyOf(selected));
                    result.put("histogram_scope","Largest 30 classes plus JVMD classes; counts and shallow bytes after diagnostic GC, not dominator retained sizes");
                    result.put("vm_native_memory",server.invoke(command,"vmNativeMemory",new Object[]{new String[]{"summary","scale=KB"}},new String[]{"[Ljava.lang.String;"}));
                    result.put("native_scope","HotSpot NMT reserved/committed categories; third-party JNI allocations may be untracked; RSS remains separate");
                }
                return Envelope.of(2,"live",result);
            });
            // The production protocol has no output-tail operation. Read its existing
            // bounded DebugSession buffer only in this benchmark executable; no runner
            // or new shipped debug operation is introduced.
            app.dispatcher().register("benchmark.runOutput",(session,params)->{
                var manager=session.state("runs");
                if(manager==null)return Envelope.of(2,"live",java.util.Map.of("output",""));
                var field=manager.getClass().getDeclaredField("runs");field.setAccessible(true);
                var runs=(java.util.Map<?,?>)field.get(manager);
                var run=runs.get(Dispatcher.required(params,"run_session"));
                if(run==null)throw RpcException.invalid("Unknown benchmark run");
                var accessor=run.getClass().getDeclaredMethod("debug");accessor.setAccessible(true);
                var debug=(dev.jvmd.runtime.DebugSession)accessor.invoke(run);
                return Envelope.of(2,"live",java.util.Map.of("output",debug.output(),"pid",debug.pid(),"run_session",debug.id()));
            });
            try{while(!app.dispatcher().shutdownRequested()){
                byte[] body=Framing.read(System.in);if(body==null)break;
                var response=app.dispatcher().dispatch(Json.MAPPER.readTree(body));
                Framing.write(System.out,Json.MAPPER.writeValueAsBytes(response));
            }}finally{for(var view:retained)view.close();}
        }
    }
}
