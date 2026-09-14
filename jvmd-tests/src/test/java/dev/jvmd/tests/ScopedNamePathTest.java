package dev.jvmd.tests;

import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 8: name paths address parameters and locals under an erased overload. */
@Tag("phase-8")
class ScopedNamePathTest {
    @TempDir Path root;
    @Test void qualifiedOverloadPathsSelectAndRenameOnlyTheirOwnParameter()throws Exception{
        Path file=root.resolve("Example.java");Files.writeString(file,"class Example { int value(java.util.Date input){int size=input.hashCode();return size;} int value(java.sql.Date input){return input.hashCode();} }");
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);
            var ambiguous=TestSupport.request(app.dispatcher(),"symbol.describe",Map.of("session",session,"ref","Example/value(Date)/input")).path("result");
            assertThat(ambiguous.path("result").path("candidates").size()).isEqualTo(2);
            var local=TestSupport.request(app.dispatcher(),"symbol.describe",Map.of("session",session,"ref","Example/value(java.util.Date)/size")).path("result").path("result");
            assertThat(local.path("name").asText()).isEqualTo("size");assertThat(local.path("scip").asText()).startsWith("local ");
            var renamed=TestSupport.request(app.dispatcher(),"edit.rename",Map.of("session",session,"ref","Example/value(java.util.Date)/input","new_name","date"));
            assertThat(renamed.has("error")).as(renamed.toString()).isFalse();assertThat(renamed.path("result").path("result").path("diagnostics").isEmpty()).isTrue();
            assertThat(Files.readString(file)).contains("java.util.Date date","size=date.hashCode()","java.sql.Date input","return input.hashCode()");
        }
    }
}
