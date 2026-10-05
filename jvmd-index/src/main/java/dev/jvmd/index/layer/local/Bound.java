package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import java.util.List;

/**
 * A route bound to leaves (stage 2, 5.3): the sequence of bound {@code k}, and the three identities of it. {@code routeHash}
 * and {@code leafSet} are digests and may key records; {@code r} (the route's {@code R}) is a sum and may not (stage 1, 2.1).
 *
 * @param bindings one per entry that bound to a leaf, in route order; an entry with nothing to bind to is absent
 * @param unbound  the coordinates of the entries that bound to nothing
 */
public record Bound(List<Binding> bindings, List<Identity> sequence, List<String> unbound, Identity routeHash, Identity r, Identity leafSet) {
    /** Which provider supplied a leaf (3.16). */
    public enum Origin { SESSION, BUILT, DEFAULT }

    public record Binding(RouteEntry entry, Identity k, Origin origin) { }
}
