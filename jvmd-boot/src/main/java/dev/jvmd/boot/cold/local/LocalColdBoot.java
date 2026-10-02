package dev.jvmd.boot.cold.local;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.CompilerPool;
import dev.jvmd.boot.cold.machine.ArtifactJob;
import dev.jvmd.boot.cold.machine.ClaimMap;
import dev.jvmd.boot.cold.machine.MachineInput;
import dev.jvmd.core.Hashing;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.ClasspathSequence;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.ArtifactBuilder;
import dev.jvmd.index.layer.machine.MachineLayer;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachinePath;
import dev.jvmd.index.layer.machine.MachineTree;
import dev.jvmd.index.rocks.RocksIndexStorage;
import dev.jvmd.index.rocks.layer.RocksLocalStore;
import dev.jvmd.resolver.Resolution;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Builds one project's LOCAL layer from its inputs, because it has no committed root (section 5.3).
 * It runs on a platform worker owned by the project's session, after MACHINE is committed. Each
 * stage is one method; a failing stage aborts the boot without writing a root.
 */
public final class LocalColdBoot implements AutoCloseable {
    /** One source file, read once: its text and the SHA-256 of the bytes it was decoded from. */
    record SourceFile(String text,String sha256) { }
    /** An input that contributes nothing to LOCAL, and why. */
    public record Fault(String location,String reason) { }

    /** The compiler context analysis uses for a module scope; the cold boot compiles that scope with it. */
    @FunctionalInterface public interface Contexts { Analyzer.Context context(Resolution.Module module,boolean test)throws Exception; }

    private static final int BATCH=32;

    private final Path generation,projectRoot;
    private final Callable<Resolution> resolve;
    private final Contexts compilerContexts;
    private final MachineLayer machine;
    private final RocksIndexStorage machineStorage;
    private final long compilerBudget;
    private final UnitQueue queue=new UnitQueue();
    private final CountDownLatch enumerated=new CountDownLatch(1);
    private final List<Fault> faults=new CopyOnWriteArrayList<>();
    private final CompletableFuture<Root> committed=new CompletableFuture<>();
    private final CompletableFuture<SortedMap<String,Route>> routed=new CompletableFuture<>();
    private volatile LocalLayer layer;
    private volatile String stage="pending";
    private Thread worker;
    private boolean closed;

    private RocksLocalStore store;
    private final Map<String,Resolution.Module> modules=new LinkedHashMap<>();
    private final SortedMap<String,List<String>> moduleGraph=new TreeMap<>();
    private final List<Context> contexts=new ArrayList<>();
    private final SortedMap<String,Route> routes=new TreeMap<>();
    private final Map<Path,SourceFile> files=new HashMap<>();
    private final List<UnitJob.Built> built=new ArrayList<>();
    private Resolution graph;

    /**
     * @param generation     the generation whose committed MACHINE this project routes into
     * @param machine        the committed MACHINE layer
     * @param machineStorage where an artifact a route needs is added to MACHINE
     */
    public LocalColdBoot(Path generation,Path projectRoot,Callable<Resolution> resolve,Contexts compilerContexts,MachineLayer machine,
                         RocksIndexStorage machineStorage,long compilerBudget)throws IOException{
        this.generation=generation;this.projectRoot=projectRoot.toRealPath();this.resolve=Objects.requireNonNull(resolve);
        this.compilerContexts=Objects.requireNonNull(compilerContexts);
        this.machine=Objects.requireNonNull(machine);this.machineStorage=Objects.requireNonNull(machineStorage);this.compilerBudget=compilerBudget;
    }

    /** Run the boot on its own platform worker; {@link #committed()} completes with the LOCAL root. */
    public synchronized void start(){
        if(worker!=null||closed)return;
        worker=Thread.ofPlatform().name("jvmd-local-cold-boot").daemon().start(()->{
            try{committed.complete(run());}catch(Throwable failure){committed.completeExceptionally(failure);}
        });
    }

    /** The LOCAL root once committed; completes exceptionally when a stage failed. */
    public CompletableFuture<Root> committed(){return committed;}

    /** Every stage in order, on the calling platform thread. */
    public Root run()throws Exception{
        try{
            create();
            moduleGraph();
            routes();
            files();
            declarations();
            var tree=buildTree();
            return commit(tree);
        }catch(Exception|Error failure){
            stage="failed: "+failure;routed.completeExceptionally(failure);
            var current=layer;if(current!=null)current.abandon();
            throw failure;
        }finally{
            enumerated.countDown();
            if(store!=null)store.close();
        }
    }

    /** Create the project's LOCAL storage. This is the only call site that creates it. */
    void create()throws Exception{
        stage="create";
        store=RocksLocalStore.create(RocksLocalStore.directory(generation,projectRoot));
    }

    /** Resolve the module graph: modules in reactor order, the edges between them, and one compiler context per module scope. */
    void moduleGraph()throws Exception{
        stage="module_graph";
        graph=resolve.call();
        var byGav=new TreeMap<String,Resolution.Module>();for(var module:graph.modules())byGav.put(module.gav(),module);
        var dependencies=new TreeMap<String,List<Resolution.Module>>();
        for(var module:byGav.values()){
            var edges=new TreeSet<String>();
            for(boolean test:List.of(false,true))for(var dependency:reactorDependencies(module,test,byGav))edges.add(dependency.gav());
            moduleGraph.put(module.gav(),List.copyOf(edges));
            dependencies.put(module.gav(),reactorDependencies(module,false,byGav));
        }
        for(var module:reactorOrder(byGav,dependencies))modules.put(module.gav(),module);
        for(var module:modules.values()){
            contexts.add(new Context(module.gav(),"main",compilerContexts.context(module,false)));
            if(!module.testSources().isEmpty())contexts.add(new Context(module.gav(),"test",compilerContexts.context(module,true)));
        }
    }

    /**
     * Map each module scope's classpath to MACHINE leaf keys and sibling modules. An artifact that is
     * not in MACHINE is built by the MACHINE artifact job, added to the MACHINE tree, and the MACHINE
     * root is recommitted once for all of them.
     */
    void routes()throws Exception{
        stage="routes";
        var outputs=new HashMap<Path,String>();
        for(var module:modules.values()){
            if(module.classes()!=null)outputs.put(normalize(module.classes()),module.gav());
            if(module.testClasses()!=null)outputs.put(normalize(module.testClasses()),module.gav());
        }
        var gavs=new HashMap<Path,String>();
        for(var node:graph.nodes())if(node.path()!=null&&node.winner()==null)gavs.putIfAbsent(normalize(node.path()),node.gav());
        var tree=machine.tree();var added=new ArrayList<MachineLeaf>();
        for(var context:contexts)for(String location:graph.classpaths().getOrDefault(context.key(),List.of())){
            Path path=normalize(location);
            if(outputs.containsKey(path)||!Files.isRegularFile(path)||tree.leafAt(path.toString())!=null)continue;
            var leaf=artifact(path,gavs.get(path),tree);
            if(leaf!=null){tree=tree.put(leaf);added.add(leaf);}
        }
        if(!added.isEmpty())machineStorage.commitMachine(tree);
        for(var context:contexts){
            var entries=new ArrayList<ClasspathSequence.Entry>();var keys=new HashSet<String>();
            for(String location:graph.classpaths().getOrDefault(context.key(),List.of())){
                Path path=normalize(location);String sibling=outputs.get(path);
                if(sibling!=null){
                    if(!sibling.equals(context.module())&&keys.add(Route.MODULE+sibling))entries.add(Route.sibling(sibling,path.toString()));
                    continue;
                }
                var leaf=tree.leafAt(path.toString());
                if(leaf==null){faults.add(new Fault(path.toString(),Files.isRegularFile(path)?"not an artifact":"missing"));continue;}
                if(keys.add(leaf.cacheKey()))entries.add(new ClasspathSequence.Entry(leaf.cacheKey(),leaf.resolution(),path.toString()));
            }
            for(var dependency:reactorDependencies(modules.get(context.module()),context.scope().equals("test"),modules))
                if(keys.add(Route.MODULE+dependency.gav()))entries.add(Route.sibling(dependency.gav(),dependency.directory()));
            var route=new Route(context.module(),context.scope(),ClasspathSequence.of(entries));routes.put(route.key(),route);
        }
        routed.complete(Collections.unmodifiableSortedMap(new TreeMap<>(routes)));
    }

    /** Every module scope's route, once the routes stage has run; completes exceptionally when the boot failed before it. */
    public CompletableFuture<SortedMap<String,Route>> routed(){return routed;}

    /** Build one artifact a route needs; content already in MACHINE only gains this path. */
    private MachineLeaf artifact(Path jar,String gav,MachineTree tree)throws Exception{
        String name=jar.getFileName().toString();
        Path sources=jar.resolveSibling(name.substring(0,name.length()-".jar".length())+"-sources.jar");
        if(!name.endsWith(".jar")||!Files.isRegularFile(sources))sources=null;
        var path=new MachinePath(jar.toString(),gav,MachinePath.Stamp.read(jar),sources==null?null:sources.toString(),
                sources==null?null:MachinePath.Stamp.read(sources));
        var outcome=new ArtifactJob(new MachineInput.Jar(path,jar,sources),new ClaimMap(),machineStorage.admission(),
                new ArtifactBuilder(machineStorage.repository())).call();
        return switch(outcome){
            case ArtifactJob.Built value->{
                var existing=tree.leaf(value.leaf().cacheKey());
                yield existing==null?value.leaf().withPath(path):existing.withPath(path);
            }
            case ArtifactJob.Faulted value->{faults.add(new Fault(value.location(),value.reason()));yield null;}
            case ArtifactJob.Duplicate value->null;
        };
    }

    /**
     * Read each source file once; its hash and its attribution use the same bytes. Queue each
     * context's units as batches that keep every dependency cycle together.
     */
    void files()throws Exception{
        stage="files";
        var batches=new ArrayList<List<UnitQueue.Unit>>();var expected=new LinkedHashMap<Path,String>();
        for(var context:contexts){
            var units=new ArrayList<UnitQueue.Unit>();
            var module=modules.get(context.module());
            var roots=context.scope().equals("test")?module.testSources():module.sources();
            for(String value:roots){
                Path root=normalize(value);if(!Files.isDirectory(root))continue;
                List<Path> sources;
                try(var walk=Files.walk(root)){sources=walk.filter(file->file.toString().endsWith(".java")&&Files.isRegularFile(file)).sorted().toList();}
                for(Path file:sources){
                    file=file.toAbsolutePath().normalize();
                    if(files.containsKey(file)||file.getFileName().toString().equals("module-info.java")||file.getFileName().toString().equals("package-info.java"))continue;
                    byte[] bytes=Files.readAllBytes(file);
                    files.put(file,new SourceFile(new String(bytes,StandardCharsets.UTF_8),Hashing.sha256(bytes)));
                    String relative=root.relativize(file).toString().replace(File.separatorChar,'/');
                    String binaryName=relative.substring(0,relative.length()-".java".length()).replace('/','.');
                    units.add(new UnitQueue.Unit(file,context,logicalPath(file),binaryName));expected.put(file,binaryName);
                }
            }
            var texts=new HashMap<Path,String>();for(var unit:units)texts.put(unit.file(),files.get(unit.file()).text());
            batches.addAll(UnitGraph.batches(units,texts));
        }
        layer=new LocalLayer(expected,routes);queue.addAll(batches);enumerated.countDown();
    }

    /** Attribute every queued batch, front of the queue first, in its context's compiler context. */
    void declarations()throws Exception{
        stage="declarations";
        try(var compiler=new CompilerPool()){
            String configured=null;
            while(!queue.isEmpty()){
                if(Thread.interrupted())throw new InterruptedException("LOCAL cold boot interrupted");
                var batch=queue.next();if(batch.isEmpty())break;
                var context=batch.getFirst().context();
                if(!context.key().equals(configured)){
                    var c=context.compiler();
                    compiler.configure(c.generation(),c.release(),c.classpath(),c.sources(),null,compilerBudget,c.compilerOptions(),c.preciseSourceRoots());
                    compiler.binarySources(c.binarySources());
                    configured=context.key();
                }
                for(var result:new UnitJob(compiler,batch,files).run()){
                    if(result.complete()){layer.admit(result.unit().file(),result.leaf(),result.declarations());built.add(result);}
                    else{layer.incomplete(result.unit().file());faults.add(new Fault(result.unit().path(),"attribution incomplete"));}
                }
            }
        }
    }

    /** Bulk-build the file tree and the semantic tree from every built unit. */
    LocalTree buildTree(){
        stage="build_tree";
        var ordered=new ArrayList<>(built);ordered.sort(Comparator.comparing(result->result.leaf().path()));
        var fileTree=LocalFileTree.build(ordered.stream().map(UnitJob.Built::leaf).toList());
        var semantic=LocalSemanticTree.build(ordered.stream().map(UnitJob::facts).toList());
        return new LocalTree(fileTree,semantic,moduleGraph,routes);
    }

    /** Write leaves, nodes and routes in bounded batches; once they are durable, write the LOCAL root. */
    Root commit(LocalTree tree)throws Exception{
        stage="commit";
        var root=store.commit(tree);
        layer.committed(tree);stage="committed";
        return root;
    }

    /**
     * A request needs {@code file}'s declarations: move its job to the front and return once it is
     * built. A file that is not a unit of this project returns at once.
     */
    public void require(Path file)throws InterruptedException{
        enumerated.await();
        var current=layer;if(current==null)return;
        queue.prioritize(file);current.await(file);
    }

    /** The LOCAL layer, once the files are enumerated; empty before that. */
    public Optional<LocalLayer> layer(){return Optional.ofNullable(layer);}
    public List<Fault> faults(){return List.copyOf(faults);}

    public Map<String,Object> status(){
        var current=layer;var status=new LinkedHashMap<String,Object>();
        status.put("stage",stage);status.put("faults",faults.size());
        if(current!=null)status.putAll(current.status());
        return status;
    }

    /** Stop the worker; an unfinished boot writes no root. */
    @Override public void close()throws InterruptedException{
        Thread current;synchronized(this){closed=true;current=worker;}
        if(current!=null&&current!=Thread.currentThread()){current.interrupt();current.join();}
    }

    private String logicalPath(Path file){
        return projectRoot.relativize(file).toString().replace(File.separatorChar,'/');
    }

    /** The reactor modules {@code module} depends on in its main (or test) scope. */
    private List<Resolution.Module> reactorDependencies(Resolution.Module module,boolean test,Map<String,Resolution.Module> byGav){
        var result=new LinkedHashMap<String,Resolution.Module>();
        for(var node:graph.nodes())if(node.id().startsWith(module.gav()+"|")&&node.winner()==null&&(test||!"runtime".equals(node.scope())&&!"test".equals(node.scope()))){
            var dependency=byGav.get(node.gav());
            if(dependency!=null&&!dependency.gav().equals(module.gav()))result.putIfAbsent(dependency.gav(),dependency);
        }
        return List.copyOf(result.values());
    }

    /** Modules with every main-scope reactor dependency first; modules in a cycle follow in coordinate order. */
    private static List<Resolution.Module> reactorOrder(SortedMap<String,Resolution.Module> byGav,Map<String,List<Resolution.Module>> dependencies){
        var ordered=new LinkedHashMap<String,Resolution.Module>();var remaining=new TreeMap<>(byGav);
        while(!remaining.isEmpty()){
            boolean progress=false;
            for(var iterator=remaining.values().iterator();iterator.hasNext();){
                var module=iterator.next();
                if(dependencies.get(module.gav()).stream().allMatch(dependency->ordered.containsKey(dependency.gav()))){
                    ordered.put(module.gav(),module);iterator.remove();progress=true;
                }
            }
            if(!progress){var first=remaining.pollFirstEntry().getValue();ordered.put(first.gav(),first);}
        }
        return List.copyOf(ordered.values());
    }

    private static Path normalize(String location){return Path.of(location).toAbsolutePath().normalize();}
}
