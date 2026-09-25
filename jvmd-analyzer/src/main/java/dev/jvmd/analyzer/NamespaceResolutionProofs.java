package dev.jvmd.analyzer;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import dev.jvmd.index.QueryProof;
import java.util.*;
import java.util.regex.Pattern;

/** Canonical package/import search domains for Java simple-type resolution. */
public final class NamespaceResolutionProofs {
    @FunctionalInterface
    public interface Lookup {
        Optional<Hash256> identity(String binaryName)throws Exception;
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

    private static final Pattern PACKAGE=Pattern.compile(
            "\\bpackage\\s+([A-Za-z_\\x24][\\w\\x24]*(?:\\.[A-Za-z_\\x24][\\w\\x24]*)*)\\s*;");
    private static final Pattern IMPORT=Pattern.compile(
            "\\bimport\\s+(?!static\\b)([A-Za-z_\\x24][\\w\\x24]*(?:\\.[A-Za-z_\\x24*][\\w\\x24*]*)*)\\s*;");
    private static final Pattern STATIC_IMPORT=Pattern.compile("\\bimport\\s+static\\b");

    private NamespaceResolutionProofs(){}

    /**
     * Produce the domains that can affect one simple-name lookup.
     *
     * A matching single-type import is decisive over package/on-demand search. Otherwise a proven
     * current-package winner stops lookup there. If it did not win, the current package plus every
     * on-demand namespace (including java.lang) remains relevant because a new declaration may
     * create or remove a winner/ambiguity.
     *
     * Static imports and nested-type winners deliberately use the caller's broad namespace fallback;
     * this helper does not attempt to implement those context-sensitive Java rules.
     */
    public static Plan plan(String source,String simpleName,String resolvedBinary){
        Objects.requireNonNull(source);Objects.requireNonNull(simpleName);
        String pkg="";
        var packageMatcher=PACKAGE.matcher(source);
        if(packageMatcher.find())pkg=packageMatcher.group(1);

        var explicit=new TreeSet<String>();
        var wildcard=new TreeSet<String>();
        var imports=IMPORT.matcher(source);
        while(imports.find()){
            String value=imports.group(1);
            if(value.endsWith(".*"))wildcard.add(value.substring(0,value.length()-2));
            else{
                int split=value.lastIndexOf('.');
                if(value.substring(split+1).equals(simpleName))explicit.add(value);
            }
        }

        boolean structurallyPrecise=!STATIC_IMPORT.matcher(source).find()
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

    public static List<QueryProof.Dependency> dependencies(Plan plan,Lookup lookup)throws Exception{
        Objects.requireNonNull(plan);Objects.requireNonNull(lookup);
        if(!plan.precise())throw new IllegalArgumentException("Namespace plan is not precise");
        var result=new ArrayList<QueryProof.Dependency>();
        Hash256 planIdentity=CanonicalDigestWriter.digest(
                "namespace-search-plan-v1",plan.simpleName(),plan.domains());
        result.add(new QueryProof.Dependency(
                QueryProof.Domain.NAMESPACE,"plan:"+plan.simpleName(),planIdentity));

        for(String binary:plan.domains()){
            Hash256 declaration=lookup.identity(binary).orElse(null);
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
        return List.copyOf(result);
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
