package dev.jvmd.analyzer;

import dev.jvmd.core.Hashing;
import dev.jvmd.core.Json;
import java.nio.file.Path;
import java.util.*;

/** Stable declaration fingerprint intentionally excluding bodies, positions, docs, and local implementation detail. */
public final class ApiFingerprint {
    private static final Set<String> EXCLUDED_KINDS=Set.of("local","parameter");
    private static final List<String> FIELDS=List.of("scip","name","name_path","kind","signature","modifiers","type_parameters","erased_descriptor","declaring","fqn","gav");
    private ApiFingerprint(){}

    public static String of(Bindings.Snapshot snapshot,Path source){
        String file=source.toAbsolutePath().normalize().toString();var declarations=new ArrayList<Map<String,Object>>();
        for(var symbol:snapshot.symbols().values()){
            Object sourceFile=symbol.get("source_file");if(sourceFile==null)continue;
            Path candidate;try{candidate=Path.of(sourceFile.toString()).toAbsolutePath().normalize();}catch(Exception ignored){continue;}
            if(!candidate.toString().equals(file)||EXCLUDED_KINDS.contains(Objects.toString(symbol.get("kind"),"")))continue;
            var declaration=new TreeMap<String,Object>();for(String field:FIELDS)if(symbol.containsKey(field))declaration.put(field,symbol.get(field));declarations.add(declaration);
        }
        declarations.sort(Comparator.comparing(value->Objects.toString(value.get("scip"),Objects.toString(value.get("name_path"),""))));
        try{return Hashing.sha256(Json.MAPPER.writeValueAsBytes(declarations));}catch(Exception e){throw new IllegalStateException("Cannot fingerprint source declarations",e);}
    }
}
