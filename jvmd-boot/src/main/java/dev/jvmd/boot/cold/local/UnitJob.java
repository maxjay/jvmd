package dev.jvmd.boot.cold.local;

import dev.jvmd.analyzer.CompilerPool;
import dev.jvmd.analyzer.capture.UnitCapture;
import dev.jvmd.core.LiveStateTree;
import dev.jvmd.index.SemanticFact;
import dev.jvmd.index.SemanticSnapshot;
import dev.jvmd.index.layer.local.LocalFile;
import dev.jvmd.index.layer.local.LocalSemanticTree;
import java.nio.file.Path;
import java.util.*;

/**
 * One batch of units of one compiler context: compile and capture them from the text that was read
 * and hashed, and turn each complete capture into a LOCAL leaf and its declarations.
 */
final class UnitJob {
    /** A unit's result: its leaf and declarations, or null for both when its attribution was not complete. */
    record Built(UnitQueue.Unit unit,LocalFile leaf,SemanticSnapshot declarations) {
        boolean complete(){return leaf!=null;}
    }

    private final CompilerPool compiler;
    private final List<UnitQueue.Unit> units;
    private final Map<Path,LocalColdBoot.SourceFile> files;

    UnitJob(CompilerPool compiler,List<UnitQueue.Unit> units,Map<Path,LocalColdBoot.SourceFile> files){
        this.compiler=compiler;this.units=List.copyOf(units);this.files=files;
    }

    List<Built> run()throws Exception{
        var context=units.getFirst().context();
        var sources=new LinkedHashMap<Path,String>();
        for(var unit:units)sources.put(unit.file(),files.get(unit.file()).text());
        var captured=UnitCapture.capture(compiler,compiler.inputSnapshot(),
                new UnitCapture.Naming(context.module(),context.release(),context::coordinates,context.navigationSources()),
                sources,id->null);
        var result=new ArrayList<Built>(units.size());
        for(var unit:units){
            var capture=captured.units().get(unit.file());
            if(capture==null||!capture.complete()||capture.semantic()==null){result.add(new Built(unit,null,null));continue;}
            var contribution=capture.contribution();var snapshot=capture.semantic();
            String namespace=LiveStateTree.namespace(contribution.exportedNames()).value();
            var declarations=new SemanticSnapshot(snapshot.unit(),snapshot.sourceFile(),snapshot.contentIdentity(),snapshot.facts(),snapshot.descriptions(),
                    contribution.apiFingerprint(),namespace,snapshot.documentationIdentity(),snapshot.dependencies());
            var tree=LocalSemanticTree.file(declarations.facts().values());
            var leaf=new LocalFile(unit.path(),context.module(),context.scope(),capture.sourceSha256(),contribution.apiFingerprint(),namespace,
                    tree.rootHash(),tree.rangeSum().identity("local-file-resolution-v1"),declarations.facts().size());
            result.add(new Built(unit,leaf,declarations));
        }
        return result;
    }

    /** The declarations of a built unit, for the layer's semantic tree. */
    static Collection<SemanticFact> facts(Built built){return built.declarations().facts().values();}
}
