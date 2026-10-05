package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code RT|} record (stage 2, B.3): the ordered entries of one module and scope, as the build tool ordered them, and the
 * identities of the route as bound in this boot. The entries hold coordinates; the identities are what they bound to.
 */
public record Route(List<RouteEntry> entries, Identity routeHash, Identity r, Identity leafSetExt, Identity leafSetSib) {
    public byte[] encode() {
        var out = new Codec.Writer(256).u32(entries.size());
        for (var entry : entries) entry.encode(out);
        return out.id(routeHash).id(r).id(leafSetExt).id(leafSetSib).toBytes();
    }

    public static Route decode(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        int n = in.count();
        var entries = new ArrayList<RouteEntry>(n);
        for (int i = 0; i < n; i++) entries.add(RouteEntry.decode(in, width));
        return new Route(List.copyOf(entries), in.id(width), in.id(width), in.id(width), in.id(width));
    }
}
