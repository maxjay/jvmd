package dev.jvmd.tests;
import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 4 checkpoint: entered declarations, generic SCIP identity and tier-one overview. */
@Tag("phase-4")
class TierOneOverviewTest {
 @TempDir Path temp;
 @Test void exposesResolvedDeclarationsWithoutAnalyzingBodies()throws Exception{
  Path file=temp.resolve("Example.java");
  Files.writeString(file,"package fixture; class Example<T extends Number> { /** Echo an input. */ T echo(T input){return nonexistent();} int echo(String input){return 1;} }");
  try(var app=new Application(TestSupport.config(temp,Duration.ofHours(4)))){
   String session=TestSupport.open(app,temp);
   var response=TestSupport.request(app.dispatcher(),"symbol.overview",Map.of("session",session,"path",file.toString(),"depth",1));
   assertThat(response.path("result").path("tier").asInt()).isEqualTo(1);
   var result=response.path("result").path("result");assertThat(result.path("diagnostics").isEmpty()).isTrue();
   var methods=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();result.path("symbols").forEach(s->{if(s.path("name").asText().equals("echo"))methods.add(s);});
   assertThat(methods).hasSize(2);
   assertThat(methods.stream().map(s->s.path("scip").asText())).contains("maven local/workspace 0 fixture/Example#echo(java.lang.Number).","maven local/workspace 0 fixture/Example#echo(java.lang.String).");
   assertThat(methods.getFirst().path("signature").asText()).contains("T echo(T input)");
   assertThat(methods.getFirst().path("doc").asText()).contains("Echo an input.");
   int start=methods.getFirst().path("name_start").asInt(),end=methods.getFirst().path("name_end").asInt();assertThat(Files.readString(file).substring(start,end)).isEqualTo("echo");
  }
 }
 @Test void unresolvedSignaturesDoNotHideHealthyDeclarations()throws Exception{
  Path file=temp.resolve("Broken.java");Files.writeString(file,"class Broken { Missing missing; int good(){return 42;} }");
  try(var app=new Application(TestSupport.config(temp,Duration.ofHours(4)))){
   String session=TestSupport.open(app,temp);var response=TestSupport.request(app.dispatcher(),"symbol.overview",Map.of("session",session,"path",file.toString(),"depth",1));
   assertThat(response.path("result").path("tier").asInt()).isEqualTo(1);
   assertThat(response.path("result").path("warnings").isEmpty()).isTrue();
   assertThat(response.path("result").path("result").path("symbols").toString()).contains("Broken#good().");
  }
 }
}
