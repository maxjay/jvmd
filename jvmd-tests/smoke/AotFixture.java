package dev.jvmd.smoke;

import com.sun.source.util.JavacTask;
import java.net.URI;
import java.util.List;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Implements section 9.5: a jarred compiler workload for a strict AOT round trip. */
public final class AotFixture {
    public static void main(String[] args) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var source = new SimpleJavaFileObject(URI.create("string:///Fixture.java"),
                javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreErrors) {
                return "class Fixture { String name; int value() { return 42; } }";
            }
        };
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var task = (JavacTask) compiler.getTask(null, manager, null,
                    List.of("-proc:none", "--should-stop=ifError=FLOW"), null, List.of(source));
            if (!task.parse().iterator().hasNext()) throw new AssertionError("No parsed unit");
        }
        System.out.println("PASS AOT fixture parse");
    }
}
