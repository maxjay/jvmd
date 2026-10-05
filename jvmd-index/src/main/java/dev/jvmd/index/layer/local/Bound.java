package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import java.util.List;

/**
 * A route bound to leaves (stage 2, 5.3): the sequence of bound {@code k}, and the identities of it. {@code routeHash},
 * {@code leafSetExt} and {@code leafSetSib} are digests and may key records; {@code r} (the route's {@code R}) is a sum and may not
 * (stage 1, 2.1). The leaves are of two kinds: external (JDK modules and jars, MACHINE-bound), which change when a dependency is
 * bumped, and sibling (this project's modules), which change on every API edit; each has its own set identity because each has its
 * own definer index (3.6).
 *
 * @param bindings one per entry that bound to a leaf, in route order; an entry with nothing to bind to is absent
 * @param unbound  the coordinates of the entries that bound to nothing
 */
public record Bound(List<Binding> bindings, List<Identity> sequence, List<String> unbound, Identity routeHash, Identity r,
                    Identity leafSetExt, Identity leafSetSib) {
    /** Which provider supplied a leaf (3.16). */
    public enum Origin { SESSION, SIBLING, EXTERNAL }

    public record Binding(RouteEntry entry, Identity k, Origin origin) { }

    /** The distinct external leaves, sorted by unsigned bytes: what the external definer index covers. */
    public List<Identity> external() { return distinct(false); }

    /** The distinct sibling leaves, sorted by unsigned bytes: what the sibling definer index covers. */
    public List<Identity> sibling() { return distinct(true); }

    private List<Identity> distinct(boolean sibling) {
        return bindings.stream().filter(b -> (b.origin() == Origin.SIBLING) == sibling).map(Binding::k).distinct().sorted().toList();
    }
}
