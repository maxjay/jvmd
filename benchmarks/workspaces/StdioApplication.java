package dev.jvmd.benchmark;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;

/** Pipe-only benchmark transport: the production Application, Dispatcher and Framing are unchanged. */
public final class StdioApplication {
    public static void main(String[] args)throws Exception {
        try(var app=new Application(Config.load())){
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
            while(!app.dispatcher().shutdownRequested()){
                byte[] body=Framing.read(System.in);if(body==null)break;
                var response=app.dispatcher().dispatch(Json.MAPPER.readTree(body));
                Framing.write(System.out,Json.MAPPER.writeValueAsBytes(response));
            }
        }
    }
}
