package dev.jvmd.analyzer;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.Path;
import java.util.*;
import javax.tools.*;

/** Implements 4.2: focused source preserving every UTF-16 offset, line and constructor invocation. */
public final class Focusing {
    /** Implements 4.2: detached focus output, suitable for content/member-keyed caching. */
    public record Result(String source,String member,int start,int end,List<Span> replaced) { }
    /** Implements 4.2: original body intervals, with identical offsets in focused source. */
    public record Span(int start,int end) { }
    private record Body(int start,int end,int preserve,String name,int declarationStart,int declarationEnd) { }
    public Result focus(Path path,String source,int cursor)throws Exception {
        var compiler=ToolProvider.getSystemJavaCompiler();var bodies=new ArrayList<Body>();
        try(var manager=compiler.getStandardFileManager(null,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8)){
            var task=(JavacTask)compiler.getTask(null,manager,d->{},List.of("-proc:none","--should-stop=ifError=FLOW"),null,List.of(Parser.source(path.toUri(),source)));
            for(var unit:task.parse()){
                var positions=Trees.instance(task).getSourcePositions();
                new TreePathScanner<Void,Void>(){
                    @Override public Void visitMethod(MethodTree method,Void unused){
                        if(method.getBody()!=null){
                            int start=(int)positions.getStartPosition(unit,method.getBody()),end=(int)positions.getEndPosition(unit,method.getBody());
                            int declarationStart=(int)positions.getStartPosition(unit,method),declarationEnd=(int)positions.getEndPosition(unit,method);int preserve=start+1;
                            if(method.getReturnType()==null&&!method.getBody().getStatements().isEmpty()){
                                var first=method.getBody().getStatements().getFirst();
                                if(first instanceof ExpressionStatementTree expression&&expression.getExpression() instanceof MethodInvocationTree invocation
                                        && Set.of("super","this").contains(invocation.getMethodSelect().toString()))preserve=(int)positions.getEndPosition(unit,first);
                            }
                            if(start>=0&&end>start&&end<=source.length())bodies.add(new Body(start,end,preserve,method.getName()+"@"+declarationStart,declarationStart,declarationEnd));
                        }
                        return super.visitMethod(method,unused);
                    }
                }.scan(unit,null);
            }
        }
        var focused=bodies.stream().filter(b->cursor>=b.declarationStart()&&cursor<b.declarationEnd()).min(Comparator.comparingInt(b->b.end()-b.start())).orElse(null);
        char[] output=source.toCharArray();var replaced=new ArrayList<Span>();
        // Outer bodies first. Nested bodies inside an already-erased outer method need no second edit.
        for(var body:bodies.stream().sorted(Comparator.comparingInt(Body::start).thenComparing(Comparator.comparingInt(Body::end).reversed())).toList()){
            if(focused!=null&&body.start()<=focused.start()&&body.end()>=focused.end())continue;
            if(replaced.stream().anyMatch(s->body.start()>=s.start()&&body.end()<=s.end()))continue;
            int position=room(source,body.preserve(),body.end()-1,11);
            // Tiny bodies cost less than a throw statement and cannot hold it without shifting positions.
            if(position<0)continue;
            for(int i=body.preserve();i<body.end()-1;i++)if(output[i]!='\n'&&output[i]!='\r')output[i]=' ';
            "throw null;".getChars(0,11,output,position);replaced.add(new Span(body.start(),body.end()));
        }
        return new Result(new String(output),focused==null?"declarations":focused.name(),focused==null?0:focused.declarationStart(),focused==null?source.length():focused.declarationEnd(),List.copyOf(replaced));
    }
    private static int room(String text,int start,int end,int length){int run=0;for(int i=start;i<end;i++){if(text.charAt(i)=='\n'||text.charAt(i)=='\r')run=0;else if(++run>=length)return i-length+1;}return -1;}
}
