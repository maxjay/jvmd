package dev.jvmd.index;

import dev.jvmd.core.Hash256;
import java.util.*;

/**
 * Backend-neutral semantic query view.
 *
 * Implementations expose maintained detached semantics; callers do not need to know whether a fact
 * originated from live resident state, workspace-local persistence, or the machine/JDK index.
 */
public interface SemanticReadView {
    enum Origin { LIVE, LOCAL, MACHINE }

    record Symbol(String id,String name,String kind,String fqn,String binaryKey,String signature,
                  String erasedDescriptor,Set<String> modifiers,ResolutionFact resolution,Origin origin) {
        public Symbol {
            Objects.requireNonNull(id);Objects.requireNonNull(name);Objects.requireNonNull(kind);
            fqn=Objects.requireNonNullElse(fqn,"");
            binaryKey=Objects.requireNonNullElse(binaryKey,"");
            signature=Objects.requireNonNullElse(signature,"");
            erasedDescriptor=Objects.requireNonNullElse(erasedDescriptor,"");
            modifiers=Set.copyOf(modifiers);
            Objects.requireNonNull(resolution);Objects.requireNonNull(origin);
        }
        public Hash256 resolutionIdentity(){return resolution.identity();}
        public SemanticType semanticType(){return resolution.type();}
        public List<ResolutionFact.TypeParameter> typeParameters(){return resolution.typeParameters();}
        public List<SemanticType> directSupertypes(){return resolution.directSupertypes();}
        public boolean varargs(){return resolution.varargs();}
        public boolean staticMember(){return resolution.modifiers().contains("static");}
    }

    /** Exact canonical symbol identity, or null when this layer does not own the symbol. */
    Symbol symbol(String id)throws Exception;

    /**
     * Whether this layer owns a complete authoritative declaration surface for the type/owner.
     * Presence alone is never treated as proof of completeness.
     */
    SemanticCompleteness completeness(String ownerId)throws Exception;

    record MemberPage(List<Symbol> symbols,String cursor) {
        public MemberPage { symbols=List.copyOf(symbols); }
    }

    /** Bounded direct-member range in deterministic owner/name order. Cursor is view-owned. */
    MemberPage members(String ownerId,String prefix,int limit,String cursor)throws Exception;

    /** Direct supertype symbol identities in deterministic order. */
    List<String> directSupertypes(String typeId)throws Exception;

    /**
     * Precise maintained semantic identity for one proof domain/key.
     *
     * Empty means this layer does not own a safe identity for that domain. Implementations must not
     * substitute a broader root merely to return a value.
     */
    Optional<Hash256> identity(QueryProof.Domain domain,String key)throws Exception;
}
