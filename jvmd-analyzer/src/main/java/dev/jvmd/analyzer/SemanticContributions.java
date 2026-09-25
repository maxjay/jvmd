package dev.jvmd.analyzer;
import dev.jvmd.index.FileSemanticContribution;
import java.nio.file.Path;
import java.util.*;
/** One extraction contract for full diagnostics and navigation snapshots. */
public final class SemanticContributions {
    private SemanticContributions() {}
    public static FileSemanticContribution from(Path file,String hash,Bindings.Snapshot snapshot,List<CompilerPool.Problem> problems){
        var symbols=List.copyOf(snapshot.symbols().values());
        var exports=new LinkedHashSet<String>();
        String source=file.toAbsolutePath().normalize().toString();
        if(!snapshot.semanticFacts().isEmpty())for(var fact:snapshot.semanticFacts().values()){
            if(!source.equals(Objects.toString(fact.sourceFile(),"")))continue;
            if(Set.of("local","local_variable","resource_variable","exception_parameter","binding_variable","parameter","type_parameter").contains(fact.kind()))continue;
            if(fact.modifiers().contains("private"))continue;
            for(String value:List.of(fact.id(),Objects.toString(fact.fqn(),""),fact.namePath()))if(!value.isBlank())exports.add(value);
        }else for(var symbol:symbols){
            if(!source.equals(Objects.toString(symbol.get("source_file"),"")))continue;
            String kind=Objects.toString(symbol.get("kind"),"");
            if(kind.equals("local")||kind.equals("parameter"))continue;
            if(symbol.get("modifiers") instanceof Collection<?> modifiers&&modifiers.contains("private"))continue;
            for(String field:List.of("scip","fqn","binary_key","name_path")){
                String value=Objects.toString(symbol.get(field),"");if(!value.isBlank())exports.add(value);
            }
        }
        var known=new HashSet<String>(snapshot.symbols().keySet());
        for(var symbol:symbols)for(String field:List.of("scip","binary_key","fqn")){
            String value=Objects.toString(symbol.get(field),"");if(!value.isBlank())known.add(value);
        }
        var unresolved=new LinkedHashSet<String>();
        for(var edge:snapshot.edges())if(!known.contains(edge.dst()))unresolved.add(edge.dst());
        unresolved.addAll(snapshot.unresolvedTypeNames());
        // Preserve precise negative type names for ordinary cant.resolve diagnostics. Other compiler
        // errors still require the explicit wildcard conservative boundary.
        var errors=problems.stream().filter(p->p.kind().equals("ERROR")).toList();
        // javac may report a missing wildcard/import package as compiler.err.doesnt.exist in the
        // same attribution that reports the precise unresolved type name. That error is still
        // represented by the namespace/negative-resolution plan for the captured type name; it
        // must not force the whole contribution back to "*". Other error domains remain coarse.
        boolean onlyNameResolutionErrors=!errors.isEmpty()&&errors.stream().allMatch(p->
                p.code().contains("cant.resolve")||p.code().contains("doesnt.exist"));
        if(!errors.isEmpty()&&(snapshot.unresolvedTypeNames().isEmpty()||!onlyNameResolutionErrors))unresolved.add("*");
        return new FileSemanticContribution(file,hash,ApiFingerprint.of(snapshot,file),snapshot.dependencies(),exports,unresolved);
    }
}
