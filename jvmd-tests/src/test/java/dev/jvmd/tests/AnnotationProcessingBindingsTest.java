package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import dev.jvmd.analyzer.SourceText;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 5 exit: MapStruct and Lombok consumers resolve generated APIs at tier two. */
@Tag("phase-5")
class AnnotationProcessingBindingsTest {
    @TempDir Path temp;
    @Test void mapStructGenerationIsCachedAndHasNoPhantomDiagnostics()throws Exception{
        Path root=temp.resolve("mapstruct");
        MavenFixtures.project(root,"<dependencies>"+AnnotationFixtures.dependency("org.mapstruct","mapstruct",AnnotationFixtures.MAPSTRUCT)+"</dependencies>"+AnnotationFixtures.processor("org.mapstruct","mapstruct-processor",AnnotationFixtures.MAPSTRUCT));
        AnnotationFixtures.source(root,"Source","public record Source(String name) {}");AnnotationFixtures.source(root,"Target","public record Target(String name) {}");
        AnnotationFixtures.source(root,"PersonMapper","@org.mapstruct.Mapper public interface PersonMapper { Target map(Source source); }");
        Path use=AnnotationFixtures.source(root,"Use","class Use { Target read(){return new PersonMapperImpl().map(new Source(\"Ada\"));} }");
        var config=AnnotationFixtures.config(root);
        try(var app=new Application(config)){
            String session=TestSupport.open(app,root);assertClean(app,session,List.of(use));
            assertClean(app,session,List.of(use));
            var state=TestSupport.request(app.dispatcher(),"session.status",Map.of("session",session)).path("result").path("result").path("annotation_processing");
            assertThat(state.path("runs").asLong()).isEqualTo(1);assertThat(state.path("cache_hits").asLong()).isGreaterThan(0);
            try(var paths=Files.walk(config.stateDir().resolve("apt"))){assertThat(paths.anyMatch(p->p.getFileName().toString().equals("PersonMapperImpl.java"))).isTrue();}
            var verified=new Verifier(config).verify(root,Json.MAPPER.createObjectNode(),Duration.ofMinutes(2));assertThat(verified.exitCode()).withFailMessage(verified.output()).isZero();
            assertClean(app,session,List.of(use));
        }
    }
    @Test void lombokGetterBindsAndSourceChangesRegenerateItsBinaryApi()throws Exception{
        Path root=temp.resolve("lombok");
        MavenFixtures.project(root,"<dependencies>"+AnnotationFixtures.dependency("org.projectlombok","lombok",AnnotationFixtures.LOMBOK)+"</dependencies>"+AnnotationFixtures.processor("org.projectlombok","lombok",AnnotationFixtures.LOMBOK));
        Path provider=AnnotationFixtures.source(root,"Provider","public class Provider { @lombok.Getter private String name=\"Ada\"; }");
        Path use=AnnotationFixtures.source(root,"Use","class Use { String read(){return new Provider().getName();} }");
        var config=AnnotationFixtures.config(root);
        try(var app=new Application(config)){
            String session=TestSupport.open(app,root);assertClean(app,session,List.of(use,provider));
            String content=Files.readString(use);var position=new SourceText(content).position(content.indexOf("getName"));
            var answer=TestSupport.request(app.dispatcher(),"symbol.atPosition",Map.of("session",session,"path",use.toString(),"line",position.line(),"character",position.character())).path("result");
            assertThat(answer.path("tier").asInt()).isEqualTo(2);assertThat(answer.path("result").path("scip").asText()).endsWith("Provider#getName().");assertThat(answer.path("warnings").toString()).contains("lombok_reduced_fidelity");
            var verified=new Verifier(config).verify(root,Json.MAPPER.createObjectNode(),Duration.ofMinutes(2));assertThat(verified.exitCode()).withFailMessage(verified.output()).isZero();
            var time=Files.getLastModifiedTime(provider);Files.writeString(provider,Files.readString(provider).replace("String name=\"Ada\"","int name=123456789"));Files.setLastModifiedTime(provider,time);
            var broken=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"paths",List.of(use.toString()))).path("result");
            assertThat(broken.path("result").path("diagnostics").toString()).contains("compiler.err.");
        }
    }
    private static void assertClean(Application app,String session,List<Path> paths){
        var response=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"paths",paths.stream().map(Path::toString).toList()));
        assertThat(response.has("error")).withFailMessage(response.toPrettyString()).isFalse();var envelope=response.path("result");
        assertThat(envelope.path("tier").asInt()).isEqualTo(2);assertThat(envelope.path("result").path("diagnostics").isEmpty()).withFailMessage(response.toPrettyString()).isTrue();
    }
}
