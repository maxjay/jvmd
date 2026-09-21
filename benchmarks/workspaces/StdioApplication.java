package dev.jvmd.benchmark;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;

/** Pipe-only benchmark transport: the production Application, Dispatcher and Framing are unchanged. */
public final class StdioApplication {
    public static void main(String[] args)throws Exception {
        try(var app=new Application(Config.load())){
            while(!app.dispatcher().shutdownRequested()){
                byte[] body=Framing.read(System.in);if(body==null)break;
                var response=app.dispatcher().dispatch(Json.MAPPER.readTree(body));
                Framing.write(System.out,Json.MAPPER.writeValueAsBytes(response));
            }
        }
    }
}
