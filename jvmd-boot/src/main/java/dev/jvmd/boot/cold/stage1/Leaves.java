package dev.jvmd.boot.cold.stage1;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code Leaves} (stage 1, 2.6): {@code k -> L}. A fact-identical jar is not written twice.
 *
 * <p>{@code L} is a function of {@code k} (B.4): every field is computed from the facts of {@code T}, and nothing per jar file is
 * in it. So the first job to claim {@code k} builds {@code L}, and no other job's {@code L} could differ. There is no canonical
 * owner and no displacement. A claim is never released; if its job fails the boot fails and no ROOT is written.
 */
final class Leaves {
    private final Set<Identity> claimed = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Identity, MachineLeaf> leaves = new ConcurrentHashMap<>();
    /** byte hash -> leaf key, so a location that lost {@code Seen} or {@code claim} resolves through its byte hash. */
    private final ConcurrentHashMap<Identity, Identity> aByHash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identity, Identity> kByHash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identity, Identity> readerByHash = new ConcurrentHashMap<>();
    void reader(Identity bh,Identity root) {readerByHash.put(bh,root);}
    Identity readerFor(Identity bh) {return readerByHash.get(bh);}
    java.util.Map<Identity,Identity> readers() {return java.util.Collections.unmodifiableMap(readerByHash);}

    /** True for exactly one caller per leaf key: that job builds N, E, O and L. */
    boolean claim(Identity k) { return claimed.add(k); }

    /** The winner has written {@code L}; keep it for {@code M}. */
    void register(MachineLeaf leaf, Identity bh, Identity a) {
        leaves.put(leaf.k(), leaf);
        kByHash.put(bh, leaf.k());
        aByHash.put(bh, a);
    }

    /** This byte hash's API is {@code k}, built (or being built) by the job that claimed it. */
    void attach(Identity bh, Identity k, Identity a) { kByHash.put(bh, k); aByHash.put(bh, a); }

    /** Every leaf, sorted by k (unsigned bytes): the input of M. */
    List<MachineLeaf> all() {
        var out = new ArrayList<>(leaves.values());
        out.sort((a, b) -> a.k().compareTo(b.k()));
        return out;
    }

    /** The leaf key of a byte hash, or null if it has none. */
    Identity kFor(Identity bh) { return kByHash.get(bh); }
    Identity aFor(Identity bh) { return aByHash.get(bh); }
}
