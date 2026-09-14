package dev.jvmd.tests;

import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 7: inheritance expands stored Markdown over override edges at query time. */
@Tag("phase-7")
class InheritDocTest {
    @TempDir Path root;
    @Test void inheritedCommentsRespectSummaryAndFullDetail()throws Exception{
        String text="""
            package fixture;
            public class Sample {
              public static class Base {
                /** Inherited summary. <p>Long explanation. */
                public String speak(){ return "base"; }
              }
              public static class Child extends Base {
                /** {@inheritDoc} */
                @Override public String speak(){ return "child"; }
              }
            }
            """;
        Path jar=IndexFixtures.jar(root,"sample",text,true);
        try(var index=new IndexService(root.resolve("index.db"),root)){
            index.indexJar(jar,"fixture:sample:1","jar");index.indexSources(root.resolve("sample-sources.jar"));index.linkEdges();
            var symbol=index.find("fixture.Sample/Child/speak()",null,false,10,0).getFirst();var docs=new Documentation(index,Path.of(System.getProperty("java.home")));
            String summary=dev.jvmd.core.Json.MAPPER.valueToTree(docs.describe(symbol,null,"summary",0,10,0).result()).path("doc").asText();
            String full=dev.jvmd.core.Json.MAPPER.valueToTree(docs.describe(symbol,null,"full",0,10,0).result()).path("doc").asText();
            assertThat(summary).contains("Inherited summary.").doesNotContain("Long explanation.","inheritDoc");assertThat(full).contains("Long explanation.").doesNotContain("inheritDoc");
        }
    }
}
