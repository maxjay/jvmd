package dev.jvmd.tests;

import com.sun.source.util.JavacTask;
import dev.jvmd.analyzer.Parser;
import dev.jvmd.analyzer.SourceNamespaces;
import dev.jvmd.index.SemanticCompleteness;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.*;
import javax.tools.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 14 stratification research (architecture §85, §88): differential check of the S0
 * syntactic namespace against javac's enter (header) phase over this repository's own sources.
 *
 * S0 must agree with javac on the declared type namespace (package, top-level and member type
 * binary names) for every unit that parses cleanly; that is the property that lets S0 replace
 * attribution for namespace questions such as package overview filtering and the attributed memo
 * namespace leaf. Writes {@code target/benchmarks/stratification.md}.
 */
class StratificationDifferentialTest {
    @Test void syntacticNamespaceAgreesWithJavacEnterOnRepositorySources()throws Exception{
        Path repository=Path.of("").toAbsolutePath().getParent();
        var files=new ArrayList<Path>();
        try(var modules=Files.list(repository)){
            for(Path module:modules.filter(path->path.getFileName().toString().startsWith("jvmd-")).sorted().toList()){
                Path sources=module.resolve("src/main/java");if(!Files.isDirectory(sources))continue;
                try(var walk=Files.walk(sources)){walk.filter(path->path.toString().endsWith(".java")&&!path.getFileName().toString().equals("module-info.java")).forEach(files::add);}
            }
        }
        assertThat(files).hasSizeGreaterThan(100);
        var namespaces=new SourceNamespaces(null);var compiler=ToolProvider.getSystemJavaCompiler();
        int agreed=0,partial=0;var mismatches=new ArrayList<String>();long s0Nanos=0,enterNanos=0;
        var mode=new SourceNamespaces.LanguageMode("",false);
        for(Path file:files){
            String text=Files.readString(file);
            long started=System.nanoTime();var s0=namespaces.namespace(text,mode);s0Nanos+=System.nanoTime()-started;
            if(s0.completeness()!=SemanticCompleteness.COMPLETE){partial++;continue;}
            started=System.nanoTime();
            var task=(JavacTask)compiler.getTask(new java.io.StringWriter(),null,diagnostic->{},List.of("-proc:none"),null,
                    List.of(Parser.source(file.toUri(),text)));
            var entered=new TreeSet<String>();
            // JavacTaskImpl.enter(): parse + enter (headers) without attribution; exported to tests at runtime.
            var elements=(Iterable<?>)task.getClass().getMethod("enter").invoke(task);
            for(var element:elements)if(element instanceof TypeElement type)collect(type,task,entered);
            enterNanos+=System.nanoTime()-started;
            var syntactic=new TreeSet<String>(s0.topLevelTypes());syntactic.addAll(s0.nestedTypes());
            if(syntactic.equals(entered))agreed++;
            else mismatches.add(repository.relativize(file)+": s0="+syntactic+" javac="+entered);
        }
        var report=new StringBuilder("## Stratification differential (Phase 14)\n\n")
                .append("| Metric | Value |\n|---|---:|\n")
                .append("| units | ").append(files.size()).append(" |\n")
                .append("| S0 namespace equal to javac enter | ").append(agreed).append(" |\n")
                .append("| PARTIAL (syntax errors, excluded) | ").append(partial).append(" |\n")
                .append("| mismatches | ").append(mismatches.size()).append(" |\n")
                .append(String.format(Locale.ROOT,"| S0 parse time total | %.1f ms |\n",s0Nanos/1e6))
                .append(String.format(Locale.ROOT,"| javac enter time total | %.1f ms |\n",enterNanos/1e6));
        mismatches.forEach(mismatch->report.append("\n- ").append(mismatch));
        System.out.println(report);
        Path target=Path.of("target/benchmarks");Files.createDirectories(target);Files.writeString(target.resolve("stratification.md"),report);
        assertThat(mismatches).isEmpty();
    }
    private static void collect(TypeElement type,JavacTask task,Set<String> result){
        result.add(task.getElements().getBinaryName(type).toString());
        for(var member:type.getEnclosedElements())if(member instanceof TypeElement nested)collect(nested,task,result);
    }
}
