package dev.jvmd.tests;

import dev.jvmd.resolver.*;
import java.nio.file.*;
import java.util.*;
import dev.jvmd.resolver.WorkspaceSource.Artifact;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 6: only current compiled output is returned by the workspace reader. */
@Tag("phase-6")
class BuiltWorkspaceReaderTest {
    @TempDir Path root;
    @Test void sourceContentChangesWithPreservedMtimeSwitchToSource()throws Exception{
        Path source=Files.createDirectories(root.resolve("src")).resolve("Library.java"),classes=Files.createDirectories(root.resolve("classes"));Files.writeString(source,"public class Library { public int value(){return 1;} }");
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-g","-d",classes.toString(),source.toString())).isZero();
        var module=new Resolution.Module("fixture:library:2",root.toString(),"jar",List.of(source.getParent().toString()),List.of(),classes.toString(),root.resolve("test-classes").toString(),"25",List.of());
        var reader=new WorkspaceOverlay(List.of(module),true);var requested=new Artifact("fixture","library","jar","","1");
        assertThat(reader.findArtifact(requested)).isEqualTo(classes.toFile());
        var timestamp=Files.getLastModifiedTime(source);Files.writeString(source,Files.readString(source).replace("value","other"));Files.setLastModifiedTime(source,timestamp);
        assertThat(reader.findArtifact(requested)).isNull();assertThat(reader.requiresSource(module)).isTrue();
        assertThat(reader.warnings().toString()).contains("fixture:library:1","fixture:library:2");
    }
    @Test void cyclesAreStatusErrorsAndStrictVersionsDoNotSubstitute(){
        var a=new Resolution.Module("fixture:a:1",root.resolve("a").toString(),"jar",List.of(),List.of(),"a","at","25",List.of("fixture:b:1"));
        var b=new Resolution.Module("fixture:b:1",root.resolve("b").toString(),"jar",List.of(),List.of(),"b","bt","25",List.of("fixture:a:1"));
        var reader=new WorkspaceOverlay(List.of(a,b),false);assertThat(reader.status().get("errors").toString()).contains("overlay_cycle");assertThat(reader.findArtifact(new Artifact("fixture","a","jar","","2"))).isNull();
    }
}
