package dev.jvmd.index.layer.machine;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Documents;
import dev.jvmd.core.Hash256;
import dev.jvmd.core.Json;
import dev.jvmd.index.*;
import java.util.*;

/**
 * Builds one MACHINE leaf from bytes it is given: parses the class files once, derives the facts
 * and the resolution identity, joins documentation from the paired sources using the same class
 * models, bulk-builds the semantic tree and publishes the artifact's immutable stores. The leaf it
 * returns has no paths; whoever read the bytes knows where they came from.
 */
public final class ArtifactBuilder {
    /** The class files of one artifact content, keyed by entry name, with the content's SHA-256. */
    public record Binary(String sha256,String mode,Map<String,byte[]> classes) {
        public Binary { Objects.requireNonNull(sha256);Objects.requireNonNull(mode);Objects.requireNonNull(classes); }
    }
    /** The Java sources paired with a binary, keyed by entry name, with the sources' SHA-256. */
    public record Sources(String sha256,Map<String,String> files) {
        public Sources { Objects.requireNonNull(sha256);Objects.requireNonNull(files); }
    }

    private static final Set<String> TYPES=Set.of("class","interface","enum","record","annotation");
    private final ArtifactPublisher publisher;

    public ArtifactBuilder(ArtifactPublisher publisher){this.publisher=Objects.requireNonNull(publisher);}

    /** Publishing failed: the storage, not the input, is at fault. */
    public static final class PublishFailed extends Exception {
        PublishFailed(Exception cause){super("Artifact publication failed: "+cause.getMessage(),cause);}
    }

    /**
     * Build and publish; {@code sources} is null when the content has no paired sources. A malformed
     * input fails with the parse error; a storage failure fails with {@link PublishFailed}.
     */
    public MachineLeaf build(Binary binary,Sources sources)throws Exception{
        var content=new BinaryReader().read(binary.classes(),false);
        var key=ArtifactIndexFormat.key(binary.sha256(),binary.mode());
        var facts=ArtifactIndexFormat.from(content,key);
        var classReferences=CodeReader.classReferences(content.models().values());
        Map<String,Map<String,Object>> members=Map.of();int unmatched=0;
        if(sources!=null){
            var join=new SourceJoin().join(content.models(),sources.files());
            members=documentation(join,sources.files());unmatched=join.unmatched().size();
        }
        var semanticTree=MachineTree.semanticTree(facts);
        String docsKey=null;
        try{
            if(sources!=null)docsKey=publisher.publishDocumentation(key,sources.sha256(),members,unmatched);
            publisher.publish(facts,classReferences,semanticTree);
        }catch(Exception storage){throw new PublishFailed(storage);}
        long types=facts.symbols().stream().filter(symbol->TYPES.contains(symbol.kind())).count();
        return new MachineLeaf(binary.sha256(),binary.mode(),List.of(),ArtifactIndexFormat.resolutionIdentity(facts),
                documentationIdentity(members),docsKey,semanticTree.rootHash(),facts.symbols().size(),facts.relationships().size(),types);
    }

    /** Documentation records by member key. Locations are entry names inside the sources, never paths. */
    private static Map<String,Map<String,Object>> documentation(SourceJoin.Result join,Map<String,String> text){
        var members=new TreeMap<String,Map<String,Object>>();
        for(var member:join.members()){
            String key=member.descriptor()==null?member.owner():member.descriptor().equals("field")
                    ?member.owner()+"#"+member.name():member.owner()+"#"+member.name()+member.descriptor();
            var data=new LinkedHashMap<String,Object>();
            data.put("doc",member.doc());data.put("source_entry",member.file());
            data.put("line",member.line());data.put("source_start",member.start());data.put("source_end",member.end());
            if(member.nameStart()>=0)data.put("name_range",Map.of("start",Documents.position(text.get(member.file()),member.nameStart()),
                    "end",Documents.position(text.get(member.file()),member.nameEnd())));
            data.put("body_start",member.bodyStart());data.put("body_end",member.bodyEnd());data.put("parameters",member.parameters());
            members.put(key,Collections.unmodifiableMap(data));
        }
        return Collections.unmodifiableMap(members);
    }

    private static Hash256 documentationIdentity(Map<String,Map<String,Object>> members)throws Exception{
        return CanonicalDigestWriter.digest("machine-documentation-v1",Json.MAPPER.writeValueAsBytes(members));
    }
}
