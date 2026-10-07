package dev.jvmd.index.layer.local;

import com.sun.source.util.JavacTask;
import java.net.URI;
import java.util.List;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class NamedMembersWorkTest {
    @Test void directNameWorkDoesNotGrowWithUnrelatedMembers() throws Exception {
        for(int count:List.of(0,128,4096)) {
            var text=new StringBuilder("class Lib { int value; class Nested {}");
            for(int i=0;i<count;i++)text.append("int other").append(i).append(";");
            text.append("}");
            var source=new SimpleJavaFileObject(URI.create("memory:///Lib.java"),JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignore){return text;}
            };
            var compiler=ToolProvider.getSystemJavaCompiler();
            try(var files=compiler.getStandardFileManager(null,null,null)) {
                var task=(JavacTask)compiler.getTask(null,files,null,List.of("-proc:none"),null,List.of(source));
                task.parse();task.analyze();var lib=task.getElements().getTypeElement("Lib");var names=new NamedMembers();
                for(int i=0;i<100;i++) {
                    assertThat(names.has(lib,"value",NamedMembers.FIELD)).isTrue();
                    assertThat(names.has(lib,"Nested",NamedMembers.TYPE)).isTrue();
                    assertThat(names.has(lib,"missing",NamedMembers.TYPE)).isFalse();
                    assertThat(names.has(lib,"value",NamedMembers.TYPE)).isFalse();
                }
                assertThat(names.queries).isEqualTo(3);assertThat(names.visited).isEqualTo(2);
                System.out.println("F14 unrelated members="+count+" queries="+names.queries+" visited="+names.visited);
            }
        }
    }
}
