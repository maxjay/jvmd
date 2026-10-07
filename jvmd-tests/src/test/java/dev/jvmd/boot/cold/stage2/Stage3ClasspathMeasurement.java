package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.Attribute;
import dev.jvmd.boot.cold.stage3.Stage3;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.MachineStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** Independent whole-scope javac against freshly compiled dependencies, for the accepted classpath boundary. */
@Tag("benchmark")
class Stage3ClasspathMeasurement {
    @TempDir Path work;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE,new Digests.Sha3()); }
    record Message(String path,ResultRecord.Diagnostic diagnostic) { }
    record NativeScope(Path directory,Map<String,byte[]> classes,List<Message> messages,int descriptors) { }

    @ParameterizedTest @MethodSource("digests")
    void compareEveryClasspathScope(Digest digest) throws Exception {
        var root=Path.of(System.getProperty("jvmd.stage3.project",Path.of("..").toAbsolutePath().normalize().toString())).toAbsolutePath().normalize();
        var repository=MavenProjectTest.repository();
        var supplied=MavenModelHelper.build(root,repository,Stage2Support.FEATURE);
        var model=ProjectModel.parse(supplied.json());var tree=new ContentTree(digest);
        var store=Stage2Support.jdkOnly(digest).copy();int workers=Integer.getInteger("jvmd.stage3.workers",4);
        var headers=new Stage2(digest,tree,Stage2Support.FEATURE,workers,repository,ClassFacts::of).run(store,model);
        assertThat(headers.faults()).isEmpty();var project=Stage2.projectKey(digest,model);
        var nativeScopes=new LinkedHashMap<String,NativeScope>();
        for(var module:Order.of(model).modules())for(int scope:new int[]{LocalStore.MAIN,LocalStore.TEST}) {
            assertThat(module.processing()).as("this repository oracle's processor policy").isEqualTo(ProjectModel.Processing.NONE);
            var classpath=new ArrayList<Path>();
            if(scope==LocalStore.TEST) {
                classpath.add(nativeScopes.get(module.name()+"/main").directory());
                dependencies(module.main().dependencies(),classpath,nativeScopes,repository);
            }
            dependencies(module.scope(scope).dependencies(),classpath,nativeScopes,repository);
            var descriptor=ModuleRecord.decode(store.get(LocalStore.moduleKey(project,module.name())));
            var options=Attribute.Options.unprocessed(digest,descriptor,Path.of(model.jdkHome()));
            var files=new java.util.TreeSet<Path>();
            for(var sourceRoot:module.scope(scope).sourceRoots()) {
                var directory=model.resolve(sourceRoot);
                if(Files.isDirectory(directory))try(var paths=Files.walk(directory)) {
                    files.addAll(paths.filter(p->p.toString().endsWith(".java")).toList());
                }
            }
            var key=module.name()+(scope==0?"/main":"/test");
            var result=compile(model,new ArrayList<>(files),classpath,options,descriptor.javacOptions(),work.resolve(key));
            assertThat(result.messages().stream().filter(m->m.diagnostic().kind()==0).toList()).as("native "+key).isEmpty();
            nativeScopes.put(key,result);
        }
        var actual=new Stage3(digest,tree,Stage2Support.FEATURE,workers,repository).run(store,model);
        var failures=new ArrayList<String>();var report=new StringBuilder("digest: "+digest.name()+"\n");
        report.append("project: ").append(root).append("\nworkers: ").append(workers).append("\nstage3 wall ms: ").append(actual.wallMillis()).append('\n');
        int equal=0,descriptors=0,files=0,classCount=0,rejected=0;
        var admissionFaults=new java.util.TreeSet<String>();
        for(var scope:actual.scopes().entrySet()) {
            int slash=scope.getKey().lastIndexOf('/');String module=scope.getKey().substring(0,slash);int kind=scope.getKey().endsWith("/main")?0:1;
            var expected=nativeScopes.get(scope.getKey());var classes=new TreeMap<String,byte[]>();var messages=new ArrayList<Message>();
            descriptors+=expected.descriptors();
            for(var file:scope.getValue().files()) {
                var computed=file.computed();
                var proof=tree.get(actual.bodies().bodiesRoot(),id->store.get(MachineStore.nodeKey(id)),LocalStore.proofKey(project,module,kind,file.path()));
                if(file.path().endsWith("/module-info.java") && computed.result().attributed()) {
                    assertThat(computed.proof()).isNull();assertThat(proof).isNull();
                    assertThat(store.get(LocalStore.usesKey(computed.aci()))).isNull();
                } else {
                    assertThat(computed.proof()).isNotNull();assertThat(proof).isNotNull();
                }
                if(computed.reusable()) {
                    assertThat(computed.faults()).isEmpty();
                    assertThat(tree.get(actual.bodies().bodiesRoot(),id->store.get(MachineStore.nodeKey(id)),LocalStore.resultKey(computed.aci()))).isNotNull();
                } else {
                    rejected++;
                    assertThat(computed.faults()).contains("unsupported for reuse: retained binary metadata reads have no exact proof projection");
                    for(var fault:computed.faults())admissionFaults.add(module+"/"+kind+": "+file.path()+": "+fault);
                    assertThat(computed.proof().reusable()).isFalse();assertThat(computed.aci()).isNull();
                }
                files++;
                for(var c:computed.result().classFiles())classes.put(c.internalName(),store.get(LocalStore.classFileKey(c.contentHash())));

            }
            for(var message:scope.getValue().diagnostics())messages.add(new Message(message.path(),message.diagnostic()));
            boolean same=true;
            var missing=new java.util.TreeSet<>(expected.classes().keySet());missing.removeAll(classes.keySet());
            var extra=new java.util.TreeSet<>(classes.keySet());extra.removeAll(expected.classes().keySet());
            if(!missing.isEmpty() || !extra.isEmpty()) {same=false;failures.add(scope.getKey()+" missing="+missing+" extra="+extra);}
            var different=new ArrayList<String>();
            for(var e:classes.entrySet())if(expected.classes().containsKey(e.getKey()) && !Arrays.equals(e.getValue(),expected.classes().get(e.getKey()))) {
                different.add(e.getKey());
                var dump=Path.of("target/stage3-byte-diff",digest.name(),scope.getKey(),e.getKey());
                Files.createDirectories(dump.getParent());
                Files.write(Path.of(dump+".native.class"),expected.classes().get(e.getKey()));
                Files.write(Path.of(dump+".body.class"),e.getValue());
            }
            if(!different.isEmpty()) {same=false;failures.add(scope.getKey()+" different class bytes="+different);}
            if(!messages.equals(expected.messages())) {
                same=false;failures.add(scope.getKey()+" diagnostics differ\n  native="+expected.messages()+"\n  body="+messages);
            }
            if(same)equal++;classCount+=classes.size();
            report.append(scope.getKey()).append(" files=").append(scope.getValue().files().size()).append(" classes=").append(classes.size()).append(" equal=").append(same).append('\n');
        }
        report.append("classpath equality: ").append(equal).append('/').append(nativeScopes.size()).append(" scopes; files=").append(files).append(" classes=").append(classCount).append('\n');
        report.append("module descriptors included in byte oracle: ").append(descriptors).append("; emitted=").append(actual.scopes().values().stream().mapToInt(Stage3.Scope::descriptorEmissions).sum()).append('\n');
        report.append("temporary metadata non-reuse boundary: ").append(rejected).append(" files; fresh native equality is independent of reuse admission\n");
        Files.write(Path.of("target/stage3-admission-faults-"+digest.name()+".txt"),admissionFaults);
        for(var failure:failures)report.append(failure).append('\n');
        Files.writeString(Path.of("target/stage3-classpath-"+digest.name()+".txt"),report);
        System.out.println(report);
        // The bridge now supplies actual admission reasons in addition to the retained guard.
        // Every reported fault must still belong to an explicitly non-reusable unit above.
        assertThat(actual.faults()).containsExactlyInAnyOrderElementsOf(admissionFaults);
        assertThat(failures).isEmpty();
        assertThat(actual.bodies().current(LocalRoot.decode(digest,store.get(LocalStore.localRootKey(project))))).isTrue();
    }

    private static void dependencies(List<ProjectModel.Dependency> dependencies,List<Path> classpath,Map<String,NativeScope> scopes,Path repository) {
        for(var dependency:dependencies)classpath.add(dependency.module()==null?repository.resolve(dependency.location()):scopes.get(dependency.module()+"/main").directory());
    }
    private NativeScope compile(ProjectModel model,List<Path> sources,List<Path> classpath,Attribute.Options options,List<String> declaredOptions,Path output) throws Exception {
        Files.createDirectories(output);var messages=new ArrayList<Message>();
        var ordinary=sources.stream().filter(p->!p.getFileName().toString().equals("module-info.java")).toList();
        var compiler=ToolProvider.getSystemJavaCompiler();
        javax.tools.DiagnosticListener<JavaFileObject> listener=d->{
            String path=d.getSource()==null?"":Path.of(model.root()).relativize(Path.of(d.getSource().toUri())).toString().replace('\\','/');
            messages.add(new Message(path,new ResultRecord.Diagnostic(switch(d.getKind()) {
                case ERROR->0;case WARNING,MANDATORY_WARNING->1;default->2;
            },d.getStartPosition(),d.getEndPosition(),d.getCode(),d.getMessage(Locale.ROOT))));
        };
        if(!ordinary.isEmpty())try(var manager=compiler.getStandardFileManager(listener,Locale.ROOT,options.charset())) {
            manager.setLocationFromPaths(StandardLocation.CLASS_PATH,classpath);manager.setLocationFromPaths(StandardLocation.SOURCE_PATH,List.of());
            manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT,List.of(output));
            var task=compiler.getTask(null,manager,listener,options.javac(),null,canonicalNames(manager.getJavaFileObjectsFromPaths(ordinary)));
            task.setLocale(Locale.ROOT);task.call();
        }
        for(var source:sources)if(source.getFileName().toString().equals("module-info.java")) {
            // A fresh native descriptor task sees independently compiled ordinary outputs and real module-path dependencies.
            // Only this oracle establishes module context; production derives bytes from the canonical Stage 2 fact.
            String name;
            try(var manager=compiler.getStandardFileManager(null,Locale.ROOT,options.charset())) {
                var parse=(com.sun.source.util.JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,manager.getJavaFileObjects(source));
                name=parse.parse().iterator().next().getModule().getName().toString();
            }
            var args=new ArrayList<>(options.javac());
            boolean release=declaredOptions.stream().anyMatch(o->o.equals("--release") || o.startsWith("--release="));
            if(release)for(int i=0;i<args.size();) {
                if(args.get(i).equals("-source") || args.get(i).equals("--system")) {args.remove(i);args.remove(i);}
                else i++;
            }
            args.addAll(List.of("--patch-module",name+"="+output));
            for(int i=0;i<declaredOptions.size();i++) {
                var option=declaredOptions.get(i);
                if(option.equals("--module-version") || option.equals("--release") || option.equals("-target") || option.equals("--target"))args.addAll(List.of(option,declaredOptions.get(++i)));
                else if(option.startsWith("--module-version=") || option.startsWith("--release=") || option.startsWith("--target="))args.add(option);
            }
            try(var manager=compiler.getStandardFileManager(listener,Locale.ROOT,options.charset())) {
                manager.setLocationFromPaths(StandardLocation.MODULE_PATH,classpath);
                manager.setLocationFromPaths(StandardLocation.SOURCE_PATH,List.of(source.getParent()));
                manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT,List.of(output));
                var task=compiler.getTask(null,manager,listener,args,null,manager.getJavaFileObjects(source));
                task.setLocale(Locale.ROOT);task.call();
            }
        }
        var classes=new TreeMap<String,byte[]>();
        try(var paths=Files.walk(output)) {
            for(var file:paths.filter(p->p.toString().endsWith(".class")).toList())
                classes.put(output.relativize(file).toString().replace('\\','/').replaceFirst("\\.class$",""),Files.readAllBytes(file));
        }
        return new NativeScope(output,classes,messages,sources.size()-ordinary.size());
    }
    /** Native javac oracle with the same explicit diagnostic filename policy; no production formatter is used. */
    private static List<javax.tools.JavaFileObject> canonicalNames(Iterable<? extends javax.tools.JavaFileObject> inputs) {
        var result=new ArrayList<javax.tools.JavaFileObject>();
        for(var input:inputs)result.add(new javax.tools.ForwardingJavaFileObject<javax.tools.JavaFileObject>(input) {
            @Override public String getName() { return Path.of(toUri()).getFileName().toString(); }
        });
        return result;
    }

}
