package dev.jvmd.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Implements 4.2 and 12.3: authoritative workspace verification in a separate process. */
public final class Verifier {
    /** Implements 4.2: build diagnostics retain javac codes and verified provenance. */
    public record Problem(String source,int tier,String code,String kind,String file,long line,long character,String message) { }
    /** Implements 12.3: a completed build, structured diagnostics and a bounded output tail. */
    public record Result(int exitCode,boolean timedOut,List<Problem> diagnostics,String output,List<String> command,List<String> warnings,long elapsedMillis) { }
    private static final Pattern RAW=Pattern.compile("^(.*\\.java):(\\d+):(\\d+):\\s*(compiler\\.(?:err|warn|note)\\.[^: ]+)(?::\\s*(.*))?$");
    private static final int TAIL_LIMIT=64*1024;
    private final Config config;
    public Verifier(Config config){this.config=config;}
    public Result verify(Path root,JsonNode manifest,Duration timeout)throws Exception{
        Files.createDirectories(config.stateDir());Path directory=Files.createTempDirectory(config.stateDir(),"verify-");
        long started=System.nanoTime();var command=command(root,manifest);var warnings=new ArrayList<String>();
        Path wrapper=directory.resolve("javac"),captures=Files.createDirectories(directory.resolve("diagnostics"));
        Files.writeString(wrapper,"""
                #!/bin/sh
                capture=$(mktemp "$JVMD_VERIFY_CAPTURE/javac.XXXXXXXX")
                printf '%s\\n' "$PWD" > "$capture.cwd"
                "$JVMD_VERIFY_JAVAC" -XDrawDiagnostics "$@" > "$capture" 2>&1
                status=$?
                cat "$capture"
                exit "$status"
                """);
        Files.setPosixFilePermissions(wrapper,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        String executable=Path.of(command.getFirst()).getFileName().toString();
        boolean maven=Set.of("mvn","mvnw","mvnd").contains(executable)
                || (Set.of("sh","bash").contains(executable)&&command.size()>1&&Path.of(command.get(1)).getFileName().toString().equals("mvnw"));
        if(maven){command.add("-Dmaven.compiler.fork=true");command.add("-Dmaven.compiler.executable="+wrapper);command.add("-Dstyle.color=never");}
        Process running=null;
        try{
            var builder=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
            builder.environment().put("JVMD_VERIFY_JAVAC",config.jdkHome().resolve("bin/javac").toString());builder.environment().put("JVMD_VERIFY_CAPTURE",captures.toString());
            builder.environment().put("JAVA_HOME",config.jdkHome().toString());
            Process process=builder.start();running=process;var tail=new ArrayDeque<String>();int[] length={0};
            var reader=Thread.ofVirtual().name("jvmd-verify-output").start(()->{
                try(var stream=process.inputReader(StandardCharsets.UTF_8)){for(String line;(line=stream.readLine())!=null;){if(line.length()>TAIL_LIMIT)line=line.substring(line.length()-TAIL_LIMIT);synchronized(tail){tail.addLast(line);length[0]+=line.length()+1;while(length[0]>TAIL_LIMIT&&!tail.isEmpty())length[0]-=tail.removeFirst().length()+1;}}}
                catch(java.io.IOException ignored) { }
            });
            boolean complete=process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS);
            if(!complete){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
            reader.join(5000);String output; synchronized(tail){output=String.join("\n",tail);}
            var diagnostics=new LinkedHashSet<Problem>();
            try(var files=Files.list(captures)){for(Path capture:files.filter(f->!f.toString().endsWith(".cwd")).sorted().toList()){
                Path cwd=Path.of(Files.readString(Path.of(capture+".cwd")).strip());
                try(var lines=Files.lines(capture)){lines.forEach(line->{var matcher=RAW.matcher(line.strip());if(matcher.matches()){
                    String filename=matcher.group(1),code=matcher.group(4);Path file=resolveSource(root,cwd,filename);
                    diagnostics.add(new Problem("verified",2,code,code.startsWith("compiler.err.")?"ERROR":code.startsWith("compiler.warn.")?"WARNING":"NOTE",file.toUri().toString(),Long.parseLong(matcher.group(2)),Long.parseLong(matcher.group(3))-1,Objects.toString(matcher.group(5),"")));
                }});}
            }}
            int exit=complete?process.exitValue():-1;
            if(!complete)warnings.add("verify_timeout: "+timeout.toSeconds()+" seconds");
            if(exit!=0&&diagnostics.isEmpty())warnings.add("build_failed_without_structured_diagnostics");
            return new Result(exit,!complete,List.copyOf(diagnostics),output,List.copyOf(command),List.copyOf(warnings),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        }finally{
            if(running!=null&&running.isAlive()){running.descendants().forEach(ProcessHandle::destroyForcibly);running.destroyForcibly();}
            try(var files=Files.walk(directory)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
        }
    }
    private static Path resolveSource(Path root,Path cwd,String filename){
        Path file=cwd.resolve(filename).normalize();if(Files.isRegularFile(file)||Path.of(filename).isAbsolute())return file;
        try(var files=Files.walk(root)){var candidates=files.filter(Files::isRegularFile).filter(f->f.getFileName().toString().equals(filename)).limit(2).toList();if(candidates.size()==1)return candidates.getFirst();}
        catch(java.io.IOException ignored) { }return file;
    }
    private static ArrayList<String> command(Path root,JsonNode manifest){
        var setting=manifest==null?null:manifest.get("verify_command");var command=new ArrayList<String>();
        if(setting!=null&&setting.isArray())setting.forEach(value->command.add(value.asText()));
        else if(setting!=null&&setting.isTextual())command.addAll(split(setting.asText()));
        else {
            Path wrapper=root.resolve("mvnw").toAbsolutePath().normalize();
            if(Files.isRegularFile(wrapper)){
                if(!Files.isExecutable(wrapper))command.add("sh");
                command.add(wrapper.toString());
            }else command.add(onPath("mvnd")?"mvnd":"mvn");
            command.addAll(List.of("-q","test-compile"));
        }
        if(command.isEmpty())throw RpcException.invalid("verify_command is empty");return command;
    }
    private static boolean onPath(String command){for(String entry:Objects.toString(System.getenv("PATH"),"").split(java.io.File.pathSeparator))if(Files.isExecutable(Path.of(entry,command)))return true;return false;}
    /** Implements 4.1: command words support quoting; no implicit shell expansion. */
    public static List<String> split(String value){
        var result=new ArrayList<String>();var word=new StringBuilder();char quote=0;boolean escape=false,started=false;
        for(char c:value.toCharArray()){
            if(escape){word.append(c);escape=false;started=true;continue;}
            if(c=='\\'&&quote!='\''){escape=true;started=true;continue;}
            if(quote!=0){if(c==quote)quote=0;else word.append(c);started=true;continue;}
            if(c=='\''||c=='"'){quote=c;started=true;continue;}
            if(Character.isWhitespace(c)){if(started){result.add(word.toString());word.setLength(0);started=false;}}else{word.append(c);started=true;}
        }if(escape||quote!=0)throw RpcException.invalid("Unterminated quote in verify_command");if(started)result.add(word.toString());return List.copyOf(result);
    }
}
