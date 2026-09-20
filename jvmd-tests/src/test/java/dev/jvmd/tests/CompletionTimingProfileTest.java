package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Documents;
import dev.jvmd.core.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class CompletionTimingProfileTest {
    @TempDir Path temp;

    @Test void recordsMissHitAndBackspacePhaseBreakdownAtRepresentativeSourceCounts()throws Exception{
        var scenarios=new ArrayList<Map<String,Object>>();
        scenarios.add(runScenario(temp.resolve("sources-24"),24));
        scenarios.add(runScenario(temp.resolve("sources-128"),128));
        scenarios.add(runScenario(temp.resolve("sources-300"),300));
        var output=TestSupport.repo().resolve("jvmd-tests/target/completion-perf.json");
        Files.createDirectories(output.getParent());
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),Map.of("scenarios",scenarios));
        System.out.println("completion-perf "+Json.MAPPER.writeValueAsString(Map.of("scenarios",scenarios)));
    }

    private static Map<String,Object> runScenario(Path root,int sourceCount)throws Exception{
        Files.createDirectories(root);
        Files.writeString(root.resolve("Api.java"),"""
                class Api {
                    /** Returns the current pets visible to the caller. */
                    int getPets(){return 1;}
                    /** Returns the number of pets currently available. */
                    int getPetCount(){return 2;}
                    int other(){return 3;}
                }
                """);
        for(int i=2;i<sourceCount;i++)Files.writeString(root.resolve("Helper"+i+".java"),"class Helper"+i+" { int value(){return "+i+";} }\n");
        Path use=root.resolve("Use.java");Files.writeString(use,text("ge"));
        var documents=new Documents();documents.open(use,text("ge"),1);
        var samples=new ArrayList<Map<String,Object>>();
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("fixture:completion-timing:"+sourceCount,"25",List.of(),List.of(root),"completion-timing-"+sourceCount,Map.of()),null,256L*1024*1024);
            analyzer.documents(documents);int version=1;
            for(String prefix:List.of("ge","get","getP","g")){
                String source=text(prefix);
                if(version>1){documents.change(use,version,List.of(new Documents.Change(null,source)));analyzer.changed(use,documents.hash(use));analyzer.documents(documents);}
                var answer=analyzer.completion(use,source,0,source.indexOf("api."+prefix)+4+prefix.length(),100,0);
                assertThat(answer.warnings()).isEmpty();
                var status=analyzer.status();
                @SuppressWarnings("unchecked") var timing=new LinkedHashMap<String,Object>((Map<String,Object>)status.get("completion_last_timing_ms"));
                var sample=new LinkedHashMap<String,Object>();
                sample.put("prefix",prefix);
                sample.put("cache_hit",status.get("completion_last_cache_hit"));
                sample.put("completion_requests",status.get("completion_requests"));
                sample.put("completion_computations",status.get("completion_computations"));
                sample.put("completion_cache_hits",status.get("completion_cache_hits"));
                sample.put("candidates_seen",status.get("completion_candidates_seen"));
                sample.put("rows_materialized",status.get("completion_rows_materialized"));
                sample.put("doc_lookups",status.get("completion_doc_lookups"));
                sample.put("source_catalog_builds",status.get("source_catalog_builds"));
                sample.put("source_catalog_files",status.get("source_catalog_files"));
                sample.put("source_list_calls",status.get("source_list_calls"));
                sample.put("source_list_entries",status.get("source_list_entries"));
                sample.put("source_watch_events",status.get("source_watch_events"));
                sample.put("timing_ms",timing);
                samples.add(sample);
                version++;
            }
            assertThat(samples.get(0).get("cache_hit")).isEqualTo(false);
            assertThat(samples.get(1).get("cache_hit")).isEqualTo(true);
            assertThat(samples.get(2).get("cache_hit")).isEqualTo(true);
            assertThat(samples.get(3).get("cache_hit")).isEqualTo(false);
            assertThat(analyzer.status()).containsEntry("completion_computations",2L).containsEntry("completion_cache_hits",2L);
        }
        return Map.of("sources",sourceCount,"samples",samples);
    }

    private static String text(String prefix){return "class Use { Object call(Api api){return api."+prefix+"();} }";}
}
