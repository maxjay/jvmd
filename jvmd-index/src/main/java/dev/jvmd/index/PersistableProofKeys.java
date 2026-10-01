package dev.jvmd.index;

import java.util.*;

/**
 * Restart-stable proof-key audit (architecture §33–36, §76, Phase 6).
 *
 * Runtime proofs may use process-local addressing (absolute paths, session ids, document offsets)
 * and runtime fences. Persisted memo certificates may not. This class is the single executable
 * statement of which proof keys are logical, restart-stable semantic keys.
 *
 * <table>
 *   <caption>Domain audit</caption>
 *   <tr><th>Domain</th><th>Persistable keys</th><th>Rejected</th></tr>
 *   <tr><td>EXACT_SYMBOL</td><td>SCIP {@code maven g/a v descriptor}</td><td>{@code local <path-hash>_...}, {@code derived:}</td></tr>
 *   <tr><td>MEMBER_RANGE, OVERLOAD_GROUP</td><td>persistable owner SCIP + name</td><td>non-persistable owners</td></tr>
 *   <tr><td>HIERARCHY</td><td>canonical type SCIP</td><td>{@code document:} keys (document-local)</td></tr>
 *   <tr><td>NAMESPACE</td><td>{@code type:<binary>}, {@code plan:<simple>}</td><td>{@code visible} (bound to live namespace fingerprint)</td></tr>
 *   <tr><td>NEGATIVE_RESOLUTION</td><td>{@code simple@binary}</td><td>—</td></tr>
 *   <tr><td>RESOLUTION_PATH</td><td>{@code type:<binary>}</td><td>{@code source:<absolute path>}</td></tr>
 *   <tr><td>CLASSPATH_SEARCH</td><td>{@code binary:<name>} (winner is a logical slot, §61)</td><td>{@code workspace:<session>}, {@code compiler}</td></tr>
 *   <tr><td>DOCUMENT_SCOPE, RECEIVER, ACCESSIBILITY, WORKSPACE</td><td>none</td><td>all (document/session addressed)</td></tr>
 * </table>
 *
 * The identity side is enforced by the producer: identities written into a certificate must come
 * from a restart-stable resolver (no uncertainty generation, epoch or path inside the digest).
 */
public final class PersistableProofKeys {
    private PersistableProofKeys(){}

    /** Reason a key cannot be persisted, or empty when it is a logical restart-stable key. */
    public static Optional<String> violation(QueryProof.Key key){
        Objects.requireNonNull(key);String value=key.value();
        if(physical(value))return Optional.of("physical-location");
        return switch(key.domain()){
            case EXACT_SYMBOL -> logicalSymbol(value)?Optional.empty():Optional.of("non-logical-symbol");
            case MEMBER_RANGE,OVERLOAD_GROUP -> {
                SemanticReadView.MemberIdentityKey member;
                try{member=SemanticReadView.parseMemberIdentityKey(value);}catch(IllegalArgumentException invalid){yield Optional.of("invalid-member-key");}
                yield logicalSymbol(member.ownerId())?Optional.empty():Optional.of("non-logical-owner");
            }
            case HIERARCHY -> logicalSymbol(value)?Optional.empty():Optional.of("document-local-hierarchy");
            case NAMESPACE -> value.startsWith("type:")&&binary(value.substring(5))||value.startsWith("plan:")&&value.length()>5
                    ?Optional.empty():Optional.of("live-namespace-fingerprint");
            case NEGATIVE_RESOLUTION -> {
                int split=value.lastIndexOf('@');
                yield split>0&&binary(value.substring(split+1))?Optional.empty():Optional.of("invalid-negative-key");
            }
            case RESOLUTION_PATH -> value.startsWith("type:")&&binary(value.substring(5))?Optional.empty():Optional.of("source-path-resolution");
            case CLASSPATH_SEARCH -> value.startsWith("binary:")&&binary(value.substring(7))?Optional.empty():Optional.of("session-classpath-root");
            case DOCUMENT_SCOPE,RECEIVER,ACCESSIBILITY,WORKSPACE -> Optional.of("document-or-session-addressed");
        };
    }
    public static boolean persistable(QueryProof.Key key){return violation(key).isEmpty();}
    public static void requirePersistable(QueryProof.Key key){
        var violation=violation(key);
        if(violation.isPresent())throw new IllegalArgumentException("Proof key is not restart-stable ("+violation.get()+"): "+key);
    }
    /** Whether every dependency of a runtime proof could enter a persisted certificate. */
    public static boolean persistable(QueryProof proof){
        return proof.dependencies().stream().allMatch(dependency->persistable(dependency.key()));
    }

    private static boolean logicalSymbol(String value){
        // Declaration SCIP ids are "maven <group>/<artifact> <version> <descriptor>", where the
        // coordinates are the logical module/artifact identity, independent of checkout location.
        return value.startsWith("maven ")&&value.split(" ",4).length==4;
    }
    private static boolean binary(String value){
        if(value.isBlank())return false;
        for(String part:value.split("[.$]",-1))if(!javax.lang.model.SourceVersion.isIdentifier(part))return false;
        return true;
    }
    private static boolean physical(String value){
        return value.startsWith("/")||value.startsWith("file:")||value.startsWith("jrt:")||value.matches("(?s)^[A-Za-z]:[\\\\/].*")
                ||value.contains("source:/")||value.contains("derived:");
    }
}
