package dev.jvmd.tests.oracle;

import java.util.ArrayList;
import java.util.List;

/** Test-only snapshot of returned native values. Does not invoke completion or query a member scope. */
public final class NativeReaderTrace {
    public record Answer(String operation,String requester,String owner,String name,String type,long flags) { }
    private static final ThreadLocal<List<Answer>> ACTIVE=new ThreadLocal<>();
    public static void begin() {if(ACTIVE.get()!=null)throw new IllegalStateException("Nested reader trace");ACTIVE.set(new ArrayList<>());}
    public static List<Answer> finish() {var result=ACTIVE.get();ACTIVE.remove();return List.copyOf(result);}
    public static void answer(Object value,Object requesting,String operation) {
        ReadOracleTrace.readerAnswer(value,requesting,operation);
        var trace=ACTIVE.get();if(trace==null)return;
        try {
            Object symbol=value.getClass().getName().equals("com.sun.tools.javac.code.Attribute$Enum")?field(value,"value"):value;
            Object type=field(symbol,"type");
            if(operation.equals("findAccessMethod"))type=compilerType().getMethod("getReturnType").invoke(type);
            trace.add(new Answer(operation,field(requesting,"flatname").toString(),
                    field(field(symbol,"owner"),"flatname").toString(),field(symbol,"name").toString(),typeName(type),(long)field(symbol,"flags_field")));
        } catch(ReflectiveOperationException failure) {throw new AssertionError("Native reader snapshot drift",failure);}
    }
    private static Object field(Object object,String name) throws ReflectiveOperationException {return object.getClass().getField(name).get(object);}
    private static Class<?> compilerType() throws ClassNotFoundException {
        return Class.forName("com.sun.tools.javac.code.Type",false,ModuleLayer.boot().findLoader("jdk.compiler"));
    }
    private static String typeName(Object type) throws ReflectiveOperationException {
        String tag=compilerType().getMethod("getTag").invoke(type).toString();
        return switch(tag) {
            case "CLASS" -> "L"+field(field(type,"tsym"),"flatname").toString().replace('.','/')+";";
            case "ARRAY" -> "["+typeName(field(type,"elemtype"));
            default -> tag;
        };
    }
}
