package dev.jvmd.runtime;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import dev.jvmd.core.*;
import java.lang.classfile.Signature;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.tools.*;

/** Implements 4.7: compile a synthetic expression method in the declaring source's lexical context. */
public final class EvaluationCompiler {
    /** Implements 4.7: detached local metadata; generic signatures preserve lambda type inference. */
    public record Local(String name,String signature,String genericSignature) {
        public String javaType(){return JavaTypes.type(Signature.parseFrom(genericSignature==null?signature:genericSignature));}
    }
    /** Implements 4.7: only evaluator bytecode is loaded; the declaring class is never replaced to eval. */
    public record Plan(String evaluator,String bootstrap,String sourceHash,double compileMillis,List<Local> locals) { }
    private EvaluationCompiler() { }
    public static Plan compile(Path compilerHome,Path source,String declaring,String method,int line,boolean instance,List<Local> locals,String expression,List<Path> classpath,List<Path> roots,List<String> options)throws Exception{
        if(expression==null||expression.isBlank()||expression.length()>8192)throw RpcException.invalid("An expression of 1..8192 characters is required");
        int slots=1;for(var local:locals){if(!javax.lang.model.SourceVersion.isIdentifier(local.name())||javax.lang.model.SourceVersion.isKeyword(local.name()))throw unsupported("Local has no Java source name: "+local.name());slots+=Set.of("J","D").contains(local.signature())?2:1;}
        if(locals.size()>200||slots>254)throw unsupported("Frame locals exceed the JVM method parameter limit");
        String original=Files.readString(source),hash=Hashing.sha256(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String suffix=UUID.randomUUID().toString().replace("-",""),evaluator="__JvmdEval"+suffix,bootstrap="__JvmdLookup"+suffix,resultName="__jvmdResult"+suffix;
        var compiler=ToolProvider.getSystemJavaCompiler();int[] insertion={-1};String[] typeName={null},parameters={""},pkg={""};
        try(var manager=compiler.getStandardFileManager(null,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8)){
            var input=new SimpleJavaFileObject(source.toUri(),JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignored){return original;}};
            var task=(JavacTask)compiler.getTask(new java.io.StringWriter(),manager,null,List.of("-proc:none"),null,List.of(input));var unit=task.parse().iterator().next();var positions=Trees.instance(task).getSourcePositions();pkg[0]=unit.getPackageName()==null?"":unit.getPackageName().toString();
            new TreeScanner<Void,String>(){
                @Override public Void visitClass(ClassTree node,String parent){
                    String name=node.getSimpleName().toString();if(name.isEmpty())return null;String binary=parent==null?(pkg[0].isEmpty()?name:pkg[0]+"."+name):parent+"$"+name;
                    if(binary.equals(declaring)){insertion[0]=(int)positions.getEndPosition(unit,node)-1;typeName[0]=name;}
                    return super.visitClass(node,binary);
                }
                @Override public Void visitMethod(MethodTree node,String owner){
                    if(declaring.equals(owner)&&node.getName().contentEquals(method)){
                        long begin=positions.getStartPosition(unit,node),end=positions.getEndPosition(unit,node);
                        if(begin>=0&&end>=begin&&unit.getLineMap().getLineNumber(begin)<=line&&unit.getLineMap().getLineNumber(end)>=line)
                            parameters[0]=node.getTypeParameters().isEmpty()?"":node.getTypeParameters().stream().map(Object::toString).collect(java.util.stream.Collectors.joining(",","<","> "));
                    }
                    return super.visitMethod(node,owner);
                }
            }.scan(unit,null);
        }
        if(insertion[0]<0||insertion[0]>=original.length()||original.charAt(insertion[0])!='}')throw unsupported("Declaring source type is unavailable or anonymous: "+declaring);
        String rewritten=rewrite(expression,instance?typeName[0]:null);
        String arguments=locals.stream().map(local->local.javaType()+" "+local.name()).collect(java.util.stream.Collectors.joining(","));
        String returned=locals.isEmpty()?"":","+locals.stream().map(Local::name).collect(java.util.stream.Collectors.joining(","));
        String header="\npublic "+(instance?"":"static ")+"final class "+evaluator+" {\npublic "+parameters[0]+"Object[] evaluate("+arguments+") throws Throwable {\n";
        String ending="\nreturn new Object[]{"+resultName+returned+"};\n}\n}\n";
        Path temporary=Files.createTempDirectory("jvmd-eval-source-");
        try{
            Path shadow=temporary.resolve(source.getFileName()),bridge=temporary.resolve(bootstrap+".java");
            String body="Object "+resultName+"=("+rewritten+");";
            Files.writeString(shadow,original.substring(0,insertion[0])+header+body+ending+original.substring(insertion[0]));
            Files.writeString(bridge,(pkg[0].isEmpty()?"":"package "+pkg[0]+";\n")+"public final class "+bootstrap+" { public static java.lang.invoke.MethodHandles.Lookup lookup(){return java.lang.invoke.MethodHandles.lookup();} }");
            RuntimeCompiler.Compilation compiled;long started=System.nanoTime();
            try{compiled=RuntimeCompiler.compile(compilerHome,temporary,List.of(shadow,bridge),classpath,roots,options,Duration.ofSeconds(60));}
            catch(RpcException error){
                // A void invocation is a Java statement expression. Retry only compilation,
                // before any target execution, and let javac validate the statement form too.
                if(error.code()!=-32004||!String.valueOf(error.data()).contains("void"))throw error;
                Files.writeString(shadow,original.substring(0,insertion[0])+header+rewritten+"; Object "+resultName+"=null;"+ending+original.substring(insertion[0]));
                compiled=RuntimeCompiler.compile(compilerHome,temporary,List.of(shadow,bridge),classpath,roots,options,Duration.ofSeconds(60));
            }
            String key=declaring.replace('.','/')+"$"+evaluator,bootstrapKey=(pkg[0].isEmpty()?"":pkg[0].replace('.','/')+"/")+bootstrap;
            byte[] bytes=compiled.classes().get(key+".class"),lookup=compiled.classes().get(bootstrapKey+".class");if(bytes==null||lookup==null)throw unsupported("Compiler omitted evaluator bytecode");
            if(compiled.classes().keySet().stream().anyMatch(name->name.startsWith(key+"$")))throw unsupported("Expressions declaring anonymous or local classes cannot be loaded as one evaluator");
            if(bytes.length>6*1024*1024||lookup.length>512*1024)throw unsupported("Evaluator bytecode exceeds the helper capacity");
            if(!Hashing.sha256(source).equals(hash))throw RpcException.invalid("Declaring source changed during evaluation compilation");
            return new Plan(Base64.getEncoder().encodeToString(bytes),Base64.getEncoder().encodeToString(lookup),hash,(System.nanoTime()-started)/1e6,List.copyOf(locals));
        }finally{try(var files=Files.walk(temporary)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
    private static String rewrite(String expression,String owner)throws Exception{
        String prefix="class __JvmdInput { Object evaluate(){return (",source=prefix+expression+");} }";var edits=new ArrayList<Integer>();
        var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8)){
            var input=new SimpleJavaFileObject(java.net.URI.create("string:///__JvmdInput.java"),JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignored){return source;}};
            var task=(JavacTask)compiler.getTask(new java.io.StringWriter(),manager,diagnostics,List.of("-proc:none"),null,List.of(input));var unit=task.parse().iterator().next();
            if(diagnostics.getDiagnostics().stream().anyMatch(d->d.getKind()==Diagnostic.Kind.ERROR)||unit.getTypeDecls().size()!=1)throw RpcException.invalid("Invalid Java expression");
            var type=(ClassTree)unit.getTypeDecls().getFirst();
            if(type.getMembers().size()!=1||!(type.getMembers().getFirst() instanceof MethodTree method)||method.getBody().getStatements().size()!=1||!(method.getBody().getStatements().getFirst() instanceof ReturnTree returned))throw RpcException.invalid("Invalid Java expression");
            var positions=Trees.instance(task).getSourcePositions();
            new TreeScanner<Void,Void>(){
                @Override public Void visitClass(ClassTree node,Void unused){throw unsupported("Anonymous and local class declarations are not supported inside a compiled expression");}
                @Override public Void visitIdentifier(IdentifierTree node,Void unused){
                    if(node.getName().contentEquals("this")||node.getName().contentEquals("super")){
                        if(owner==null)throw RpcException.invalid("this and super are unavailable in a static frame");
                        edits.add((int)positions.getStartPosition(unit,node)-prefix.length());
                    }return super.visitIdentifier(node,unused);
                }
            }.scan(returned.getExpression(),null);
        }
        var result=new StringBuilder(expression);edits.sort(Comparator.reverseOrder());for(int edit:edits)result.insert(edit,owner+".");return result.toString();
    }
    private static RpcException unsupported(String reason){return new RpcException(-32003,"unsupported_capability",Map.of("capability","eval_tier_2","reason",reason));}
}
