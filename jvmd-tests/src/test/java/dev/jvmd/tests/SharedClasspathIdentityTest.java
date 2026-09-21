package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class SharedClasspathIdentityTest {
    @TempDir Path root;
    @Test void independentWorkspacesShareJarHashesAndStillDetectReplacement()throws Exception{
        Path jar=IndexFixtures.jar(root.resolve("repository"),"api","package lib; public class Sample { public int value(){return 1;} }",true);
        var shared=new FileStateRegistry();
        try(var first=new Analyzer(shared);var second=new Analyzer(shared)){
            Path one=Files.createDirectories(root.resolve("one")),two=Files.createDirectories(root.resolve("two"));
            String text="class Use { int call(){return new lib.Sample().value();} }";
            Path a=one.resolve("Use.java"),b=two.resolve("Use.java");Files.writeString(a,text);Files.writeString(b,text);
            first.configure(new Analyzer.Context("test:one:1","25",List.of(jar),List.of(one),"one",Map.of()),null,256L*1024*1024);
            second.configure(new Analyzer.Context("test:two:1","25",List.of(jar),List.of(two),"two",Map.of()),null,256L*1024*1024);
            assertThat(first.bindings(a,text,null).diagnostics()).isEmpty();
            var afterFirst=shared.status();
            assertThat(second.bindings(b,text,null).diagnostics()).isEmpty();
            if(Files.getFileStore(jar).supportsFileAttributeView("unix"))assertThat(shared.status())
                    .containsEntry("hashes",((Number)afterFirst.get("hashes")).longValue()+1)
                    .containsEntry("bytes_hashed",((Number)afterFirst.get("bytes_hashed")).longValue()+Files.size(b));
            var modified=Files.getLastModifiedTime(jar);
            Path changed=IndexFixtures.jar(root.resolve("replacement"),"api","package lib; public class Sample { public int other(){return 1;} }",true);
            Files.copy(changed,jar,StandardCopyOption.REPLACE_EXISTING);Files.setLastModifiedTime(jar,modified);
            assertThat(first.bindings(a,text,null).diagnostics()).anyMatch(d->d.code().contains("cant.resolve"));
            assertThat(second.bindings(b,text,null).diagnostics()).anyMatch(d->d.code().contains("cant.resolve"));
        }
    }
    @Test void streamingIdentityAgreesWithContentHashAndRejectsPreservedTimestampWrites()throws Exception{
        byte[] content=new byte[3*1024*1024+17];new Random(42).nextBytes(content);
        Path file=root.resolve("large.bin");Files.write(file,content);var modified=Files.getLastModifiedTime(file);
        var identities=new FileStateRegistry();String before=identities.hash(file);
        assertThat(before).isEqualTo(Hashing.sha256(content));
        content[content.length/2]^=1;Files.write(file,content);Files.setLastModifiedTime(file,modified);
        assertThat(identities.hash(file)).isEqualTo(Hashing.sha256(content)).isNotEqualTo(before);
        Files.delete(file);assertThat(identities.hash(file)).isEqualTo("missing");
    }
}
