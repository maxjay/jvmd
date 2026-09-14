package dev.jvmd.tests;

import dev.jvmd.core.*;
import java.nio.file.*;
import java.time.Duration;

/** Implements phase 5: pinned real processors from the test repository, outside daemon libraries. */
final class AnnotationFixtures {
    static final String LOMBOK="1.18.48",MAPSTRUCT="1.6.3";
    static Config config(Path root){
        var plain=TestSupport.config(root,Duration.ofHours(4));
        return new Config(plain.jdkHome(),null,Path.of(System.getProperty("maven.repo.local",System.getProperty("user.home")+"/.m2/repository")),3,plain.idleTimeout(),plain.heapCeilingMb(),false,plain.stateDir(),plain.socket());
    }
    static String dependency(String group,String artifact,String version){return "<dependency><groupId>"+group+"</groupId><artifactId>"+artifact+"</artifactId><version>"+version+"</version></dependency>";}
    static String processor(String group,String artifact,String version){
        return "<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version><configuration><release>25</release><annotationProcessorPaths><path><groupId>"+group+"</groupId><artifactId>"+artifact+"</artifactId><version>"+version+"</version></path></annotationProcessorPaths></configuration></plugin></plugins></build>";
    }
    static Path source(Path root,String name,String text)throws Exception{Path file=Files.createDirectories(root.resolve("src/main/java/p")).resolve(name+".java");Files.writeString(file,"package p; "+text);return file;}
}
