package dev.jvmd.boot.cold.machine;

import dev.jvmd.index.layer.machine.MachinePath;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This cold boot's claims on artifact contents. The first job to claim a content builds its leaf;
 * every later job with equal content only records its path. The map lives for one cold boot and is
 * never stored.
 */
final class ClaimMap {
    private final ConcurrentHashMap<String,List<MachinePath>> claims=new ConcurrentHashMap<>();

    /** Record {@code path} against {@code cacheKey}; true when this is the first claim on it. */
    boolean claim(String cacheKey,MachinePath path){
        var first=new boolean[1];
        claims.compute(cacheKey,(_,paths)->{
            if(paths==null){first[0]=true;paths=new ArrayList<>();}
            paths.add(path);return paths;
        });
        return first[0];
    }

    /** Every path claimed for {@code cacheKey}. */
    List<MachinePath> paths(String cacheKey){return List.copyOf(claims.getOrDefault(cacheKey,List.of()));}
}
