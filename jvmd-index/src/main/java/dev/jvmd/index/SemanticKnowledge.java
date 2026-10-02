package dev.jvmd.index;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import java.util.*;

/**
 * Three-valued semantic information domain: {@code UNKNOWN ⪯ ABSENT} and {@code UNKNOWN ⪯ PRESENT(v)}.
 *
 * ABSENT and PRESENT are established conclusions and therefore carry an equality-bearing identity.
 * UNKNOWN deliberately has no identity at all: a cache miss, eviction, missing accelerator, stale
 * owner or corrupt memo is never comparable with anything, including another UNKNOWN. There is no
 * sentinel hash that two unknown observations could accidentally share.
 */
public sealed interface SemanticKnowledge {
    /** Missing evidence. The reason is diagnostic text only and never enters an identity. */
    record Unknown(String reason) implements SemanticKnowledge {
        public Unknown { reason=Objects.requireNonNullElse(reason,""); }
    }
    /** Established absence, e.g. a complete negative search. */
    record Absent() implements SemanticKnowledge { }
    /** Established presence with the identity of the canonical semantic projection observed. */
    record Present(Hash256 identity) implements SemanticKnowledge {
        public Present { Objects.requireNonNull(identity); }
    }

    Absent ABSENT=new Absent();

    static SemanticKnowledge unknown(String reason){return new Unknown(reason);}
    static SemanticKnowledge absent(){return ABSENT;}
    static SemanticKnowledge present(Hash256 identity){return new Present(identity);}
    /** Lift an established lookup where {@code null} is a complete negative answer. */
    static SemanticKnowledge established(Hash256 identityOrNullForAbsent){
        return identityOrNullForAbsent==null?ABSENT:new Present(identityOrNullForAbsent);
    }

    default boolean known(){return !(this instanceof Unknown);}

    /**
     * Identity of the established conclusion within a domain-separated key, or empty for UNKNOWN.
     * ABSENT and PRESENT(v) never collide because the conclusion tag is part of the digest.
     */
    default Optional<Hash256> identity(String domain,Object... key){
        Objects.requireNonNull(domain);
        return switch(this){
            case Unknown ignored -> Optional.empty();
            case Absent ignored -> Optional.of(CanonicalDigestWriter.digest(domain,key,"absent"));
            case Present present -> Optional.of(CanonicalDigestWriter.digest(domain,key,"present",present.identity()));
        };
    }

    /** Present identity, empty for ABSENT or UNKNOWN. */
    default Optional<Hash256> presentIdentity(){
        return this instanceof Present present?Optional.of(present.identity()):Optional.empty();
    }
}
