package dev.jvmd.boot.cold.stage3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Independent native encodings at the retained-metadata admission boundary. */
@Tag("phase-3")
class FixedMetadataTest {
    @TempDir Path directory;

    @Test
    void retainedDeclarationMetadataIncludesPrivateMembersParametersReceiversBoundsRecordsAndDefaults() throws Exception {
        var sources = Map.ofEntries(
                Map.entry("Mark", """
                        package q;
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                        @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE,java.lang.annotation.ElementType.FIELD,
                                java.lang.annotation.ElementType.METHOD,java.lang.annotation.ElementType.PARAMETER})
                        public @interface Mark { String value() default "value"; }
                        """),
                Map.entry("Use", """
                        package q;
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                        @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
                        public @interface Use {}
                        """),
                Map.entry("Component", """
                        package q;
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                        @java.lang.annotation.Target(java.lang.annotation.ElementType.RECORD_COMPONENT)
                        public @interface Component {}
                        """),
                Map.entry("Mode", "package q; public enum Mode { X }"),
                Map.entry("Default", "package q; public @interface Default { Mode value() default Mode.X; }"),
                Map.entry("Type", "package q; @Mark public class Type {}"),
                Map.entry("PrivateField", "package q; public class PrivateField { @Mark private int unused; }"),
                Map.entry("PrivateMethod", "package q; public class PrivateMethod { @Mark private void unused(){} }"),
                Map.entry("Parameter", "package q; public class Parameter { private void unused(@Mark int x){} }"),
                Map.entry("Receiver", "package q; public class Receiver { private void unused(@Use Receiver this){} }"),
                Map.entry("Bound", "package q; public class Bound<T extends @Use Number> {}"),
                Map.entry("Array", "package q; public class Array { private String @Use [] unused; }"),
                Map.entry("Record", "package q; public record Record(@Component int value) {}"),
                Map.entry("Literal", "package q; public @interface Literal { Class<?> value() default String.class; }"),
                Map.entry("Platform", "package q; @Deprecated(since=\"21\", forRemoval=true) public class Platform {}"),
                Map.entry("BodyOnly", "package q; public class BodyOnly { private void unused(){ @Use String local=\"value\"; } }"));
        var files = new java.util.ArrayList<Path>();
        for (var source : sources.entrySet()) {
            var file = directory.resolve(source.getKey() + ".java"); Files.writeString(file,source.getValue()); files.add(file);
        }
        var output = Files.createDirectories(directory.resolve("out"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null,null,StandardCharsets.UTF_8)) {
            manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT,List.of(output));
            assertThat(compiler.getTask(null,manager,null,List.of("-proc:none","-g"),null,manager.getJavaFileObjectsFromPaths(files)).call()).isTrue();
        }
        for (String name : List.of("Default","Type","PrivateField","PrivateMethod","Parameter","Receiver","Bound","Array","Record","Literal"))
            assertThat(FixedMetadata.platformOnly(Files.readAllBytes(output.resolve("q/" + name + ".class")))).as(name).isFalse();
        for (String name : List.of("Mark","Use","Component","Mode","Platform","BodyOnly"))
            assertThat(FixedMetadata.platformOnly(Files.readAllBytes(output.resolve("q/" + name + ".class")))).as(name).isTrue();
    }
}
