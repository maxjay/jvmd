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
                  String erasedDescriptor,Set<String> modifiers,Hash256 resolutionIdentity,Origin origin) {
        public Symbol {
            Objects.requireNonNull(id);Objects.requireNonNull(name);Objects.requireNonNull(kind);
            fqn=Objects.requireNonNullElse(fqn,"");
            binaryKey=Objects.requireNonNullElse(binaryKey,"");
            signature=Objects.requireNonNullElse(signature,"");
            erasedDescriptor=Objects.requireNonNullElse(erasedDescriptor,"");
            modifiers=Set.copyOf(modifiers);
            Objects.requireNonNull(resolutionIdentity);Objects.requireNonNull(origin);
        }
    }

    /** Exact canonical symbol identity, or null when this layer does not own the symbol. */
    Symbol symbol(String id)throws Exception;

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
