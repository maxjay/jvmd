package dev.jvmd.boot.cold.machine;

import dev.jvmd.index.ArtifactAdmission;
import dev.jvmd.index.layer.machine.ArtifactBuilder;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * One cold boot input, start to finish: read its raw bytes once and hash them, claim its content,
 * and, only if this job holds the claim, take admission, decompress those bytes and build its leaf.
 * A job whose content was already claimed has recorded its path and decompresses nothing.
 */
public final class ArtifactJob implements Callable<ArtifactJob.Outcome> {
    /** What one job produced. */
    public sealed interface Outcome permits Built,Duplicate,Faulted { }
    /** This job built the leaf for its content. */
    public record Built(MachineLeaf leaf) implements Outcome { }
    /** Another job builds this content, or it is built already; this job's path was recorded against it. */
    public record Duplicate(String cacheKey) implements Outcome { }
    /** The input could not be read or parsed; it contributes no leaf. */
    public record Faulted(String location,String reason) implements Outcome { }

    private final MachineInput input;
    private final ClaimMap claims;
    private final ArtifactAdmission admission;
    private final ArtifactBuilder builder;

    public ArtifactJob(MachineInput input,ClaimMap claims,ArtifactAdmission admission,ArtifactBuilder builder){
        this.input=Objects.requireNonNull(input);this.claims=claims;this.admission=admission;this.builder=builder;
    }

    /** Build this input's leaf without claiming: its content is already claimed and this input documents it. */
    Outcome rebuild()throws Exception{
        MachineInput.Raw raw;
        try{raw=input.read();}
        catch(Exception unreadable){return fault(unreadable);}
        return build(raw);
    }

    @Override public Outcome call()throws Exception{
        MachineInput.Raw raw;
        try{raw=input.read();}
        catch(Exception unreadable){return fault(unreadable);}
        String cacheKey=raw.cacheKey();
        if(!claims.claim(cacheKey,new ClaimMap.Claim(input,raw.sourcesSha256())))return new Duplicate(cacheKey);
        return build(raw);
    }

    private Outcome build(MachineInput.Raw raw)throws Exception{
        try(var permit=admission.acquire(raw.estimatedBytes())){
            var read=raw.decompress();
            return new Built(builder.build(read.binary(),read.sources()));
        }catch(ArtifactBuilder.PublishFailed storage){throw storage;}
        catch(Exception malformed){return fault(malformed);}
    }

    private Faulted fault(Exception error){
        return new Faulted(input.path().location(),error.getClass().getSimpleName()+": "+Objects.toString(error.getMessage(),""));
    }
}
