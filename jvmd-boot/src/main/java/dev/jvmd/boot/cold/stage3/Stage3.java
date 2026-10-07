package dev.jvmd.boot.cold.stage3;

import dev.jvmd.boot.cold.stage2.Order;
import dev.jvmd.boot.cold.stage2.Stage2;
import dev.jvmd.boot.cold.stage2.StubDirectories;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.Format;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.MachineTree;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cold body attribution: every committed file, dependency-ordered scopes, and a separate bodies-root commit. */
public final class Stage3 {
    public record File(String path,Attribute.Computed computed) { }
    public record Scope(List<File> files,Root output,List<ProcessorRecords.Message> aggregateDiagnostics,int descriptorEmissions,
                        List<Diagnostics.Message> headerDiagnostics) {
        public Scope {files=List.copyOf(files);aggregateDiagnostics=List.copyOf(aggregateDiagnostics);headerDiagnostics=List.copyOf(headerDiagnostics);}
        public List<Diagnostics.Message> diagnostics() {
            var messages=new ArrayList<>(headerDiagnostics);messages.addAll(Diagnostics.assemble(files,aggregateDiagnostics));return List.copyOf(messages);
        }
    }
    public record Result(Identity project,BodiesRoot bodies,Map<String,Scope> scopes,int files,List<String> faults,long wallMillis) {
        public Result {scopes=java.util.Collections.unmodifiableMap(new TreeMap<>(scopes));faults=List.copyOf(faults);}
    }
    private record ScopeKey(String module,int scope) { }
    private final Digest digest;
    private final ContentTree tree;
    private final int feature;
    private final int workers;
    private final Path repository;

    public Stage3(Digest digest,ContentTree tree,int feature,int workers,Path repository) {
        if(workers<1)throw new IllegalArgumentException("Stage 3 needs at least one worker");
        this.digest=digest;this.tree=tree;this.feature=feature;this.workers=workers;this.repository=repository;
    }

    public Result run(LocalStore store,ProjectModel model) throws IOException {
        long started=System.nanoTime();model.validate();var order=Order.of(model);var project=Stage2.projectKey(digest,model);
        var local=local(store,model,project);var generation=BodyGeneration.begin(tree,store,project,local);
        var rows=rows(model,project,local,generation);var scopes=new ConcurrentHashMap<String,Scope>();
        var leaves=new ConcurrentHashMap<Identity,MachineLeaf>();
        java.util.function.Function<Identity,MachineLeaf> leaf=k->leaves.computeIfAbsent(k,id->MachineLeaf.decode(required(generation.get(MachineStore.leafKey(id))),digest.width()));
        try(var stubs=new StubDirectories(tree,generation,leaf);
            var files=Executors.newFixedThreadPool(workers,Thread.ofPlatform().name("jvmd-body-",0).daemon(true).factory());
            var modules=Executors.newFixedThreadPool(workers,Thread.ofPlatform().name("jvmd-bodies-module-",0).daemon(true).factory())) {
            var futures=new LinkedHashMap<String,CompletableFuture<Void>>();
            for(var module:order.modules()) {
                var dependencies=order.dependencies().get(module.name()).stream().map(futures::get).toArray(CompletableFuture[]::new);
                futures.put(module.name(),CompletableFuture.allOf(dependencies).thenRunAsync(()->{
                    for(int scope:new int[]{LocalStore.MAIN,LocalStore.TEST}) {
                        var result=scope(model,project,local,generation,module,scope,rows.get(new ScopeKey(module.name(),scope)),stubs,leaf,files);
                        scopes.put(module.name()+(scope==0?"/main":"/test"),result);
                    }
                },modules));
            }
            try {CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).join();}
            catch(CompletionException failure) {files.shutdownNow();modules.shutdownNow();throw failure;}
        } catch(CompletionException failure) {
            var cause=failure.getCause();if(cause instanceof UncheckedIOException io)throw io.getCause();
            if(cause instanceof RuntimeException runtime)throw runtime;if(cause instanceof Error error)throw error;throw failure;
        }
        // A later module may revoke admission for processor bytes used by an earlier scope. Check after every worker finished.
        scopes.replaceAll((name, scope) -> admit(scope, generation));
        var records=new TreeMap<byte[],byte[]>(Arrays::compareUnsigned);var faults=new java.util.TreeSet<String>();int count=0;
        for(var module:model.modules())for(int scope:new int[]{LocalStore.MAIN,LocalStore.TEST}) {
            var result=scopes.get(module.name()+(scope==0?"/main":"/test"));
            records.put(LocalStore.outputKey(project,module.name(),scope),DefinerIndex.encodeRoot(result.output()));
            for(var file:result.files()) {
                count++;var computed=file.computed();
                if(computed.proof()!=null) {
                    records.put(LocalStore.proofKey(project,module.name(),scope,file.path()),computed.proof().encode());
                    for(var dependency:ReverseIndex.dependencies(computed.proof()))records.put(ReverseIndex.bodyKey(dependency,project,module.name(),scope,file.path()),Entry.NONE);
                }
                for(var c:computed.result().classFiles()) {
                    var key=LocalStore.classFileKey(c.contentHash());records.put(key,required(generation.get(key)));
                }
                if(computed.reusable()) {
                    records.put(LocalStore.resultKey(computed.aci()),computed.result().encode());
                    if(computed.proof()!=null)records.put(LocalStore.usesKey(computed.aci()),computed.uses().encode());
                }
                for(var fault:computed.faults())faults.add(module.name()+"/"+scope+": "+file.path()+": "+fault);
            }
        }
        var bodies=generation.commit(records);
        return new Result(project,bodies,scopes,count,new ArrayList<>(faults),(System.nanoTime()-started)/1_000_000);
    }

    private static Scope admit(Scope scope, BodyGeneration generation) {
        var processed = scope.files().stream().map(File::computed).map(Attribute.Computed::proof)
                .filter(proof -> proof != null && proof.processorBody() != null).findFirst();
        if (processed.isEmpty()) return scope;
        var proof = processed.orElseThrow();
        var violated = proof.processorBody().violations(proof.header().processor(), generation::get);
        if (violated.isEmpty()) return scope;
        var files = scope.files().stream().map(file -> {
            var computed = file.computed();
            if (computed.proof() == null || computed.proof().processorBody() == null) return file;
            var faults = new java.util.TreeSet<>(computed.faults());
            for (var name : violated) faults.add(name + ": unsupported for reuse: recorded capability violation for these processor bytes");
            var rejected = computed.proof().withProcessorBody(computed.proof().processorBody().rejectReuse());
            return new File(file.path(), new Attribute.Computed(null, computed.result(), rejected, computed.uses(), new ArrayList<>(faults)));
        }).toList();
        return new Scope(files, scope.output(), scope.aggregateDiagnostics(), scope.descriptorEmissions(), scope.headerDiagnostics());
    }

    private Scope scope(ProjectModel model,Identity project,LocalRoot local,BodyGeneration generation,ProjectModel.Module module,int scope,
                        List<FileRow> rows,StubDirectories stubs,java.util.function.Function<Identity,MachineLeaf> leaves,ExecutorService workers) {
        if(rows.isEmpty())return new Scope(List.of(),Output.build(tree,generation,List.of()),List.of(),0,List.of());
        var descriptor=ModuleRecord.decode(required(generation.local(LocalStore.moduleKey(project,module.name()))));
        var own=SourceLeaf.decode(required(generation.local(LocalStore.sourceLeafKey(project,module.name(),scope))),digest.width());
        var route=Route.decode(required(generation.local(LocalStore.routeKey(project,module.name(),scope))),digest.width());
        var classpath=new ArrayList<Path>();
        for(var entry:route.entries())switch(entry) {
            case RouteEntry.Sibling sibling -> {
                var source=SourceLeaf.decode(required(generation.local(LocalStore.sourceLeafKey(project,sibling.module(),LocalStore.MAIN))),digest.width());
                classpath.add(stubs.get(source.k()).path());
            }
            case RouteEntry.Jar jar -> {if(jar.defaultK()!=null)classpath.add(repository.resolve(jar.location()));}
            case RouteEntry.Jrt ignored -> { }
        }
        boolean processed=!descriptor.processing().path().isEmpty() || !descriptor.processing().processors().isEmpty();
        var plan=processed?ProcessorPlan.load(tree,local,project,module.name(),scope,generation::get):null;
        var options=processed?Attribute.Options.processed(digest,descriptor,Path.of(model.jdkHome()),plan.invocation())
                :Attribute.Options.unprocessed(digest,descriptor,Path.of(model.jdkHome()));
        var ownStubs=stubs.get(own.k());var configuration=new Pool.Configuration(new Pool.Key(route.routeHash(),own.k()),ownStubs.path(),classpath,
                options.charset(),options.javac(),ownStubs.types());
        var descriptorOptions=new ArrayList<>(descriptor.javacOptions());descriptorOptions.addAll(descriptor.processing().options());
        var headerOptions=dev.jvmd.boot.cold.stage2.JavacOptions.optionsHash(digest,descriptorOptions,
                dev.jvmd.boot.cold.stage2.JavacOptions.effectiveRelease(descriptor.release(),descriptorOptions.contains("--enable-preview"),Runtime.version().feature()),null,List.of());
        var results=new ArrayList<File>();int descriptorEmissions=0;
        var headerDiagnostics=new ArrayList<Diagnostics.Message>();
        try(var pool=new Pool(configuration,this.workers)) {
            var attribute=processed?Attribute.processed(tree,generation,leaves.apply(own.k()),route,pool,options,plan,
                    descriptor.processing().path().stream().map(p->repository.resolve(p.location())).toList(),Path.of(model.root()))
                    :Attribute.unprocessed(tree,generation,leaves.apply(own.k()),route,pool,options);
            var tasks=new ArrayList<java.util.concurrent.Future<File>>();
            for(var row:rows) {
                if(row.path().equals("module-info.java") || row.path().endsWith("/module-info.java")) {
                    var diagnostics=HeaderDiagnostics.decode(required(generation.local(LocalStore.headerDiagnosticsKey(project,
                            new SourceUnit(module.name(),scope,row.path())))),digest.width());
                    var derived=diagnostics.failed()?ModuleDescriptor.failed(tree,generation,leaves.apply(own.k()),route,row,diagnostics,headerOptions)
                            :ModuleDescriptor.derive(tree,generation,own,ModuleDescriptor.Options.of(descriptorOptions));
                    if(!diagnostics.failed())for(var message:diagnostics.messages())headerDiagnostics.add(new Diagnostics.Message(row.path(),message));
                    results.add(new File(row.path(),derived.computed()));if(derived.emitted())descriptorEmissions++;
                } else tasks.add(workers.submit(()->{
                    var path=model.resolve(row.path());return new File(row.path(),attribute.run(row,path.toUri(),Files.readAllBytes(path)));
                }));
            }
            try {for(var task:tasks)results.add(task.get());}
            catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new IllegalStateException("Body attribution interrupted",failure);}
            catch(java.util.concurrent.ExecutionException failure) {
                var cause=failure.getCause();if(cause instanceof IOException io)throw new UncheckedIOException(io);
                if(cause instanceof RuntimeException runtime)throw runtime;if(cause instanceof Error error)throw error;throw new IllegalStateException(cause);
            } finally {for(var task:tasks)if(!task.isDone())task.cancel(true);}
        } catch(IOException failure) {throw new UncheckedIOException(failure);}
        // One new capability violation invalidates scope reuse, including results completed before the violation was observed.
        var violations=results.stream().flatMap(f->f.computed().faults().stream()).distinct().sorted().toList();
        if(processed && results.stream().anyMatch(f->!f.computed().reusable())) {
            results.replaceAll(file->{var c=file.computed();if(c.proof()==null || c.proof().header().processor()==null)return file;var proof=c.proof().withProcessorBody(c.proof().processorBody().rejectReuse());
                return new File(file.path(),new Attribute.Computed(null,c.result(),proof,c.uses(),violations));});
        }
        var aggregate=new ArrayList<ProcessorRecords.Message>();
        if(processed) {
            var diagnostics=generation.local(LocalStore.processorDiagnosticsKey(project,module.name(),scope));
            if(diagnostics!=null)for(var message:ProcessorRecords.Diagnostics.decode(diagnostics).messages()) {
                var capability=plan.invocation().capability(message.processorClass());
                if(capability!=null && capability.declared()==ProcessorRecords.AGGREGATING)aggregate.add(message);
            }
        }
        results.sort(java.util.Comparator.comparing(File::path));
        return new Scope(results,Output.build(tree,generation,results.stream().map(f->f.computed().result()).toList()),aggregate,descriptorEmissions,headerDiagnostics);
    }

    private Map<ScopeKey,List<FileRow>> rows(ProjectModel model,Identity project,LocalRoot local,BodyGeneration generation) {
        var roots=new LinkedHashMap<ScopeKey,List<Path>>();var rows=new LinkedHashMap<ScopeKey,List<FileRow>>();
        for(var module:model.modules())for(int scope:new int[]{LocalStore.MAIN,LocalStore.TEST}) {
            var key=new ScopeKey(module.name(),scope);var paths=new ArrayList<Path>();
            for(var path:module.scope(scope).sourceRoots())paths.add(model.resolve(path).toAbsolutePath().normalize());
            String name=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(module.name().getBytes(StandardCharsets.UTF_8));
            paths.add(model.resolve(".jvmd/generated/"+name+"/"+scope).toAbsolutePath().normalize());
            roots.put(key,paths);rows.put(key,new ArrayList<>());
        }
        var prefix=LocalStore.filePrefix(project);
        tree.forEach(local.local().hash(),id->generation.get(MachineStore.nodeKey(id)),prefix,entry->{
            var key=entry.key();
            var unit=SourceUnit.fromFileKey(key,digest.width());var path=model.resolve(unit.path()).toAbsolutePath().normalize();
            var owner=new ScopeKey(unit.module(),unit.scope());var declared=roots.get(owner);
            if(declared==null || declared.stream().noneMatch(path::startsWith))throw new ProjectModel.Fault("Source row is outside its module scope: "+unit);
            rows.get(owner).add(FileRow.decode(unit.path(),required(generation.local(entry)),digest.width()));
        });
        return rows;
    }

    private LocalRoot local(LocalStore store,ProjectModel model,Identity project) {
        var local=LocalRoot.decode(digest,required(store.get(LocalStore.localRootKey(project))));
        if(!local.format().equals(LocalFormat.of(Format.of(digest,feature))) || !local.modelHash().equals(digest.hash(model.bytes()))
                || !local.machineRoot().equals(MachineTree.decodeRoot(digest,required(store.get(MachineStore.ROOT_KEY))).digest()))
            throw new IllegalStateException("Stage 3 requires current committed LOCAL inputs");
        return local;
    }
    private static byte[] required(byte[] value) {if(value==null)throw new IllegalStateException("Missing committed input");return value;}

    public Output.Changes materialise(LocalStore store,ProjectModel model,String module,int scope,Path directory) throws IOException {
        var project=Stage2.projectKey(digest,model);var local=local(store,model,project);
        var bodies=BodiesRoot.decode(required(store.get(LocalStore.bodiesRootKey(project))),digest.width());
        if(!bodies.current(local))throw new IllegalStateException("Bodies root is stale");
        var key=LocalStore.outputKey(project,module,scope);var entry=tree.get(bodies.bodiesRoot(),id->store.get(MachineStore.nodeKey(id)),key);
        if(entry==null)throw new IllegalStateException("Scope output is not in BROOT");var value=required(store.get(key));
        if(!digest.hash(value).equals(entry.h()))throw new IllegalStateException("Output record digest mismatch");
        return Output.materialise(tree,store,project,module,scope,DefinerIndex.decodeRoot(value,digest.width()),directory);
    }
}
