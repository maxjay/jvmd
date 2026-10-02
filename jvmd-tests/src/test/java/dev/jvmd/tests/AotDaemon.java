package dev.jvmd.tests;

import dev.jvmd.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Implements 12.4: strict-AOT socket test client, with the production launch configuration. */
final class AotDaemon implements AutoCloseable {
    final Process process;
    final SocketChannel channel;
    final java.io.InputStream input;
    final java.io.OutputStream output;
    final double startupMillis;
    private long sequence;
    AotDaemon(Path temp)throws Exception{this(temp,Map.of());}
    AotDaemon(Path temp,Map<String,Object> overrides)throws Exception{this(temp,overrides,true);}
    /** A daemon whose state directory is left as it is: with no committed MACHINE root, it cold-boots. */
    static AotDaemon unprepared(Path temp,Map<String,Object> overrides)throws Exception{return new AotDaemon(temp,overrides,false);}
    private AotDaemon(Path temp,Map<String,Object> overrides,boolean prepared)throws Exception{
        Path image=TestSupport.repo().resolve("jvmd-dist/target/image"),socket=temp.resolve("semantic.sock"),log=temp.resolve("daemon.log"),config=temp.resolve("config.json"),aot=temp.resolve("aot.log");
        var settings=new LinkedHashMap<String,Object>(overrides);Json.MAPPER.writeValue(config.toFile(),settings);
        // The daemon boots from a prepared MACHINE: a MACHINE cold boot of the machine's repository and
        // JDK is covered by its own tests, not repeated by every daemon process.
        if(prepared)TestMachine.prepareDaemon(temp.resolve("state"));
        long start=System.nanoTime();
        process=new ProcessBuilder(image.resolve("bin/java").toString(),"-XX:AOTCache="+image.resolve("lib/jvmd/jvmd.aot"),"-XX:AOTMode=on","-Xlog:aot=info:file="+aot,"-Djvmd.aot.log="+aot,"-Djvmd.config="+config,"-Djvmd.socket="+socket,"-Djvmd.state="+temp.resolve("state"),"-Djvmd.index.scan.initial_delay_seconds="+System.getProperty("jvmd.index.scan.initial_delay_seconds","2"),"-cp",image.resolve("lib/jvmd/*").toString(),"dev.jvmd.dist.Application").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        while(process.isAlive()&&System.nanoTime()-start<TimeUnit.SECONDS.toNanos(10)){if(Files.exists(log)&&Files.readString(log).contains("READY "))break;Thread.sleep(2);}
        if(!process.isAlive()||!Files.readString(log).contains("READY ")){process.destroyForcibly();throw new AssertionError(Files.readString(log));}
        channel=SocketChannel.open(StandardProtocolFamily.UNIX);channel.connect(UnixDomainSocketAddress.of(socket));input=Channels.newInputStream(channel);output=Channels.newOutputStream(channel);
        var status=request("daemon.status",Map.of());if(!status.path("result").path("aot_cache").asText().equals("used"))throw new AssertionError(status.toPrettyString());
        startupMillis=(System.nanoTime()-start)/1e6;
    }
    JsonNode request(String method,Map<String,?> params)throws Exception{
        var request=Json.MAPPER.createObjectNode().put("jsonrpc","2.0").put("id",++sequence).put("method",method);request.set("params",Json.MAPPER.valueToTree(params));Framing.write(output,Json.MAPPER.writeValueAsBytes(request));var response=Json.MAPPER.readTree(Framing.read(input));if(response.has("error"))throw new AssertionError(response.toPrettyString());return response.path("result");
    }
    /** Stop the process without shutting it down, as a crash would. */
    void kill()throws Exception{try{channel.close();}finally{process.destroyForcibly().waitFor(10,TimeUnit.SECONDS);}}
    @Override public void close()throws Exception{try{channel.close();}finally{process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();}}
}
