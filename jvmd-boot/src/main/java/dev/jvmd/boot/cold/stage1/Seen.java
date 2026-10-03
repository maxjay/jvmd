package dev.jvmd.boot.cold.stage1;

import dev.jvmd.core.hash.Identity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * {@code Seen} (stage 1, 2.6): byte hash to claimed. A byte-identical jar is not inflated twice. It also remembers every
 * enumerated location with its stamp and byte hash, and what went wrong in it, which is what the path table is written from at
 * commit (step 4). Faults are a fact about one file, so they live here and in {@code P|}, never in {@code L} (A.7).
 */
final class Seen {
    /** One enumerated location. {@code unreadable} is set when the file could not be read at all, so there are no bytes to hash. */
    record Observation(Enumerate.Location location, Identity bh, String unreadable) { }

    private final ConcurrentHashMap<Identity, Boolean> claimed = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();
    /** byte hash -> why the bytes are not an archive at all (read fine, but no central directory). */
    private final Map<Identity, String> unreadable = new ConcurrentHashMap<>();
    /** byte hash -> the class entries skipped in a jar with those bytes, in owner order. */
    private final Map<Identity, List<String>> skipped = new ConcurrentHashMap<>();

    /** True for exactly one caller per byte hash. */
    boolean claim(Identity bh) { return claimed.putIfAbsent(bh, Boolean.TRUE) == null; }

    void observe(Enumerate.Location location, Identity bh) { observations.add(new Observation(location, bh, null)); }

    /** The file could not be read: no bytes, so the zero identity stands in for the byte hash. */
    void observeUnreadable(Enumerate.Location location, Identity zero, String reason) { observations.add(new Observation(location, zero, reason)); }

    /** These bytes are not a usable archive. Every location holding them records the same one fault. */
    void unreadable(Identity bh, String reason) { unreadable.put(bh, reason); }

    /** The class entries skipped in the jar with these bytes (A.7). */
    void faults(Identity bh, List<String> entries) { if (!entries.isEmpty()) skipped.put(bh, List.copyOf(entries)); }

    int distinct() { return claimed.size(); }

    /** The reason this observation has no usable bytes, or null. */
    String unreadableReason(Observation o) { return o.unreadable() != null ? o.unreadable() : unreadable.get(o.bh()); }

    List<String> skipped(Identity bh) { return skipped.getOrDefault(bh, List.of()); }

    /** Every enumerated location, in the location order of C.1. */
    List<Observation> all() {
        var out = new ArrayList<>(observations);
        out.sort(Comparator.comparing((Observation o) -> o.location().name(), Enumerate::compareNames));
        return out;
    }
}
