package dev.jvmd.boot.cold.stage1;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code Leaves} (stage 1, 2.6): {@code k -> (L, locations)}. A fact-identical jar is not written twice.
 *
 * <p>Which of several API-identical jars supplies {@code L} (its faults, its edges) must not depend on timing, or the root
 * would. The owner of a leaf is the location that is first in unsigned byte order of its name, whatever order the jobs ran in.
 * A job that finishes building after a lower location took the claim is displaced: its result is dropped and its location
 * attached (step 3.1, {@link #resolvePending}).
 */
final class Leaves {
    private static final class Holder {
        String owner;
        MachineLeaf leaf;
        Holder(String owner) { this.owner = owner; }
    }

    private final Map<Identity, Holder> holders = new HashMap<>();
    /** byte hash -> leaf key. A byte hash with no entry, or a null value, produced no leaf (a fault). */
    private final Map<Identity, Identity> kByHash = new HashMap<>();

    /** True if this location owns {@code k}: the first claimer, or any location lower than the current owner. */
    synchronized boolean claim(Identity k, String location) {
        var holder = holders.get(k);
        if (holder == null) { holders.put(k, new Holder(location)); return true; }
        if (Enumerate.compareNames(location, holder.owner) < 0) { holder.owner = location; holder.leaf = null; return true; }
        return false;
    }

    /** The owner finished building {@code L}. A displaced owner's result is dropped. */
    synchronized void register(Identity k, String location, MachineLeaf leaf, Identity bh) {
        var holder = holders.get(k);
        if (holder.owner.equals(location)) holder.leaf = leaf;
        kByHash.put(bh, k);
    }

    /** This byte hash's API is {@code k}, built (or being built) by another location. */
    synchronized void attach(Identity k, Identity bh) { kByHash.put(bh, k); }

    /** This byte hash produced no leaf: an unreadable jar. */
    synchronized void fault(Identity bh) { kByHash.put(bh, null); }

    /** Step 3.1: every claim must have ended with a registered leaf. */
    synchronized void resolvePending() {
        for (var e : holders.entrySet())
            if (e.getValue().leaf == null) throw new IllegalStateException("A leaf claim ended without a result; the boot cannot commit");
    }

    /** Every leaf, sorted by k (unsigned bytes): the input of M. */
    synchronized List<MachineLeaf> all() {
        var out = new ArrayList<MachineLeaf>(holders.size());
        for (var h : holders.values()) out.add(h.leaf);
        out.sort((a, b) -> a.k().compareTo(b.k()));
        return out;
    }

    /** The leaf key of a byte hash, or null if it faulted. Attached locations resolve through their byte hash too. */
    synchronized Identity kFor(Identity bh) { return kByHash.get(bh); }

    synchronized int count() { return holders.size(); }
}
