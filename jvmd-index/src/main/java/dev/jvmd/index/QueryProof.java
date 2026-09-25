package dev.jvmd.index;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import java.util.*;

/**
 * Canonical detached certificate for a reusable semantic conclusion.
 *
 * A proof contains only domain/key identities. It deliberately retains no javac objects and owns
 * no semantic state: producers bind current maintained identities; consumers compare them.
 *
 * Broad structural roots are not ordinary proof dependencies merely because they changed. In
 * particular classpath proofs should bind the ordered search prefix/interval and exact artifact or
 * symbol identities that can affect a resolution. The complete classpath root is a diff-discovery
 * identity, not an automatic query invalidation key.
 */
public final class QueryProof {
    public enum Domain {
        DOCUMENT_SCOPE,
        RECEIVER,
        EXACT_SYMBOL,
        MEMBER_RANGE,
        OVERLOAD_GROUP,
        HIERARCHY,
        NAMESPACE,
        RESOLUTION_PATH,
        NEGATIVE_RESOLUTION,
        ACCESSIBILITY,
        /** Ordered classpath search prefix/interval evidence; not the whole classpath root by default. */
        CLASSPATH_SEARCH,
        /** Reserved for conclusions whose semantics genuinely depend on a composed workspace identity. */
        WORKSPACE
    }

    public record Key(Domain domain,String value) implements Comparable<Key> {
        public Key {
            Objects.requireNonNull(domain);
            Objects.requireNonNull(value);
            if(value.isBlank())throw new IllegalArgumentException("Proof key must not be blank");
        }
        @Override public int compareTo(Key other){
            int domainOrder=domain.compareTo(other.domain);
            return domainOrder!=0?domainOrder:value.compareTo(other.value);
        }
    }

    public record Dependency(Key key,Hash256 identity) implements Comparable<Dependency> {
        public Dependency {
            Objects.requireNonNull(key);
            Objects.requireNonNull(identity);
        }
        public Dependency(Domain domain,String key,Hash256 identity){
            this(new Key(domain,key),identity);
        }
        @Override public int compareTo(Dependency other){return key.compareTo(other.key);}
    }

    public record Change(Key key,Hash256 previous,Hash256 current) {
        public Change {
            Objects.requireNonNull(key);
            Objects.requireNonNull(previous);
            Objects.requireNonNull(current);
            if(previous.equals(current))throw new IllegalArgumentException("Changed dependency identities must differ");
        }
    }

    public record Difference(List<Dependency> added,List<Change> changed,List<Dependency> removed) {
        public Difference {
            added=List.copyOf(added);
            changed=List.copyOf(changed);
            removed=List.copyOf(removed);
        }
        public boolean equal(){return added.isEmpty()&&changed.isEmpty()&&removed.isEmpty();}
    }

    private final List<Dependency> dependencies;
    private final Map<Key,Hash256> identities;
    private final Hash256 identity;

    public QueryProof(Collection<Dependency> dependencies){
        try(var trace=dev.jvmd.core.RequestScope.stage("proof.construct")){
            Objects.requireNonNull(dependencies);trace.count("dependencies",dependencies.size());
            var ordered=new TreeMap<Key,Hash256>();
            for(var dependency:dependencies){
                Objects.requireNonNull(dependency);
                if(ordered.putIfAbsent(dependency.key(),dependency.identity())!=null)
                    throw new IllegalArgumentException("Duplicate proof dependency: "+dependency.key());
            }
            var canonical=new ArrayList<Dependency>(ordered.size());
            ordered.forEach((key,value)->canonical.add(new Dependency(key,value)));
            this.dependencies=List.copyOf(canonical);
            this.identities=Collections.unmodifiableMap(new TreeMap<>(ordered));
            try(var digest=dev.jvmd.core.RequestScope.stage("proof.canonicalDigest")){
                this.identity=CanonicalDigestWriter.digest("query-proof-v1",
                        this.dependencies.stream()
                                .map(dependency->new Object[]{
                                        dependency.key().domain().name(),
                                        dependency.key().value(),
                                        dependency.identity()})
                                .toList());
            }
        }
    }

    public static QueryProof empty(){return new QueryProof(List.of());}

    public List<Dependency> dependencies(){return dependencies;}
    public Hash256 identity(){return identity;}
    public Optional<Hash256> identity(Domain domain,String key){
        return Optional.ofNullable(identities.get(new Key(domain,key)));
    }
    public boolean proves(Domain domain,String key,Hash256 expected){
        return identity(domain,key).filter(expected::equals).isPresent();
    }

    /** Compare this (previous) proof with a current proof and identify the precise changed keys. */
    public Difference diff(QueryProof current){
        Objects.requireNonNull(current);
        var added=new ArrayList<Dependency>();
        var changed=new ArrayList<Change>();
        var removed=new ArrayList<Dependency>();
        var keys=new TreeSet<Key>();keys.addAll(identities.keySet());keys.addAll(current.identities.keySet());
        for(var key:keys){
            Hash256 previous=identities.get(key),next=current.identities.get(key);
            if(previous==null)added.add(new Dependency(key,next));
            else if(next==null)removed.add(new Dependency(key,previous));
            else if(!previous.equals(next))changed.add(new Change(key,previous,next));
        }
        return new Difference(added,changed,removed);
    }

    @Override public boolean equals(Object other){
        return other instanceof QueryProof proof&&dependencies.equals(proof.dependencies);
    }
    @Override public int hashCode(){return dependencies.hashCode();}
    @Override public String toString(){return identity.hex();}
}
