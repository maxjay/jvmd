package dev.jvmd.tests.oracle;

import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Checkpoint-1 interception and selection probes, entirely outside JVMD compilation/extraction. */
@Tag("read-oracle")
class NativeReaderOperationTest {
    @TempDir Path directory;
    private final ClassFile cf=ClassFile.of();
    private Path dependencies,source;
    private void fixture() throws Exception {
        dependencies=Files.createDirectories(directory.resolve("classes"));
        var files=new ArrayList<String>();
        for(var item:List.of(new String[]{"Mode","public enum Mode { X }"},
                new String[]{"Ann","public @interface Ann { Mode mode(); int number(); }"},
                new String[]{"Lib","public class Lib { @Ann(mode=Mode.X,number=1) private int hidden; public static int call(){return 1;} }"})) {
            var file=directory.resolve(item[0]+".java");Files.writeString(file,"package q; "+item[1]);files.add(file.toString());
        }
        var args=new ArrayList<>(List.of("-proc:none","-d",dependencies.toString()));args.addAll(files);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new))).isZero();
        source=directory.resolve("App.java");Files.writeString(source,"class App { int value(){return q.Lib.call();} }");
    }
    private record Result(List<NativeReaderTrace.Answer> answers,List<String> diagnostics,byte[] bytes) { }
    private Result compile() throws Exception {
        var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(diagnostics,java.util.Locale.ROOT,null)) {
            NativeReaderTrace.begin();
            boolean success;List<NativeReaderTrace.Answer> trace;
            try {success=compiler.getTask(null,manager,diagnostics,List.of("-proc:none","-Xlint:classfile","-classpath",dependencies.toString(),
                    "-d",directory.toString()),null,manager.getJavaFileObjects(source)).call();}
            finally {trace=NativeReaderTrace.finish();}
            assertThat(success).as(diagnostics.getDiagnostics().toString()).isTrue();
            return new Result(trace,diagnostics.getDiagnostics().stream().map(d->d.getCode()+": "+d.getMessage(java.util.Locale.ROOT)).toList(),
                    Files.readAllBytes(directory.resolve("App.class")));
        }
    }
    private List<NativeReaderTrace.Answer> named(Result result,String operation,String owner,String name) {
        return result.answers().stream().filter(a->a.operation().equals(operation) && a.owner().equals(owner) && a.name().equals(name)).toList();
    }

    @Test void firstNamedVarIsLastEnteredEvenWhenPrivateAndNotAnEnumConstant() throws Exception {
        fixture();var mode=dependencies.resolve("q/Mode.class");var original=cf.parse(Files.readAllBytes(mode));
        byte[] previous=null;
        for(var order:List.of(List.of("I","Lq/Mode;"),List.of("Lq/Mode;","I"))) {
            var bytes=cf.transformClass(original,(builder,element)->{
                if(element instanceof java.lang.classfile.FieldModel field && field.fieldName().equalsString("X"))return;
                builder.with(element);
            });
            bytes=cf.transformClass(cf.parse(bytes),new java.lang.classfile.ClassTransform() {
                @Override public void accept(java.lang.classfile.ClassBuilder builder,java.lang.classfile.ClassElement element) {builder.with(element);}
                @Override public void atEnd(java.lang.classfile.ClassBuilder builder) {
                    for(var descriptor:order)builder.withField("X",ClassDesc.ofDescriptor(descriptor),ClassFile.ACC_PRIVATE|ClassFile.ACC_STATIC);
                }
            });
            assertThat(cf.verify(bytes)).isEmpty();Files.write(mode,bytes);
            var result=compile();var answers=named(result,"visitEnumAttributeProxy","q.Mode","X");
            assertThat(answers).hasSize(1);
            assertThat(answers.getFirst().type()).isEqualTo(order.getLast().equals("I")?"INT":"Lq/Mode;");
            assertThat(answers.getFirst().requester()).isEqualTo("q.Lib");
            assertThat(answers.getFirst().flags() & ClassFile.ACC_PRIVATE).isNotZero();
            assertThat(answers.getFirst().flags() & ClassFile.ACC_ENUM).isZero();
            System.out.println("Q04 native VAR order="+order+" answer="+answers.getFirst());
            assertThat(result.diagnostics()).isEmpty();
            if(previous!=null)assertThat(result.bytes()).isEqualTo(previous);previous=result.bytes();
        }
    }

    @Test void annotationMethodSelectsLastEnteredZeroArgumentReturnDescriptor() throws Exception {
        fixture();var ann=dependencies.resolve("q/Ann.class");var original=cf.parse(Files.readAllBytes(ann));
        for(var order:List.of(List.of("()I","()Ljava/lang/String;"),List.of("()Ljava/lang/String;","()I"))) {
            var bytes=cf.transformClass(original,new java.lang.classfile.ClassTransform() {
                @Override public void accept(java.lang.classfile.ClassBuilder builder,java.lang.classfile.ClassElement element) {
                    if(element instanceof MethodModel method && method.methodName().equalsString("number"))return;
                    builder.with(element);
                }
                @Override public void atEnd(java.lang.classfile.ClassBuilder builder) {
                    for(var descriptor:order)builder.withMethod("number",MethodTypeDesc.ofDescriptor(descriptor),ClassFile.ACC_PUBLIC|ClassFile.ACC_ABSTRACT,m->{});
                    // Native search must skip this most recently entered, nonzero-argument declaration.
                    builder.withMethod("number",MethodTypeDesc.ofDescriptor("(I)I"),ClassFile.ACC_PUBLIC|ClassFile.ACC_ABSTRACT,m->{});
                }
            });
            assertThat(cf.verify(bytes)).isEmpty();Files.write(ann,bytes);
            var result=compile();var answers=named(result,"findAccessMethod","q.Ann","number");
            assertThat(answers).hasSize(1);
            assertThat(answers.getFirst().type()).isEqualTo(order.getLast().equals("()I")?"INT":"Ljava/lang/String;");
            assertThat(answers.getFirst().requester()).isEqualTo("q.Lib");
            System.out.println("Q04 native METHOD order="+order+" answer="+answers.getFirst());
            assertThat(result.diagnostics()).isEmpty();
        }
    }

    @Test void missingMemberAndMissingOwnerProduceDifferentNativeFailures() throws Exception {
        fixture();var mode=dependencies.resolve("q/Mode.class");var original=Files.readAllBytes(mode);
        var missing=cf.transformClass(cf.parse(original),(builder,element)->{
            if(element instanceof java.lang.classfile.FieldModel field && field.fieldName().equalsString("X"))return;
            builder.with(element);
        });
        assertThat(cf.verify(missing)).isEmpty();Files.write(mode,missing);
        var member=compile();
        assertThat(named(member,"visitEnumAttributeProxy","q.Mode","X")).singleElement().extracting(NativeReaderTrace.Answer::type).isEqualTo("BOT");
        assertThat(member.diagnostics()).anyMatch(d->d.contains("unknown.enum.constant") && !d.contains("reason"));
        Files.delete(mode);var owner=compile();
        // A return-only hook is not sufficient: both recovery symbols look identical.
        assertThat(named(owner,"visitEnumAttributeProxy","q.Mode","X"))
                .isEqualTo(named(member,"visitEnumAttributeProxy","q.Mode","X"));
        assertThat(owner.diagnostics()).anyMatch(d->d.contains("unknown.enum.constant.reason"));
        assertThat(owner.diagnostics()).isNotEqualTo(member.diagnostics());
        System.out.println("Q03 absent member="+member.diagnostics()+" absent owner="+owner.diagnostics());
        Files.write(mode,original);var restored=compile();
        assertThat(restored.diagnostics()).isEmpty();
        assertThat(named(restored,"visitEnumAttributeProxy","q.Mode","X")).singleElement().extracting(NativeReaderTrace.Answer::type).isEqualTo("Lq/Mode;");
    }

    @Test void hookBuildAndRawClassInputsAreAuditable() throws Exception {
        System.out.println("NATIVE-READER runtime="+Runtime.version()+" vendor="+System.getProperty("java.vendor"));
        var compiler=ModuleLayer.boot().findModule("jdk.compiler").orElseThrow();
        for(var name:List.of("jvm/ClassReader","jvm/ClassReader$AnnotationDeproxy","code/Scope$ScopeImpl")) {
            try(var input=compiler.getResourceAsStream("com/sun/tools/javac/"+name+".class")) {
                assertThat(input).isNotNull();var bytes=input.readAllBytes();
                System.out.println("NATIVE-READER "+name+" sha256="+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            }
        }
    }
}
