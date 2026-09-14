package dev.jvmd.runtime;

import dev.jvmd.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Implements 4.7: explicit jcmd allocation-recording start and dump wrappers. */
public final class FlightRecorder {
    private final Path javaHome;
    private final long pid;
    public FlightRecorder(Path javaHome,long pid){this.javaHome=javaHome;this.pid=pid;}
    public Map<String,Object> start(String name,Duration duration)throws Exception{
        if(name==null||!name.matches("[A-Za-z0-9_-]{1,64}")||duration.isNegative()||duration.isZero()||duration.compareTo(Duration.ofHours(1))>0)throw RpcException.invalid("Recording needs a name and duration up to one hour");
        return command(List.of("JFR.start","name="+name,"settings=profile","duration="+duration.toSeconds()+"s"));
    }
    public Map<String,Object> dump(String name,Path destination)throws Exception{
        if(name==null||!name.matches("[A-Za-z0-9_-]{1,64}"))throw RpcException.invalid("Invalid recording name");
        destination=destination.toAbsolutePath().normalize();Files.createDirectories(destination.getParent());
        Path temporary=Files.createTempDirectory("jvmd-recording-"),recording=temporary.resolve("capture.jfr");
        try{
            // jcmd joins argv and JFR parses quotes and %p/%t itself. Keep user paths out of
            // that second parser, then publish the completed recording to the exact requested path.
            String path=recording.toString().replace("%","%%"),quote=path.contains("\"")?"'":"\"";
            if(path.contains(quote)||path.contains("\n")||path.contains("\r"))throw RpcException.invalid("Temporary recording directory cannot be represented by jcmd");
            var result=new LinkedHashMap<>(command(List.of("JFR.dump","name="+name,"filename="+quote+path+quote)));
            if(!Files.isRegularFile(recording)||Files.size(recording)==0)throw new RpcException(-32003,"unsupported_capability",Map.of("capability","jfr","reason","The JVM did not produce a recording","output",result));
            Files.move(recording,destination,StandardCopyOption.REPLACE_EXISTING);result.put("file",destination.toString());return result;
        }finally{Files.deleteIfExists(recording);Files.deleteIfExists(temporary);}
    }
    private Map<String,Object> command(List<String> arguments)throws Exception{
        var command=new ArrayList<String>(List.of(javaHome.resolve("bin/jcmd").toString(),Long.toString(pid)));command.addAll(arguments);
        var child=new ProcessBuilder(command).redirectErrorStream(true).start();child.getOutputStream().close();var output=new StringBuilder();
        var reader=Thread.ofVirtual().start(()->{try(var input=new java.io.InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8)){char[] buffer=new char[4096];int count;while((count=input.read(buffer))>=0)synchronized(output){output.append(buffer,0,count);if(output.length()>32768)output.delete(0,output.length()-32768);}}catch(java.io.IOException ignored){}});
        try{
            if(!child.waitFor(15,TimeUnit.SECONDS)){child.destroyForcibly();throw RpcException.invalid("jcmd timed out");}reader.join(1000);
            if(child.exitValue()!=0||output.toString().contains("Could not")||output.toString().contains("not found"))throw new RpcException(-32003,"unsupported_capability",Map.of("capability","jfr","output",output.toString()));
            return Map.of("pid",pid,"output",output.toString());
        }finally{if(child.isAlive())child.destroyForcibly();}
    }
}
