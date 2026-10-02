package dev.jvmd.analyzer;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import dev.jvmd.index.QueryProof;
import dev.jvmd.index.SemanticKnowledge;
import java.util.*;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import dev.jvmd.core.Hashing;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** Canonical package/import search domains for Java simple-type resolution. */
public final class NamespaceResolutionProofs {
    @FunctionalInterface
    public interface Lookup {
        /** PRESENT declaration identity, established ABSENT, or UNKNOWN when currency is unproven. */
        SemanticKnowledge identity(String binaryName)throws Exception;
    }

    public record Plan(String simpleName,String resolvedBinary,List<String> domains,boolean precise) {
        public Plan {
            Objects.requireNonNull(simpleName);
            if(simpleName.isBlank()||simpleName.indexOf('.')>=0)throw new IllegalArgumentException("simpleName");
            resolvedBinary=resolvedBinary==null?"":normalize(resolvedBinary);
            domains=List.copyOf(domains);
            if(domains.isEmpty())throw new IllegalArgumentException("Namespace search must have at least one domain");
        }
        public boolean winner(String binaryName){
            return !resolvedBinary.isBlank()&&normalize(binaryName).equals(resolvedBinary);
        }
        public Set<String> packages(){
            var result=new TreeSet<String>();
            for(String domain:domains){
                int split=domain.lastIndexOf('.');
                result.add(split<0?"":domain.substring(0,split));
            }
            return Set.copyOf(result);
        }
    }

    /**
     * The package and imports of one compilation unit, read from javac's syntax tree: comments, string
     * and text-block contents and Unicode escapes are handled by javac's own scanner and parser, so a
     * comment such as {@code /* package com.old; *}{@code /} is never the package. {@code singleImports}
     * and {@code onDemandImports} are the non-static imports (qualified names; on-demand without the
     * trailing {@code .*}); {@code staticImports} are the static imports as written ({@code a.C.m} or {@code a.C.*}).
     * {@code complete} is false when the header holds an erroneous name or a module import, whose
     * search domain this class does not model: plans from such a header are never precise.
     */
    public record Header(String packageName,List<String> singleImports,List<String> onDemandImports,List<String> staticImports,boolean complete) {
        public Header {
            packageName=Objects.requireNonNullElse(packageName,"");singleImports=List.copyOf(new TreeSet<>(singleImports));
            onDemandImports=List.copyOf(new TreeSet<>(onDemandImports));staticImports=List.copyOf(new TreeSet<>(staticImports));
        }
        public static Header of(CompilationUnitTree unit){
            boolean complete=true;
            String pkg="";
            if(unit.getPackageName()!=null){pkg=unit.getPackageName().toString();complete&=wellFormed(unit.getPackageName());}
            var single=new ArrayList<String>();var onDemand=new ArrayList<String>();var statics=new ArrayList<String>();
            for(ImportTree value:unit.getImports()){
                if(value.isModule()){complete=false;continue;}
                Tree identifier=value.getQualifiedIdentifier();
                if(!wellFormed(identifier)){complete=false;continue;}
                String name=identifier.toString();
                if(value.isStatic()){if(name.indexOf('.')<0){complete=false;continue;}statics.add(name);}
                else if(name.endsWith(".*"))onDemand.add(name.substring(0,name.length()-2));
                else single.add(name);
            }
            return new Header(pkg,single,onDemand,statics,complete);
        }
        /** The qualified type name of each static import. */
        public Set<String> staticImportTypes(){
            var result=new TreeSet<String>();for(String value:staticImports)result.add(value.substring(0,value.lastIndexOf('.')));
            return Collections.unmodifiableSet(result);
        }
        /** The unit's own package and every non-static on-demand package: the packages a simple name may resolve in. */
        public Set<String> consultedPackages(){
            var result=new TreeSet<String>(onDemandImports);result.add(packageName);return Collections.unmodifiableSet(result);
        }
        private static boolean wellFormed(Tree tree){
            return switch(tree){
                case IdentifierTree identifier -> identifier.getName().contentEquals("*")||javax.lang.model.SourceVersion.isIdentifier(identifier.getName());
                case MemberSelectTree select -> (select.getIdentifier().contentEquals("*")||javax.lang.model.SourceVersion.isIdentifier(select.getIdentifier()))
                        &&wellFormed(select.getExpression());
                default -> false;
            };
        }
    }

    private static final JavaCompiler COMPILER=ToolProvider.getSystemJavaCompiler();
    private static final Map<String,Header> HEADERS=Collections.synchronizedMap(new LinkedHashMap<>(64,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Header> eldest){return size()>256;}
    });
    private static long headerParses;
    private static javax.tools.StandardJavaFileManager fileManager;
    /** javac parses since start; a parse happens once per distinct content while it stays cached. */
    public static synchronized long headerParses(){return headerParses;}

    /**
     * The header of {@code source} as javac parses it. Parsed once per content (bounded cache): the
     * same text always has the same header. Parsed without a language level, so newer header syntax
     * (module imports) is recognised and reported incomplete rather than misread.
     */
    public static Header header(String source){
        Objects.requireNonNull(source);
        String key=Hashing.sha256(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var cached=HEADERS.get(key);if(cached!=null)return cached;
        Header value;
        synchronized(NamespaceResolutionProofs.class){
        try{
            if(fileManager==null)fileManager=COMPILER.getStandardFileManager(null,java.util.Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8);
            var task=(JavacTask)COMPILER.getTask(new java.io.StringWriter(),fileManager,new DiagnosticCollector<JavaFileObject>(),
                    List.of("-proc:none","-Xlint:-options"),null,List.of(Parser.source(java.net.URI.create("string:///Unit.java"),source)));
            var units=new ArrayList<CompilationUnitTree>();for(var unit:task.parse())units.add(unit);
            value=units.size()==1?Header.of(units.get(0)):new Header("",List.of(),List.of(),List.of(),false);
        }catch(Exception unparsable){value=new Header("",List.of(),List.of(),List.of(),false);}
        headerParses++;
        }
        HEADERS.put(key,value);
        return value;
    }

    private NamespaceResolutionProofs(){}

    /** {@link #plan(Header,String,String)} for the header javac parses from {@code source}. */
    public static Plan plan(String source,String simpleName,String resolvedBinary){
        return plan(header(Objects.requireNonNull(source)),simpleName,resolvedBinary);
    }

    /**
     * Produce the domains that can affect one simple-name lookup.
     *
     * A matching single-type import is decisive over package/on-demand search. Otherwise a proven
     * current-package winner stops lookup there. If it did not win, the current package plus every
     * on-demand namespace (including java.lang) remains relevant because a new declaration may
     * create or remove a winner/ambiguity.
     *
     * Static imports, module imports, an erroneous header and nested-type winners deliberately use the
     * caller's broad namespace fallback; this helper does not attempt to implement those
     * context-sensitive Java rules.
     */
    public static Plan plan(Header header,String simpleName,String resolvedBinary){
        Objects.requireNonNull(header);Objects.requireNonNull(simpleName);
        String pkg=header.packageName();
        var explicit=new TreeSet<String>();
        for(String value:header.singleImports()){
            int split=value.lastIndexOf('.');
            if(value.substring(split+1).equals(simpleName))explicit.add(value);
        }
        var wildcard=new TreeSet<String>(header.onDemandImports());

        boolean structurallyPrecise=header.complete()&&header.staticImports().isEmpty()
                &&(resolvedBinary==null||resolvedBinary.indexOf(36)<0);

        if(!explicit.isEmpty()){
            var domains=List.copyOf(explicit);
            boolean winnerKnown=resolvedBinary==null||domains.stream()
                    .map(NamespaceResolutionProofs::normalize)
                    .anyMatch(normalize(Objects.requireNonNull(resolvedBinary))::equals);
            return new Plan(simpleName,resolvedBinary,domains,structurallyPrecise&&winnerKnown);
        }

        String current=pkg.isBlank()?simpleName:pkg+"."+simpleName;
        if(resolvedBinary!=null&&normalize(current).equals(normalize(resolvedBinary)))
            return new Plan(simpleName,resolvedBinary,List.of(current),structurallyPrecise);

        var domains=new ArrayList<String>();
        domains.add(current);
        var onDemand=new TreeSet<String>();
        onDemand.add("java.lang");
        onDemand.addAll(wildcard);
        onDemand.remove(pkg);
        for(String namespace:onDemand)domains.add(namespace+"."+simpleName);
        var canonical=List.copyOf(new LinkedHashSet<>(domains));
        boolean winnerKnown=resolvedBinary==null||canonical.stream()
                .map(NamespaceResolutionProofs::normalize)
                .anyMatch(normalize(Objects.requireNonNull(resolvedBinary))::equals);
        return new Plan(simpleName,resolvedBinary,canonical,structurallyPrecise&&winnerKnown);
    }

    /**
     * Namespace leaves for a precise plan, or empty when any searched domain is UNKNOWN. An unproven
     * domain has no identity and therefore cannot participate in a reusable certificate.
     */
    public static Optional<List<QueryProof.Dependency>> dependencies(Plan plan,Lookup lookup)throws Exception{
        Objects.requireNonNull(plan);Objects.requireNonNull(lookup);
        if(!plan.precise())throw new IllegalArgumentException("Namespace plan is not precise");
        var result=new ArrayList<QueryProof.Dependency>();
        Hash256 planIdentity=CanonicalDigestWriter.digest(
                "namespace-search-plan-v1",plan.simpleName(),plan.domains());
        result.add(new QueryProof.Dependency(
                QueryProof.Domain.NAMESPACE,"plan:"+plan.simpleName(),planIdentity));

        for(String binary:plan.domains()){
            var knowledge=Objects.requireNonNull(lookup.identity(binary));
            if(!knowledge.known())return Optional.empty();
            Hash256 declaration=knowledge.presentIdentity().orElse(null);
            Hash256 domain=CanonicalDigestWriter.digest(
                    "namespace-search-domain-v1",binary,declaration);
            result.add(new QueryProof.Dependency(QueryProof.Domain.NAMESPACE,"type:"+binary,domain));
            if(!plan.winner(binary)){
                Hash256 negative=CanonicalDigestWriter.digest(
                        "negative-resolution-domain-v1",plan.simpleName(),binary,domain);
                result.add(new QueryProof.Dependency(
                        QueryProof.Domain.NEGATIVE_RESOLUTION,plan.simpleName()+"@"+binary,negative));
            }
        }
        return Optional.of(List.copyOf(result));
    }

    public static String simpleNameFromPlanKey(String key){
        if(key==null||!key.startsWith("plan:")||key.length()==5)
            throw new IllegalArgumentException("Invalid namespace plan proof key");
        return key.substring(5);
    }

    private static String normalize(String binaryName){
        return binaryName.replace((char)36,'.');
    }
}
