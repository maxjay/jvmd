package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.Ann;
import dev.jvmd.index.layer.machine.ClassFacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.TypeKind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** Persist the actual source view independently of T/A and read it only through the committed generation. */
@Tag("phase-3")
class ProcessorSourcesTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    @AfterAll static void release() { Stage2Support.release(); }
    static final String PATH = "app/src/main/java/p/Input.java";
    static final String SOURCE = """
            package p;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.SOURCE) @interface Label { String value() default "default"; int number() default 3; }
            @Retention(RetentionPolicy.SOURCE) @Target(ElementType.TYPE_USE) @interface Use { int value(); }
            /** Input documentation. */ @Label(number=9, value="source")
            public class Input<T extends @Use(1) Number & Comparable<T>> {
                static final int C = -7;
                Object initialized = new Object();
                /** Method documentation. */ <V extends T> @Use(2) String @Use(3) [] call(
                    @Use(4) Input<T> this, @Use(5) String parameter) throws java.io.IOException { return null; }
                class Nested$Name { int first; long second; }
                record Data(@Use(6) String component) { }
            }
            """;

    record State(ContentTree tree, InMemoryLocalStore store, Identity project, LocalRoot local, Stage2.Result result) {
        ProcessorSources sources(String module, int scope) { return ProcessorSources.load(tree, local, project, module, scope, store::get); }
    }
    private State boot(Digest digest, ProjectModel model, InMemoryLocalStore store, int workers) throws Exception {
        var tree = new ContentTree(digest);
        var result = new Stage2(digest, tree, Stage2Support.FEATURE, workers, dir, ClassFacts::of).run(store, model);
        var project = Stage2.projectKey(digest, model);
        var local = LocalRoot.decode(digest, store.get(LocalStore.localRootKey(project)));
        assertThat(local.format()).contains(";local=" + LocalFormat.LAYOUT + ";");
        return new State(tree, store, project, local, result);
    }
    private ProjectModel model() { return ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("app", "g:app:1", List.of()))); }
    private State initial(Digest digest) throws Exception {
        Stage2Support.write(dir, Map.of(PATH, SOURCE));
        var state = boot(digest, model(), Stage2Support.jdkOnly(digest).copy(), 2);
        assertThat(state.result().faults()).isEmpty(); return state;
    }
    private static ProcessorDeclaration.TypeDeclaration type(ProcessorDeclaration declaration) {
        return (ProcessorDeclaration.TypeDeclaration) declaration.detail();
    }
    private static ProcessorDeclaration member(ProcessorDeclaration declaration, String name) {
        return type(declaration).enclosed().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }
    private static long use(ProcessorDeclaration.Type type) {
        return ((Ann.Val.Prim) type.annotations().getFirst().explicit().elements().getFirst().value()).bits();
    }

    @ParameterizedTest @MethodSource("digests")
    void sourceMetadataIsQueryableAfterJavacClosesAndSourceBytesDisappear(Digest digest) throws Exception {
        var state = initial(digest); Files.delete(dir.resolve(PATH));
        var sources = state.sources("app", 0); var input = sources.type("p/Input");
        assertThat(input.path()).isEqualTo(PATH);
        assertThat(input.declaration().docComment()).isEqualTo("Input documentation. ");
        assertThat(input.declaration().modifiers()).containsExactly("PUBLIC");
        var label = input.declaration().annotations().getFirst();
        assertThat(label.explicit().descriptor()).isEqualTo("Lp/Label;");
        assertThat(label.explicit().elements()).extracting(Ann.Element::name).containsExactly("number", "value");
        assertThat(label.explicit().elements().get(1).value()).isEqualTo(new Ann.Val.Str("source"));
        var bound = (ProcessorDeclaration.Parameter) type(input.declaration()).parameters().getFirst().detail();
        assertThat(use(bound.bounds().getFirst())).isEqualTo(1);
        var comparable = (ProcessorDeclaration.Declared) bound.bounds().get(1).shape();
        assertThat(comparable.arguments().getFirst().kind()).isEqualTo(TypeKind.TYPEVAR);
        var constant = (ProcessorDeclaration.Variable) member(input.declaration(), "C").detail();
        assertThat((int) ((Ann.Val.Prim) constant.constant()).bits()).isEqualTo(-7);
        var method = member(input.declaration(), "call");
        assertThat(method.docComment()).isEqualTo("Method documentation. ");
        var signature = (ProcessorDeclaration.Executable) method.detail();
        assertThat(use(signature.returns())).isEqualTo(3);
        assertThat(use(((ProcessorDeclaration.Array) signature.returns().shape()).component())).isEqualTo(2);
        assertThat(use(signature.receiver())).isEqualTo(4);
        assertThat(signature.parameters().getFirst().name()).isEqualTo("parameter");
        assertThat(use(((ProcessorDeclaration.Variable) signature.parameters().getFirst().detail()).type())).isEqualTo(5);
        assertThat(((ProcessorDeclaration.Declared) signature.thrown().getFirst().shape()).binaryName()).isEqualTo("java.io.IOException");
        var nested = sources.type("p/Input$Nested$Name");
        assertThat(nested.declaration()).isEqualTo(member(input.declaration(), "Nested$Name"));
        assertThat(type(nested.declaration()).enclosed()).extracting(ProcessorDeclaration::name).containsExactly("<init>", "first", "second");
        assertThat(sources.type("p/Input$Nested")).isNull();
        var record = sources.type("p/Input$Data").declaration();
        assertThat(record.kind()).isEqualTo(ElementKind.RECORD);
        assertThat(type(record).components().getFirst().name()).isEqualTo("component");
        assertThat(use(((ProcessorDeclaration.Other) type(record).components().getFirst().detail()).type())).isEqualTo(6);
        var labelType = sources.type("p/Label").declaration();
        assertThat(((ProcessorDeclaration.Executable) member(labelType, "value").detail()).explicitDefault()).isEqualTo(new Ann.Val.Str("default"));
        assertThat(state.sources("app", 1).root().count()).isZero();
        assertThat(sources.packageMembers("p")).containsExactly("p.Label", "p.Use", "p.Input");
        assertThat(sources.packageMembers("missing")).isNull();
        assertThat(state.store().readsBeforeRoot()).doesNotContain("PM");
        assertThat(state.store().events()).noneMatch(e -> e.startsWith("prefix:"));
        assertThatThrownBy(() -> type(input.declaration()).enclosed().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest @MethodSource("digests")
    void bodyChangesPreserveSourceModelWhileSourceOnlyMetadataMovesIt(Digest digest) throws Exception {
        var before = initial(digest); var root = before.sources("app", 0).root();
        for (var body : List.of(SOURCE.replace("return null;", "class Local {} int value=missing(); return null;"),
                SOURCE.replace("new Object()", "new String(\"different\")"))) {
            Stage2Support.write(dir, Map.of(PATH, body));
            var after = boot(digest, model(), before.store().copy(), 1);
            assertThat(after.result().faults()).isEmpty();
            assertThat(after.sources("app", 0).root()).isEqualTo(root);
            assertThat(after.sources("app", 0).type("p/Input$1Local")).isNull();
        }
        for (var source : List.of(SOURCE.replace("value=\"source\"", "value=\"changed\""),
                SOURCE.replace("String parameter", "String renamed"), SOURCE.replace("Input documentation.", "Changed documentation."),
                SOURCE.replace("int first; long second;", "long second; int first;"))) {
            Stage2Support.write(dir, Map.of(PATH, source));
            var after = boot(digest, model(), before.store().copy(), 2);
            assertThat(after.result().faults()).isEmpty();
            assertThat(after.sources("app", 0).root()).isNotEqualTo(root);
            assertThat(after.result().leaves()).isEqualTo(before.result().leaves());
            assertThat(after.result().annotations()).isEqualTo(before.result().annotations());
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void scopeRootsDoNotDependOnProcessorPresenceWorkerCountOrModuleOrder(Digest digest) throws Exception {
        Stage2Support.write(dir, Map.of(PATH, SOURCE, "consumer/src/main/java/q/Use.java", "package q; class Use { p.Input<?> input; }"));
        var app = new Stage2Support.Mod("app", "g:app:1", List.of());
        var consumer = new Stage2Support.Mod("consumer", "g:consumer:1", List.of(Stage2Support.Dep.module("g:app:1", "app")));
        var first = boot(digest, ProjectModel.parse(Stage2Support.model(dir, app, consumer)), Stage2Support.jdkOnly(digest).copy(), 1);
        var second = boot(digest, ProjectModel.parse(Stage2Support.model(dir, consumer, app)), first.store().copy(), 4);
        assertThat(first.result().faults()).isEmpty(); assertThat(second.result().faults()).isEmpty();
        assertThat(second.result().root()).isEqualTo(first.result().root());
        assertThat(second.sources("app", 0).root()).isEqualTo(first.sources("app", 0).root());
        assertThat(second.sources("consumer", 0).type("p/Input")).isNull();
        assertThat(second.sources("app", 0).type("p/Input").declaration().annotations()).hasSize(1);
        assertThat(second.sources("consumer", 0).type("q/Use")).isNotNull();
        assertThat(second.store().readsBeforeRoot()).doesNotContain("PM");
    }

    @ParameterizedTest @MethodSource("digests")
    void staleAndTamperedRecordsCannotSupplyCurrentDeclarations(Digest digest) throws Exception {
        var before = initial(digest); var old = before.sources("app", 0);
        Stage2Support.write(dir, Map.of(PATH, "package p; public class Input {}"));
        var current = boot(digest, model(), before.store().copy(), 2);
        assertThat(current.sources("app", 0).type("p/Input$Data")).isNull();
        assertThat(old.type("p/Input$Data")).isNotNull();
        var fake = LocalStore.processorSourcesKey(current.project(), "fake", 0);
        current.store().put(fake, DefinerIndex.encodeRoot(old.root())); current.store().flush();
        var reads = current.store().watchReads(fake);
        assertThatThrownBy(() -> current.sources("fake", 0)).hasMessageContaining("not in the committed LOCAL tree");
        assertThat(reads.get()).isZero();
        var key = LocalStore.processorSourcesKey(current.project(), "app", 0);
        current.store().put(key, DefinerIndex.encodeRoot(old.root())); current.store().flush();
        var expected = current.sources("app", 0).root();
        assertThat(expected).isNotEqualTo(old.root());
        var entry = current.tree().get(current.local().local().hash(),
                id -> current.store().get(dev.jvmd.index.layer.machine.MachineStore.nodeKey(id)), key);
        current.store().put(LocalStore.bodyValueKey(entry.h()), new byte[]{42}); current.store().flush();
        assertThatThrownBy(() -> current.sources("app", 0)).hasMessageContaining("Rooted record digest mismatch");
    }

    @ParameterizedTest @MethodSource("digests")
    void f03HistoricalLocalAndInheritedBodyValuesSurviveCurrentBindingReplacement(Digest digest) throws Exception {
        var before = initial(digest);
        var values = new java.util.TreeMap<byte[], byte[]>(java.util.Arrays::compareUnsigned);
        before.tree().forEach(before.local().local().hash(),
                id -> before.store().get(dev.jvmd.index.layer.machine.MachineStore.nodeKey(id)),
                entry -> values.put(entry.key(), RootedRecords.value(before.tree(), before.store()::get, entry)));
        var body = dev.jvmd.boot.cold.stage3.BodyGeneration.begin(
                before.tree(), before.store(), before.project(), before.local()).commit(Map.of());
        Stage2Support.write(dir, Map.of(PATH, "package p; public class Input { public String changed; }"));
        var after = boot(digest, model(), before.store(), 2);
        assertThat(after.local().local()).isNotEqualTo(before.local().local());
        assertThat(ProcessorSources.load(before.tree(), before.local(), before.project(), "app", 0,
                after.store()::get).type("p/Input$Data")).isNotNull();
        assertThat(after.sources("app", 0).type("p/Input$Data")).isNull();
        before.tree().forEach(before.local().local().hash(),
                id -> after.store().get(dev.jvmd.index.layer.machine.MachineStore.nodeKey(id)), entry -> {
            assertThat(RootedRecords.value(before.tree(), after.store()::get, entry)).isEqualTo(values.get(entry.key()));
            assertThat(BodyRecords.read(before.tree(), after.store(), body.bodiesRoot(), entry.key()))
                    .isEqualTo(values.get(entry.key()));
        });
    }

    @ParameterizedTest @MethodSource("digests")
    void annotationIterationOrderIsPreservedInTheProjection(Digest digest) throws Exception {
        var before = initial(digest);
        Stage2Support.write(dir, Map.of(PATH, SOURCE.replace("number=9, value=\"source\"", "value=\"source\", number=9")));
        var after = boot(digest, model(), before.store().copy(), 1);
        assertThat(after.sources("app", 0).type("p/Input").declaration().annotations().getFirst().explicit().elements())
                .extracting(Ann.Element::name).containsExactly("value", "number");
        assertThat(after.sources("app", 0).root()).isNotEqualTo(before.sources("app", 0).root());
        assertThat(after.result().leaves()).isEqualTo(before.result().leaves());
        assertThat(after.result().annotations()).isEqualTo(before.result().annotations());
    }

    @ParameterizedTest @MethodSource("digests")
    void anInvalidAnnotationIsAnUnavailableDeclarationAndDoesNotAbortTheScope(Digest digest) throws Exception {
        Stage2Support.write(dir, Map.of(PATH, SOURCE.replace("number=9", "number=\"invalid\""),
                "app/src/main/java/p/Healthy.java", "package p; class Healthy {}"));
        var state = boot(digest, model(), Stage2Support.jdkOnly(digest).copy(), 2);
        assertThat(state.result().faults()).anyMatch(s -> s.contains("processor declaration:"));
        assertThat(state.sources("app", 0).type("p/Healthy")).isNotNull();
        assertThatThrownBy(() -> state.sources("app", 0).type("p/Input"))
                .isInstanceOf(ProcessorSources.Unavailable.class).hasMessageContaining(PATH);
    }

    @ParameterizedTest @MethodSource("digests")
    void exactJavaStringsAndNestedAnnotationValuesSurviveDetachment(Digest digest) throws Exception {
        String source = """
                package p;
                import java.lang.annotation.*;
                @interface Nested { String value() default "default"; }
                enum Choice { ONE, TWO }
                @Retention(RetentionPolicy.SOURCE) @interface Label {
                    String value(); Nested nested(); Nested[] array(); Class<?> type(); Choice choice();
                }
                /** DOC */ @Label(value="VALUE", nested=@Nested, array={@Nested("ARRAY")}, type=int[].class, choice=Choice.TWO)
                public class Input { static final String CONSTANT="CONSTANT"; }
                """;
        String escaped = "\\" + "uD800", value = Character.toString((char) 0xd800);
        Stage2Support.write(dir, Map.of(PATH, source.replace("DOC", escaped).replace("VALUE", escaped).replace("ARRAY", escaped).replace("\"CONSTANT\"", "\""+escaped+"\"")));
        var state = boot(digest, model(), Stage2Support.jdkOnly(digest).copy(), 2);
        assertThat(state.result().faults()).isEmpty();
        var declaration = state.sources("app", 0).type("p/Input").declaration();
        assertThat(declaration.docComment()).isEqualTo(value + " ");
        var annotation = declaration.annotations().getFirst();
        assertThat(annotation.explicit().elements().getFirst().value()).isEqualTo(new Ann.Val.Str(value));
        assertThat(((Ann.Val.Nested) annotation.explicit().elements().get(1).value()).annotation().elements()).isEmpty();
        assertThat(((Ann.Val.Nested) annotation.effective().elements().get(1).value()).annotation().elements().getFirst().value())
                .isEqualTo(new Ann.Val.Str("default"));
        var array = (Ann.Val.Array) annotation.explicit().elements().get(2).value();
        assertThat(((Ann.Val.Nested) array.values().getFirst()).annotation().elements().getFirst().value()).isEqualTo(new Ann.Val.Str(value));
        assertThat(annotation.explicit().elements().get(3).value()).isEqualTo(new Ann.Val.Cls("[I"));
        assertThat(annotation.explicit().elements().get(4).value()).isEqualTo(new Ann.Val.Enum("Lp/Choice;", "TWO"));
        assertThat(((ProcessorDeclaration.Variable) member(declaration, "CONSTANT").detail()).constant()).isEqualTo(new Ann.Val.Str(value));
        Stage2Support.write(dir, Map.of(PATH, source.replace("DOC", "?").replace("VALUE", "?").replace("ARRAY", "?").replace("\"CONSTANT\"", "\"?\"")));
        var other = boot(digest, model(), state.store().copy(), 2);
        assertThat(other.sources("app", 0).root()).isNotEqualTo(state.sources("app", 0).root());
    }

    @ParameterizedTest @MethodSource("digests")
    void queryBindingRetainsSourceOriginsWhenScopesHaveTheSameTypeIdentity(Digest digest) throws Exception {
        Stage2Support.write(dir, Map.of("a/src/main/java/p/Metadata.java", "package p; /** first */ public class Metadata {}",
                "b/src/main/java/p/Metadata.java", "package p; /** second */ public class Metadata {}",
                "app/src/main/java/p/Input.java", "package p; public class Input {}"));
        var a = new Stage2Support.Mod("a", "g:a:1", List.of()); var b = new Stage2Support.Mod("b", "g:b:1", List.of());
        var first = new Stage2Support.Mod("app", "g:app:1", List.of(Stage2Support.Dep.module("g:a:1", "a"), Stage2Support.Dep.module("g:b:1", "b")));
        var state = boot(digest, ProjectModel.parse(Stage2Support.model(dir, a, b, first)), Stage2Support.jdkOnly(digest).copy(), 2);
        assertThat(state.result().leaves().get("a/main")).isEqualTo(state.result().leaves().get("b/main"));
        var sources = ProcessorSources.bind(state.tree(), state.local(), state.project(), "app", 0, state.store()::get);
        assertThat(sources.apply("p/Metadata").declaration().docComment()).isEqualTo("first ");
        var reversed = new Stage2Support.Mod("app", "g:app:1", List.of(Stage2Support.Dep.module("g:b:1", "b"), Stage2Support.Dep.module("g:a:1", "a")));
        var changed = boot(digest, ProjectModel.parse(Stage2Support.model(dir, a, b, reversed)), state.store().copy(), 2);
        var current = ProcessorSources.bind(changed.tree(), changed.local(), changed.project(), "app", 0, changed.store()::get);
        assertThat(current.apply("p/Metadata").declaration().docComment()).isEqualTo("second ");
        // An earlier binary definer shadows both source declarations, even with the same T identity.
        var jar = Stage2Support.pack(dir.resolve("binary.jar"), Stage2Support.compile(dir.resolve("binary"),
                Map.of("p/Metadata.java", "package p; public class Metadata {}"), List.of(), List.of()));
        var external = new Stage2Support.Mod("app", "g:app:1", List.of(Stage2Support.Dep.jar("g:binary:1", jar.toString()), Stage2Support.Dep.module("g:a:1", "a")));
        var shadow = boot(digest, ProjectModel.parse(Stage2Support.model(dir, a, external)), state.store().copy(), 2);
        var binding = ProcessorSources.bind(shadow.tree(), shadow.local(), shadow.project(), "app", 0, shadow.store()::get);
        assertThat(binding.apply("p/Metadata")).isNull(); assertThat(binding.apply("p/Input").path()).isEqualTo(PATH);
    }

    @ParameterizedTest @MethodSource("digests")
    void packageHeadersPersistWithoutEnumeratingMembersOrReopeningSources(Digest digest) throws Exception {
        String info="app/src/main/java/p/package-info.java";
        Stage2Support.write(dir,Map.of(PATH,"package p; public class Input {}",info,"""
                /// Package **documentation**.
                @Deprecated(since="source") package p;
                """));
        var state=boot(digest,model(),Stage2Support.jdkOnly(digest).copy(),2);
        assertThat(state.result().faults()).isEmpty();Files.delete(dir.resolve(info));
        var header=state.sources("app",0).packageHeader("p");
        assertThat(header.path()).isEqualTo(info);
        assertThat(header.declaration().detail()).isEqualTo(new ProcessorDeclaration.PackageHeader("p"));
        assertThat(header.declaration().kind()).isEqualTo(ElementKind.PACKAGE);
        assertThat(header.declaration().docComment()).contains("Package **documentation**.");
        assertThat(header.declaration().docCommentKind()).isEqualTo(javax.lang.model.util.Elements.DocCommentKind.END_OF_LINE);
        assertThat(header.declaration().annotations()).hasSize(1);
        assertThat(header.declaration().annotations().getFirst().explicit().descriptor()).isEqualTo("Ljava/lang/Deprecated;");
        assertThat(state.sources("app",0).packageHeader("q")).isNull();
        assertThat(state.sources("app",0).type("p/package-info")).isNull();
        assertThat(ProcessorSources.bind(state.tree(),state.local(),state.project(),"app",0,state.store()::get).packageHeader("p")).isEqualTo(header);
    }

    @ParameterizedTest @MethodSource("digests")
    void f10QueryWorkIsIndependentOfIrrelevantRouteLength(Digest digest) throws Exception {
        var state=initial(digest);var tree=state.tree();var store=state.store();
        var key=LocalStore.routeKey(state.project(),"app",0);
        var route=Route.decode(store.get(key),digest.width());Long baseline=null;
        for(int size:new int[]{0,128,4096}) {
            var entries=new java.util.ArrayList<>(route.entries());
            for(int i=0;i<size;i++)entries.add(route.entries().getFirst());
            var bytes=new Route(entries,route.routeHash(),route.r(),route.leafSetExt(),route.leafSetSib()).encode();
            var hash=digest.hash(bytes);store.put(LocalStore.bodyValueKey(hash),bytes);store.flush();
            var changed=tree.apply(state.local().local(),List.of(key),List.of(new dev.jvmd.core.tree.Entry(key,new byte[0],hash)),
                    id->store.get(dev.jvmd.index.layer.machine.MachineStore.nodeKey(id)),store);store.flush();
            var local=LocalRoot.decode(digest,LocalRoot.encode(digest,state.local().format(),changed,state.local().machineRoot(),state.local().modelHash()));
            var bound=ProcessorSources.bind(tree,local,state.project(),"app",0,store::get);
            int before=store.events().size();
            for(int i=0;i<100;i++) {
                assertThat(bound.apply("missing/T"+i)).isNull();
                assertThat(bound.packageHeader("missing.p"+i)).isNull();
                assertThat(bound.packageExists("missing.p"+i)).isFalse();
            }
            long reads=store.events().subList(before,store.events().size()).stream().filter(e->e.equals("read:N")).count();
            if(baseline==null)baseline=reads;else assertThat(reads).isEqualTo(baseline);
            System.out.println("F10 "+digest.getClass().getSimpleName()+" extra route origins="+size+" node reads="+reads);
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void packageBindingUsesPackageInfoOriginsEvenWhenTypeLeavesAreEqual(Digest digest) throws Exception {
        Stage2Support.write(dir,Map.of("a/src/main/java/p/A.java","package p; /** first type */ public class A {}",
                "a/src/main/java/p/package-info.java","/** first */ package p;",
                "b/src/main/java/p/A.java","package p; /** second type */ public class A {}",
                "b/src/main/java/p/package-info.java","/** second */ package p;",PATH,"package p; public class Input {}"));
        var a=new Stage2Support.Mod("a","g:a:1",List.of());var b=new Stage2Support.Mod("b","g:b:1",List.of());
        var app=new Stage2Support.Mod("app","g:app:1",List.of(Stage2Support.Dep.module("g:a:1","a"),Stage2Support.Dep.module("g:b:1","b")));
        var first=boot(digest,ProjectModel.parse(Stage2Support.model(dir,a,b,app)),Stage2Support.jdkOnly(digest).copy(),2);
        assertThat(first.result().leaves().get("a/main")).isEqualTo(first.result().leaves().get("b/main"));
        assertThat(ProcessorSources.bind(first.tree(),first.local(),first.project(),"app",0,first.store()::get).apply("p/A").declaration().docComment()).isEqualTo("first type ");
        assertThat(ProcessorSources.bind(first.tree(),first.local(),first.project(),"app",0,first.store()::get).packageHeader("p").declaration().docComment()).isEqualTo("first ");
        var reversed=new Stage2Support.Mod("app","g:app:1",List.of(Stage2Support.Dep.module("g:b:1","b"),Stage2Support.Dep.module("g:a:1","a")));
        var second=boot(digest,ProjectModel.parse(Stage2Support.model(dir,a,b,reversed)),first.store().copy(),2);
        assertThat(ProcessorSources.bind(second.tree(),second.local(),second.project(),"app",0,second.store()::get).apply("p/A").declaration().docComment()).isEqualTo("second type ");
        assertThat(ProcessorSources.bind(second.tree(),second.local(),second.project(),"app",0,second.store()::get).packageHeader("p").declaration().docComment()).isEqualTo("second ");
        var jar=Stage2Support.pack(dir.resolve("package.jar"),Stage2Support.compile(dir.resolve("binary-package"),
                Map.of("p/package-info.java","@Deprecated package p;"),List.of(),List.of()));
        var binary=new Stage2Support.Mod("app","g:app:1",List.of(Stage2Support.Dep.jar("g:package:1",jar.toString()),Stage2Support.Dep.module("g:a:1","a")));
        var shadow=boot(digest,ProjectModel.parse(Stage2Support.model(dir,a,binary)),first.store().copy(),2);
        assertThat(ProcessorSources.bind(shadow.tree(),shadow.local(),shadow.project(),"app",0,shadow.store()::get).packageHeader("p")).isNull();
        Stage2Support.write(dir,Map.of("app/src/main/java/p/package-info.java","/** own */ package p;"));
        var own=boot(digest,ProjectModel.parse(Stage2Support.model(dir,a,binary)),shadow.store().copy(),2);
        assertThat(ProcessorSources.bind(own.tree(),own.local(),own.project(),"app",0,own.store()::get).packageHeader("p").declaration().docComment()).isEqualTo("own ");
    }

    @ParameterizedTest @MethodSource("digests")
    void faultedPackageMetadataRemainsUnavailableInItsCurrentRoot(Digest digest) throws Exception {
        Stage2Support.write(dir,Map.of(PATH,"package p; public class Input {} @interface Label {int value();}",
                "app/src/main/java/p/package-info.java","@p.Label(\"invalid\") package p;"));
        var state=boot(digest,model(),Stage2Support.jdkOnly(digest).copy(),2);
        assertThat(state.result().faults()).anyMatch(f->f.contains("processor package:"));
        assertThat(state.sources("app",0).type("p/Input")).isNotNull();
        var bound=ProcessorSources.bind(state.tree(),state.local(),state.project(),"app",0,state.store()::get);
        assertThat(bound.packageExists("p")).isTrue();
        assertThatThrownBy(()->bound.packageHeader("p")).isInstanceOf(ProcessorSources.Unavailable.class)
                .hasMessageContaining("app/src/main/java/p/package-info.java");
    }

    @ParameterizedTest @MethodSource("digests")
    void malformedDeclarationLengthsAreRejectedBeforeAllocation(Digest digest) throws Exception {
        var state = initial(digest);
        var bytes = new dev.jvmd.core.tree.Codec.Writer().zstr(PATH).u32(Integer.MAX_VALUE).toBytes();
        assertThatThrownBy(() -> ProcessorDeclaration.decode(bytes)).isInstanceOf(IllegalArgumentException.class);
        var key = dev.jvmd.index.layer.machine.Keys.typeKey("p/Input");
        var entry = state.tree().get(state.sources("app", 0).root().hash(), h -> state.store().get(dev.jvmd.index.layer.machine.MachineStore.nodeKey(h)), key);
        var reference=new dev.jvmd.core.tree.Codec.Reader(entry.value());
        assertThat(reference.u8()).isEqualTo(3);reference.zstr();var id=reference.id(digest.width());
        var projection=state.store().get(LocalStore.processorDeclarationKey(id));
        var truncated=java.util.Arrays.copyOf(projection,projection.length-1);
        var trailing=java.util.Arrays.copyOf(projection,projection.length+1);
        assertThatThrownBy(() -> new ProcessorDeclaration.Graph(digest,ignored->truncated).get(digest.hash(truncated)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProcessorDeclaration.Graph(digest,ignored->trailing).get(digest.hash(trailing)))
                .hasMessage("Trailing processor declaration node bytes");
    }

    @ParameterizedTest @MethodSource("digests")
    void f13NestedDeclarationsUseSharedNodesAndLazyDirectReads(Digest digest) throws Exception {
        for(int depth:new int[]{4,16,64}) {
            var source=new StringBuilder("package p; public class Input {");
            for(int i=0;i<depth;i++)source.append("static class N").append(Integer.toString(i,36)).append(" {");
            source.append("/** ").append("metadata ".repeat(1024)).append("*/ int value;");
            source.append("}".repeat(depth+1));
            Stage2Support.write(dir,Map.of(PATH,source.toString()));
            var state=boot(digest,model(),Stage2Support.jdkOnly(digest).copy(),1);
            assertThat(state.result().faults()).isEmpty();
            var sources=state.sources("app",0);
            state.tree().forEach(sources.root().hash(),id->state.store().get(dev.jvmd.index.layer.machine.MachineStore.nodeKey(id)),entry->{
                if(entry.value()[0]==3)assertThat(entry.value()).hasSizeLessThan(128);
            });
            var watched=new java.util.ArrayList<java.util.concurrent.atomic.AtomicInteger>();
            state.store().withPrefix("PE").keySet().forEach(key->watched.add(state.store().watchReads(key)));
            var declaration=sources.type("p/Input").declaration();
            assertThat(watched.stream().mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum()).isOne();
            String name="p/Input";
            for(int i=0;i<depth;i++) {
                name+="$N"+Integer.toString(i,36);
                var direct=sources.type(name).declaration();
                assertThat(member(declaration,"N"+Integer.toString(i,36))).isSameAs(direct);
                declaration=direct;
            }
            assertThat(member(declaration,"value").docComment()).contains("metadata");
            assertThat(watched).allSatisfy(count->assertThat(count.get()).isLessThanOrEqualTo(1));
            var leaf=declaration;
            assertThatThrownBy(()->type(leaf).enclosed().clear()).isInstanceOf(UnsupportedOperationException.class);
            long bytes=state.store().withPrefix("PE").values().stream().mapToLong(value->value.length).sum();
            System.out.println("F13 "+digest.getClass().getSimpleName()+" nesting="+depth+" declarations="+watched.size()+" bytes="+bytes);
        }
    }
}
