package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import dev.jvmd.index.IndexService;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 8: simple, qualified, nested and ambiguous erased overload references. */
@Tag("phase-8")
class NamePathResolutionTest {
    @TempDir Path root;
    @Test void sourceOverloadsUseErasureAndNeverPickTheFirstSimpleMatch()throws Exception{
        Path pkg=Files.createDirectories(root.resolve("fixture"));Files.writeString(pkg.resolve("Outer.java"),"""
                package fixture;
                class Outer { static class Inner {
                  void accept(java.util.Date[] input) {}
                  void accept(java.sql.Date[] input) {}
                  void accept(int[][] input) {}
                } }
                """);
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);
            var ambiguous=TestSupport.request(app.dispatcher(),"symbol.describe",Map.of("session",session,"ref","Outer/Inner/accept(Date[])")).path("result");
            assertThat(ambiguous.path("warnings").toString()).contains("ambiguous");assertThat(ambiguous.path("result").path("candidates").size()).isEqualTo(2);
            for(String ref:List.of("Outer/Inner/accept(java.util.Date[])","fixture.Outer/Inner/accept(java.util.Date[])")){
                var answer=TestSupport.request(app.dispatcher(),"symbol.describe",Map.of("session",session,"ref",ref));
                assertThat(answer.has("error")).isFalse();assertThat(answer.path("result").path("result").path("scip").asText()).contains("java.util.Date[]");
            }
            var array=TestSupport.request(app.dispatcher(),"symbol.find",Map.of("session",session,"name_path","Inner/accept(int[][])")).path("result");
            assertThat(array.path("tier").asInt()).isEqualTo(1);assertThat(array.path("result").path("matches").size()).isEqualTo(1);
            var bad=TestSupport.request(app.dispatcher(),"symbol.describe",Map.of("session",session,"ref","Outer/Inner/accept(List<String>)"));
            assertThat(bad.path("error").path("code").asInt()).isEqualTo(-32602);
        }
    }
    @Test void dependencyOverloadsUseTheSameGrammar()throws Exception{
        Path jar=IndexFixtures.jar(root.resolve("repository"),"sample","package fixture; public class Sample { public void accept(java.util.Date[] value){} public void accept(java.sql.Date[] value){} public void accept(int[][] value){} }",false);
        try(var index=new IndexService(root.resolve("index.db"),root.resolve("repository"))){
            index.indexJar(jar,"fixture:sample:1","jar");
            assertThat(index.find("Sample/accept(Date[])",null,false,10,0)).hasSize(2);
            assertThat(index.find("Sample/accept",null,false,10,0)).hasSize(3);
            assertThat(index.find("fixture.Sample/accept(java.sql.Date[])",null,false,10,0)).singleElement().satisfies(row->assertThat(row.get("erased_descriptor")).isEqualTo("([Ljava/sql/Date;)V"));
            assertThat(index.find("Sample/accept(int[][])",null,false,10,0)).hasSize(1);
        }
        for(String invalid:List.of("Foo//bar","Foo/bar(","Foo/bar(void)","Foo/bar(String,)","Foo/bar(String))","Foo/bar(java..lang.String)")){
            assertThatThrownBy(()->NamePath.parse(invalid)).as(invalid).isInstanceOf(RpcException.class);
        }
    }
}
