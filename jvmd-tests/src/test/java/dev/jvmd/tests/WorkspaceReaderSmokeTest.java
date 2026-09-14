package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.resolver.*;
import java.nio.file.*;
import java.util.*;
import java.time.Duration;
import dev.jvmd.resolver.WorkspaceSource.Artifact;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements blocking smoke 4: an unbuilt local module substitutes through public resolver and javac seams. */
@Tag("smoke-4")
class WorkspaceReaderSmokeTest {
    @TempDir Path root;
    @Test void unbuiltReaderReturnsNullAndSourcePathWinsOverThePublishedJar()throws Exception{
        var config=TestSupport.config(root,Duration.ofHours(4));Path repository=config.m2Repo();
        Path jar=MavenFixtures.artifact(repository,"library","1","");
        Path published=Files.createDirectories(root.resolve("published")),binary=Files.createDirectories(root.resolve("published-classes"));
        Path old=published.resolve("Library.java");Files.writeString(old,"package p; public class Library { public int oldApi(){return 1;} }");
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-g","-d",binary.toString(),old.toString())).isZero();
        try(var output=new java.util.jar.JarOutputStream(Files.newOutputStream(jar))){output.putNextEntry(new java.util.jar.JarEntry("p/Library.class"));output.write(Files.readAllBytes(binary.resolve("p/Library.class")));output.closeEntry();}
        Files.writeString(jar.resolveSibling(jar.getFileName()+".sha1"),java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(jar))));
        Path local=Files.createDirectories(root.resolve("local")),sources=Files.createDirectories(local.resolve("src/main/java/p"));
        Files.writeString(local.resolve("pom.xml"),MavenFixtures.pom("fixture","library","2",""));
        Path provider=sources.resolve("Library.java");Files.writeString(provider,"package p; public class Library { public String localApi(){return \"local\";} }");
        var reader=new WorkspaceSource(){
            @Override public java.io.File findArtifact(Artifact artifact){
                if(!artifact.groupId().equals("fixture")||!artifact.artifactId().equals("library"))return null;
                return artifact.extension().equals("pom")?local.resolve("pom.xml").toFile():null;
            }
            @Override public List<String> findVersions(Artifact artifact){return artifact.groupId().equals("fixture")&&artifact.artifactId().equals("library")?List.of("2"):List.of();}
        };
        assertThat(reader.findArtifact(new Artifact("fixture","library","jar","","1"))).isNull();
        Path app=MavenFixtures.project(root.resolve("app"),"<dependencies>"+MavenFixtures.dependency("library","1")+"</dependencies>");
        Path use=Files.createDirectories(app.resolve("src/main/java/p")).resolve("Use.java");String text="package p; class Use { String read(){return new Library().localApi();} }";Files.writeString(use,text);
        try(var resolver=new MavenResolver(config);var analyzer=new Analyzer()){
            var graph=resolver.resolve(app,reader);assertThat(graph.classpath()).contains(jar.toString());
            analyzer.configure(new Analyzer.Context("fixture:app:1","25",graph.classpaths().get("fixture:app:1:main").stream().map(Path::of).toList(),
                    List.of(use.getParent().getParent(),sources.getParent()),"smoke",Map.of(local.toString(),"fixture:library:2",local.toUri().toString(),"fixture:library:2")),null,256L*1024*1024);
            var outcome=analyzer.bindings(use,text,null);assertThat(outcome.tier()).isEqualTo(2);assertThat(outcome.diagnostics()).isEmpty();assertThat(outcome.warnings()).isEmpty();
            assertThat(outcome.result().symbols().values()).anyMatch(s->s.get("name").equals("localApi")&&provider.toString().equals(s.get("source_file"))&&"fixture:library:2".equals(s.get("gav")));
            System.out.println("smoke-4: unbuilt reader returned null; source API resolved to "+provider);
        }
    }
}
