package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;

/**
 * One entry of a route (stage 2, 2.2 and B.3): a coordinate and what it defaults to. A jar entry carries its location and the
 * MACHINE leaf key found through {@code P|location} (or indexed on the spot, 3.15); a sibling entry carries the module's name
 * and no default; a JDK entry carries its module and leaf key.
 */
public sealed interface RouteEntry {
    String coordinate();

    /** A jar. {@code defaultK} is null when the location could not be bound (the file is missing): the entry then binds to nothing. */
    record Jar(String coordinate, String location, Identity defaultK, Identity a) implements RouteEntry { }

    /** Another module of this project, bound to its {@code main} leaf once built. */
    record Sibling(String coordinate, String module) implements RouteEntry { }

    /** A module of the JDK the project compiles with: {@code jrt:/<module>}. */
    record Jrt(String coordinate, String module, Identity k, Identity a) implements RouteEntry { }

    default void encode(Codec.Writer out) {
        out.str(coordinate());
        switch (this) {
            case Jar j -> { out.u8(0).str(j.location()).optId(j.defaultK()).optId(j.a()); }
            case Sibling s -> out.u8(1).str(s.module());
            case Jrt j -> out.u8(2).str(j.module()).id(j.k()).id(j.a());
        }
    }

    static RouteEntry decode(Codec.Reader in, int width) {
        String coordinate = in.str();
        return switch (in.u8()) {
            case 0 -> new Jar(coordinate, in.str(), in.u8() == 1 ? in.id(width) : null, in.u8() == 1 ? in.id(width) : null);
            case 1 -> new Sibling(coordinate, in.str());
            case 2 -> new Jrt(coordinate, in.str(), in.id(width), in.id(width));
            default -> throw new IllegalArgumentException("Unknown route entry kind");
        };
    }
}
