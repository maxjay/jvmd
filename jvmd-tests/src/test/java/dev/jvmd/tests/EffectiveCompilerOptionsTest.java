package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import dev.jvmd.resolver.MavenResolver;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 4 agreement: Maven compiler configuration controls the live compiler. */
@Tag("phase-4")
class EffectiveCompilerOptionsTest {
    @TempDir Path root;
    @Test void runtimeDependenciesStayOffTheCompileClasspath()throws Exception{
        var config=TestSupport.config(root,Duration.ofHours(4));Path jar=MavenFixtures.artifact(config.m2Repo(),"runtime","1","");
        MavenFixtures.project(root,"<dependencies>"+MavenFixtures.dependency("runtime","1").replace("</dependency>","<scope>runtime</scope></dependency>")+"</dependencies>");
        try(var resolver=new MavenResolver(config)){var graph=resolver.resolve(root);assertThat(graph.classpaths().get("fixture:app:1:main")).doesNotContain(jar.toString());assertThat(graph.classpaths().get("fixture:app:1:test")).contains(jar.toString());}
    }
    @Test void blankReleaseUsesSourceTargetAndExplicitCompilerExports()throws Exception{
        Files.writeString(root.resolve("pom.xml"),"""
                <project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>options</artifactId><version>1</version>
                <properties><maven.compiler.release></maven.compiler.release><maven.compiler.source>25</maven.compiler.source><maven.compiler.target>25</maven.compiler.target></properties>
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version>
                <configuration><compilerArgs><arg>--add-exports</arg><arg>jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED</arg></compilerArgs></configuration></plugin></plugins></build></project>
                """);
        Path source=Files.createDirectories(root.resolve("src/main/java")).resolve("Uses.java");
        Files.writeString(source,"import com.sun.tools.javac.code.Symbol; class Uses { Symbol symbol; }");
        var config=TestSupport.config(root,Duration.ofHours(4));
        try(var resolver=new MavenResolver(config)){
            var module=resolver.resolve(root).modules().getFirst();
            assertThat(module.compilerOptions()).containsSequence("-source","25","-target","25").contains("--add-exports","jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED").doesNotContain("--release");
        }
        try(var app=new Application(config)){
            String session=TestSupport.open(app,root);var live=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session)).path("result");
            assertThat(live.path("tier").asInt()).isEqualTo(2);assertThat(live.path("warnings").isEmpty()).isTrue();assertThat(live.path("result").path("diagnostics").isEmpty()).withFailMessage(live.toPrettyString()).isTrue();
        }
    }
}
