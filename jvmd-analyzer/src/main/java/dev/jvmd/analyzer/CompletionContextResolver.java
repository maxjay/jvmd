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
    public enum Access { ALLOWED, DENIED, UNKNOWN }
    public enum StaticContext { STATIC, INSTANCE, UNKNOWN }

    public record Resolved(
            SemanticReadView.Symbol receiver,
            SemanticType receiverType,
            boolean staticReceiver,
            String packageName,
            String enclosingTypeId,
            String enclosingTypeName,
            StaticContext staticContext,
            Set<String> resolutionNames) {
        public Resolved {
            Objects.requireNonNull(receiver);
            Objects.requireNonNull(receiverType);
            packageName=Objects.requireNonNullElse(packageName,"");
            enclosingTypeName=Objects.requireNonNullElse(enclosingTypeName,"");
            resolutionNames=Set.copyOf(resolutionNames);
        }
    }

    private record State(SemanticReadView.Symbol owner,SemanticType type,boolean staticReceiver) { }
    private record Declaration(String type,int start,int end,boolean field,boolean staticField) { }
    private static final Set<String> TYPE_KINDS=Set.of("class","interface","enum","record","annotation");
    private static final Set<String> NON_TYPE_WORDS=Set.of(
            "return","throw","new","case","yield","instanceof","this","super",
            "if","else","for","while","do","switch","try","catch","finally","synchronized",
            "break","continue","assert","class","interface","enum","record","extends","implements",
            "package","import","static","public","protected","private","final","abstract","native",
            "strictfp","transient","volatile");
    private static final Pattern PACKAGE=Pattern.compile("(?m)\\bpackage\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");
    private static final Pattern IMPORT=Pattern.compile("(?m)\\bimport\\s+(?!static\\b)([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$*][\\w$*]*)*)\\s*;");
    private static final Pattern CLASS=Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");

    private CompletionContextResolver(){}

    public static Resolved resolve(String text,CompletionProbe.Shape probe,SemanticReadView view,TypeLookup lookup)throws Exception{
        try(var trace=dev.jvmd.core.RequestScope.stage("completion.tier1.resolve")){
        Objects.requireNonNull(text);Objects.requireNonNull(probe);Objects.requireNonNull(view);Objects.requireNonNull(lookup);
        trace.count("calls",1);trace.count("source_chars",text.length());
        if(!probe.qualified())return null;
        int dot=previousCode(text,probe.selectorStart()-1);
        if(dot<0||text.charAt(dot)!='.')return null;
        String expression=receiverExpression(text,dot);
        if(expression==null||expression.isBlank())return null;
        var segments=segments(expression);if(segments.isEmpty())return null;

        String pkg=packageName(text);
        String enclosingName=enclosingTypeName(text,dot,pkg);
        SemanticReadView.Symbol enclosing=enclosingName==null?null:unique(resolveType(enclosingName,text,pkg,lookup));
        StaticContext staticContext=staticContext(text,dot);

        State state=base(segments.getFirst(),text,dot,pkg,enclosing,staticContext,view,lookup);
        if(state==null)return null;
        for(int i=1;i<segments.size();i++){
            state=advance(state,segments.get(i),pkg,enclosing,enclosingName,view,lookup);
            if(state==null)return null;
        }
        return new Resolved(state.owner(),state.type(),state.staticReceiver(),pkg,
                enclosing==null?null:enclosing.id(),enclosingName,staticContext,resolutionNames(text,pkg));
        }
    }

    private static State base(String segment,String text,int receiverEnd,String pkg,SemanticReadView.Symbol enclosing,
                              StaticContext staticContext,SemanticReadView view,TypeLookup lookup)throws Exception{
        if(segment.equals("this")){
            return enclosing==null||staticContext!=StaticContext.INSTANCE?null:new State(enclosing,enclosing.semanticType(),false);
        }
        if(segment.equals("super")){
            if(enclosing==null||staticContext!=StaticContext.INSTANCE)return null;
            var parents=view.directSupertypes(enclosing.id());
            if(parents.size()!=1)return null;
            var parent=view.symbol(parents.getFirst());
            return parent==null?null:new State(parent,parent.semanticType(),false);
        }
        if(segment.endsWith("()"))return null;
        Declaration declaration=declaration(text,segment,receiverEnd);
        if(declaration!=null){
            if(staticContext==StaticContext.UNKNOWN)return null;
            if(declaration.field()&&staticContext==StaticContext.STATIC&&!declaration.staticField())return null;
            var type=unique(resolveType(declaration.type(),text,pkg,lookup));
            return type==null?null:new State(type,type.semanticType(),false);
        }
        var type=unique(resolveType(segment,text,pkg,lookup));
        return type==null?null:new State(type,type.semanticType(),true);
    }

    private static State advance(State state,String segment,String pkg,SemanticReadView.Symbol enclosing,String enclosingName,
                                 SemanticReadView view,TypeLookup lookup)throws Exception{
        boolean call=segment.endsWith("()");
        String name=call?segment.substring(0,segment.length()-2):segment;
        if(name.isBlank())return null;
        var scan=members(view,state.owner(),name,64);
        if(!scan.complete())return null;
        var matching=new LinkedHashMap<String,SemanticReadView.Symbol>();
        for(var member:scan.symbols()){
            if(!member.name().equals(name))continue;
            if(state.staticReceiver()&&!member.staticMember())continue;
            var access=access(member,pkg,enclosing,enclosingName);
            if(access==Access.UNKNOWN)return null;
            if(access==Access.DENIED)continue;
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
    private static List<SemanticReadView.Symbol> resolveType(String sourceName,String text,String pkg,TypeLookup lookup)throws Exception{
        String raw=sourceName.replaceAll("\\s+","").replace("[]","");
        if(raw.isBlank()||raw.equals("var")||raw.indexOf('<')>=0||primitive(raw))return List.of();
        String simple=raw.substring(raw.lastIndexOf('.')+1);
        var values=lookup.find(raw.indexOf('.')>=0?raw:simple);
        if(values.isEmpty()&&raw.indexOf('.')>=0)values=lookup.find(simple);
        if(raw.indexOf('.')>=0)
            return values.stream().filter(value->matchesTypeName(value,raw)).toList();

        var explicit=new LinkedHashSet<String>();var wildcard=new LinkedHashSet<String>();
        var imports=matcher(IMPORT,codeMask(text));
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
        var matches=new ArrayList<Declaration>();var matcher=matcher(pattern,masked);
        while(matcher.find()){
            String candidate=matcher.group(1).replaceAll("\\s+","");
            String first=candidate.replace("[]","");int dot=first.indexOf('.');if(dot>=0)first=first.substring(0,dot);
            if(NON_TYPE_WORDS.contains(first)||primitive(first))continue;
            matches.add(new Declaration(matcher.group(1),matcher.start(),matcher.end(),false,false));
        }
        if(matches.isEmpty())return null;
        int[] braces=braceDepths(masked),parens=parenDepths(masked);int cursorDepth=depthAt(braces,Math.max(0,receiverEnd-1));
        int classOpen=enclosingTypeOpen(masked,receiverEnd);
        int classBodyDepth=classOpen<0?-1:depthAt(braces,classOpen)+1;
        for(int i=matches.size()-1;i>=0;i--){
            var value=matches.get(i);
            if(!declarationType(value.type()))continue;
            int depth=depthAt(braces,value.start());
            if(depth>cursorDepth||dropsBelow(braces,value.end(),receiverEnd,depth))continue;
            if(depthAt(parens,value.start())>0&&!parameterScopeContains(masked,braces,value.end(),receiverEnd,depth))continue;
            boolean field=classBodyDepth>=0&&depth==classBodyDepth&&depthAt(parens,value.start())==0;
            boolean statik=field&&matcher(Pattern.compile("\\bstatic\\b"),memberPrefix(masked,classOpen,value.start())).find();
            return new Declaration(value.type(),value.start(),value.end(),field,statik);
        }
        return null;
    }

    private static boolean declarationType(String sourceType){
        String value=sourceType.replaceAll("\\s+","").replace("[]","");
        int dot=value.indexOf('.');String head=dot<0?value:value.substring(0,dot);
        return !head.isBlank()&&!javax.lang.model.SourceVersion.isKeyword(head)
                &&!Set.of("var","yield","record","sealed","permits","non").contains(head);
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

    private record MemberScan(List<SemanticReadView.Symbol> symbols,boolean complete) {
        MemberScan { symbols=List.copyOf(symbols); }
    }
    /**
     * Read only the exact-name group needed for chained resolution. The semantic member view is
     * ordered by name, so observing a later name proves the exact group is exhausted. Hitting the
     * bound while a cursor remains is uncertainty, never evidence that the first match is unique.
     */
    private static MemberScan members(SemanticReadView view,SemanticReadView.Symbol owner,String name,int limit)throws Exception{
        var result=new LinkedHashMap<String,SemanticReadView.Symbol>();
        var queue=new ArrayDeque<SemanticReadView.Symbol>();queue.add(owner);var seen=new HashSet<String>();
        while(!queue.isEmpty()){
            var current=queue.removeFirst();if(!seen.add(current.id()))continue;
            String cursor=null;boolean exhausted=false;
            do{
                int remaining=Math.max(1,limit-result.size());
                var page=view.members(current.id(),name,remaining,cursor);
                for(var value:page.symbols()){
                    int compared=value.name().compareTo(name);
                    if(compared==0)result.putIfAbsent(value.resolution().symbolKey(),value);
                    else if(compared>0){exhausted=true;break;}
                }
                if(exhausted||page.cursor()==null)break;
                if(result.size()>=limit)return new MemberScan(result.values().stream().toList(),false);
                cursor=page.cursor();
            }while(true);
            if(!exhausted&&cursor!=null&&result.size()>=limit)
                return new MemberScan(result.values().stream().toList(),false);
            for(String parent:view.directSupertypes(current.id())){
                var symbol=view.symbol(parent);if(symbol!=null)queue.addLast(symbol);
            }
        }
        return new MemberScan(result.values().stream().toList(),true);
    }

    /**
     * Detached Tier-1 access proof. Context-sensitive cases are deliberately UNKNOWN so javac
     * remains authoritative for protected qualification, private nestmates and module semantics.
     */
    public static Access access(SemanticReadView.Symbol member,String callerPackage,SemanticReadView.Symbol enclosing){
        return access(member,callerPackage,enclosing,enclosing==null?null:enclosing.fqn());
    }

    /**
     * Lexical enclosing type name is sufficient to prove that two declarations belong to distinct
     * nests even before the caller type itself has entered maintained semantic state.
     */
    public static Access access(SemanticReadView.Symbol member,String callerPackage,SemanticReadView.Symbol enclosing,String enclosingName){
        var modifiers=member.modifiers();
        if(modifiers.contains("public"))return Access.ALLOWED;
        String ownerPackage=member.resolution().packageName();
        if(modifiers.contains("private")){
            if(!Objects.equals(ownerPackage,callerPackage))return Access.DENIED;
            String caller=enclosing!=null?enclosing.fqn():Objects.requireNonNullElse(enclosingName,"");
            if(!caller.isBlank()&&member.resolution().ownerKey()!=null
                    &&!nestHost(member.resolution().ownerKey()).equals(nestHost(caller)))return Access.DENIED;
            return Access.UNKNOWN;
        }
        if(Objects.equals(ownerPackage,callerPackage))return Access.ALLOWED;
        if(modifiers.contains("protected"))return Access.UNKNOWN;
        return Access.DENIED;
    }

    private static String nestHost(String binaryName){
        String value=Objects.requireNonNullElse(binaryName,"");
        int nested=value.indexOf(36);return nested<0?value:value.substring(0,nested);
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

    private static java.util.regex.Matcher matcher(Pattern pattern,CharSequence input){
        dev.jvmd.core.RequestScope.count("regex_matchers",1);return pattern.matcher(input);
    }
    private static String receiverExpression(String text,int dot){
        String prefix=text.substring(0,dot);
        var matcher=matcher(Pattern.compile("((?:this|super|[A-Za-z_$][\\w$]*)(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*(?:\\s*\\(\\s*\\))?)*)\\s*$"),codeMask(prefix));
        return matcher.find()?prefix.substring(matcher.start(1),matcher.end(1)):null;
    }

    private static String packageName(String text){
        var matcher=matcher(PACKAGE,codeMask(text));return matcher.find()?matcher.group(1):"";
    }

    private static Set<String> resolutionNames(String text,String pkg){
        var result=new LinkedHashSet<String>();if(!pkg.isBlank())result.add(pkg);
        var matcher=IMPORT.matcher(codeMask(text));
        while(matcher.find())result.add(matcher.group(1));
        result.add("java.lang");return Set.copyOf(result);
    }

    private static String enclosingTypeName(String text,int cursor,String pkg){
        String masked=codeMask(text);var matcher=matcher(CLASS,masked);String selected=null;int selectedStart=-1;
        while(matcher.find()&&matcher.start()<cursor){
            int open=masked.indexOf('{',matcher.end());if(open<0||open>=cursor)continue;
            int close=matchingBrace(masked,open);if(close>=0&&cursor>close)continue;
            if(open>selectedStart){selected=matcher.group(2);selectedStart=open;}
        }
        return selected==null?null:pkg.isBlank()?selected:pkg+"."+selected;
    }

    private static StaticContext staticContext(String text,int cursor){
        String masked=codeMask(text);int classOpen=enclosingTypeOpen(masked,cursor);
        if(classOpen<0)return StaticContext.UNKNOWN;
        int[] depths=braceDepths(masked);int classBodyDepth=depthAt(depths,classOpen)+1;
        int cursorDepth=depthAt(depths,Math.max(0,cursor-1));

        // Class-level field initializer.
        if(cursorDepth==classBodyDepth){
            String segment=memberPrefix(masked,classOpen,cursor);
            int equals=segment.indexOf('=');
            if(equals>=0){
                String declaration=segment.substring(0,equals);
                return matcher(Pattern.compile("\\bstatic\\b"),declaration).find()
                        ?StaticContext.STATIC:StaticContext.INSTANCE;
            }
        }

        // Walk enclosing blocks outward; control-flow/lambda blocks defer to their parent context.
        var opens=new ArrayDeque<Integer>();
        for(int i=classOpen+1;i<Math.min(cursor,masked.length());i++){
            char ch=masked.charAt(i);
            if(ch=='{')opens.push(i);
            else if(ch=='}'&&!opens.isEmpty())opens.pop();
        }
        for(int open:opens){
            if(depthAt(depths,open)!=classBodyDepth)continue;
            String header=memberPrefix(masked,classOpen,open).trim();
            if(header.equals("static"))return StaticContext.STATIC;
            if(header.isEmpty())return StaticContext.INSTANCE;
            if(header.indexOf(')')>=0&&!controlHeader(header))
                return matcher(Pattern.compile("\\bstatic\\b"),header).find()
                        ?StaticContext.STATIC:StaticContext.INSTANCE;
        }
        return StaticContext.UNKNOWN;
    }

    private static boolean controlHeader(String header){
        String value=header.stripLeading();
        return matcher(Pattern.compile("^(if|for|while|switch|catch|try|else|do|synchronized)\\b"),value).find()
                ||value.contains("->");
    }

    private static int enclosingTypeOpen(String masked,int cursor){
        var matcher=CLASS.matcher(masked);int selected=-1;
        while(matcher.find()&&matcher.start()<cursor){
            int open=masked.indexOf('{',matcher.end());if(open<0||open>=cursor)continue;
            int close=matchingBrace(masked,open);if(close>=0&&cursor>close)continue;
            if(open>selected)selected=open;
        }
        return selected;
    }

    private static String memberPrefix(String source,int classOpen,int end){
        int start=Math.max(0,classOpen+1);
        int[] depths=braceDepths(source);int classBodyDepth=depthAt(depths,classOpen)+1;
        for(int i=Math.min(end-1,source.length()-1);i>classOpen;i--){
            char ch=source.charAt(i);
            if((ch==';'||ch=='}'||ch=='{')&&depthAt(depths,i)<=classBodyDepth){start=i+1;break;}
        }
        return source.substring(Math.min(start,end),end);
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
        dev.jvmd.core.RequestScope.count("mask_calls",1);dev.jvmd.core.RequestScope.count("masked_chars",source.length());
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
