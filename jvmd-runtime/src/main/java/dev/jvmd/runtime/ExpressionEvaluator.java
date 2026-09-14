package dev.jvmd.runtime;

import com.sun.jdi.*;
import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import dev.jvmd.core.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.tools.*;

/** Implements 4.7: tier-1 evaluation of expressions through public javac trees and JDI operations. */
public final class ExpressionEvaluator {
    private record Name(String text) { }
    private final DebugSession debug;
    private final VirtualMachine vm;
    private final ThreadReference thread;
    private final ObjectReference self;
    private final ReferenceType declaring;
    private final Map<String,Value> locals=new HashMap<>();
    private int nodes;
    public ExpressionEvaluator(DebugSession debug,String frame)throws Exception{
        this.debug=debug;vm=debug.vm();var current=debug.frame(frame);thread=current.thread();self=current.thisObject();declaring=current.location().declaringType();
        if(current.location().method().isObsolete())throw new RpcException(-32003,"unsupported_capability",Map.of("capability","eval","reason","obsolete_frame: resume or step out of the replaced method before evaluating"));
        try{for(var entry:current.getValues(current.visibleVariables()).entrySet())locals.put(entry.getKey().name(),entry.getValue());}
        catch(AbsentInformationException e){
            // javac omits an empty LocalVariableTable even with -g. A debug-enabled declaring class
            // can therefore have a valid static frame with no locals; a class without tables still fails.
            if(!debug.hasLocalInformation(declaring))throw debug.missingLocals();
        }
    }
    public Envelope evaluate(String expression)throws Exception{
        if(expression==null||expression.isBlank()||expression.length()>8192)throw RpcException.invalid("An expression of 1..8192 characters is required");
        var diagnostics=new DiagnosticCollector<JavaFileObject>();var compiler=ToolProvider.getSystemJavaCompiler();
        try(var manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8)){
            String source="class Eval { Object value(){ return ("+expression+"); } }";
            var file=new SimpleJavaFileObject(URI.create("string:///Eval.java"),JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignoreEncodingErrors){return source;}};
            var task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none"),null,List.of(file));
            var unit=task.parse().iterator().next();
            if(diagnostics.getDiagnostics().stream().anyMatch(d->d.getKind()==Diagnostic.Kind.ERROR)||unit.getTypeDecls().size()!=1)throw RpcException.invalid("Invalid Java expression");
            var type=(ClassTree)unit.getTypeDecls().getFirst();if(type.getMembers().size()!=1||!(type.getMembers().getFirst() instanceof MethodTree method)||method.getBody().getStatements().size()!=1||!(method.getBody().getStatements().getFirst() instanceof ReturnTree returned))throw RpcException.invalid("Expected one expression");
            try{
                Object result=eval(returned.getExpression(),0);Value value=value(result);
                return Envelope.of(1,"live",Map.of("value",debug.value(value),"evaluation_tier",1));
            }catch(InvocationException e){throw new RpcException(-32003,"evaluation_threw",Map.of("exception",debug.value(e.exception())));}
            catch(ArithmeticException|IndexOutOfBoundsException|ClassCastException e){throw RpcException.invalid("Evaluation failed: "+e.getMessage());}
        }
    }
    private Object eval(ExpressionTree tree,int depth)throws Exception{
        if(++nodes>512||depth>64)throw new RpcException(-32005,"budget_exceeded",Map.of("reason","Expression complexity exceeds 512 nodes or depth 64","cursor","expression"));
        if(tree instanceof ParenthesizedTree p)return eval(p.getExpression(),depth+1);
        if(tree instanceof LiteralTree literal)return mirror(literal.getValue());
        if(tree instanceof IdentifierTree identifier){
            String name=identifier.getName().toString();if(name.equals("this"))return self;if(locals.containsKey(name))return locals.get(name);
            Field field=declaring.fieldByName(name);if(field!=null)return field.isStatic()?declaring.getValue(field):requireObject(self).getValue(field);
            var type=type(name);return type==null?new Name(name):type;
        }
        if(tree instanceof MemberSelectTree member){
            Object target=eval(member.getExpression(),depth+1);String name=member.getIdentifier().toString();
            if(target instanceof Name prefix){String full=prefix.text()+"."+name;var type=type(full);return type==null?new Name(full):type;}
            if(target instanceof ReferenceType type){
                if(name.equals("class"))return type.classObject();Field field=type.fieldByName(name);if(field==null||!field.isStatic())throw RpcException.invalid("No static field "+type.name()+"."+name);return type.getValue(field);
            }
            var object=requireObject(value(target));if(object instanceof ArrayReference array&&name.equals("length"))return vm.mirrorOf(array.length());
            Field field=object.referenceType().fieldByName(name);if(field==null)throw RpcException.invalid("No field "+name);return object.getValue(field);
        }
        if(tree instanceof ArrayAccessTree array){var object=requireObject(value(eval(array.getExpression(),depth+1)));if(!(object instanceof ArrayReference target))throw RpcException.invalid("Array required");return target.getValue(integer(value(eval(array.getIndex(),depth+1))).intValue());}
        if(tree instanceof TypeCastTree cast)return cast(value(eval(cast.getExpression(),depth+1)),cast.getType().toString());
        if(tree instanceof ConditionalExpressionTree choice)return eval(bool(value(eval(choice.getCondition(),depth+1)))?choice.getTrueExpression():choice.getFalseExpression(),depth+1);
        if(tree instanceof UnaryTree unary){Value operand=value(eval(unary.getExpression(),depth+1));return switch(unary.getKind()){
            case LOGICAL_COMPLEMENT->vm.mirrorOf(!bool(operand));
            case UNARY_PLUS->promote(number(operand));
            case UNARY_MINUS->{var n=number(operand);yield n instanceof Double?vm.mirrorOf(-n.doubleValue()):n instanceof Float?vm.mirrorOf(-n.floatValue()):n instanceof Long?vm.mirrorOf(-n.longValue()):vm.mirrorOf(-n.intValue());}
            case BITWISE_COMPLEMENT->{var n=integer(operand);yield n instanceof Long?vm.mirrorOf(~n.longValue()):vm.mirrorOf(~n.intValue());}
            default->throw unsupported(unary.getKind().toString());
        };}
        if(tree instanceof BinaryTree binary){
            Value left=value(eval(binary.getLeftOperand(),depth+1));
            if(binary.getKind()==Tree.Kind.CONDITIONAL_AND&&!bool(left))return vm.mirrorOf(false);
            if(binary.getKind()==Tree.Kind.CONDITIONAL_OR&&bool(left))return vm.mirrorOf(true);
            Value right=value(eval(binary.getRightOperand(),depth+1));return binary(binary.getKind(),left,right);
        }
        if(tree instanceof MethodInvocationTree call){
            Object target;String name;
            if(call.getMethodSelect() instanceof MemberSelectTree member){target=eval(member.getExpression(),depth+1);name=member.getIdentifier().toString();}
            else if(call.getMethodSelect() instanceof IdentifierTree identifier){target=self==null?declaring:self;name=identifier.getName().toString();}
            else throw unsupported("method selector");
            var arguments=new ArrayList<Value>();for(var argument:call.getArguments())arguments.add(value(eval(argument,depth+1)));
            return invoke(target,name,arguments);
        }
        throw unsupported(tree.getKind().toString());
    }
    private RpcException unsupported(String kind){return new RpcException(-32003,"unsupported_capability",Map.of("capability","eval_tier_1","reason","Unsupported expression: "+kind,"suggestion","Use evaluation tier 2 for full Java expressions"));}
    private Value value(Object value){if(value==null||value instanceof Value)return (Value)value;throw RpcException.invalid(value instanceof Name n?"Unknown name: "+n.text():"A value is required");}
    private ObjectReference requireObject(Value value){if(value instanceof ObjectReference object)return object;throw RpcException.invalid(value==null?"Null receiver":"Object required");}
    private Value mirror(Object value){
        if(value==null)return null;if(value instanceof String s){var result=vm.mirrorOf(s);debug.handles().pin(result);return result;}
        if(value instanceof Boolean b)return vm.mirrorOf(b);if(value instanceof Character c)return vm.mirrorOf(c);
        if(value instanceof Integer i)return vm.mirrorOf(i);if(value instanceof Long l)return vm.mirrorOf(l);if(value instanceof Float f)return vm.mirrorOf(f);if(value instanceof Double d)return vm.mirrorOf(d);throw RpcException.invalid("Unsupported literal");
    }
    private boolean bool(Value value){if(value instanceof BooleanValue b)return b.value();throw RpcException.invalid("Boolean required");}
    private Number number(Value value){
        if(value instanceof ByteValue b)return b.value();if(value instanceof ShortValue s)return s.value();if(value instanceof CharValue c)return (int)c.value();if(value instanceof IntegerValue i)return i.value();if(value instanceof LongValue l)return l.value();if(value instanceof FloatValue f)return f.value();if(value instanceof DoubleValue d)return d.value();
        throw RpcException.invalid("Numeric primitive required");
    }
    private Number integer(Value value){var n=number(value);if(n instanceof Float||n instanceof Double)throw RpcException.invalid("Integral primitive required");return n;}
    private Value promote(Number value){return value instanceof Double?vm.mirrorOf(value.doubleValue()):value instanceof Float?vm.mirrorOf(value.floatValue()):value instanceof Long?vm.mirrorOf(value.longValue()):vm.mirrorOf(value.intValue());}
    private Value cast(Value value,String name)throws Exception{
        if(name.equals("boolean"))return vm.mirrorOf(bool(value));
        if(Set.of("byte","short","char","int","long","float","double").contains(name)){Number n=number(value);return switch(name){case "byte"->vm.mirrorOf(n.byteValue());case "short"->vm.mirrorOf(n.shortValue());case "char"->vm.mirrorOf((char)n.intValue());case "int"->vm.mirrorOf(n.intValue());case "long"->vm.mirrorOf(n.longValue());case "float"->vm.mirrorOf(n.floatValue());default->vm.mirrorOf(n.doubleValue());};}
        if(value==null)return null;var object=requireObject(value);var target=type(name);if(target==null||!assignable(object.referenceType(),target.name()))throw RpcException.invalid("Invalid reference cast to "+name);return object;
    }
    private ReferenceType type(String name){
        var candidates=vm.classesByName(name);if(candidates.isEmpty()&&!name.contains("."))candidates=vm.classesByName("java.lang."+name);
        if(candidates.isEmpty())candidates=vm.allClasses().stream().filter(t->t.name().endsWith("."+name)||t.name().replace('$','.').equals(name)||t.name().endsWith("$"+name)).toList();
        if(candidates.isEmpty())return null;
        if(candidates.size()>1){var same=candidates.stream().filter(t->Objects.equals(t.classLoader(),declaring.classLoader())).toList();if(same.size()==1)return same.getFirst();throw new RpcException(-32002,"ambiguous_ref",Map.of("candidates",candidates.stream().limit(20).map(t->debug.sources().symbol(t,null)).toList()));}
        return candidates.getFirst();
    }
    private boolean assignable(ReferenceType from,String to){
        if(from.name().equals(to)||to.equals("java.lang.Object"))return true;
        if(from instanceof ArrayType)return Set.of("java.lang.Cloneable","java.io.Serializable").contains(to);
        if(from instanceof ClassType type){if(type.allInterfaces().stream().anyMatch(i->i.name().equals(to)))return true;return type.superclass()!=null&&assignable(type.superclass(),to);}
        if(from instanceof InterfaceType type)for(var parent:type.superinterfaces())if(assignable(parent,to))return true;return false;
    }
    private int cost(Value argument,String parameter){
        if(argument==null)return Set.of("boolean","byte","short","char","int","long","float","double").contains(parameter)?-1:5;
        String from=argument.type().name();if(from.equals(parameter))return 0;
        if(argument instanceof ObjectReference object)return assignable(object.referenceType(),parameter)?5:-1;
        String widening=switch(from){case "byte"->"short,int,long,float,double";case "short","char"->"int,long,float,double";case "int"->"long,float,double";case "long"->"float,double";case "float"->"double";default->"";};
        var targets=Arrays.asList(widening.split(","));int i=targets.indexOf(parameter);return i<0?-1:i+1;
    }
    private Value invoke(Object receiver,String name,List<Value> arguments)throws Exception{
        ReferenceType target=receiver instanceof ReferenceType type?type:requireObject(value(receiver)).referenceType();boolean staticOnly=receiver instanceof ReferenceType;
        var candidates=new ArrayList<Method>();int best=Integer.MAX_VALUE;
        for(var method:target.visibleMethods()){
            if(!method.name().equals(name)||method.isAbstract()||staticOnly&&!method.isStatic())continue;
            var parameters=method.argumentTypeNames();if(arguments.size()!=parameters.size())continue;int total=0;boolean valid=true;
            for(int i=0;i<parameters.size();i++){int cost=cost(arguments.get(i),parameters.get(i));if(cost<0){valid=false;break;}total+=cost;}if(!valid)continue;
            if(total<best){best=total;candidates.clear();}if(total==best)candidates.add(method);
        }
        if(candidates.size()>1){
            var specific=new ArrayList<Method>();for(var candidate:candidates){boolean dominates=true;for(var other:candidates)if(candidate!=other)for(int i=0;i<candidate.argumentTypeNames().size();i++){
                String a=candidate.argumentTypeNames().get(i),b=other.argumentTypeNames().get(i);if(a.equals(b))continue;var type=type(a);if(type==null||!assignable(type,b)){dominates=false;break;}
            }if(dominates)specific.add(candidate);}if(specific.size()==1)candidates=specific;
        }
        if(candidates.isEmpty())throw unsupported("no applicable loaded method "+target.name()+"."+name);
        if(candidates.size()!=1)throw new RpcException(-32002,"ambiguous_ref",Map.of("candidates",candidates.stream().limit(20).map(m->debug.sources().symbol(m.declaringType(),m)).toList()));
        var method=candidates.getFirst();Value result=debug.invocation(()->{
            if(method.isStatic()){if(method.declaringType() instanceof ClassType type)return type.invokeMethod(thread,method,arguments,ClassType.INVOKE_SINGLE_THREADED);if(method.declaringType() instanceof InterfaceType type)return type.invokeMethod(thread,method,arguments,ObjectReference.INVOKE_SINGLE_THREADED);}
            return requireObject(value(receiver)).invokeMethod(thread,method,arguments,ObjectReference.INVOKE_SINGLE_THREADED);
        });if(result instanceof ObjectReference object)debug.handles().pin(object);return result;
    }
    private String string(Value value)throws Exception{
        if(value==null)return "null";if(value instanceof StringReference s)return s.value();if(value instanceof CharValue c)return Character.toString(c.value());if(value instanceof ObjectReference){Value text=invoke(value,"toString",List.of());return text instanceof StringReference s?s.value():"null";}return value.toString();
    }
    private Value binary(Tree.Kind kind,Value left,Value right)throws Exception{
        if(kind==Tree.Kind.PLUS&&(left instanceof StringReference||right instanceof StringReference))return mirror(string(left)+string(right));
        if(kind==Tree.Kind.CONDITIONAL_AND||kind==Tree.Kind.CONDITIONAL_OR)return vm.mirrorOf(bool(right));
        if(kind==Tree.Kind.EQUAL_TO||kind==Tree.Kind.NOT_EQUAL_TO){
            boolean equal;if(left==null||right==null||left instanceof ObjectReference||right instanceof ObjectReference)equal=Objects.equals(left,right);else if(left instanceof BooleanValue||right instanceof BooleanValue)equal=bool(left)==bool(right);else{Number a=number(left),b=number(right);equal=a instanceof Float||a instanceof Double||b instanceof Float||b instanceof Double?a.doubleValue()==b.doubleValue():a.longValue()==b.longValue();}return vm.mirrorOf(kind==Tree.Kind.EQUAL_TO?equal:!equal);
        }
        if(left instanceof BooleanValue&&right instanceof BooleanValue)return vm.mirrorOf(switch(kind){case AND->bool(left)&bool(right);case OR->bool(left)|bool(right);case XOR->bool(left)^bool(right);default->throw unsupported(kind.toString());});
        Number a=number(left),b=number(right);boolean floating=a instanceof Double||a instanceof Float||b instanceof Double||b instanceof Float,wide=a instanceof Long||b instanceof Long;
        if(Set.of(Tree.Kind.LESS_THAN,Tree.Kind.LESS_THAN_EQUAL,Tree.Kind.GREATER_THAN,Tree.Kind.GREATER_THAN_EQUAL).contains(kind)){
            boolean comparison=floating?switch(kind){case LESS_THAN->a.doubleValue()<b.doubleValue();case LESS_THAN_EQUAL->a.doubleValue()<=b.doubleValue();case GREATER_THAN->a.doubleValue()>b.doubleValue();default->a.doubleValue()>=b.doubleValue();}:switch(kind){case LESS_THAN->a.longValue()<b.longValue();case LESS_THAN_EQUAL->a.longValue()<=b.longValue();case GREATER_THAN->a.longValue()>b.longValue();default->a.longValue()>=b.longValue();};return vm.mirrorOf(comparison);
        }
        if(floating){double result=switch(kind){case PLUS->a.doubleValue()+b.doubleValue();case MINUS->a.doubleValue()-b.doubleValue();case MULTIPLY->a.doubleValue()*b.doubleValue();case DIVIDE->a.doubleValue()/b.doubleValue();case REMAINDER->a.doubleValue()%b.doubleValue();default->throw unsupported(kind.toString());};return a instanceof Double||b instanceof Double?vm.mirrorOf(result):vm.mirrorOf((float)result);}
        if(kind==Tree.Kind.LEFT_SHIFT||kind==Tree.Kind.RIGHT_SHIFT||kind==Tree.Kind.UNSIGNED_RIGHT_SHIFT){
            if(a instanceof Long)return vm.mirrorOf(switch(kind){case LEFT_SHIFT->a.longValue()<<b.intValue();case RIGHT_SHIFT->a.longValue()>>b.intValue();default->a.longValue()>>>b.intValue();});
            return vm.mirrorOf(switch(kind){case LEFT_SHIFT->a.intValue()<<b.intValue();case RIGHT_SHIFT->a.intValue()>>b.intValue();default->a.intValue()>>>b.intValue();});
        }
        long result=switch(kind){case PLUS->a.longValue()+b.longValue();case MINUS->a.longValue()-b.longValue();case MULTIPLY->a.longValue()*b.longValue();case DIVIDE->a.longValue()/b.longValue();case REMAINDER->a.longValue()%b.longValue();case AND->a.longValue()&b.longValue();case OR->a.longValue()|b.longValue();case XOR->a.longValue()^b.longValue();default->throw unsupported(kind.toString());};return wide?vm.mirrorOf(result):vm.mirrorOf((int)result);
    }
}
