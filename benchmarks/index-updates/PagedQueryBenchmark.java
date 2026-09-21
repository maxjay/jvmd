package dev.jvmd.bench;

import dev.jvmd.core.*;
import dev.jvmd.index.IndexService;
import java.nio.file.*;
import java.util.*;

/** Read-only queries over an already seeded state; compare the complete returned pages. */
public final class PagedQueryBenchmark {
    public static void main(String[] args)throws Exception{
        Path repository=Path.of(args[0]),state=Path.of(args[1]),output=Path.of(args[2]);int repetitions=Integer.parseInt(args[3]);
        var report=new LinkedHashMap<String,Object>();var queries=new ArrayList<Map<String,Object>>();
        try(var index=new IndexService(state.resolve("index.db"),repository)){
            index.scan();List<Path> jars;try(var files=Files.walk(repository)){jars=files.filter(p->p.toString().endsWith(".jar")).sorted().toList();}
            if(jars.size()!=1)throw new IllegalArgumentException("This page-position fixture requires one JAR");
            index.loadWorkspace("pages",jars.stream().map(p->new IndexService.WorkspaceArtifact(p.toString(),"compile")).toList(),List.of());
            for(String query:List.of("Type","")){
                long after=0;
                for(int page=0;page<3;page++){
                    if(page==2)after=(after&0xffffffff00000000L)|(((Number)index.status().get("symbols")).longValue()/2);
                    long before=reads(index);
                    var first=index.find(query,"pages",true,20,after,Set.of());
                    if(first.size()!=20)throw new IllegalStateException("Expected a complete page");
                    String hash=Hashing.sha256(Json.MAPPER.writeValueAsBytes(first));var times=new ArrayList<Double>();
                    for(int i=0;i<repetitions;i++){
                        long start=System.nanoTime();var rows=index.find(query,"pages",true,20,after,Set.of());times.add((System.nanoTime()-start)/1e6);
                        if(!rows.equals(first))throw new IllegalStateException("Page contents changed");
                    }
                    var sorted=new ArrayList<>(times);Collections.sort(sorted);
                    var result=new LinkedHashMap<String,Object>();
                    result.putAll(Map.of("query",query,"page",page,"after",after,"rows_sha256",hash,"results",first.size(),"samples_ms",times,"p50_ms",sorted.get(sorted.size()/2)));
                    long read=reads(index);result.put("selected_symbol_reads",before<0||read<0?null:read-before);queries.add(result);
                    after=((Number)first.getLast().get("id")).longValue();
                }
            }
            report.put("status",index.status());
            if(((Number)index.status().get("faults")).longValue()!=0||((Number)index.status().get("indexed")).longValue()!=0)
                throw new IllegalStateException("Expected a clean unchanged reopen");
        }
        report.put("queries",queries);Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
    }
    private static long reads(IndexService index)throws Exception{
        var status=index.status();var storage=(Map<?,?>)status.getOrDefault("storage",status.get("generation_sink"));var repository=(Map<?,?>)storage.get("repository");
        return repository.get("query_symbol_reads") instanceof Number n?n.longValue():-1;
    }
}
