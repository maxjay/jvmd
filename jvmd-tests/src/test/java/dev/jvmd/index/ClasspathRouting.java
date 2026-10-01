package dev.jvmd.index;

import java.util.*;

/**
 * Classpath composition algebra (architecture §17–18, §63–67, Phase 13).
 *
 * For an artifact A, {@code f_A : Name ⇀ Declaration} is a partial map. First-winner classpath
 * semantics compose artifacts with the left-biased union
 * <pre>  (L ◁ R)(t) = L(t) if t ∈ dom(L), else R(t)</pre>
 * which is associative with the empty map as identity, so a classpath {@code [A0..An]} resolves
 * through {@code f_C = f_A0 ◁ f_A1 ◁ … ◁ f_An}. Existence is a projection:
 * {@code dom(L ◁ R) = dom(L) ∪ dom(R)}, so membership filters compose as existence accelerators.
 *
 * The flat fold is the baseline routing implementation (§63): rebuild the winner map in O(T) on a
 * classpath change. Hierarchical routing is not built without evidence of reusable subtrees (§64).
 *
 * <p>Benchmark code, not production: the strict task's W8 rule kept RocksDB as the MACHINE backend
 * ({@code docs/evidence/machine-decision.md}), so this proof of concept lives with its harness.
 */
public final class ClasspathRouting {
    private ClasspathRouting(){}

    /** One slot's declarations: binary name → declaration identity (here the winning slot key). */
    public record Slot(String key,Set<String> declarations) {
        public Slot { Objects.requireNonNull(key);declarations=Set.copyOf(declarations); }
    }

    /** Partial map {@code Name ⇀ Declaration}. */
    public record Routing(Map<String,String> winners) {
        public static final Routing EMPTY=new Routing(Map.of());
        public Routing { winners=Collections.unmodifiableMap(new HashMap<>(winners)); }
        public static Routing of(Slot slot){
            var map=new HashMap<String,String>(slot.declarations().size()*2);
            for(String name:slot.declarations())map.put(name,slot.key());
            return new Routing(map);
        }
        /** Left-biased union {@code this ◁ right}. */
        public Routing then(Routing right){
            var map=new HashMap<String,String>(right.winners);map.putAll(winners);return new Routing(map);
        }
        public Optional<String> winner(String binaryName){return Optional.ofNullable(winners.get(binaryName));}
        public Set<String> domain(){return winners.keySet();}
    }

    /** Flat left fold of the ordered classpath: the baseline routing map. */
    public static Routing fold(List<Slot> classpath){
        var map=new HashMap<String,String>();
        for(int i=classpath.size()-1;i>=0;i--)for(String name:classpath.get(i).declarations())map.put(name,classpath.get(i).key());
        return new Routing(map);
    }

    /** Reference semantics: ordered first-winner search. */
    public static Optional<String> search(List<Slot> classpath,String binaryName){
        for(var slot:classpath)if(slot.declarations().contains(binaryName))return Optional.of(slot.key());
        return Optional.empty();
    }

    /** Approximate membership with no false negatives, bound to the slot it describes. */
    public static final class Filter {
        private final long[] bits;private final int hashes;private final String slot;
        private Filter(long[] bits,int hashes,String slot){this.bits=bits;this.hashes=hashes;this.slot=slot;}
        public static Filter of(Slot slot,int bitsPerElement){
            long size=Math.max(64,((long)Math.max(1,slot.declarations().size())*bitsPerElement+63)/64*64);
            var value=new long[(int)(size/64)];int hashes=Math.max(1,(int)Math.round(bitsPerElement*0.69));
            for(String name:slot.declarations())for(int i=0;i<hashes;i++){long index=index(name,i,size);value[(int)(index>>>6)]|=1L<<(index&63);}
            return new Filter(value,hashes,slot.key());
        }
        public boolean mightContain(String name){
            long size=64L*bits.length;
            for(int i=0;i<hashes;i++){long index=index(name,i,size);if((bits[(int)(index>>>6)]&(1L<<(index&63)))==0)return false;}
            return true;
        }
        public String slot(){return slot;}
        public long bytes(){return 8L*bits.length;}
        private static long index(String name,int i,long size){
            long h1=name.hashCode()*0x9E3779B97F4A7C15L,h2=Long.rotateLeft(h1,31)*0xC2B2AE3D27D4EB4FL|1;
            return Math.floorMod(h1+i*h2,size);
        }
    }

    /**
     * Filtered first-winner search: a negative filter answer skips the canonical check for that slot.
     * Filters must be bound to their slot; a missing or mismatched filter falls back to the
     * canonical check (§66). Returns the winner and the number of canonical checks performed.
     */
    public record Search(Optional<String> winner,int canonicalChecks) { }
    public static Search filteredSearch(List<Slot> classpath,Map<String,Filter> filters,String binaryName){
        int checks=0;
        for(var slot:classpath){
            var filter=filters.get(slot.key());
            if(filter!=null&&filter.slot().equals(slot.key())&&!filter.mightContain(binaryName))continue;
            checks++;
            if(slot.declarations().contains(binaryName))return new Search(Optional.of(slot.key()),checks);
        }
        return new Search(Optional.empty(),checks);
    }
}
