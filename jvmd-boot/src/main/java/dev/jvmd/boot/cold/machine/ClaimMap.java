package dev.jvmd.boot.cold.machine;

import dev.jvmd.index.layer.machine.MachinePath;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This cold boot's claims on artifact contents. The first job to claim a content builds its leaf;
 * every later job with equal content only records its path. The map lives for one cold boot and is
 * never stored.
 */
public final class ClaimMap {
    /** One input's claim: where it was found and the SHA-256 of its paired sources, or null. */
    public record Claim(MachineInput input,String sourcesSha256) { }

    private final ConcurrentHashMap<String,List<Claim>> claims=new ConcurrentHashMap<>();
    private final java.util.function.Predicate<String> built;

    public ClaimMap(){this(_->false);}
    /** Claims over contents of which those {@code built} accepts already have a leaf and are never claimed. */
    public ClaimMap(java.util.function.Predicate<String> built){this.built=Objects.requireNonNull(built);}

    /** Record {@code claim} against {@code cacheKey}; true when this is the first claim on it and it is not built. */
    boolean claim(String cacheKey,Claim claim){
        if(built.test(cacheKey))return false;
        var first=new boolean[1];
        claims.compute(cacheKey,(_,list)->{
            if(list==null){first[0]=true;list=new ArrayList<>();}
            list.add(claim);return list;
        });
        return first[0];
    }

    /** Every path claimed for {@code cacheKey}. */
    public List<MachinePath> paths(String cacheKey){return claims(cacheKey).stream().map(claim->claim.input().path()).toList();}

    /**
     * The claim whose sources document {@code cacheKey}: the first location, in path order, that has
     * paired sources, or the first location when none has. This is independent of which job built it.
     */
    Claim documenting(String cacheKey){
        var ordered=claims(cacheKey);
        return ordered.stream().filter(claim->claim.sourcesSha256()!=null).findFirst().orElse(ordered.getFirst());
    }

    private List<Claim> claims(String cacheKey){
        return claims.getOrDefault(cacheKey,List.of()).stream()
                .sorted(Comparator.comparing((Claim claim)->claim.input().path().location())).toList();
    }
}
