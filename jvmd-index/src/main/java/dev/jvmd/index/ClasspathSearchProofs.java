package dev.jvmd.index;

import dev.jvmd.core.Hash256;
import java.util.*;

/**
 * Bridges ordered classpath structural diff discovery to precise classpath-search proof leaves.
 *
 * A structural change merely selects which prior search proofs need reconsideration. Recomputed
 * semantic equality stops propagation; only changed proof identities are returned as leaf changes.
 */
public final class ClasspathSearchProofs {
    private ClasspathSearchProofs(){}

    @FunctionalInterface
    public interface Resolver {
        Optional<IndexStore.ClasspathSearchProof> resolve(String binaryName)throws Exception;
    }

    public record Update(ClasspathSequence.Difference structuralDiff,
                         Set<QueryProof.Key> reconsidered,
                         Set<QueryProof.Key> equal,
                         Map<QueryProof.Key,Hash256> changed,
                         Set<QueryProof.Key> unavailable) {
        public Update {
            Objects.requireNonNull(structuralDiff);
            reconsidered=ordered(reconsidered);
            equal=ordered(equal);
            changed=Collections.unmodifiableMap(new TreeMap<>(changed));
            unavailable=ordered(unavailable);
        }
        private static Set<QueryProof.Key> ordered(Collection<QueryProof.Key> values){
            return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(values)));
        }
    }

    public static Update update(ClasspathSequence previous,ClasspathSequence current,
                                Collection<IndexStore.ClasspathSearchProof> previousProofs,
                                Resolver resolver)throws Exception{
        Objects.requireNonNull(previous);Objects.requireNonNull(current);
        Objects.requireNonNull(previousProofs);Objects.requireNonNull(resolver);
        var diff=previous.diff(current);
        var reconsidered=new TreeSet<QueryProof.Key>();
        var equal=new TreeSet<QueryProof.Key>();
        var changed=new TreeMap<QueryProof.Key,Hash256>();
        var unavailable=new TreeSet<QueryProof.Key>();
        if(diff.equal())return new Update(diff,reconsidered,equal,changed,unavailable);

        var ordered=new ArrayList<>(previousProofs);
        ordered.sort(Comparator.comparing(IndexStore.ClasspathSearchProof::binaryName));
        for(var before:ordered){
            if(!before.affectedBy(diff))continue;
            var key=before.key();reconsidered.add(key);
            var after=resolver.resolve(before.binaryName());
            if(after.isEmpty()){unavailable.add(key);continue;}
            Hash256 identity=after.get().identity();
            if(identity.equals(before.identity()))equal.add(key);
            else changed.put(key,identity);
        }
        return new Update(diff,reconsidered,equal,changed,unavailable);
    }
}
