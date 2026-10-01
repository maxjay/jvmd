package dev.jvmd.tests;

import com.sun.source.util.JavacTask;
import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.Bindings;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * Strict task W2: a unit's recorded dependency set must contain every source unit javac completed
 * while attributing it, including declaring classes and supertypes reached only through
 * inheritance, which the unit never names.
 */
class CompletedDependencyCaptureTest {
    @TempDir Path root;

    private Set<Path> dependencies(Map<String,String> units,String target)throws Exception{
        for(var entry:units.entrySet())Files.writeString(root.resolve(entry.getKey()),entry.getValue());
        try(var analyzer=new Analyzer(new FileStateRegistry())){
            analyzer.configure(new Analyzer.Context("fixture:capture:1","25",List.of(),List.of(root),"capture",Map.of()),null,256L*1024*1024);
            analyzer.documents(new Documents(new FileStateRegistry()));
            Path file=root.resolve(target);analyzer.diagnostics(file,Files.readString(file));
            return analyzer.contribution(file).dependencies();
        }
    }

    /**
     * Soundness of the per-unit completion model against javac itself: for every unit of the
     * P_diag sufficiency fixture, compiled alone, every source unit javac entered must be among the
     * captured dependencies.
     */
    @Test void capturedDependenciesCoverJavacsCompletionSetForEveryFixtureUnit()throws Exception{
        var units=DiagnosticProjectionSufficiencyTest.baseline();
        for(var entry:units.entrySet()){var file=root.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());}
        var missing=new TreeMap<String,Set<Path>>();
        try(var analyzer=new Analyzer(new FileStateRegistry())){
            analyzer.configure(new Analyzer.Context("fixture:capture:1","25",List.of(),List.of(root),"capture",Map.of()),null,256L*1024*1024);
            analyzer.documents(new Documents(new FileStateRegistry()));
            for(var unit:units.keySet()){
                Path file=root.resolve(unit);analyzer.diagnostics(file,Files.readString(file));
                var captured=analyzer.contribution(file).dependencies();
                var completed=new LinkedHashSet<>(javacCompletion(file));completed.removeAll(captured);completed.remove(file);
                if(!completed.isEmpty())missing.put(unit,completed);
            }
        }
        assertThat(missing).as("source units javac completed but capture omitted").isEmpty();
    }

    private Set<Path> javacCompletion(Path file)throws Exception{
        var compiler=ToolProvider.getSystemJavaCompiler();
        try(var manager=compiler.getStandardFileManager(null,Locale.ROOT,null)){
            var task=(JavacTask)compiler.getTask(new java.io.StringWriter(),manager,d->{},
                    List.of("-proc:none","-Xprefer:source","--release","25","-sourcepath",root.toString(),"-implicit:none"),null,manager.getJavaFileObjects(file));
            // Attribute only the requested unit, as the analyzer does; implicit units are entered on demand.
            var entered=task.getClass().getMethod("enter",Iterable.class).invoke(task,task.parse());
            task.getClass().getMethod("analyze",Iterable.class).invoke(task,entered);
            return Bindings.completedSources(task);
        }
    }

    @Test void inheritedMethodDeclaringUnitIsADependency()throws Exception{
        var deps=dependencies(Map.of(
                "A.java","class A { int m() { return 1; } }",
                "B.java","class B extends A { }",
                "X.java","class X { int f(B b) { return b.m(); } }"),"X.java");
        assertThat(deps).contains(root.resolve("A.java"),root.resolve("B.java"));
    }

    @Test void supertypesCompletedForSubtypingAreDependencies()throws Exception{
        var deps=dependencies(Map.of(
                "Iface.java","interface Iface { }",
                "A.java","class A implements Iface { }",
                "B.java","class B extends A { }",
                "Util.java","class Util { static int f(Iface i) { return 1; } }",
                "Y.java","class Y { int g(B b) { return Util.f(b); } }"),"Y.java");
        assertThat(deps).contains(root.resolve("Util.java"),root.resolve("B.java"),root.resolve("A.java"),root.resolve("Iface.java"));
    }

    @Test void inheritedDefaultMethodDeclaringInterfaceIsADependency()throws Exception{
        var deps=dependencies(Map.of(
                "Greeter.java","interface Greeter { default String hello() { return \"hi\"; } }",
                "Impl.java","class Impl implements Greeter { }",
                "Z.java","class Z { String f(Impl i) { return i.hello(); } }"),"Z.java");
        assertThat(deps).contains(root.resolve("Greeter.java"),root.resolve("Impl.java"));
    }
}
