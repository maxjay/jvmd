package dev.jvmd.analyzer;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import dev.jvmd.index.DocMarkdown;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;

/** Implements 4.9: detached scope completions and invocation signatures from public javac APIs. */
public final class EditorQueries {
    public static final String MARKER="__jvmd_completion__";
    private EditorQueries() { }
    private static boolean accessible(Trees trees,Scope scope,Element element,DeclaredType owner){
        if(scope==null)return !element.getModifiers().contains(Modifier.PRIVATE);
        if(element instanceof TypeElement type)return trees.isAccessible(scope,type);
        return !(element.getEnclosingElement() instanceof TypeElement)||owner!=null&&trees.isAccessible(scope,element,owner);
    }
    private static Map<String,Object> row(JavacTask task,SymbolIdentity identity,Element element,DeclaredType receiver){
        var value=new LinkedHashMap<String,Object>();value.put("scip",identity.scip(element));value.put("name",identity.displayName(element));
        value.put("name_path",identity.namePath(element));value.put("kind",SymbolIdentity.kind(element));value.put("signature",identity.signature(element));
        String sourceFile=identity.sourceFile(element);if(sourceFile!=null)value.put("source_file",sourceFile);
        value.put("modifiers",element.getModifiers().stream().map(Object::toString).sorted().toList());
        TypeMirror member=element.asType();if(receiver!=null&&element.getEnclosingElement() instanceof TypeElement)member=task.getTypes().asMemberOf(receiver,element);
        if(element instanceof ExecutableElement method&&member instanceof ExecutableType executable){
            var parameters=new ArrayList<Map<String,Object>>();var label=new StringBuilder(identity.displayName(element)).append('(');
            for(int i=0;i<method.getParameters().size();i++){
                if(i>0)label.append(", ");String type=executable.getParameterTypes().get(i).toString();
                if(method.isVarArgs()&&i==method.getParameters().size()-1&&type.endsWith("[]"))type=type.substring(0,type.length()-2)+"...";
                int start=label.length();label.append(type).append(' ').append(method.getParameters().get(i).getSimpleName());parameters.add(Map.of("label",List.of(start,label.length())));
            }label.append(')');if(method.getKind()!=ElementKind.CONSTRUCTOR)label.append(": ").append(executable.getReturnType());
            value.put("label",label.toString());value.put("parameters",parameters);
        }else value.put("label",identity.displayName(element)+": "+member);
        value.put("doc",DocMarkdown.summary(task.getElements().getDocComment(element)));
        return value;
    }

    public static Map<String,Object> signatures(JavacTask task,List<CompilationUnitTree> units,SymbolIdentity identity,int cursor){
        var trees=Trees.instance(task);TreePath[] selected={null};long[] width={Long.MAX_VALUE};
        for(var unit:units)new TreePathScanner<Void,Void>(){
            private void consider(Tree node,Tree selector){
                long start=trees.getSourcePositions().getEndPosition(unit,selector),end=trees.getSourcePositions().getEndPosition(unit,node);
                if(start>=0&&start<cursor&&cursor<=end&&end-start<width[0]){selected[0]=getCurrentPath();width[0]=end-start;}
            }
            @Override public Void visitMethodInvocation(MethodInvocationTree node,Void unused){consider(node,node.getMethodSelect());return super.visitMethodInvocation(node,unused);}
            @Override public Void visitNewClass(NewClassTree node,Void unused){consider(node,node.getIdentifier());return super.visitNewClass(node,unused);}
        }.scan(unit,null);
        if(selected[0]==null)return Map.of("signatures",List.of(),"activeSignature",0,"activeParameter",0);
        var path=selected[0];var scope=trees.getScope(path);var unit=path.getCompilationUnit();List<? extends ExpressionTree> arguments;String name;DeclaredType receiver=null;boolean constructor=false,staticOnly=false;Element resolved=trees.getElement(path);
        if(path.getLeaf() instanceof MethodInvocationTree invocation){
            arguments=invocation.getArguments();var selector=invocation.getMethodSelect();
            if(selector instanceof MemberSelectTree member){name=member.getIdentifier().toString();var qualifier=new TreePath(new TreePath(path,selector),member.getExpression());var mirror=trees.getTypeMirror(qualifier);if(mirror instanceof DeclaredType declared)receiver=declared;staticOnly=trees.getElement(qualifier) instanceof TypeElement;}
            else{name=selector.toString();if(scope!=null&&scope.getEnclosingClass()!=null)receiver=(DeclaredType)scope.getEnclosingClass().asType();}
        }else{
            var creation=(NewClassTree)path.getLeaf();arguments=creation.getArguments();name="<init>";constructor=true;
            var mirror=trees.getTypeMirror(new TreePath(path,creation.getIdentifier()));if(mirror instanceof DeclaredType declared)receiver=declared;
        }
        var candidates=new LinkedHashSet<Element>();if(receiver!=null)candidates.addAll(constructor?receiver.asElement().getEnclosedElements():task.getElements().getAllMembers((TypeElement)receiver.asElement()));
        if(!constructor&&!(path.getLeaf() instanceof MethodInvocationTree call&&call.getMethodSelect() instanceof MemberSelectTree))for(Scope current=scope;current!=null;current=current.getEnclosingScope())current.getLocalElements().forEach(candidates::add);
        var result=new ArrayList<Map<String,Object>>();int active=0,parameter=0;
        for(var argument:arguments){long end=trees.getSourcePositions().getEndPosition(unit,argument);if(end>=0&&cursor>end)parameter++;else break;}
        if(!arguments.isEmpty()){long lastEnd=trees.getSourcePositions().getEndPosition(unit,arguments.getLast());if(cursor==lastEnd)parameter=arguments.size()-1;}
        for(var element:candidates)if(element instanceof ExecutableElement method&&method.getSimpleName().contentEquals(name)){
            if(staticOnly&&!method.getModifiers().contains(Modifier.STATIC)||!accessible(trees,scope,method,receiver))continue;
            try{var value=row(task,identity,method,receiver);if(method.equals(resolved))active=result.size();value.put("activeParameter",Math.max(0,Math.min(parameter,method.getParameters().size()-1)));result.add(value);}catch(IllegalArgumentException unresolved){/* Retain only signatures javac can identify. */}
        }
        return Map.of("signatures",result,"activeSignature",active,"activeParameter",result.isEmpty()?0:result.get(active).get("activeParameter"));
    }
}
