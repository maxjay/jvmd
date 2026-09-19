package dev.jvmd.benchmark;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;
import jdk.jfr.Recording;

/** Changing-source editor workload, isolated from transport to attribute compiler costs. */
public final class EditorProfile {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);Path file=root.resolve("Example.java");
        var classpath=Arrays.stream(args).skip(2).map(Path::of).toList();
        String template="import com.fasterxml.jackson.databind.ObjectMapper; class Example { int helper(int value){return value*2;} int other(){return helper(7);} int use(){int local=NUMBER;return helper(local);} Object binary(){return new ObjectMapper().getFactory();} }";
        Files.writeString(file,template.replace("NUMBER","1"));
        var samples=new ArrayList<Map<String,Object>>();
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("bench:editor:1","25",classpath,List.of(root),"editor",Map.of()),null,512L*1024*1024);
            var documents=new Documents();documents.open(file,template.replace("NUMBER","0"),1);analyzer.documents(documents);
            try(var recording=new Recording(jdk.jfr.Configuration.getConfiguration("profile"))){
                recording.start();
                for(int i=0;i<160;i++){
                    String source=template.replace("NUMBER",Integer.toString(i));
                    if(i>0)documents.change(file,i+1,List.of(new Documents.Change(null,source)));
                    analyzer.documents(documents);
                    var timings=new LinkedHashMap<String,Object>();timings.put("iteration",i);
                    for(String operation:List.of("completion","binary_completion","signature_help")){
                        int offset=switch(operation){case "completion"->source.lastIndexOf("helper(local)")+3;case "binary_completion"->source.indexOf("getFactory")+3;default->source.lastIndexOf("local);")+2;};
                        var position=Documents.position(source,offset);long start=System.nanoTime();
                        var response=operation.equals("signature_help")?analyzer.signatureHelp(file,source,position.line(),position.character()):analyzer.completion(file,source,position.line(),position.character(),200,0);
                        timings.put(operation,(System.nanoTime()-start)/1e6);
                        if(!response.warnings().isEmpty()||!response.result().toString().contains(operation.equals("binary_completion")?"getFactory":"helper"))throw new AssertionError(response);
                    }
                    samples.add(timings);
                }
                recording.stop();recording.dump(Path.of(args[1]));
            }
            System.out.println(Json.MAPPER.writeValueAsString(Map.of("samples",samples,"status",analyzer.status())));
        }
    }
}
