package dev.jvmd.boot.cold.machine;

import dev.jvmd.core.BootEvents;
import dev.jvmd.index.ArtifactAdmission;
import dev.jvmd.index.ArtifactIndexFormat;
import dev.jvmd.index.layer.machine.ArtifactBuilder;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * One cold boot input, start to finish: read it into memory once and hash those bytes, claim its
 * content, and, only if this job holds the claim, take admission and build its leaf from the same
 * bytes. A job whose content was already claimed has recorded its path and builds nothing.
 */
public final class ArtifactJob implements Callable<ArtifactJob.Outcome> {
    /** What one job produced. */
    public sealed interface Outcome permits Built,Duplicate,Faulted { }
    /** This job built the leaf for its content. */
    public record Built(MachineLeaf leaf) implements Outcome { }
    /** Another job builds this content; this job's path was recorded against it. */
    public record Duplicate() implements Outcome { }
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
        MachineInput.Read read;
        try{read=input.read();}
        catch(Exception unreadable){return fault(unreadable);}
        return build(read);
    }

    @Override public Outcome call()throws Exception{
        MachineInput.Read read;long started=BootEvents.nanos();
        try{read=input.read();}
        catch(Exception unreadable){return fault(unreadable);}
        BootEvents.timed("jar.read_and_hash",started);
        String cacheKey=ArtifactIndexFormat.key(read.binary().sha256(),read.binary().mode()).cacheKey();
        if(!claims.claim(cacheKey,new ClaimMap.Claim(input,read.sources()==null?null:read.sources().sha256()))){BootEvents.count("jar.duplicate_content",1);return new Duplicate();}
        return build(read);
    }

    private Outcome build(MachineInput.Read read)throws Exception{
        try(var permit=admission.acquire(read.estimatedBytes())){
            long started=BootEvents.nanos();
            var built=new Built(builder.build(read.binary(),read.sources()));
            BootEvents.timed("jar.build",started);BootEvents.count("jar.parsed",1);if(read.sources()!=null)BootEvents.count("sources.parsed",1);
            return built;
        }catch(ArtifactBuilder.PublishFailed storage){throw storage;}
        catch(Exception malformed){return fault(malformed);}
    }

    private Faulted fault(Exception error){
        return new Faulted(input.path().location(),error.getClass().getSimpleName()+": "+Objects.toString(error.getMessage(),""));
    }
}
