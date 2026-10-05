package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.index.layer.local.SourceFacts;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code FileMemo} (stage 2, 2.6): {@code (κ_file, routeHash) ->} facts, across the boot. A file's facts depend on its bytes and on
 * what its names resolve to, so the route is part of the key: {@code κ_file} alone would let a vendored copy under a different
 * classpath reuse wrong facts. The javac options of the module are part of it too, because {@code -parameters} changes the tail
 * and {@code --enable-preview} changes parsing; two modules with one route and different options are different keys.
 */
final class FileMemo {
    private record Key(Identity kappa, Identity routeHash, Identity options) { }

    private final ConcurrentHashMap<Key, SourceFacts.Result> memo = new ConcurrentHashMap<>();

    SourceFacts.Result get(Identity kappa, Identity routeHash, Identity options) { return memo.get(new Key(kappa, routeHash, options)); }

    void put(Identity kappa, Identity routeHash, Identity options, SourceFacts.Result facts) { memo.put(new Key(kappa, routeHash, options), facts); }

    int size() { return memo.size(); }
}
