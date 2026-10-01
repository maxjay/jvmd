package dev.jvmd.analyzer;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import dev.jvmd.core.Hashing;
import dev.jvmd.core.Json;
import dev.jvmd.index.SemanticCompleteness;
import dev.jvmd.index.SemanticMemoStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import javax.tools.*;

/**
 * S0 syntactic namespace: {@code S0 = ParseNamespace(content)} (architecture §85–86).
 *
 * The result is a pure function of the source bytes, the parser semantics and the language mode, so
 * its LOCAL memo key is {@code H(contentIdentity, parserSemanticVersion, languageMode)} with an empty
 * dynamic certificate. It deliberately excludes path, module, classpath and platform library state,
 * so one record is reused across restart, branch switches, worktrees and checkout relocation.
 *
 * Only syntax is captured: package, top-level and member type names and basic declaration
 * structure. Nothing that requires attribution (implicit members, resolved types) is included.
 * A unit with syntax errors is recorded and restored as {@link SemanticCompleteness#PARTIAL}.
 */
public final class SourceNamespaces {
    /** Bump when extraction or canonical encoding changes (§81). */
    public static final SemanticMemoStore.Function FUNCTION=new SemanticMemoStore.Function("s0-namespace",1);

    /** Parser-relevant language mode. Only source level and preview affect parsing. */
    public record LanguageMode(String release,boolean preview) {
        public LanguageMode { release=Objects.requireNonNullElse(release,""); }
        public static LanguageMode of(List<String> compilerOptions){
            String release="";boolean preview=false;
            var options=compilerOptions==null?List.<String>of():compilerOptions;
            for(int i=0;i<options.size();i++){
                String option=options.get(i);
                if((option.equals("--release")||option.equals("-source")||option.equals("--source"))&&i+1<options.size())release=options.get(++i);
                else if(option.startsWith("--release="))release=option.substring("--release=".length());
                else if(option.equals("--enable-preview"))preview=true;
            }
            return new LanguageMode(release,preview);
        }
    }
    public record Declaration(String kind,String binaryName,int depth) { }
    public record Namespace(String packageName,List<String> topLevelTypes,List<String> nestedTypes,
                            List<Declaration> declarations,SemanticCompleteness completeness) {
        public Namespace {
            packageName=Objects.requireNonNullElse(packageName,"");topLevelTypes=List.copyOf(topLevelTypes);
            nestedTypes=List.copyOf(nestedTypes);declarations=List.copyOf(declarations);Objects.requireNonNull(completeness);
        }
        /** Whether this unit provably declares nothing in {@code packageName}. Only COMPLETE units qualify. */
        public boolean provablyOutside(String packageName){
            return completeness==SemanticCompleteness.COMPLETE&&!this.packageName.equals(packageName);
        }
    }

    private final SemanticMemoStore store;
    private final JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
    private long parses,memoHits,memoMisses,memoWrites,memoFailures;

    /** {@code store} may be null, in which case every request parses. */
    public SourceNamespaces(SemanticMemoStore store){this.store=store;}

    public static SemanticMemoStore.StaticKey key(String content,LanguageMode mode){
        String contentIdentity=Hashing.sha256(content.getBytes(StandardCharsets.UTF_8));
        // Conservative parser semantic version: the JVMD extraction version plus the exact javac
        // runtime. It may be narrowed later only with differential evidence (§83).
        return SemanticMemoStore.StaticKey.of(FUNCTION,contentIdentity,Runtime.version().toString(),mode.release(),mode.preview());
    }

    public synchronized Namespace namespace(String content,LanguageMode mode)throws Exception{
        Objects.requireNonNull(content);Objects.requireNonNull(mode);
        var key=key(content,mode);
        if(store!=null){
            try{
                if(store.lookup(key,ignored->Optional.empty()) instanceof SemanticMemoStore.Lookup.Hit hit
                        &&hit.record().result() instanceof SemanticMemoStore.Result.Present present){
                    var restored=Json.MAPPER.readValue(present.value(),Namespace.class);
                    // Completeness is restored exactly as persisted; never strengthened (§7).
                    if(restored.completeness()==hit.record().completeness()){memoHits++;return restored;}
                }
            }catch(Exception unreadable){memoFailures++;}
            memoMisses++;
        }
        var value=parse(content,mode);
        if(store!=null)try{
            store.put(new SemanticMemoStore.MemoRecord(key,SemanticMemoStore.Certificate.empty(),SemanticMemoStore.Coverage.PRECISE,
                    value.completeness(),SemanticMemoStore.Result.present(Json.MAPPER.writeValueAsBytes(value))));
            memoWrites++;
        }catch(Exception failure){memoFailures++;}
        return value;
    }

    public synchronized Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        result.put("parses",parses);result.put("memo_hits",memoHits);result.put("memo_misses",memoMisses);
        result.put("memo_writes",memoWrites);result.put("memo_failures",memoFailures);
        if(store!=null)result.put("store",store.status());
        return Collections.unmodifiableMap(result);
    }

    private Namespace parse(String content,LanguageMode mode)throws Exception{
        parses++;
        var options=new ArrayList<String>(List.of("-proc:none"));
        if(!mode.release().isBlank())options.addAll(List.of("--release",mode.release()));
        if(mode.preview()&&!mode.release().isBlank())options.add("--enable-preview");
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        var source=Parser.source(Path.of("/s0/Unit.java").toUri(),content);
        var task=(JavacTask)compiler.getTask(new java.io.StringWriter(),null,diagnostics,options,null,List.of(source));
        String pkg="";var top=new ArrayList<String>();var nested=new ArrayList<String>();var declarations=new ArrayList<Declaration>();
        for(var unit:task.parse()){
            if(unit.getPackageName()!=null)pkg=unit.getPackageName().toString();
            String prefix=pkg.isEmpty()?"":pkg+".";
            for(var tree:unit.getTypeDecls())if(tree instanceof ClassTree type){
                String binary=prefix+type.getSimpleName();top.add(binary);collect(type,binary,0,nested,declarations);
            }
        }
        boolean errors=diagnostics.getDiagnostics().stream().anyMatch(value->value.getKind()==Diagnostic.Kind.ERROR);
        return new Namespace(pkg,top,nested,declarations,errors?SemanticCompleteness.PARTIAL:SemanticCompleteness.COMPLETE);
    }
    private static void collect(ClassTree type,String binary,int depth,List<String> nested,List<Declaration> declarations){
        declarations.add(new Declaration(type.getKind().name().toLowerCase(Locale.ROOT),binary,depth));
        for(var member:type.getMembers()){
            switch(member){
                case ClassTree inner -> {
                    String name=binary+"$"+inner.getSimpleName();nested.add(name);collect(inner,name,depth+1,nested,declarations);
                }
                case MethodTree method -> declarations.add(new Declaration(method.getReturnType()==null?"ctor":"method",
                        binary+"#"+method.getName(),depth+1));
                case VariableTree field -> declarations.add(new Declaration("field",binary+"#"+field.getName(),depth+1));
                default -> {}
            }
        }
    }
}
