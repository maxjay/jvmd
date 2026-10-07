package dev.jvmd.boot.cold.stage3;

import java.nio.file.Path;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Isolated processes prove absent/drifted instrumentation rejects reuse without disabling native javac. */
class NativeBridgeContractTest {
    @Test void absentBridgeAndChangedCompilerBytesRejectReuse() throws Exception {
        for(boolean drift:List.of(false,true)) {
            var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin","java").toString());
            if(drift) {
                var jar=Path.of("..","jvmd-boot","target","jvmd-boot-0.1.0-SNAPSHOT-compiler-bridge.jar").toAbsolutePath().normalize();
                command.add("-javaagent:"+jar+"="+jar);
            }
            command.addAll(List.of("-cp",System.getProperty("java.class.path"),Probe.class.getName(),Boolean.toString(drift)));
            var process=new ProcessBuilder(command).redirectErrorStream(true).start();
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as(output).isZero();assertThat(output).contains("REJECTED;NATIVE_OK");
        }
    }
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            if(Boolean.parseBoolean(args[0])) {
                var module=ModuleLayer.boot().findModule("jdk.compiler").orElseThrow();
                byte[] bytes;try(var input=module.getResourceAsStream("com/sun/tools/javac/jvm/ClassReader.class")) {bytes=input.readAllBytes();}
                bytes[0]^=1; // feed drifted bytes to the production transformer, without modifying the native compiler
                var type=Class.forName("dev.jvmd.boot.cold.stage3.NativeReaderAgent$Transformer");
                var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);
                var transform=(java.lang.instrument.ClassFileTransformer)ctor.newInstance();
                if(transform.transform(module,module.getClassLoader(),"com/sun/tools/javac/jvm/ClassReader",null,null,bytes)!=null)throw new AssertionError();
            }
            try {
                new BodyValidation(null,null,null,"app",0,List.of(),new BodyValidation.Work());
                throw new AssertionError("Unsupported bridge admitted validation");
            } catch(IllegalStateException expected) {
                if(!expected.getMessage().startsWith("Unsupported compiler reuse:"))throw expected;
            }
            var source=new SimpleJavaFileObject(java.net.URI.create("string:///C.java"),JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignore){return "class C {}";}
            };
            var compiler=ToolProvider.getSystemJavaCompiler();
            try(var files=compiler.getStandardFileManager(null,Locale.ROOT,null)) {
                var out=java.nio.file.Files.createTempDirectory("jvmd-bridge-native-");
                try {
                    files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT,List.of(out));
                    if(!compiler.getTask(null,files,null,List.of("-proc:none"),null,List.of(source)).call())throw new AssertionError("Native compiler changed");
                } finally {
                    java.nio.file.Files.deleteIfExists(out.resolve("C.class"));java.nio.file.Files.delete(out);
                }
            }
            System.out.println("REJECTED;NATIVE_OK");
        }
    }
}
