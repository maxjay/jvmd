package dev.jvmd.analyzer.capture;

import dev.jvmd.analyzer.Bindings;
import dev.jvmd.analyzer.CompilerPool;
import dev.jvmd.analyzer.SemanticContributions;
import dev.jvmd.analyzer.SemanticFacts;
import dev.jvmd.analyzer.SourceText;
import dev.jvmd.analyzer.SymbolIdentity;
import dev.jvmd.core.CompilerInputs;
import dev.jvmd.core.Hashing;
import dev.jvmd.index.FileSemanticContribution;
import dev.jvmd.index.SemanticSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/**
 * The javac-and-capture part of batch attribution: compile the given units in one javac task and
 * capture each unit's bindings, semantic snapshot and contribution. It builds from the text it is
 * given and keeps nothing: no admission into resident state, proofs, diagnostics store, memos or
 * publishing.
 */
public final class UnitCapture {
    private UnitCapture(){}

    /** How captured declarations are named: the module coordinates and release of the compiler context. */
    public record Naming(String gav,String release,Function<String,String> coordinates,List<Path> navigationSources) {
        public Naming { Objects.requireNonNull(coordinates);navigationSources=List.copyOf(navigationSources); }
    }

    /**
     * One captured unit. {@code semantic} and {@code contribution} are present only when the batch
     * attributed fully without warnings.
     */
    public record Unit(Path file,String sourceSha256,Bindings.Snapshot snapshot,SemanticSnapshot semantic,
                       FileSemanticContribution contribution,List<CompilerPool.Problem> problems) {
        public boolean complete(){return contribution!=null;}
    }

    /** The batch outcome: the tier javac reached, its warnings and diagnostics, and each requested unit. */
    public record Result(int tier,List<String> warnings,List<CompilerPool.Problem> diagnostics,Map<Path,Unit> units) {
        public boolean complete(){return tier==2&&warnings.isEmpty();}
    }

    /** Compile {@code sources} (absolute path to text) with {@code compiler} and capture each of them. */
    public static Result capture(CompilerPool compiler,CompilerInputs.Snapshot observed,Naming naming,Map<Path,String> sources)throws Exception{
        var hashes=new HashMap<Path,String>();
        for(var entry:sources.entrySet())hashes.put(entry.getKey().toAbsolutePath().normalize(),Hashing.sha256(entry.getValue().getBytes(StandardCharsets.UTF_8)));
        return capture(compiler,Objects.requireNonNull(observed),naming,sources,hashes);
    }

    /**
     * A cold boot batch: compile against the inputs given to {@link CompilerPool#bootSources}, with
     * each unit's identity the hash of the bytes the boot read. Nothing is hashed again.
     */
    public static Result boot(CompilerPool compiler,Naming naming,Map<Path,String> sources,Map<Path,String> hashes)throws Exception{
        return capture(compiler,null,naming,sources,hashes);
    }

    private static Result capture(CompilerPool compiler,CompilerInputs.Snapshot observed,Naming naming,Map<Path,String> sources,Map<Path,String> hashes)throws Exception{
        var inputs=new ArrayList<CompilerPool.SourceInput>(sources.size());
        for(var entry:sources.entrySet())inputs.add(new CompilerPool.SourceInput(entry.getKey(),entry.getValue()));
        var semanticSnapshots=new LinkedHashMap<Path,SemanticSnapshot>();
        CompilerPool.Query<Map<Path,Bindings.Snapshot>> query=(task,units,tier)->{
            var snapshots=new LinkedHashMap<Path,Bindings.Snapshot>();
            var identity=new SymbolIdentity(task,naming.gav(),naming.release(),naming.coordinates(),naming.navigationSources());
            for(var unit:units){
                Path file=Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();String text=sources.get(file);
                if(text==null)continue;
                var captured=Bindings.capture(task,List.of(unit),identity,file,new SourceText(text),true,null,_->null);
                snapshots.put(file,captured);
                if(tier==2)semanticSnapshots.put(file,SemanticFacts.sourceSnapshot(unit,captured.semanticFacts().values(),hashes.get(file)));
            }
            return snapshots;
        };
        var outcome=observed==null?compiler.bootQuery(inputs,2,query):compiler.batchQuery(inputs,2,observed,query);
        boolean complete=outcome.result()!=null&&outcome.tier()==2&&outcome.warnings().isEmpty();
        var units=new LinkedHashMap<Path,Unit>();
        for(var input:inputs){
            Path file=input.file();
            String sha=Objects.requireNonNull(hashes.get(file),"No hash for "+file);
            var problems=outcome.diagnostics().stream().filter(problem->sameFile(problem.file(),file)).toList();
            var snapshot=outcome.result()==null?null:outcome.result().get(file);
            var contribution=complete&&snapshot!=null?SemanticContributions.from(file,sha,snapshot,problems):null;
            units.put(file,new Unit(file,sha,snapshot,complete?semanticSnapshots.get(file):null,contribution,problems));
        }
        return new Result(outcome.tier(),List.copyOf(outcome.warnings()),List.copyOf(outcome.diagnostics()),Collections.unmodifiableMap(units));
    }

    /** Whether a diagnostic's file names {@code file}; a diagnostic without a file applies to every unit. */
    public static boolean sameFile(String source,Path file){
        if(source==null)return true;
        try{return (source.startsWith("file:")?Path.of(java.net.URI.create(source)):Path.of(source)).toAbsolutePath().normalize().equals(file);}
        catch(Exception invalid){return false;}
    }
}
