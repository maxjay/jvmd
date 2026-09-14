package dev.jvmd.tests;

import java.nio.file.*;

/** Implements phase 6: independent Maven roots with no installed local artifacts. */
final class OverlayFixtures {
    static Path project(Path root,String name,String version,String body)throws Exception{
        MavenFixtures.project(root,body);Files.writeString(root.resolve("pom.xml"),MavenFixtures.pom("fixture",name,version,body));return root;
    }
    static Path source(Path root,String name,String text)throws Exception{
        Path file=Files.createDirectories(root.resolve("src/main/java/p")).resolve(name+".java");Files.writeString(file,"package p;\n"+text);return file;
    }
}
