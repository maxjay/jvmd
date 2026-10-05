package dev.jvmd.boot.cold.stage2;

import java.util.LinkedHashMap;
import java.util.Map;

/** Source fixtures of the stage 2 tests: every construct whose class-file encoding Φ_src has to reproduce. */
final class Fixtures {
    private Fixtures() { }

    static final String LIB_AB_14 = "org/example/libAB/1.4.0/libAB-1.4.0.jar", LIB_AB_15 = "org/example/libAB/1.5.0/libAB-1.5.0.jar",
            LIB_X = "org/example/libX/1.0/libX-1.0.jar", LIB_T = "org/example/libT/1.0/libT-1.0.jar";

    /** libAB: version 1.5 adds a method, replaces one and adds a type; its {@code ab.Util} is also declared by libX. */
    static Map<String, String> libAB(boolean v15) {
        var s = new LinkedHashMap<String, String>();
        s.put("ab/Api.java", "package ab; public class Api<T> { public T get() { return null; } public int a() { return 1; } public "
                + (v15 ? "String b(CharSequence s) { return null; } public long c() { return 2; }" : "String b(String s) { return s; }") + " }");
        s.put("ab/Util.java", "package ab; public final class Util { public static int u(int x) { return x; } }");
        s.put("ab/Shape.java", "package ab; public interface Shape { double area(); }");
        if (v15) s.put("ab/Extra.java", "package ab; public class Extra { public int e; }");
        return s;
    }

    /** libX shadows {@code ab.Util} with another API, so a route holding both has one conflict. */
    static Map<String, String> libX() {
        var s = new LinkedHashMap<String, String>();
        s.put("ab/Util.java", "package ab; public final class Util { public static String u(String s) { return s; } }");
        s.put("x/Thing.java", "package x; public class Thing { public int n; }");
        return s;
    }

    static Map<String, String> libT() { return Map.of("t/Check.java", "package t; public class Check { public static void ok() { } }"); }

    /** The multi-module project of the tests: a library module, two servers whose dependencies are the same in different orders, a loner. */
    static Map<String, String> multi() {
        var s = new LinkedHashMap<String, String>();
        s.put("common/src/main/java/common/Base.java", "package common; import ab.Api; public abstract class Base { protected Api<String> api; public abstract int run(); public static final String TAG = \"base\"; }");
        s.put("server-a/src/main/java/a/Server.java", "package a; import common.Base; import x.Thing; public class Server extends Base { Thing thing; public int run() { return new ab.Api<String>().a(); } }");
        s.put("server-a/src/test/java/a/ServerTest.java", "package a; public class ServerTest { Server server; t.Check check; }");
        s.put("server-b/src/main/java/b/Worker.java", "package b; public class Worker extends common.Base { x.Thing thing; public int run() { return 0; } }");
        s.put("tool/src/main/java/tool/Main.java", "package tool; public class Main { public static void main(String[] args) { } }");
        return s;
    }

    /** A client of {@link #rich()} that uses its members the way a dependent module would: constants, overrides, generics, patterns, annotations. */
    static Map<String, String> userOfRich() {
        return Map.of("b/UseA.java", """
                package b;
                import fx.*;
                import java.util.*;
                public class UseA extends Box.Shape implements Box.Visitor<String>, Annotated {
                    public double area() { return Box.VERSION * Box.RATIO + Box.BIG; }
                    public String visit(Box<?> box) { return Box.NAME + box.size() + Box.LETTER + Box.FLAG + Box.BYTE; }
                    public void old() { }
                    public void marked(int p) { }
                    Box.Mode mode = Box.Mode.FAST;
                    Color color = Color.RED;
                    Pair<String, Integer> pair = Pair.of("x");
                    int cmp() { Cmp c = new Cmp(); return Cmp.max(c, c) + c.compareTo(c) + pair.second() + pair.rest().size(); }
                    String names(Expr e) { return switch (e) { case Expr.Num n -> "n" + n.value(); case Expr.Add a -> "a"; }; }
                    int ranks(Color c) { switch (c) { case RED: return 1; case GREEN: return 2; default: return 0; } }
                    @Kinds.Marker(nums = {4}, kind = java.lang.annotation.ElementType.METHOD) void tagged() { }
                    <T extends Comparable<T>> Box<T> make() { return new Box<>(); }
                    Box<String>.Inner inner(Box<String> b) { return b.new Inner(3); }
                }
                """);
    }

    /** A client with one error of each kind: javac must say the same about it whether it reads class files or stubs. */
    static Map<String, String> brokenUserOfRich() {
        return Map.of("b/Bad.java", """
                package b;
                import fx.*;
                public class Bad {
                    Box<Object> bound;
                    String s = Box.VERSION;
                    Object o = Box.nothing();
                    int m = Cmp.max(1);
                    Color c = Color.BLUE;
                    static class Incomplete extends Box.Shape { }
                    void use(Kinds.Marker marker) { marker.nope(); int n = marker.value(); }
                }
                """);
    }

    /** A module that declares every kind of header javac resolves, and bodies with local classes, lambdas and switches. */
    static Map<String, String> rich() {
        var s = new LinkedHashMap<String, String>();
        // A module descriptor with every kind of directive: the fact is read from the parsed file, and javac adds the mandated java.base.
        s.put("module-info.java", """
                module fx.rich {
                    requires java.logging;
                    requires static java.sql;
                    requires transitive java.xml;
                    exports fx;
                    exports fx.sub to java.logging, java.sql;
                    opens fx to java.sql;
                    uses java.util.function.Supplier;
                    provides java.util.function.Supplier with fx.Supplied;
                }
                """);
        s.put("fx/Supplied.java", "package fx; public class Supplied implements java.util.function.Supplier<String> { public String get() { return \"\"; } }");
        s.put("fx/Box.java", """
                package fx;
                import java.io.Serializable;
                import java.util.*;
                import java.util.function.Function;
                public class Box<T extends Comparable<T>> extends AbstractList<T> implements Serializable, Comparable<Box<T>> {
                    public static final int VERSION = 3;
                    public static final long BIG = 1L << 40;
                    public static final double RATIO = 0.5;
                    public static final float SMALL = 1.5f;
                    public static final char LETTER = 'x';
                    public static final byte BYTE = -3;
                    public static final short SHORT = 12;
                    public static final boolean FLAG = true;
                    public static final String NAME = "box" + VERSION;
                    public final int instanceConstant = 7;
                    protected volatile int seen;
                    transient String cache;
                    int[][] grid;
                    Map<String, List<? extends Number>> index;
                    List<? super Integer> sink;
                    private int hidden;
                    private final List<String> items = new ArrayList<>();
                    static int counter;
                    static { counter = 1; }
                    { seen = 2; }
                    public Box() { }
                    protected Box(T first, T... rest) { items.add(String.valueOf(first)); }
                    Box(int capacity) { }
                    private Box(String secret) { }
                    @Override public T get(int i) { return null; }
                    @Override public int size() { return items.size(); }
                    @Override public int compareTo(Box<T> other) { return 0; }
                    public <E extends Exception> void rethrow(E e) throws E { throw e; }
                    public <A, B extends A> Function<A, B> convert(Class<? extends A> type, B... values) throws java.io.IOException, IllegalStateException { return null; }
                    public static <K extends Comparable<? super K>, V> Map<K, V> sorted(Map<K, V> in) { return new TreeMap<>(in); }
                    public final synchronized void locked() { }
                    public native void nativeCall();
                    public abstract static class Shape { public abstract double area(); protected void touch() { } }
                    public class Inner { public Inner(int x) { } public T value() { return null; } public class Deep { Deep() { } } }
                    public static class Nested<N> { N value; public Nested(N value) { this.value = value; } }
                    protected static class Guarded { }
                    private class Secret { void reveal() { } }
                    static class Pkg { }
                    public interface Visitor<R> { R visit(Box<?> box); default String name() { return "v"; } static Visitor<String> of() { return b -> "x"; } }
                    public enum Mode { FAST, SLOW; public Mode flip() { return this == FAST ? SLOW : FAST; } }
                    void bodies(Mode mode) {
                        class Local { int x; }
                        Object anonymous = new Object() { public String toString() { return "anon"; } };
                        Runnable r = () -> System.out.println(mode);
                        switch (mode) { case FAST -> r.run(); case SLOW -> { } }
                        assert mode != null;
                        String s = switch (hidden) { case 1 -> "a"; default -> "b"; };
                    }
                }
                """);
        s.put("fx/Kinds.java", """
                package fx;
                import java.lang.annotation.*;
                import java.util.List;
                public interface Kinds {
                    int LIMIT = 10;
                    String LABEL = "kinds";
                    void abstractOne() throws Exception;
                    default int defaulted(List<String> in) { return in.size(); }
                    static Kinds none() { return null; }
                    private void helper() { }
                    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD}) @Documented @Inherited
                    @interface Marker { String value() default "x"; int[] nums() default {1, 2}; Class<?> type() default Object.class;
                        ElementType kind() default ElementType.TYPE; Tag tag() default @Tag(name = "t"); byte small() default 1; char c() default 'q'; long big() default 5L; double d() default 0.25; }
                    @Retention(RetentionPolicy.CLASS) @interface Tag { String name(); }
                    @Retention(RetentionPolicy.SOURCE) @interface Fleeting { }
                    @Repeatable(Roles.class) @Retention(RetentionPolicy.RUNTIME) @interface Role { String value(); }
                    @Retention(RetentionPolicy.RUNTIME) @interface Roles { Role[] value(); }
                }
                """);
        s.put("fx/Annotated.java", """
                package fx;
                @Kinds.Marker(value = "a", nums = {3}, type = String.class, small = 4, c = 'z', big = 9L, d = 1.5)
                @Kinds.Tag(name = "class-retained") @Kinds.Fleeting @Kinds.Role("admin") @Kinds.Role("user")
                @SuppressWarnings("unchecked")
                public interface Annotated {
                    @Deprecated(since = "1", forRemoval = true) void old();
                    @Kinds.Marker void marked(@Kinds.Tag(name = "p") final int param);
                    @Override String toString();
                }
                """);
        s.put("fx/Pair.java", """
                package fx;
                import java.util.List;
                public record Pair<A, B extends Number>(A first, B second, List<? extends A> rest) implements Comparable<Pair<A, B>> {
                    public Pair { }
                    public int compareTo(Pair<A, B> o) { return 0; }
                    public static <X> Pair<X, Integer> of(X x) { return new Pair<>(x, 1, List.of()); }
                    public A first() { return first; }
                    public record Unit() { }
                }
                """);
        s.put("fx/Color.java", """
                package fx;
                public enum Color implements java.io.Serializable {
                    RED(1), GREEN(2);
                    private final int code;
                    Color(int code) { this.code = code; }
                    public int code() { return code; }
                    public static Color parse(String s) { return valueOf(s); }
                }
                """);
        s.put("fx/Expr.java", """
                package fx;
                public sealed interface Expr permits Expr.Num, Expr.Add {
                    record Num(int value) implements Expr { }
                    record Add(Expr left, Expr right) implements Expr { }
                    final class Hole implements java.io.Serializable { }
                }
                """);
        s.put("fx/Cmp.java", "package fx; public class Cmp implements Comparable<Cmp> { public int compareTo(Cmp o) { return 0; } public static int max(Cmp... all) { return 0; } }");
        s.put("fx/sub/Deep.java", """
                package fx.sub;
                import fx.Box;
                public abstract class Deep<S extends Comparable<S> & java.io.Serializable> extends fx.Cmp {
                    protected abstract <R extends java.util.Collection<? super S>> R fill(R target);
                    protected Deep(S seed) { }
                    public Box<S>.Inner inner() { return null; }
                }
                """);
        return s;
    }
}
