package dev.jvmd.analyzer;
import java.util.*;
import javax.lang.model.SourceVersion;
/** Implements 4.2 and 4.9: UTF-16 source positions; semantic identities come from javac. */
public final class SourceText {
    /** Implements 4.9: zero-based position. */
    public record Position(int line,int character) { }
    /** Implements 4.9: half-open range. */
    public record Range(Position start,Position end) { }
    /** Implements 5: identifier span for binding probes. */
    public record Token(String text,int start,int end) { }
    private final String text;
    private final int[] lines;
    private List<Token> tokens;
    public SourceText(String text){this.text=text;var starts=new ArrayList<Integer>();starts.add(0);for(int i=0;i<text.length();i++)if(text.charAt(i)=='\n')starts.add(i+1);lines=starts.stream().mapToInt(Integer::intValue).toArray();}
    public String text(){return text;}
    public Position position(long offset){int point=(int)Math.max(0,Math.min(text.length(),offset));int line=Arrays.binarySearch(lines,point);if(line<0)line=-line-2;return new Position(line,point-lines[line]);}
    public int offset(int line,int character){if(line<0||line>=lines.length||character<0)throw new IllegalArgumentException("Position outside file");int offset=lines[line]+character;int end=line+1<lines.length?lines[line+1]-1:text.length();if(offset>end)throw new IllegalArgumentException("Position outside line");return offset;}
    public Range range(long start,long end){return new Range(position(start),position(Math.max(start,end)));}
    public Token tokenAt(int offset){var values=tokens();int index=lowerBound(offset);if(index<values.size()&&values.get(index).start()==offset)return values.get(index);if(index>0&&values.get(index-1).end()>offset)return values.get(index-1);return null;}
    public Token named(String name,int start,int end,boolean last){Token selected=null;for(var token:tokens(start,end))if(token.text().equals(name)){selected=token;if(!last)return token;}return selected;}
    private int lowerBound(int offset){var values=tokens();int low=0,high=values.size();while(low<high){int middle=(low+high)>>>1;if(values.get(middle).start()<offset)low=middle+1;else high=middle;}return low;}
    public List<Token> tokens(int start,int end){var values=tokens();int from=lowerBound(start),to=lowerBound(end);if(to>from&&values.get(to-1).end()>end)to--;return values.subList(from,Math.max(from,to));}
    public int nextCode(int start){int i=start;while(i<text.length()){if(Character.isWhitespace(text.charAt(i))){i++;continue;}if(text.startsWith("/*",i)){int end=text.indexOf("*/",i+2);i=end<0?text.length():end+2;continue;}if(text.startsWith("//",i)){int end=text.indexOf('\n',i+2);i=end<0?text.length():end+1;continue;}break;}return i;}
    /**
     * Implements 5: classify contextual keywords from a parsed unit, without using binding success.
     * The raw spans remain available to tolerant editing; actual identifier uses of the same words survive.
     */
    public List<Token> identifiers(com.sun.source.tree.CompilationUnitTree unit,com.sun.source.util.SourcePositions positions){
        var contextual=Set.of("exports","module","open","opens","provides","requires","to","transitive","uses","with","var","yield","record","sealed","permits","when","non");
        var names=new HashSet<Integer>();boolean[] erroneous={false};
        new com.sun.source.util.TreeScanner<Void,Void>(){
            int start(com.sun.source.tree.Tree tree){return (int)positions.getStartPosition(unit,tree);}
            int end(com.sun.source.tree.Tree tree){return (int)positions.getEndPosition(unit,tree);}
            void name(String name,int begin,int finish,boolean last){
                if(!contextual.contains(name)||begin<0||finish<begin)return;
                var token=named(name,begin,finish,last);if(token!=null)names.add(token.start());
            }
            @Override public Void visitIdentifier(com.sun.source.tree.IdentifierTree tree,Void unused){name(tree.getName().toString(),start(tree),end(tree),false);return super.visitIdentifier(tree,unused);}
            @Override public Void visitMemberSelect(com.sun.source.tree.MemberSelectTree tree,Void unused){name(tree.getIdentifier().toString(),start(tree),end(tree),true);return super.visitMemberSelect(tree,unused);}
            @Override public Void visitMemberReference(com.sun.source.tree.MemberReferenceTree tree,Void unused){name(tree.getName().toString(),start(tree),end(tree),true);return super.visitMemberReference(tree,unused);}
            @Override public Void visitVariable(com.sun.source.tree.VariableTree tree,Void unused){name(tree.getName().toString(),start(tree),tree.getInitializer()==null?end(tree):start(tree.getInitializer()),true);return super.visitVariable(tree,unused);}
            @Override public Void visitMethod(com.sun.source.tree.MethodTree tree,Void unused){
                int begin=start(tree);if(tree.getReturnType()!=null)begin=Math.max(begin,end(tree.getReturnType()));
                for(var type:tree.getTypeParameters())begin=Math.max(begin,end(type));
                name(tree.getName().toString(),begin,tree.getBody()==null?end(tree):start(tree.getBody()),false);return super.visitMethod(tree,unused);
            }
            @Override public Void visitClass(com.sun.source.tree.ClassTree tree,Void unused){name(tree.getSimpleName().toString(),Math.max(start(tree),end(tree.getModifiers())),end(tree),false);return super.visitClass(tree,unused);}
            @Override public Void visitTypeParameter(com.sun.source.tree.TypeParameterTree tree,Void unused){name(tree.getName().toString(),start(tree),end(tree),false);return super.visitTypeParameter(tree,unused);}
            @Override public Void visitLabeledStatement(com.sun.source.tree.LabeledStatementTree tree,Void unused){name(tree.getLabel().toString(),start(tree),start(tree.getStatement()),false);return super.visitLabeledStatement(tree,unused);}
            @Override public Void visitBreak(com.sun.source.tree.BreakTree tree,Void unused){if(tree.getLabel()!=null)name(tree.getLabel().toString(),start(tree),end(tree),true);return super.visitBreak(tree,unused);}
            @Override public Void visitContinue(com.sun.source.tree.ContinueTree tree,Void unused){if(tree.getLabel()!=null)name(tree.getLabel().toString(),start(tree),end(tree),true);return super.visitContinue(tree,unused);}
            @Override public Void visitErroneous(com.sun.source.tree.ErroneousTree tree,Void unused){erroneous[0]=true;return super.visitErroneous(tree,unused);}
        }.scan(unit,null);
        if(erroneous[0])return tokens();
        return tokens().stream().filter(token->!contextual.contains(token.text())||names.contains(token.start())).toList();
    }
    public List<Token> tokens(){
        if(tokens!=null)return tokens;var result=new ArrayList<Token>();
        for(int i=0;i<text.length();){
            char c=text.charAt(i);
            if(c=='/'&&i+1<text.length()&&text.charAt(i+1)=='/'){i+=2;while(i<text.length()&&text.charAt(i)!='\n')i++;continue;}
            if(c=='/'&&i+1<text.length()&&text.charAt(i+1)=='*'){int close=text.indexOf("*/",i+2);i=close<0?text.length():close+2;continue;}
            if(c=='"'||c=='\''){
                boolean block=c=='"'&&text.startsWith("\"\"\"",i);i+=block?3:1;
                while(i<text.length()){
                    if(text.charAt(i)=='\\'){i=Math.min(text.length(),i+2);continue;}
                    if(block&&text.startsWith("\"\"\"",i)){i+=3;break;}
                    if(!block&&text.charAt(i)==c){i++;break;}i++;
                }continue;
            }
            if(Character.isDigit(c)){i++;while(i<text.length()&&(Character.isJavaIdentifierPart(text.codePointAt(i))||text.charAt(i)=='.'))i+=Character.charCount(text.codePointAt(i));continue;}
            int cp=text.codePointAt(i);
            if(Character.isJavaIdentifierStart(cp)){
                int start=i;i+=Character.charCount(cp);while(i<text.length()&&Character.isJavaIdentifierPart(text.codePointAt(i)))i+=Character.charCount(text.codePointAt(i));String word=text.substring(start,i);
                if(!SourceVersion.isKeyword(word)&&!Set.of("true","false","null").contains(word))result.add(new Token(word,start,i));continue;
            }i+=Character.charCount(cp);
        }return tokens=List.copyOf(result);
    }
}
