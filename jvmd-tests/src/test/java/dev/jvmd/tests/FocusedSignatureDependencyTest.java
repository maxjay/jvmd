package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 4: compact focused answers still invalidate on unselected overload signatures. */
@Tag("phase-4")
class FocusedSignatureDependencyTest {
    @TempDir Path root;
    @Test void changingAnOverloadParameterHierarchyInvalidatesTheCaller()throws Exception{
        Path dependency=root.resolve("Dependency.java"),use=root.resolve("Use.java");
        Files.writeString(dependency,"interface Dependency extends CharSequence {}");
        String text="class Use { int pick(Dependency d){return 1;} int pick(CharSequence s){return 2;} int read(){return pick(null);} }";Files.writeString(use,text);
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("fixture:app:1","25",List.of(),List.of(root),"1",Map.of(root.toString(),"fixture:app:1")),null,256L*1024*1024);
            int cursor=text.indexOf("pick(null)");
            var first=analyzer.bindings(use,text,cursor);assertThat(first.diagnostics()).isEmpty();assertThat(first.result().dependencies()).contains(dependency);
            var time=Files.getLastModifiedTime(dependency);Files.writeString(dependency,"interface Dependency {}");Files.setLastModifiedTime(dependency,time);
            var second=analyzer.bindings(use,text,cursor);assertThat(second.diagnostics()).anyMatch(d->d.code().contains("ref.ambiguous"));
        }
    }
}
