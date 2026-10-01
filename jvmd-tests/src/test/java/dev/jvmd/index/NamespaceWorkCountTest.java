package dev.jvmd.index;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.Processing;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * P2 (corrective pass), permanent work-count bounds for namespace work. Until B1 every memo capture
 * rebuilt the root's member set, and every record write recomputed its packages' S0 identities over
 * every member on the writer thread. Now: the root is enumerated once per live owner and updated from
 * the change journal; a package identity is built once per package per input snapshot; an S0 result is
 * parsed once per content and language mode.
 */
class NamespaceWorkCountTest {
    @TempDir Path root;
    private static final String GAV="g:app:1";
    private static final int UNITS=40;

    private Path sources()throws Exception{return Files.createDirectories(root.resolve("app/src/main/java"));}
    private static Map<?,?> memo(Analyzer analyzer){return (Map<?,?>)analyzer.status().get("attributed_memo");}
    private static long count(Analyzer analyzer,String key){return ((Number)memo(analyzer).get(key)).longValue();}
    private static long parses(Analyzer analyzer){return ((Number)((Map<?,?>)memo(analyzer).get("source_namespaces")).get("parses")).longValue();}

    @Test void namespaceWorkIsPerPackageAndContentNotPerMemo()throws Exception{
        var files=new ArrayList<Path>();
        for(int i=0;i<UNITS;i++){
            String body=i==0?"public class U0 { public int v(){ return 0; } }":"public class U"+i+" { public int v(){ return new U"+(i-1)+"().v() + 1; } }";
            Path file=sources().resolve("p/U"+i+".java");Files.createDirectories(file.getParent());Files.writeString(file,"package p;\nimport q.*;\n"+body+"\n");files.add(file);
        }
        Path q=sources().resolve("q/Q.java");Files.createDirectories(q.getParent());Files.writeString(q,"package q;\npublic class Q { }\n");files.add(q);
        var documents=new Documents(new FileStateRegistry());
        var analyzer=new Analyzer(new FileStateRegistry());
        analyzer.configure(new Analyzer.Context(GAV,"25",List.of(),List.of(sources()),"reactor:"+GAV+":main",
                Map.of(root.resolve("app").toString(),GAV,sources().toString(),GAV),List.of("--release","25"),
                Set.of(),List.of(),List.of(sources()),true,"",Processing.NONE),null,256L*1024*1024);
        analyzer.documents(documents);analyzer.memos(new SemanticMemoStore(root.resolve("memo")));
        try(analyzer){
            for(Path file:files)analyzer.diagnostics(file,Files.readString(file));
            analyzer.awaitMemoWrites();
            assertThat(count(analyzer,"writes")).isEqualTo(UNITS+1);
            assertThat(count(analyzer,"membership_enumerations")).as("one root, one enumeration for "+(UNITS+1)+" captures").isEqualTo(1);
            assertThat(count(analyzer,"package_identity_builds")).as("packages p and q, once each").isLessThanOrEqualTo(2);
            long cold=parses(analyzer);
            assertThat(cold).as("each file's S0 parsed once").isLessThanOrEqualTo(UNITS+1);

            // One body edit: a new input snapshot. Membership is updated from the journal, not enumerated.
            Path edited=files.get(5);String text=Files.readString(edited).replace("+ 1;","+ 2;");
            documents.open(edited,text,1);
            analyzer.diagnostics(edited,text);analyzer.awaitMemoWrites();
            assertThat(count(analyzer,"membership_enumerations")).as("no new enumeration after an edit").isEqualTo(1);
            assertThat(count(analyzer,"membership_updates")).as("the changed path only").isLessThanOrEqualTo(2);
            assertThat(count(analyzer,"package_identity_builds")).as("p and q again for the new snapshot, not per memo").isLessThanOrEqualTo(4);
            assertThat(parses(analyzer)-cold).as("only the edited content is parsed").isEqualTo(1);
        }
    }
}
