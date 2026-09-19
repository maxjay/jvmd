package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class SourceEditBinaryCacheTest {
    @TempDir Path root;
    @Test void editsReuseDependencyBytesButReplacementAndDeletionRemainVisible()throws Exception {
        Path jar=IndexFixtures.jar(root.resolve("repository"),"api","package lib; public class Sample { public int value(){return 1;} }",true);
        Path sources=Files.createDirectories(root.resolve("sources")),file=sources.resolve("Use.java");
        String text="class Use { int use(){return new lib.Sample().value();} }";Files.writeString(file,text);
        var documents=new Documents();documents.open(file,text,1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:use:1","25",List.of(jar),List.of(sources),"use",Map.of()),null,256L*1024*1024);
            analyzer.documents(documents);
            assertThat(analyzer.bindings(file,text,null).diagnostics()).isEmpty();
            long loads=(long)analyzer.status().get("class_byte_loads");assertThat(loads).isPositive();
            String edited=text.replace("value();","value()+1;");
            documents.change(file,2,List.of(new Documents.Change(null,edited)));analyzer.documents(documents);analyzer.changed(file,documents.hash(file));
            assertThat(analyzer.bindings(file,edited,null).diagnostics()).isEmpty();
            assertThat(analyzer.status()).containsEntry("class_byte_loads",loads);
            assertThat((long)analyzer.status().get("class_byte_hits")).isPositive();

            var modified=Files.getLastModifiedTime(jar);
            Path replacement=IndexFixtures.jar(root.resolve("replacement"),"api","package lib; public class Sample { public String value(){return \"changed\";} }",true);
            Files.copy(replacement,jar,StandardCopyOption.REPLACE_EXISTING);Files.setLastModifiedTime(jar,modified);
            String newer=edited.replace("+1;","+2;");
            documents.change(file,3,List.of(new Documents.Change(null,newer)));analyzer.documents(documents);analyzer.changed(file,documents.hash(file));
            var changed=analyzer.bindings(file,newer,null);
            assertThat(changed.warnings()).isEmpty();
            assertThat(changed.diagnostics()).anyMatch(d->d.code().contains("prob.found.req"));
            assertThat((long)analyzer.status().get("class_byte_loads")).isGreaterThan(loads);
            Files.delete(jar);
            var deleted=analyzer.bindings(file,newer,null);
            assertThat(deleted.result()).isNull();
            assertThat(deleted.warnings()).anyMatch(w->w.startsWith("analyzer_fault:"));
            assertThat(analyzer.status()).containsEntry("class_bytes",0L);
        }
    }
}
