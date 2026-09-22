package dev.jvmd.benchmark;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;

/** Pipe-only benchmark transport: the production Application, Dispatcher and Framing are unchanged. */
public final class StdioApplication {
    private static Application start()throws Exception {
        if(!Boolean.getBoolean("jvmd.trace"))return new Application(Config.load());
        String workflow=System.getProperty("jvmd.benchmark.workflow","");
        // One startup lookup lets comparison mode compile against uninstrumented revisions.
        var traced=RequestScope.class.getMethod("traced",String.class,String.class,String.class,String.class,RequestScope.ThrowingSupplier.class);
        return (Application)traced.invoke(null,"server.start",workflow,workflow+":startup","fixture:initial",
                (RequestScope.ThrowingSupplier<Application>)()->new Application(Config.load()));
    }
    public static void main(String[] args) throws Exception {
        try (var app = start()) {
            while (!app.dispatcher().shutdownRequested()) {
                byte[] body = Framing.read(System.in);
                if (body == null) break;
                var response = app.dispatcher().dispatch(Json.MAPPER.readTree(body));
                Framing.write(System.out, Json.MAPPER.writeValueAsBytes(response));
            }
        }
    }
}
