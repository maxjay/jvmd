package dev.jvmd.tests;

import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 7: signature closure pages preserve depth and terminate on graph cycles. */
@Tag("phase-7")
class SignatureClosureTest {
    @TempDir Path root;
    @Test void followsSignaturesAndResumesWithoutDuplicates()throws Exception{
        String source=IndexFixtures.generic().replace("public static class Nested","public Sample<T> same(Sample<T> other) { return this; }\n  public static class Nested");
        Path jar=IndexFixtures.jar(root.resolve("repository"),"sample",source,true);
        try(var index=TestMachine.index(root.resolve("index.db"),root.resolve("repository"))){
            index.indexJar(jar,"fixture:sample:1","jar");index.indexSources(jar.resolveSibling("sample-sources.jar"));
            var docs=new Documentation(index);
            for(String name:List.of("transform","same")){
                var symbol=index.find(name,null,false,10,0).stream().filter(s->s.get("scip").toString().contains("fixture/Sample#")).findFirst().orElseThrow();var identities=new LinkedHashSet<String>();int cursor=0,pages=0;
                while(true){
                    var page=docs.describe(symbol,null,"summary",3,2,cursor);var closure=dev.jvmd.core.Json.MAPPER.valueToTree(page.result()).path("closure");
                    assertThat(closure.size()).isLessThanOrEqualTo(2);
                    for(var member:closure){assertThat(identities.add(member.path("scip").asText())).isTrue();assertThat(member.path("signature").asText()).isNotBlank();}
                    if(!page.truncated())break;cursor=Integer.parseInt(page.cursor());assertThat(++pages).isLessThan(30);
                }
                // A signature that refers to its own owner must not duplicate the root or prevent pagination.
                assertThat(identities).doesNotContain(symbol.get("scip").toString());
                if(name.equals("same"))assertThat(identities).anyMatch(s->s.endsWith("fixture/Sample#"));
                assertThat(dev.jvmd.core.Json.MAPPER.valueToTree(docs.describe(symbol,null,"full",0,20,0).result()).path("closure").isEmpty()).isTrue();
            }
        }
    }
}
