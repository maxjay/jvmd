package dev.jvmd.tests;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import dev.jvmd.analyzer.DiagnosticProjection;
import dev.jvmd.analyzer.Parser;
import dev.jvmd.core.Hash256;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import javax.tools.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Gate for persisted attributed memos (strict task W2/A13): {@code P_diag} must be a sufficient
 * diagnostic projection.
 *
 * For every seeded random mutation of one library unit {@code D}, all 45 units are compiled afresh.
 * If {@code P_diag(D)} is unchanged, the diagnostics of every other unit must be identical to the
 * baseline. This is stronger than "every dependant": it does not trust any dependency capture. A
 * second assertion keeps the projection honest: a body-only edit must leave {@code P_diag}
 * unchanged, otherwise the projection has degraded into a content hash and early cutoff is lost.
 */
class DiagnosticProjectionSufficiencyTest {
    static final int MUTATIONS=Integer.getInteger("jvmd.sufficiency.mutations",2_000);
    static final long SEED=0x5EED_D1A6L;

    // ------------------------------------------------------------------ fixture

    static final Map<String,String> LIBRARY=new LinkedHashMap<>();
    static{
        LIBRARY.put("lib/Base.java","""
                package lib;
                public abstract class Base {
                    protected int count;
                    private int secret;
                    public static final int LIMIT = 10;
                    public static final String NAME = "base";
                    public int value() { return 1; }
                    protected void hook() { count++; }
                    private int hidden() { return 2; }
                    public abstract String describe();
                }
                """);
        LIBRARY.put("lib/Util.java","""
                package lib;
                public final class Util {
                    private Util() {}
                    public static int sum(int a, int b) { return a + b; }
                    public static long sum(long a, long b) { return a + b; }
                    public static <T extends Number> T first(java.util.List<T> values) { return values.get(0); }
                    @SafeVarargs public static <T> java.util.List<T> listOf(T... values) { return java.util.List.of(values); }
                    public static void risky() throws java.io.IOException { if (Boolean.getBoolean("x")) throw new java.io.IOException(); }
                    @Deprecated public static int old() { return 0; }
                    public static String joined(String... parts) { return String.join(",", parts); }
                    public static final java.util.List<String> NAMES = java.util.List.of("a");
                }
                """);
        LIBRARY.put("lib/Shape.java","""
                package lib;
                public sealed interface Shape permits Circle, Square {
                    double area();
                }
                """);
        LIBRARY.put("lib/Circle.java","""
                package lib;
                public record Circle(double r) implements Shape {
                    public double area() { return Math.PI * r * r; }
                }
                """);
        LIBRARY.put("lib/Square.java","""
                package lib;
                public record Square(double side) implements Shape {
                    public double area() { return side * side; }
                }
                """);
        LIBRARY.put("lib/Color.java","""
                package lib;
                public enum Color {
                    RED, GREEN, BLUE;
                    public Color next() { return values()[(ordinal() + 1) % values().length]; }
                }
                """);
        LIBRARY.put("lib/Mapper.java","""
                package lib;
                @FunctionalInterface
                public interface Mapper<A, B> {
                    B apply(A a);
                    default Mapper<A, B> self() { return this; }
                }
                """);
        LIBRARY.put("lib/Marker.java","""
                package lib;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Marker {
                    int level() default 1;
                    String tag() default "x";
                }
                """);
        LIBRARY.put("lib/Box.java","""
                package lib;
                public class Box<T extends Comparable<T>> {
                    private T value;
                    public Box(T value) { this.value = value; }
                    public T get() { return value; }
                    public boolean greater(Box<T> other) { return value.compareTo(other.value) > 0; }
                    public static class Entry { public int key; }
                    public interface Visitor { void visit(Box<?> box); }
                }
                """);
        LIBRARY.put("lib/Config.java","""
                package lib;
                public final class Config {
                    private Config() {}
                    public static final int MODE_A = 1;
                    public static final int MODE_B = 2;
                    public static final String KEY = "k";
                    static final int PACKAGE_ONLY = 3;
                }
                """);
        LIBRARY.put("lib/LibException.java","""
                package lib;
                public class LibException extends Exception {
                    public LibException(String message) { super(message); }
                }
                """);
        LIBRARY.put("lib/Service.java","""
                package lib;
                public class Service extends Base {
                    @Override public String describe() { return "service"; }
                    public void run() throws LibException { if (count < 0) throw new LibException("negative"); }
                    public int compute(int x) { return x; }
                    public int compute(double x) { return 1; }
                    protected int guarded() { return 3; }
                }
                """);
        LIBRARY.put("lib/Holder.java","""
                package lib;
                public class Holder {
                    public static class Inner { public int x; }
                    public interface Callback { void call(); }
                    public static int counter() { return 0; }
                }
                """);
        LIBRARY.put("lib/extra/Widget.java","""
                package lib.extra;
                public class Widget {
                    public int size() { return 1; }
                }
                """);
        LIBRARY.put("lib/extra/Gadget.java","""
                package lib.extra;
                public class Gadget {
                    public static Gadget make() { return new Gadget(); }
                }
                """);
    }

    static final List<String> CLIENT_BODIES=List.of(
            "class C%d extends lib.Base { public String describe() { return \"x\" + value() + count + LIMIT + NAME; } int peek() { return secret; } void h() { hook(); } }",
            "class C%d { int f() { int a = lib.Util.sum(1, 2); long b = lib.Util.sum(1L, 2L); Integer i = lib.Util.first(java.util.List.of(1)); java.util.List<String> l = lib.Util.listOf(\"a\"); try { lib.Util.risky(); } catch (java.io.IOException e) { } return a + lib.Util.old() + lib.Util.joined(\"a\", \"b\").length(); } }",
            "class C%d { double f(lib.Shape s) { return switch (s) { case lib.Circle c -> c.r(); case lib.Square q -> q.side(); }; } boolean g(Object o) { return o instanceof lib.Circle(double r) && r > 0; } }",
            "class C%d { int f(lib.Color c) { return switch (c) { case RED -> 1; case GREEN -> 2; case BLUE -> 3; }; } lib.Color n(lib.Color c) { return c.next(); } }",
            "class C%d { lib.Mapper<String, Integer> m = String::length; int f() { return m.self().apply(\"abc\"); } }",
            "@lib.Marker(level = lib.Config.MODE_A, tag = lib.Config.KEY) class C%d { @lib.Marker void plain() { } }",
            "class C%d { boolean f() { lib.Box.Entry e = new lib.Box.Entry(); e.key = 1; lib.Box.Visitor v = b -> { }; return new lib.Box<>(\"a\").greater(new lib.Box<>(\"b\")); } }",
            "class C%d { String f(int x) { switch (x) { case lib.Config.MODE_A: return \"a\"; case lib.Config.MODE_B: return \"b\"; default: return \"\" + lib.Config.PACKAGE_ONLY; } } }",
            "class C%d { int f() { lib.Service s = new lib.Service(); try { s.run(); } catch (lib.LibException e) { return -1; } return s.compute(1) + s.compute(1.5) + s.guarded(); } }",
            "class C%d extends lib.Service { @Override public int compute(int x) { return x + 1; } @Override protected int guarded() { return super.guarded(); } }",
            "class C%d { int f() { lib.Holder.Inner i = new lib.Holder.Inner(); lib.Holder.Callback cb = () -> { }; cb.call(); return i.x + lib.Holder.counter(); } }",
            "class C%d { int f(lib.extra.Widget w) { return w.size() + (lib.extra.Gadget.make() == null ? 0 : 1); } }");
    static final String STAR_CLIENT="""
            package app;
            import lib.*;
            import lib.extra.*;
            class Star%d { Widget w; Gadget g; Box<String> b; int f() { return w.size() + Config.MODE_B; } }
            """;

    static Map<String,String> baseline(){
        var units=new LinkedHashMap<String,String>(LIBRARY);
        int index=0;
        for(int copy=0;copy<2;copy++)for(String body:CLIENT_BODIES){
            units.put("app/C"+index+".java","package app;\n"+String.format(body,index)+"\n");index++;
        }
        for(int i=0;i<6;i++)units.put("app/Star"+i+".java",String.format(STAR_CLIENT,i));
        return units;
    }

    // ------------------------------------------------------------------ mutations

    record Mutation(String kind,String unit,String description,Function<String,String> edit,boolean bodyOnly) { }

    static List<Mutation> catalogue(Random random){
        var result=new ArrayList<Mutation>();
        int n=random.nextInt(1000);
        // Body edits: P_diag must not change.
        for(String unit:List.of("lib/Base.java","lib/Util.java","lib/Circle.java","lib/Color.java","lib/Box.java","lib/Service.java","lib/Holder.java","lib/extra/Widget.java","lib/Mapper.java"))
            result.add(new Mutation("body",unit,"statement inserted into first body",text->insertIntoFirstBody(text,"int unused"+n+" = "+n+";"),true));
        result.add(new Mutation("body","lib/Util.java","return expression changed",text->text.replace("return a + b; }\n    public static long","return b + a; }\n    public static long"),true));
        result.add(new Mutation("body","lib/Service.java","throw condition changed",text->text.replace("if (count < 0)","if (count < "+(n%5+1)+")"),true));
        result.add(new Mutation("body","lib/Base.java","statement removed",text->text.replace("protected void hook() { count++; }","protected void hook() { }"),true));
        // Neutral non-body edits: P_diag must be equal, so dependants must be unaffected.
        result.add(new Mutation("neutral","lib/Util.java","javadoc added",text->text.replace("    public static int sum(int a, int b)","    /** Adds "+n+" things. */\n    public static int sum(int a, int b)"),true));
        result.add(new Mutation("neutral","lib/Base.java","comment and formatting",text->text.replace("public int value() { return 1; }","// note "+n+"\n    public int value()    {\n        return 1;\n    }"),true));
        result.add(new Mutation("neutral","lib/Service.java","unused import",text->text.replace("package lib;\n","package lib;\nimport java.util.concurrent.TimeUnit;\n"),true));
        result.add(new Mutation("neutral","lib/Util.java","non-constant initializer",text->text.replace("java.util.List.of(\"a\");","java.util.List.of(\"b"+n+"\");"),true));
        result.add(new Mutation("neutral","lib/Holder.java","local class in body",text->text.replace("public static int counter() { return 0; }","public static int counter() { class Local"+n+" { } return new Local"+n+"().hashCode() * 0; }"),true));
        result.add(new Mutation("neutral","lib/Util.java","parameter renamed",text->text.replace("public static int sum(int a, int b) { return a + b; }",
                "public static int sum(int p"+n+", int b) { return p"+n+" + b; }"),true));
        result.add(new Mutation("neutral","lib/Util.java","parameter made final",text->text.replace("public static int sum(int a, int b)",
                "public static int sum(final int a, int b)"),true));
        result.add(new Mutation("deprecation","lib/Util.java","javadoc @deprecated tag",text->text.replace("    public static int sum(int a, int b)","    /** @deprecated use something else */\n    public static int sum(int a, int b)"),false));
        // Private members.
        for(String unit:List.of("lib/Base.java","lib/Service.java","lib/Box.java","lib/Util.java"))
            result.add(new Mutation("private-added",unit,"private method added",text->beforeLastBrace(text,"    private int extra"+n+"() { return "+n+"; }\n"),false));
        result.add(new Mutation("private-removed","lib/Base.java","private field removed",text->text.replace("    private int secret;\n",""),false));
        result.add(new Mutation("private-renamed","lib/Base.java","private field renamed",text->text.replace("private int secret;","private int secret"+n+";"),false));
        result.add(new Mutation("private-removed","lib/Base.java","private method removed",text->text.replace("    private int hidden() { return 2; }\n",""),false));
        result.add(new Mutation("private-added","lib/Config.java","private constant added",text->beforeLastBrace(text,"    private static final int HIDDEN"+n+" = "+n+";\n"),false));
        // Constants.
        result.add(new Mutation("constant","lib/Config.java","MODE_A value",text->text.replace("MODE_A = 1;","MODE_A = "+other(n%4,1)+";"),false));
        result.add(new Mutation("constant","lib/Config.java","KEY value",text->text.replace("KEY = \"k\";","KEY = \"k"+n+"\";"),false));
        result.add(new Mutation("constant","lib/Base.java","LIMIT value",text->text.replace("LIMIT = 10;","LIMIT = "+other(n,10)+";"),false));
        result.add(new Mutation("constant","lib/Config.java","constant made non-constant",text->text.replace("MODE_B = 2;","MODE_B = Integer.parseInt(\"2\");"),false));
        result.add(new Mutation("constant","lib/Marker.java","annotation default",text->text.replace("default 1;","default "+other(n,1)+";"),false));
        // Signatures.
        result.add(new Mutation("signature","lib/Util.java","parameter type",text->text.replace("sum(int a, int b)","sum(short a, short b)"),false));
        result.add(new Mutation("signature","lib/Util.java","return type",text->text.replace("public static int sum(int a, int b) { return a + b; }","public static long sum(int a, int b) { return a + b; }"),false));
        result.add(new Mutation("signature","lib/Util.java","throws removed",text->text.replace("risky() throws java.io.IOException { if (Boolean.getBoolean(\"x\")) throw new java.io.IOException(); }","risky() { }"),false));
        result.add(new Mutation("signature","lib/Service.java","throws widened",text->text.replace("public void run() throws LibException","public void run() throws LibException, java.io.IOException"),false));
        result.add(new Mutation("signature","lib/Util.java","generic bound",text->text.replace("<T extends Number> T first","<T extends "+(n%2==0?"Integer":"CharSequence")+"> T first"),false));
        result.add(new Mutation("signature","lib/Box.java","class bound",text->text.replace("class Box<T extends Comparable<T>>","class Box<T extends Number & Comparable<T>>"),false));
        result.add(new Mutation("signature","lib/Mapper.java","second abstract method",text->text.replace("B apply(A a);","B apply(A a);\n    void reset"+n+"();"),false));
        result.add(new Mutation("signature","lib/Service.java","overload removed",text->text.replace("    public int compute(double x) { return 1; }\n",""),false));
        // Annotations.
        result.add(new Mutation("annotation","lib/Util.java","@Deprecated removed",text->text.replace("@Deprecated public static int old()","public static int old()"),false));
        result.add(new Mutation("annotation","lib/Util.java","@Deprecated added",text->text.replace("public static int sum(int a, int b)","@Deprecated public static int sum(int a, int b)"),false));
        result.add(new Mutation("annotation","lib/Mapper.java","@FunctionalInterface removed",text->text.replace("@FunctionalInterface\n",""),false));
        result.add(new Mutation("annotation","lib/Util.java","@SafeVarargs removed",text->text.replace("@SafeVarargs public static","public static"),false));
        result.add(new Mutation("annotation","lib/Util.java","@SafeVarargs added",text->text.replace("public static String joined(String... parts)","@SafeVarargs public static String joined(String... parts)"),false));
        // Visibility.
        result.add(new Mutation("visibility","lib/Base.java","value() protected",text->text.replace("public int value()","protected int value()"),false));
        result.add(new Mutation("visibility","lib/Service.java","compute private",text->text.replace("public int compute(int x)","private int compute(int x)"),false));
        result.add(new Mutation("visibility","lib/extra/Widget.java","class package-private",text->text.replace("public class Widget","class Widget"),false));
        result.add(new Mutation("visibility","lib/Config.java","PACKAGE_ONLY public",text->text.replace("static final int PACKAGE_ONLY","public static final int PACKAGE_ONLY"),false));
        result.add(new Mutation("visibility","lib/Service.java","guarded public",text->text.replace("protected int guarded()","public int guarded()"),false));
        // Nested types.
        result.add(new Mutation("nested","lib/Holder.java","nested class removed",text->text.replace("    public static class Inner { public int x; }\n",""),false));
        result.add(new Mutation("nested","lib/Box.java","nested class added",text->beforeLastBrace(text,"    public static class Extra"+n+" { }\n"),false));
        result.add(new Mutation("nested","lib/Holder.java","nested interface removed",text->text.replace("    public interface Callback { void call(); }\n",""),false));
        // Sealed / permits.
        result.add(new Mutation("sealed","lib/Shape.java","sealed removed",text->text.replace("public sealed interface Shape permits Circle, Square","public interface Shape"),false));
        result.add(new Mutation("sealed","lib/Shape.java","permits narrowed",text->text.replace("permits Circle, Square","permits Circle"),false));
        // Records.
        result.add(new Mutation("record","lib/Circle.java","component renamed",text->text.replace("record Circle(double r)","record Circle(double radius)").replace("r * r","radius * radius"),false));
        result.add(new Mutation("record","lib/Square.java","component added",text->text.replace("record Square(double side)","record Square(double side, int tag"+n+")"),false));
        result.add(new Mutation("record","lib/Circle.java","component type",text->text.replace("record Circle(double r)","record Circle(float r)"),false));
        // Enums.
        result.add(new Mutation("enum","lib/Color.java","constant added",text->text.replace("RED, GREEN, BLUE;","RED, GREEN, BLUE, C"+n+";"),false));
        result.add(new Mutation("enum","lib/Color.java","constant removed",text->text.replace("RED, GREEN, BLUE;","RED, GREEN;"),false));
        result.add(new Mutation("enum","lib/Color.java","constants reordered",text->text.replace("RED, GREEN, BLUE;","BLUE, GREEN, RED;"),false));
        // New top-level types in the same package or a star-imported one.
        result.add(new Mutation("top-level","lib/Config.java","colliding top-level type in star-imported package",text->text+"\nclass Widget { }\n",false));
        result.add(new Mutation("top-level","lib/Config.java","colliding public-name top-level type",text->text+"\nclass Gadget { }\n",false));
        result.add(new Mutation("top-level","lib/extra/Gadget.java","unrelated top-level type",text->text+"\nclass Helper"+n+" { }\n",false));
        result.add(new Mutation("top-level","lib/Holder.java","top-level type in same package",text->text+"\nclass Box"+n+" { }\n",false));
        return result;
    }
    static int other(int value,int avoid){return value==avoid?avoid+1000:value;}
    static String insertIntoFirstBody(String text,String statement){
        int brace=text.indexOf(") {");if(brace<0)return text;
        int open=text.indexOf('{',brace);
        // Skip class headers: only insert into a method/constructor body (the first "{ " after a ')').
        return text.substring(0,open+1)+" "+statement+text.substring(open+1);
    }
    static String beforeLastBrace(String text,String member){
        int end=text.lastIndexOf('}');return text.substring(0,end)+member+text.substring(end);
    }

    // ------------------------------------------------------------------ compilation

    record Compiled(Map<String,Hash256> projections,Map<String,List<String>> diagnostics) { }

    static Compiled compile(Map<String,String> units){
        var compiler=ToolProvider.getSystemJavaCompiler();
        var collector=new DiagnosticCollector<JavaFileObject>();
        var sources=new ArrayList<JavaFileObject>();
        for(var entry:units.entrySet())sources.add(Parser.source(URI.create("mem:///"+entry.getKey()),entry.getValue()));
        var task=(JavacTask)compiler.getTask(new java.io.StringWriter(),null,collector,
                List.of("-proc:none","-Xlint:all","-XDshould-stop.at=FLOW","--release","25"),null,sources);
        List<CompilationUnitTree> parsed=new ArrayList<>();
        try{task.parse().forEach(parsed::add);task.analyze();}catch(Exception failure){throw new IllegalStateException(failure);}
        var projections=new TreeMap<String,Hash256>();
        for(var unit:parsed)projections.put(unit.getSourceFile().toUri().getPath().substring(1),DiagnosticProjection.of(task,unit));
        var diagnostics=new TreeMap<String,List<String>>();
        for(String name:units.keySet())diagnostics.put(name,new ArrayList<>());
        for(var diagnostic:collector.getDiagnostics()){
            String name=diagnostic.getSource()==null?"<none>":diagnostic.getSource().toUri().getPath().substring(1);
            diagnostics.computeIfAbsent(name,ignored->new ArrayList<>()).add(diagnostic.getKind()+"|"+diagnostic.getCode()+"|"+diagnostic.getLineNumber()+":"
                    +diagnostic.getColumnNumber()+"|"+diagnostic.getMessage(Locale.ROOT));
        }
        return new Compiled(projections,diagnostics);
    }

    // ------------------------------------------------------------------ the gate

    @Test void unchangedDiagnosticProjectionImpliesUnchangedDependantDiagnostics()throws Exception{
        var base=baseline();
        assertThat(base).hasSizeGreaterThanOrEqualTo(40);
        var reference=compile(base);
        assertThat(reference.diagnostics().values().stream().mapToInt(List::size).sum())
                .as("the fixture exercises private-access and package-access diagnostics").isPositive();
        record Outcome(Mutation mutation,boolean projectionEqual,List<String> mismatches) { }
        var executor=Executors.newFixedThreadPool(Math.max(1,Runtime.getRuntime().availableProcessors()));
        var futures=new ArrayList<Future<Outcome>>();
        for(int i=0;i<MUTATIONS;i++){
            var random=new Random(SEED+i);
            var catalogue=catalogue(random);var mutation=catalogue.get(random.nextInt(catalogue.size()));
            futures.add(executor.submit(()->{
                var units=new LinkedHashMap<>(base);String before=units.get(mutation.unit());String after=mutation.edit().apply(before);
                if(after.equals(before))throw new IllegalStateException("mutation did not apply: "+mutation);
                units.put(mutation.unit(),after);
                var compiled=compile(units);
                boolean equal=compiled.projections().get(mutation.unit()).equals(reference.projections().get(mutation.unit()));
                var mismatches=new ArrayList<String>();
                if(equal)for(String unit:base.keySet())if(!unit.equals(mutation.unit())
                        &&!compiled.diagnostics().get(unit).equals(reference.diagnostics().get(unit)))
                    mismatches.add(unit+": before="+reference.diagnostics().get(unit)+" after="+compiled.diagnostics().get(unit));
                return new Outcome(mutation,equal,mismatches);
            }));
        }
        var failures=new ArrayList<String>();var kinds=new TreeMap<String,int[]>();
        for(var future:futures){
            var outcome=future.get();
            var counts=kinds.computeIfAbsent(outcome.mutation().kind(),ignored->new int[2]);counts[0]++;if(outcome.projectionEqual())counts[1]++;
            if(!outcome.mismatches().isEmpty())failures.add(outcome.mutation().unit()+" "+outcome.mutation().description()+" -> "+outcome.mismatches());
            if(outcome.mutation().bodyOnly()&&!outcome.projectionEqual())
                failures.add("body-only edit changed P_diag: "+outcome.mutation().unit()+" "+outcome.mutation().description());
        }
        executor.shutdown();
        var report=new StringBuilder("P_diag sufficiency: mutations=").append(MUTATIONS).append(" by kind (total/projection-equal): ");
        kinds.forEach((kind,counts)->report.append(kind).append('=').append(counts[0]).append('/').append(counts[1]).append(' '));
        System.out.println(report);
        assertThat(kinds.keySet()).contains("body","neutral","deprecation","private-added","private-removed","private-renamed","constant","signature",
                "annotation","visibility","nested","sealed","record","enum","top-level");
        assertThat(failures).as("sufficiency mismatches").isEmpty();
    }

    /** P_diag is restart-stable and location independent: the same units at two locations agree. */
    @Test void projectionIsIndependentOfCheckoutLocation(){
        var here=compile(baseline());
        var moved=new LinkedHashMap<String,String>();baseline().forEach((name,text)->moved.put("elsewhere/checkout/"+name,text));
        var there=compile(moved);
        for(var entry:here.projections().entrySet())
            assertThat(there.projections().get("elsewhere/checkout/"+entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
    }

    /** Private members are part of P_diag; the API fingerprint alone would miss them. */
    @Test void privateMembersAndConstantsAreIncludedButBodiesAreNot(){
        var base=baseline();var reference=compile(base);
        var privateAdded=new LinkedHashMap<>(base);privateAdded.put("lib/Base.java",beforeLastBrace(base.get("lib/Base.java"),"    private int x() { return 1; }\n"));
        assertThat(compile(privateAdded).projections().get("lib/Base.java")).isNotEqualTo(reference.projections().get("lib/Base.java"));
        var body=new LinkedHashMap<>(base);body.put("lib/Base.java",base.get("lib/Base.java").replace("return 2;","return 3;"));
        assertThat(compile(body).projections().get("lib/Base.java")).isEqualTo(reference.projections().get("lib/Base.java"));
        var constant=new LinkedHashMap<>(base);constant.put("lib/Config.java",base.get("lib/Config.java").replace("MODE_A = 1;","MODE_A = 7;"));
        assertThat(compile(constant).projections().get("lib/Config.java")).isNotEqualTo(reference.projections().get("lib/Config.java"));
    }
}
