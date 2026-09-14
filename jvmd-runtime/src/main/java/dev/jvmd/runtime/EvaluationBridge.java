package dev.jvmd.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.Base64;

/** Implements 4.7: java.base-only target helper; evaluators unload independently of application classes. */
public final class EvaluationBridge {
    private static final class Holder { MethodHandles.Lookup lookup; }
    private static final class Lookups extends ClassValue<Holder> {
        @Override protected Holder computeValue(Class<?> type){return new Holder();}
    }
    private static final Lookups LOOKUPS=new Lookups();
    private EvaluationBridge() { }
    public static Object[] arguments(int count){if(count<0||count>200)throw new IllegalArgumentException("At most 200 frame locals can be compiled");return new Object[count];}
    private static MethodHandles.Lookup lookup(Class<?> host,String bootstrap)throws Exception{
        var holder=LOOKUPS.get(host);
        synchronized(holder){
            if(holder.lookup!=null)return holder.lookup;
            var lookup=MethodHandles.privateLookupIn(host,MethodHandles.lookup());
            if(!lookup.hasFullPrivilegeAccess()){
                // A package lookup can define a bootstrap in the target loader. Its own lookup
                // preserves the target module when obtaining private access to the declaring class.
                var bridge=lookup.defineClass(Base64.getDecoder().decode(bootstrap));var factory=bridge.getDeclaredMethod("lookup");
                if(!factory.trySetAccessible())throw new IllegalAccessException("Evaluation bootstrap is inaccessible");
                lookup=MethodHandles.privateLookupIn(host,(MethodHandles.Lookup)factory.invoke(null));
            }
            if(!lookup.hasFullPrivilegeAccess())throw new IllegalAccessException("Declaring module does not grant an evaluation lookup");
            return holder.lookup=lookup;
        }
    }
    public static Object[] evaluate(Class<?> host,String evaluator,String bootstrap,Object self,Object[] arguments,String primitives)throws Throwable{
        if(evaluator.length()>8*1024*1024||bootstrap.length()>1024*1024)throw new IllegalArgumentException("Evaluator bytecode exceeds the bounded helper capacity");
        for(String line:primitives.split("\n"))if(!line.isEmpty()){
            String[] parts=line.split(":",3);if(parts.length!=3)throw new IllegalArgumentException("Invalid primitive argument");
            int index=Integer.parseInt(parts[0]);if(index<0||index>=arguments.length)throw new IllegalArgumentException("Invalid argument index");
            arguments[index]=switch(parts[1]){
                case "boolean"->Boolean.valueOf(parts[2]);case "byte"->Byte.valueOf(parts[2]);case "short"->Short.valueOf(parts[2]);case "char"->Character.valueOf((char)Integer.parseInt(parts[2]));
                case "int"->Integer.valueOf(parts[2]);case "long"->Long.valueOf(parts[2]);case "float"->Float.valueOf(parts[2]);case "double"->Double.valueOf(parts[2]);default->throw new IllegalArgumentException("Unknown primitive type");
            };
        }
        var type=lookup(host,bootstrap).defineHiddenClass(Base64.getDecoder().decode(evaluator),true,MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
        var constructor=self==null?type.getDeclaredConstructor():type.getDeclaredConstructor(host);
        if(!constructor.trySetAccessible())throw new IllegalAccessException("Evaluator constructor is inaccessible");
        Object instance=self==null?constructor.newInstance():constructor.newInstance(self);
        var method=java.util.Arrays.stream(type.getDeclaredMethods()).filter(m->m.getName().equals("evaluate")).findFirst().orElseThrow();
        if(!method.trySetAccessible())throw new IllegalAccessException("Evaluator method is inaccessible");
        try{
            Object[] values=(Object[])method.invoke(instance,arguments);var result=java.util.Arrays.copyOf(values,values.length+1);var summary=new StringBuilder();
            for(int i=0;i<values.length;i++){
                Object value=values[i];String primitive=switch(value){case Boolean ignored->"boolean";case Byte ignored->"byte";case Short ignored->"short";case Character ignored->"char";case Integer ignored->"int";case Long ignored->"long";case Float ignored->"float";case Double ignored->"double";case null->null;default->null;};
                if(primitive!=null)summary.append(i).append(':').append(primitive).append(':').append(value instanceof Character c?(int)c:value).append('\n');
            }
            result[values.length]=summary.toString();return result;
        }catch(InvocationTargetException error){throw error.getCause();}
    }
}
