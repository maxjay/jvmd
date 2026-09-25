package dev.jvmd.analyzer;

import dev.jvmd.index.SemanticReadView;
import dev.jvmd.index.SemanticType;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Detached completion-context resolver for Java shapes whose meaning can be proven from lexical
 * source facts plus maintained semantic state. It never enumerates candidates through javac.
 */
public final class CompletionContextResolver {
    @FunctionalInterface
    public interface TypeLookup {
        List<SemanticReadView.Symbol> find(String simpleOrQualifiedName)throws Exception;
    }

    public record Resolved(
            SemanticReadView.Symbol receiver,
            SemanticType receiverType,
            boolean staticReceiver,
            String packageName,
            String enclosingTypeId,
            boolean staticContext,
            Set<String> resolutionNames) {
        public Resolved {
            Objects.requireNonNull(receiver);
            Objects.requireNonNull(receiverType);
            packageName=Objects.requireNonNullElse(packageName,"");
            resolutionNames=Set.copyOf(resolutionNames);
        }
    }

    private record State(SemanticReadView.Symbol owner,SemanticType type,boolean staticReceiver) { }
    private record Declaration(String type,int start,int end) { }
    private static final Set<String> TYPE_KINDS=Set.of("class","interface","enum","record","annotation");
    private static final Pattern PACKAGE=Pattern.compile("(?m)\\bpackage\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");
    private static final Pattern IMPORT=Pattern.compile("(?m)\\bimport\\s+(?!static\\b)([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$*][\\w$*]*)*)\\s*;");
    private static final Pattern CLASS=Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");

    private CompletionContextResolver(){}

    public static Resolved resolve(String text,CompletionProbe.Shape probe,SemanticReadView view,TypeLookup lookup)throws Exception{
        Objects.requireNonNull(text);Objects.requireNonNull(probe);Objects.requireNonNull(view);Objects.requireNonNull(lookup);
        if(!probe.qualified())return null;
        int dot=previousCode(text,probe.selectorStart()-1);
        if(dot<0||text.charAt(dot)!='.')return null;
        String expression=receiverExpression(text,dot);
        if(expression==null||expression.isBlank())return null;
        var segments=segments(expression);if(segments.isEmpty())return null;

        String pkg=packageName(text);
        String enclosingName=enclosingTypeName(text,dot,pkg);
        SemanticReadView.Symbol enclosing=enclosingName==null?null:unique(resolveType(enclosingName,text,pkg,lookup));
        boolean staticContext=staticContext(text,dot);

        State state=base(segments.getFirst(),text,dot,pkg,enclosing,view,lookup);
        if(state==null)return null;
        for(int i=1;i<segments.size();i++){
            state=advance(state,segments.get(i),pkg,enclosing,view,lookup);
            if(state==null)return null;
        }
        return new Resolved(state.owner(),state.type(),state.staticReceiver(),pkg,
                enclosing==null?null:enclosing.id(),staticContext,resolutionNames(text,pkg));
    }

    private static State base(String segment,String text,int receiverEnd,String pkg,SemanticReadView.Symbol enclosing,
                              SemanticReadView view,TypeLookup lookup)throws Exception{
        if(segment.equals("this")){
            return enclosing==null?null:new State(enclosing,enclosing.semanticType(),false);
        }
        if(segment.equals("super")){
            if(enclosing==null)return null;
            var parents=view.directSupertypes(enclosing.id());
            if(parents.size()!=1)return null;
            var parent=view.symbol(parents.getFirst());
            return parent==null?null:new State(parent,parent.semanticType(),false);
        }
        if(segment.endsWith("()"))return null;
        Declaration declaration=declaration(text,segment,receiverEnd);
        if(declaration!=null){
            var type=unique(resolveType(declaration.type(),text,pkg,lookup));
            return type==null?null:new State(type,type.semanticType(),false);
        }
        var type=unique(resolveType(segment,text,pkg,lookup));
        return type==null?null:new State(type,type.semanticType(),true);
    }

    private static State advance(State state,String segment,String pkg,SemanticReadView.Symbol enclosing,
                                 SemanticReadView view,TypeLookup lookup)throws Exception{
        boolean call=segment.endsWith("()");
        String name=call?segment.substring(0,segment.length()-2):segment;
        if(name.isBlank())return null;
        var candidates=members(view,state.owner(),name,64);
        var matching=new LinkedHashMap<String,SemanticReadView.Symbol>();
        for(var member:candidates){
            if(!member.name().equals(name))continue;
            if(state.staticReceiver()&&!member.staticMember())continue;
            if(!accessible(member,pkg,enclosing,view))continue;
            if(call){
                if(!(member.semanticType() instanceof SemanticType.Executable executable)||!executable.parameters().isEmpty())continue;
            }else if(member.semanticType() instanceof SemanticType.Executable)continue;
            matching.putIfAbsent(member.resolution().symbolKey(),member);
        }
        if(matching.size()!=1)return null;
        var selected=matching.values().iterator().next();
        SemanticType next=call?((SemanticType.Executable)selected.semanticType()).returns():selected.semanticType();
        var owner=declaredOwner(next,lookup);
        return owner==null?null:new State(owner,next,false);
    }

    private static SemanticReadView.Symbol declaredOwner(SemanticType type,TypeLookup lookup)throws Exception{
        if(type instanceof SemanticType.Declared declared){
            var values=lookup.find(declared.name());
            if(values.isEmpty()&&!declared.symbolId().equals(declared.name()))values=lookup.find(declared.symbolId());
            return unique(values);
        }
        if(type instanceof SemanticType.Intersection intersection&&intersection.bounds().size()==1)
            return declaredOwner(intersection.bounds().getFirst(),lookup);
        return null;
    }
    private static String textForLookup(SemanticType type){return type instanceof SemanticType.Declared d?d.name():"";}

    private static List<SemanticReadView.Symbol> resolveType(String sourceName,String text,String pkg,TypeLookup lookup)throws Exception{
        String raw=sourceName.replaceAll("\\s+","").replace("[]","");
        if(raw.isBlank()||raw.equals("var")||raw.indexOf('<')>=0||primitive(raw))return List.of();
        String simple=raw.substring(raw.lastIndexOf('.')+1);
        var values=lookup.find(raw.indexOf('.')>=0?raw:simple);
        if(values.isEmpty()&&raw.indexOf('.')>=0)values=lookup.find(simple);
        if(raw.indexOf('.')>=0)
            return values.stream().filter(value->matchesTypeName(value,raw)).toList();

        var explicit=new LinkedHashSet<String>();var wildcard=new LinkedHashSet<String>();
        var imports=IMPORT.matcher(codeMask(text));
        while(imports.find()){
            String value=imports.group(1);
            if(value.endsWith(".*"))wildcard.add(value.substring(0,value.length()-2));
            else if(value.substring(value.lastIndexOf('.')+1).equals(simple))explicit.add(value);
        }
        return values.stream().filter(value->{
            if(!TYPE_KINDS.contains(value.kind())||!value.name().equals(simple))return false;
            String fqn=value.fqn();
            if(!explicit.isEmpty())return explicit.contains(fqn);
            String valuePackage=packageOf(fqn);
            return valuePackage.equals(pkg)||valuePackage.equals("java.lang")||wildcard.contains(valuePackage)
                    ||pkg.isEmpty()&&valuePackage.isEmpty();
        }).toList();
    }

    private static boolean matchesTypeName(SemanticReadView.Symbol value,String requested){
        return TYPE_KINDS.contains(value.kind())
                &&(requested.equals(value.fqn())||requested.equals(value.binaryKey())
                ||requested.equals(value.fqn().replace('$','.')));
    }

    private static Declaration declaration(String text,String name,int receiverEnd)throws Exception{
        String masked=codeMask(text.substring(0,receiverEnd));
        String identifier=Pattern.quote(name);
        var pattern=Pattern.compile("(?<![\\w$])([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*(?:\\s*\\[\\s*\\])*)\\s+"+identifier+"\\b");
        var matches=new ArrayList<Declaration>();var matcher=pattern.matcher(masked);
        while(matcher.find())matches.add(new Declaration(matcher.group(1),matcher.start(),matcher.end()));
        if(matches.isEmpty())return null;
        int[] braces=braceDepths(masked),parens=parenDepths(masked);int cursorDepth=depthAt(braces,Math.max(0,receiverEnd-1));
        for(int i=matches.size()-1;i>=0;i--){
            var value=matches.get(i);int depth=depthAt(braces,value.start());
            if(depth>cursorDepth||dropsBelow(braces,value.end(),receiverEnd,depth))continue;
            if(depthAt(parens,value.start())>0&&!parameterScopeContains(masked,braces,value.end(),receiverEnd,depth))continue;
            return value;
        }
        return null;
    }

    private static boolean parameterScopeContains(String source,int[] braces,int start,int cursor,int declarationDepth){
        for(int i=start;i<cursor;i++){
            if(source.charAt(i)=='{'&&depthAt(braces,i)==declarationDepth){
                int close=matchingBrace(source,i);return close<0||cursor<close;
            }
            if(source.charAt(i)==';'&&depthAt(braces,i)==declarationDepth)return false;
        }
        return false;
    }

    private static List<SemanticReadView.Symbol> members(SemanticReadView view,SemanticReadView.Symbol owner,String prefix,int limit)throws Exception{
        var result=new LinkedHashMap<String,SemanticReadView.Symbol>();
        var queue=new ArrayDeque<SemanticReadView.Symbol>();queue.add(owner);var seen=new HashSet<String>();
        while(!queue.isEmpty()&&result.size()<limit){
            var current=queue.removeFirst();if(!seen.add(current.id()))continue;
            String cursor=null;
            do{
                var page=view.members(current.id(),prefix,Math.max(1,limit-result.size()),cursor);
                for(var value:page.symbols())result.putIfAbsent(value.resolution().symbolKey(),value);
                cursor=page.cursor();
            }while(cursor!=null&&result.size()<limit);
            for(String parent:view.directSupertypes(current.id())){
                var symbol=view.symbol(parent);if(symbol!=null)queue.addLast(symbol);
            }
        }
        return List.copyOf(result.values());
    }

    public static boolean accessible(SemanticReadView.Symbol member,String callerPackage,SemanticReadView.Symbol enclosing,
                                      SemanticReadView view)throws Exception{
        var modifiers=member.modifiers();
        if(modifiers.contains("public"))return true;
        String ownerPackage=member.resolution().packageName();
        if(modifiers.contains("private"))
            return enclosing!=null&&Objects.equals(member.resolution().ownerKey(),enclosing.resolution().symbolKey());
        if(Objects.equals(ownerPackage,callerPackage))return true;
        if(!modifiers.contains("protected")||enclosing==null)return false;
        String wanted=member.resolution().ownerKey();
        var queue=new ArrayDeque<String>();queue.add(enclosing.id());var seen=new HashSet<String>();
        while(!queue.isEmpty()){
            String id=queue.removeFirst();if(!seen.add(id))continue;
            var symbol=view.symbol(id);
            if(symbol!=null&&(Objects.equals(symbol.resolution().symbolKey(),wanted)||Objects.equals(symbol.fqn(),wanted)))return true;
            queue.addAll(view.directSupertypes(id));
        }
        return false;
    }

    private static List<String> segments(String expression){
        var result=new ArrayList<String>();
        for(String part:expression.split("\\s*\\.\\s*")){
            String value=part.replaceAll("\\s+","");
            if(!value.matches("[A-Za-z_$][\\w$]*(?:\\(\\))?"))return List.of();
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static String receiverExpression(String text,int dot){
        String prefix=text.substring(0,dot);
        var matcher=Pattern.compile("((?:this|super|[A-Za-z_$][\\w$]*)(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*(?:\\s*\\(\\s*\\))?)*)\\s*$").matcher(codeMask(prefix));
        return matcher.find()?prefix.substring(matcher.start(1),matcher.end(1)):null;
    }

    private static String packageName(String text){
        var matcher=PACKAGE.matcher(codeMask(text));return matcher.find()?matcher.group(1):"";
    }

    private static Set<String> resolutionNames(String text,String pkg){
        var result=new LinkedHashSet<String>();if(!pkg.isBlank())result.add(pkg);
        var matcher=IMPORT.matcher(codeMask(text));
        while(matcher.find())result.add(matcher.group(1));
        result.add("java.lang");return Set.copyOf(result);
    }

    private static String enclosingTypeName(String text,int cursor,String pkg){
        String masked=codeMask(text);var matcher=CLASS.matcher(masked);String selected=null;int selectedStart=-1;
        while(matcher.find()&&matcher.start()<cursor){
            int open=masked.indexOf('{',matcher.end());if(open<0||open>=cursor)continue;
            int close=matchingBrace(masked,open);if(close>=0&&cursor>close)continue;
            if(open>selectedStart){selected=matcher.group(2);selectedStart=open;}
        }
        return selected==null?null:pkg.isBlank()?selected:pkg+"."+selected;
    }

    private static boolean staticContext(String text,int cursor){
        String masked=codeMask(text);int[] depths=braceDepths(masked);int depth=depthAt(depths,Math.max(0,cursor-1));
        for(int i=cursor-1;i>=0;i--){
            if(masked.charAt(i)=='{'&&depthAt(depths,i)==depth-1){
                int start=i-1;while(start>=0&&masked.charAt(start)!='}'&&masked.charAt(start)!=';'&&masked.charAt(start)!='{')start--;
                String header=masked.substring(start+1,i);
                return header.indexOf('(')>=0&&Pattern.compile("\\bstatic\\b").matcher(header).find();
            }
        }
        return false;
    }

    private static int previousCode(String text,int from){
        for(int i=Math.min(from,text.length()-1);i>=0;i--)if(!Character.isWhitespace(text.charAt(i)))return i;
        return -1;
    }

    private static String packageOf(String fqn){
        if(fqn==null)return "";int split=fqn.lastIndexOf('.');return split<0?"":fqn.substring(0,split);
    }
    private static boolean primitive(String value){
        return Set.of("boolean","byte","short","int","long","char","float","double","void").contains(value);
    }
    private static <T> T unique(Collection<T> values){return values.size()==1?values.iterator().next():null;}

    private static boolean dropsBelow(int[] depths,int start,int end,int floor){
        for(int i=Math.max(0,start);i<Math.min(end,depths.length);i++)if(depths[i]<floor)return true;
        return false;
    }
    private static int depthAt(int[] values,int index){return values.length==0?0:values[Math.max(0,Math.min(index,values.length-1))];}

    private static int[] braceDepths(String source){return depths(source,'{','}');}
    private static int[] parenDepths(String source){return depths(source,'(',')');}
    private static int[] depths(String source,char open,char close){
        int[] result=new int[Math.max(1,source.length())];int depth=0;
        for(int i=0;i<source.length();i++){result[i]=depth;char c=source.charAt(i);if(c==open)depth++;else if(c==close)depth=Math.max(0,depth-1);}
        if(source.isEmpty())result[0]=0;return result;
    }

    private static int matchingBrace(String source,int open){
        int depth=0;
        for(int i=open;i<source.length();i++){
            char c=source.charAt(i);if(c=='{')depth++;else if(c=='}'&&--depth==0)return i;
        }
        return -1;
    }

    /** Replace comments and literals with spaces while preserving offsets and line breaks. */
    private static String codeMask(String source){
        var out=new StringBuilder(source);int i=0;
        while(i<source.length()){
            char c=source.charAt(i);
            if(c=='/'&&i+1<source.length()&&source.charAt(i+1)=='/'){
                int end=source.indexOf('\n',i+2);if(end<0)end=source.length();
                blank(out,i,end);i=end;continue;
            }
            if(c=='/'&&i+1<source.length()&&source.charAt(i+1)=='*'){
                int end=source.indexOf("*/",i+2);end=end<0?source.length():end+2;
                blank(out,i,end);i=end;continue;
            }
            if(c=='"'||c=='\''){
                boolean block=c=='"'&&source.startsWith("\"\"\"",i);int end=i+(block?3:1);
                while(end<source.length()){
                    if(!block&&source.charAt(end)=='\\'){end=Math.min(source.length(),end+2);continue;}
                    if(block&&source.startsWith("\"\"\"",end)){end+=3;break;}
                    if(!block&&source.charAt(end)==c){end++;break;}end++;
                }
                blank(out,i,end);i=end;continue;
            }
            i++;
        }
        return out.toString();
    }
    private static void blank(StringBuilder out,int start,int end){
        for(int i=start;i<Math.min(end,out.length());i++)if(out.charAt(i)!='\n'&&out.charAt(i)!='\r')out.setCharAt(i,' ');
    }
}
