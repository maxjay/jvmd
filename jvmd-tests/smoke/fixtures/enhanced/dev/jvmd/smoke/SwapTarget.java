package dev.jvmd.smoke;

/** Implements section 9: a structural replacement with an additional method. */
public class SwapTarget {
    public static int value() {
        return 2;
    }

    public static int added() {
        return 42;
    }
}
