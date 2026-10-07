package dev.jvmd.tests.oracle;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.JarEntry;

/** Test-only native javac instrumentation. No production collector supplies these observations. */
public final class ReadOracleAgent {
    private static final ClassDesc TAP = ClassDesc.of("dev.jvmd.tests.oracle.ReadOracleTrace");
    private static final ClassDesc RESOLVE = ClassDesc.of("com.sun.tools.javac.comp.Resolve");
    private static final ClassDesc SYMTAB = ClassDesc.of("com.sun.tools.javac.code.Symtab");
    private static final ClassDesc CLASS_SYMBOL = ClassDesc.of("com.sun.tools.javac.code.Symbol$ClassSymbol");
    private static final MethodTypeDesc OBJECT = MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)V");
    private static final MethodTypeDesc NONE = MethodTypeDesc.ofDescriptor("()V");
    private static final MethodTypeDesc LOOKUP = MethodTypeDesc.ofDescriptor(
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V");
    private static final MethodTypeDesc ITERATOR = MethodTypeDesc.ofDescriptor(
            "(Ljava/lang/Object;ZLjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z");
    private static final MethodTypeDesc NAMES = MethodTypeDesc.ofDescriptor(
            "(Ljava/lang/Iterable;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Iterable;");

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        var jar = Path.of(arguments);
        var bootstrap = Files.createTempFile("jvmd-read-oracle-", ".jar");
        bootstrap.toFile().deleteOnExit();
        try (var input = new JarFile(jar.toFile()); var output = new JarOutputStream(Files.newOutputStream(bootstrap))) {
            for (var entry : input.stream().filter(e -> e.getName().startsWith("dev/jvmd/tests/oracle/ReadOracleTrace")).toList()) {
                output.putNextEntry(new JarEntry(entry.getName()));
                try (var bytes = input.getInputStream(entry)) { bytes.transferTo(output); }
                output.closeEntry();
            }
        }
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(bootstrap.toFile()));
        var tap = Class.forName("dev.jvmd.tests.oracle.ReadOracleTrace", true, null);
        var compiler = ModuleLayer.boot().findModule("jdk.compiler").orElseThrow();
        instrumentation.redefineModule(compiler, Set.of(tap.getModule()), Map.of(
                "com.sun.tools.javac.code", Set.of(tap.getModule()),
                "com.sun.tools.javac.util", Set.of(tap.getModule())), Map.of(), Set.of(), Map.of());
        instrumentation.addTransformer(new Transformer());
        System.setProperty("jvmd.readOracle.active", "true");
    }

    public static final class Transformer implements ClassFileTransformer {
        public Transformer() { }
        @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefining,
                                          ProtectionDomain domain, byte[] bytes) {
            if (name == null || !Set.of("dev/jvmd/boot/cold/stage3/Attribute", "dev/jvmd/index/layer/local/ProofCollector",
                    "dev/jvmd/boot/cold/stage3/Pool", "dev/jvmd/boot/cold/stage3/Arrange",
                    "com/sun/tools/javac/jvm/ClassReader", "com/sun/tools/javac/code/Symbol",
                    "com/sun/tools/javac/code/Scope$ScopeImpl", "com/sun/tools/javac/code/Types",
                    "com/sun/tools/javac/code/Symbol$ClassSymbol", "dev/jvmd/boot/cold/stage3/Pool$HierarchyReads",
                    "com/sun/tools/javac/comp/Resolve").contains(name)) return null;
            try {
                var cf = ClassFile.of();
                var hooks = new java.util.TreeMap<String, Integer>();
                var transformed = cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethods((builder, element) -> {
                    if (!(element instanceof java.lang.classfile.CodeModel code)) { builder.with(element); return; }
                    var method = code.parent().orElseThrow();
                    String methodName = method.methodName().stringValue();
                    var descriptor = method.methodTypeSymbol();
                    boolean attribute = name.endsWith("/Attribute") && methodName.equals("run");
                    boolean pool = name.endsWith("/Pool") && methodName.equals("withTask")
                            && descriptor.parameterType(0).descriptorString().equals("Ljavax/tools/JavaFileObject;");
                    boolean arrange = name.endsWith("/Arrange") && methodName.equals("body");
                    boolean collector = name.endsWith("/ProofCollector") && methodName.equals("bodies")
                            || name.endsWith("/Pool$HierarchyReads") && Set.of("read","functional","method").contains(methodName);
                    boolean hierarchy=name.endsWith("/Types") && Set.of("supertype","interfaces").contains(methodName);
                    boolean header=name.endsWith("/Symbol$ClassSymbol") && methodName.equals("flags");
                    boolean read = name.endsWith("/ClassReader") && methodName.equals("readClassFile");
                    boolean complete = name.endsWith("/Symbol") && methodName.equals("complete");
                    boolean methodScope = name.endsWith("/Resolve") && methodName.equals("findMethodInScope");
                    boolean methodLookup=name.endsWith("/Resolve") && methodName.equals("findMethod");
                    boolean globalType = name.endsWith("/Resolve") && methodName.equals("findGlobalType");
                    boolean memberScope = name.endsWith("/Scope$ScopeImpl") && methodName.equals("getSymbols");
                    boolean namedScope = name.endsWith("/Scope$ScopeImpl") && methodName.equals("getSymbolsByName");
                    int owner = -1, key = -1;
                    if (name.endsWith("/Resolve")) {
                        switch (methodName) {
                            case "findField", "findImmediateMemberType" -> { owner = 4; key = 3; }
                            case "findMethod" -> { owner = 2; key = 3; }
                            case "loadClass" -> { owner = 0; key = 2; }
                            default -> { }
                        }
                    }
                    int ownerSlot = owner, nameSlot = key;
                    if (!(attribute || pool || arrange || collector || read || complete || methodScope || globalType || namedScope || memberScope || hierarchy || header || key >= 0)) { builder.with(code); return; }
                    hooks.merge(methodName, 1, Integer::sum);
                    if (key >= 0 && !descriptor.returnType().descriptorString().equals("Lcom/sun/tools/javac/code/Symbol;"))
                        throw new IllegalStateException("Unexpected javac lookup descriptor: " + methodName + descriptor);
                    builder.transformCode(code, new CodeTransform() {
                        java.lang.classfile.Label start;
                        @Override public void atStart(java.lang.classfile.CodeBuilder out) {
                            if (attribute) out.aload(1).invokestatic(TAP, "begin", OBJECT);
                            if(name.endsWith("/Resolve"))out.aload(0).getfield(RESOLVE,"syms",SYMTAB).invokestatic(TAP,"nativeSymbols",OBJECT);
                            if (pool) out.aload(1).invokestatic(TAP, "beginPool", OBJECT);
                            if (globalType) out.invokestatic(TAP, "beginGlobalType", NONE);
                            if (methodLookup) out.aload(3).invokestatic(TAP,"beginMethod",OBJECT);
                            if (collector) out.invokestatic(TAP, "suspend", NONE);
                            if (attribute || pool || globalType || collector || methodLookup) { start = out.newLabel(); out.labelBinding(start); }
                            if (read || complete) out.aload(read ? 1 : 0).invokestatic(TAP, "loaded", OBJECT);
                        }
                        @Override public void accept(java.lang.classfile.CodeBuilder out, java.lang.classfile.CodeElement instruction) {
                            if (methodScope && instruction instanceof InvokeInstruction invoke
                                    && invoke.owner().asInternalName().equals("java/util/Iterator") && invoke.name().equalsString("hasNext")) {
                                hooks.merge("findMethodInScope#hasNext", 1, Integer::sum);
                                // Preserve the actual iterator and its native answer; do not iterate or re-resolve in the observer.
                                out.dup().with(instruction).aload(6).aload(3);
                                predefined(out).invokestatic(TAP, "methodScope", ITERATOR);
                                return;
                            }
                            if (instruction instanceof ReturnInstruction result) {
                                if(hierarchy)out.aload(1).invokestatic(TAP,"hierarchy",OBJECT);
                                if(header)out.aload(0).invokestatic(TAP,"header",OBJECT);
                                if (attribute) out.dup().invokestatic(TAP, "end", OBJECT);
                                if (pool) out.invokestatic(TAP, "endPool", NONE);
                                if (arrange) out.dup().invokestatic(TAP, "arranged", OBJECT);
                                if (globalType) out.invokestatic(TAP, "endGlobalType", NONE);
                                if (namedScope) out.aload(0).aload(1).invokestatic(TAP, "names", NAMES);
                                if (memberScope) out.aload(0).aload(1).invokestatic(TAP, "members", NAMES);
                                if (collector) out.invokestatic(TAP, "resume", NONE);
                                if (nameSlot >= 0 && result.opcode() == Opcode.ARETURN) {
                                    out.dup().ldc(methodName);
                                    if (ownerSlot == 0) out.aconst_null(); else out.aload(ownerSlot);
                                    out.aload(nameSlot);
                                    predefined(out).invokestatic(TAP, "lookup", LOOKUP);
                                }
                            }
                            if(methodLookup && instruction instanceof ReturnInstruction)out.invokestatic(TAP,"endMethod",NONE);
                            out.with(instruction);
                        }
                        @Override public void atEnd(java.lang.classfile.CodeBuilder out) {
                            if (start != null) {
                                var end = out.newLabel(); var handler = out.newLabel();
                                out.labelBinding(end).exceptionCatchAll(start, end, handler).labelBinding(handler)
                                        .invokestatic(TAP, collector ? "resume" : globalType ? "endGlobalType" : methodLookup ? "endMethod" : "abort", NONE).athrow();
                            }
                        }
                    });
                }));
                Map<String, Integer> expected = switch (name) {
                    case "dev/jvmd/boot/cold/stage3/Attribute" -> Map.of("run", 1);
                    case "dev/jvmd/boot/cold/stage3/Pool" -> Map.of("withTask", 1);
                    case "dev/jvmd/boot/cold/stage3/Arrange" -> Map.of("body", 1);
                    case "dev/jvmd/index/layer/local/ProofCollector" -> Map.of("bodies", 1);
                    case "com/sun/tools/javac/jvm/ClassReader" -> Map.of("readClassFile", 1);
                    case "com/sun/tools/javac/code/Symbol" -> Map.of("complete", 1);
                    case "com/sun/tools/javac/code/Scope$ScopeImpl" -> Map.of("getSymbolsByName", 1, "getSymbols", 1);
                    case "com/sun/tools/javac/code/Types" -> Map.of("supertype",1,"interfaces",1);
                    case "com/sun/tools/javac/code/Symbol$ClassSymbol" -> Map.of("flags",1);
                    case "dev/jvmd/boot/cold/stage3/Pool$HierarchyReads" -> Map.of("read",2,"functional",1,"method",1);
                    default -> Map.of("findField", 1, "findImmediateMemberType", 1, "findMethod", 2, "loadClass", 1,
                            "findMethodInScope", 1, "findMethodInScope#hasNext", 1, "findGlobalType", 1);
                };
                if (!hooks.equals(expected)) throw new AssertionError("Native oracle hook drift: " + name + " " + hooks);
                ReadOracleTrace.installed(name);
                return transformed;
            } catch (Throwable failure) {
                // A transformer exception normally gets swallowed by the JVM. Fail the fork instead of silently losing coverage.
                failure.printStackTrace(); Runtime.getRuntime().halt(97); return null;
            }
        }
        /** Exact native identity, rather than a binary-name, null-classfile or compiler-package heuristic. */
        private static java.lang.classfile.CodeBuilder predefined(java.lang.classfile.CodeBuilder out) {
            return out.aload(0).getfield(RESOLVE, "syms", SYMTAB).getfield(SYMTAB, "predefClass", CLASS_SYMBOL);
        }
    }
}
