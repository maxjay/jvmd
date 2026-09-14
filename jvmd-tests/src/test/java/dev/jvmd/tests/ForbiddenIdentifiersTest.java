package dev.jvmd.tests;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** Implements 8 and 12.4: prohibited implementation paths remain absent from production sources. */
@Tag("phase-1") @Tag("phase-10")
class ForbiddenIdentifiersTest {
    @Test void productionSourcesRespectTheArchitectureBoundaries()throws Exception{
        Path root=TestSupport.repo();var failures=new ArrayList<String>();
        var forbidden=List.of("org.eclipse.jdt","MethodEntryRequest","MethodExitRequest","SecurityManager","dependency:tree","ASTParser","lsp4j","objectweb.asm");
        try(var files=Files.walk(root)){
            for(Path file:files.filter(Files::isRegularFile).filter(p->p.toString().replace('\\','/').contains("/src/main/")).filter(p->p.toString().endsWith(".java")).toList()){
                String text=Files.readString(file),relative=root.relativize(file).toString().replace('\\','/');
                for(String token:forbidden)if(text.contains(token))failures.add(relative+": "+token);
                if(relative.startsWith("jvmd-index/")&&text.contains("WatchService"))failures.add(relative+": filesystem watcher");
                if(!relative.startsWith("jvmd-analyzer/")&&text.contains("com.sun.tools.javac"))failures.add(relative+": compiler internals outside analyzer");
            }
        }
        assertThat(failures).isEmpty();
    }
}
