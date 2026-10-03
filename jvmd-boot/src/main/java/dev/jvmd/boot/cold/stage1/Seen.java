package dev.jvmd.boot.cold.stage1;

import dev.jvmd.core.hash.Identity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * {@code Seen} (stage 1, 2.6): byte hash to claimed. A byte-identical jar is not inflated twice. It also remembers every
 * enumerated location with its stamp and byte hash, which is what the path table is written from at commit (step 4).
 */
final class Seen {
    /** One enumerated location as the path table records it. */
    record Observation(Enumerate.Location location, Identity bh) { }

    private final ConcurrentHashMap<Identity, Boolean> claimed = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();

    /** True for exactly one caller per byte hash. */
    boolean claim(Identity bh) { return claimed.putIfAbsent(bh, Boolean.TRUE) == null; }

    void observe(Enumerate.Location location, Identity bh) { observations.add(new Observation(location, bh)); }

    int distinct() { return claimed.size(); }

    /** Every enumerated location, in the location order of C.1. */
    List<Observation> all() {
        var out = new ArrayList<>(observations);
        out.sort(Comparator.comparing((Observation o) -> o.location().name(), Enumerate::compareNames));
        return out;
    }
}
