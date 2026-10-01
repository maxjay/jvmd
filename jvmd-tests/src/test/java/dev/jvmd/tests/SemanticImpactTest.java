package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Documents;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/** Architecture §109: speculative impact through hypothetical leaves and the reverse ProofDag. */
class SemanticImpactTest {
    @TempDir Path root;

    @SuppressWarnings("unchecked")
    @Test void impactReportsAffectedConsumersWithoutMutatingState()throws Exception{
        Path a=root.resolve("A.java"),b=root.resolve("B.java"),c=root.resolve("C.java");
        String aText="class A { int one(){return 1;} int two(){return 2;} }";
        String bText="class B { int f(A a){return a.one();} }";
        String cText="class C { int g(A a){return a.two();} }";
        Files.writeString(a,aText);Files.writeString(b,bText);Files.writeString(c,cText);
        var documents=new Documents();documents.open(a,aText,1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("fixture:impact:1","25",List.of(),List.of(root),"impact",Map.of()),null,256L*1024*1024);
            analyzer.documents(documents);
            for(Path file:List.of(a,b,c))analyzer.diagnostics(file,Files.readString(file));
            var before=analyzer.contribution(a);long queries=queries(analyzer);

            var api=analyzer.impact(a,aText.replace("int one(){return 1;}","String one(){return \"1\";}"));
            var result=(Map<String,Object>)api.result();
            assertThat(result.get("api_changed")).isEqualTo(true);
            assertThat((List<String>)result.get("affected_consumers")).anyMatch(value->value.startsWith(b+"#"))
                    .noneMatch(value->value.startsWith(c+"#"));
            assertThat((List<String>)result.get("files_requiring_reconsideration")).contains(b.toString()).doesNotContain(c.toString());
            assertThat(api.warnings()).anyMatch(warning->warning.startsWith("impact_speculative"));

            var body=analyzer.impact(a,aText.replace("return 1;","return 3;"));
            var bodyResult=(Map<String,Object>)body.result();
            assertThat(bodyResult.get("api_changed")).isEqualTo(false);
            assertThat((List<String>)bodyResult.get("affected_consumers")).isEmpty();

            // Nothing was admitted, published or invalidated by the speculative queries.
            assertThat(analyzer.contribution(a)).isEqualTo(before);
            long afterImpact=queries(analyzer);
            assertThat(afterImpact).isEqualTo(queries+2);
            analyzer.diagnostics(b,bText);analyzer.diagnostics(c,cText);
            assertThat(queries(analyzer)).as("dependants stay cached after speculative impact").isEqualTo(afterImpact);
        }
    }
    private static long queries(Analyzer analyzer){return ((Number)analyzer.status().get("queries")).longValue();}
}
