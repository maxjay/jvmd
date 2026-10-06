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
 * @param external the distinct external leaves, sorted by unsigned bytes: what the external definer index covers
 * @param sibling  the distinct sibling leaves, sorted by unsigned bytes: what the sibling definer index covers
 */
public record Bound(List<Binding> bindings, List<Identity> sequence, Identity routeHash, Identity r, Identity leafSetExt, Identity leafSetSib,
                    List<Identity> external, List<Identity> sibling, dev.jvmd.core.tree.Root routeRoot,
                    dev.jvmd.core.tree.Root externalRoot, dev.jvmd.core.tree.Root siblingRoot) {
    /** Which provider supplied a leaf (3.16). */
    public enum Origin { SESSION, SIBLING, EXTERNAL }

    public record Binding(RouteEntry entry, Identity k, Identity a, Origin origin) { }

    /** Parallel to sequence; deliberately absent from every resolution identity. */
    public List<Identity> aSequence() { return bindings.stream().map(Binding::a).toList(); }
}
