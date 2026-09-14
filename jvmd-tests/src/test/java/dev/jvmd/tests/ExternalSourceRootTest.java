package dev.jvmd.tests;

import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements 4.3 and 4.6: effective source roots determine compiler context and source identity. */
@Tag("phase-4") @Tag("phase-6")
class ExternalSourceRootTest {
    @TempDir Path root;
    @Test void buildHelperSourcesOutsideTheModuleUseItsClasspathAndCoordinates()throws Exception{
        Files.writeString(root.resolve("pom.xml"),"""
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging>
                  <modules><module>module</module></modules><properties><maven.compiler.release>25</maven.compiler.release></properties>
                </project>
                """);
        Path module=Files.createDirectories(root.resolve("module"));
        Files.writeString(module.resolve("pom.xml"),"""
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>fixture</groupId><artifactId>reactor</artifactId><version>1</version></parent><artifactId>module</artifactId>
                  <build><plugins><plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId><version>3.6.1</version>
                    <executions><execution><id>shared</id><phase>generate-sources</phase><goals><goal>add-source</goal></goals>
                      <configuration><sources><source>${project.basedir}/../shared</source></sources></configuration>
                    </execution></executions>
                  </plugin></plugins></build>
                </project>
                """);
        Path sources=Files.createDirectories(root.resolve("shared")),use=sources.resolve("Use.java");
        String source="class Use { Other value; }";Files.writeString(use,source);Files.writeString(sources.resolve("Other.java"),"class Other {}");
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);
            var response=TestSupport.complete(app.dispatcher(),"diag.get",Map.of("session",session,"paths",List.of(use.toString())));
            assertThat(response.has("error")).as(response.toString()).isFalse();
            assertThat(response.path("result").path("tier").asInt()).isEqualTo(2);
            assertThat(response.path("result").path("result").path("diagnostics").isEmpty()).as(response.toString()).isTrue();
            var at=TestSupport.complete(app.dispatcher(),"symbol.atPosition",Map.of("session",session,"path",use.toString(),"line",0,"character",source.indexOf("Other"))).path("result").path("result");
            assertThat(at.path("scip").asText()).isEqualTo("maven fixture/module 1 Other#");assertThat(at.path("file").asText()).isEqualTo(sources.resolve("Other.java").toString());
        }
    }
}
