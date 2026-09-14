package dev.jvmd.smoke;

/** Implements section 9: a retained object and a repeatable line-breakpoint target. */
public class Debuggee {
    static final Holder root = new Holder();

    static class Holder {
        final Leaf leaf = new Leaf();
    }

    static class Leaf {
        final int value = 73;
    }

    static void checkpoint() {
        int value = SwapTarget.value();
        if (value < 0) throw new AssertionError(value);
    }

    public static void main(String[] args) throws Exception {
        checkpoint();
        if (args.length > 0 && args[0].equals("train")) return;
        System.out.println("READY");
        System.out.flush();
        while (System.in.read() != -1) checkpoint();
    }
}
