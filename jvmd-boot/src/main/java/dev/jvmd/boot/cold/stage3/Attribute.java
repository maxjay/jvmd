package dev.jvmd.boot.cold.stage3;

import com.sun.source.util.Trees;
import dev.jvmd.boot.cold.stage2.JavacOptions;
import dev.jvmd.boot.cold.stage2.ProcessorHost;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ModuleRecord;
import dev.jvmd.index.layer.local.Proof;
import dev.jvmd.index.layer.local.ProofIndex;
import dev.jvmd.index.layer.local.ProofCollector;
import dev.jvmd.index.layer.local.ProcessorPlan;
import dev.jvmd.index.layer.local.ProcessorRecords;
import dev.jvmd.index.layer.local.ResultRecord;
import dev.jvmd.index.layer.local.Route;
import dev.jvmd.index.layer.local.UsesRecord;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/** One source snapshot to detached results and exact observations (Stage 3, 5.1). */
public final class Attribute {
    /** Prepared together so that the options hashed into ACI are the options actually given to javac. */
    public static final class Options {
        private final List<String> javac;
        private final Charset charset;
        private final Identity hash;
        private final ProcessorRecords.Scope processing;

        private Options(List<String> javac,Charset charset,Identity hash,ProcessorRecords.Scope processing) {
            this.javac=List.copyOf(javac);this.charset=charset;this.hash=hash;this.processing=processing;
        }
        public List<String> javac() { return javac; }
        public Charset charset() { return charset; }
        public Identity hash() { return hash; }

        /** Processor-bearing modules require the separate admission/output-check path; this cannot silently disable one. */
        public static Options unprocessed(Digest digest,ModuleRecord module,Path jdkHome) {
            if (!module.processing().path().isEmpty() || !module.processing().processors().isEmpty())
                throw new IllegalArgumentException("Processor-bearing module requires processor-aware attribution");
            return prepare(digest,module,jdkHome,null);
        }

        public static Options processed(Digest digest,ModuleRecord module,Path jdkHome,ProcessorRecords.Scope processing) {
            java.util.Objects.requireNonNull(processing);
            if (module.processing().path().isEmpty() && module.processing().processors().isEmpty())
                throw new IllegalArgumentException("Module has no processor path or declared processors");
            if (!module.processing().processors().isEmpty() && !module.processing().processors().equals(processing.names()))
                throw new IllegalArgumentException("Declared processors differ from Stage 2");
            return prepare(digest,module,jdkHome,processing);
        }

        private static Options prepare(Digest digest,ModuleRecord module,Path jdkHome,ProcessorRecords.Scope processing) {
            var input=new ArrayList<>(module.javacOptions());input.addAll(module.processing().options());
            int release=JavacOptions.effectiveRelease(module.release(),input.contains("--enable-preview"),Runtime.version().feature());
            var charset=JavacOptions.charset(input);
            boolean run=processing!=null && processing.processors().stream().anyMatch(p -> p.capability().declared()!=ProcessorRecords.AGGREGATING);
            var options=new ArrayList<>(List.of("-source",String.valueOf(release),"-Xlint:-options","-implicit:none",run ? "-proc:full" : "-proc:none",
                    "--system",jdkHome.toString()));
            options.addAll(JavacOptions.filtered(input));options.addAll(List.of("-encoding",charset.name()));
            var hash=JavacOptions.optionsHash(digest,input,release,processing==null ? null : processing.processorPathHash(),
                    processing==null ? List.of() : processing.names());
            if (processing!=null && !hash.equals(processing.optionsHash())) throw new IllegalArgumentException("Processor options differ from Stage 2");
            return new Options(options,charset,hash,processing);
        }
    }

    /** An unsupported fresh result has no reusable ACI/RS/U, but its class bytes and diagnostics remain available.
     * A successful zero-body module descriptor has no body proof; its RS is addressed directly by the exact T/A inputs. */
    public record Computed(Identity aci,ResultRecord result,Proof proof,UsesRecord uses,List<String> faults,ProofIndex indexed) {
        public Computed(Identity aci,ResultRecord result,Proof proof,UsesRecord uses,List<String> faults) {
            this(aci,result,proof,uses,faults,null);
        }
        public Computed { faults=List.copyOf(faults); }
        public boolean reusable() { return aci!=null; }
    }
    private final ContentTree tree;
    private final LocalStore store;
    private final MachineLeaf own;
    private final Route route;
    private final Pool pool;
    private final Options options;
    private final ProcessorBody processing;
    private final dev.jvmd.core.tree.NodeSink proofNodes;
    public dev.jvmd.boot.cold.stage2.ProcessorPath.Statistics processorStatistics() {
        return processing==null?new dev.jvmd.boot.cold.stage2.ProcessorPath.Statistics(0,0,0):processing.statistics();
    }
    public dev.jvmd.boot.cold.stage2.FrozenConfiguration.Statistics configurationStatistics() {
        return processing==null?new dev.jvmd.boot.cold.stage2.FrozenConfiguration.Statistics(0,0,0):processing.configurationStatistics();
    }

    private Attribute(ContentTree tree,LocalStore store,MachineLeaf own,Route route,Pool pool,Options options,ProcessorBody processing) {
        if (!pool.key().equals(new Pool.Key(route.routeHash(),own.k())))
            throw new IllegalArgumentException("Compiler pool does not match the current route and own leaf");
        if (!pool.configuration().options().equals(options.javac()) || !pool.configuration().charset().equals(options.charset()))
            throw new IllegalArgumentException("Compiler pool does not match the hashed attribution options");
        if(pool.configuration().readers()!=null && !java.util.Objects.equals(pool.configuration().readers().binding(),route.readerBinding()))
            throw new IllegalArgumentException("Compiler reader binding differs from the current snapshot");
        this.tree=tree;this.store=store;this.own=own;this.route=route;this.pool=pool;this.options=options;this.processing=processing;
        this.proofNodes=store instanceof BodyGeneration?store:new dev.jvmd.boot.cold.stage1.Written().throughShared(store);
    }

    public static Attribute unprocessed(ContentTree tree,LocalStore store,MachineLeaf own,Route route,Pool pool,Options options) {
        if (options.processing!=null) throw new IllegalArgumentException("Processor options require processor-aware attribution");
        return new Attribute(tree,store,own,route,pool,options,null);
    }

    public static Attribute processed(ContentTree tree,LocalStore store,MachineLeaf own,Route route,Pool pool,Options options,
                                      ProcessorPlan plan,List<Path> processorPath,Path project) {
        if (!plan.invocation().equals(options.processing)) throw new IllegalArgumentException("Attribution options differ from the processor plan");
        return new Attribute(tree,store,own,route,pool,options,new ProcessorBody(plan,processorPath,project,pool));
    }

    /** Persist CF/RS/U only. The driver owns C, reverse/output trees and the BROOT commit. */
    public Computed run(FileRow row,URI uri,byte[] bytes) throws InterruptedException {
        if (row.path().equals("module-info.java") || row.path().endsWith("/module-info.java"))
            throw new IllegalArgumentException("Module descriptors derive from their canonical fact, never Attribute");
        if (processing==null && row.processor()!=null) throw new IllegalArgumentException("Processor-bearing file requires processor-aware attribution: "+row.path());
        if (processing!=null && row.processor()==null) throw new IllegalArgumentException("File has no committed processor context: "+row.path());
        byte[] source=bytes.clone();
        var digest=tree.digest();
        if (!digest.hash(source).equals(row.kappa())) throw new IllegalArgumentException("Source snapshot differs from F: "+row.path());
        String basename=row.path().substring(row.path().lastIndexOf('/')+1);
        if (uri.getPath()==null || !uri.getPath().substring(uri.getPath().lastIndexOf('/')+1).equals(basename))
            throw new IllegalArgumentException("Source basename differs from F: "+row.path());
        var messages=new ArrayList<ResultRecord.Diagnostic>();
        var faults=new java.util.LinkedHashSet<String>();
        Pool.Completed<ProofCollector.Body> completed;
        ProcessorRecords.Body body=null;
        if (processing==null) completed=compile(uri,source,messages,null);
        else try {
            faults.addAll(processing.check(digest,row,uri));
            var host=processing.open(digest,options);
            try (host) {
                completed=compile(uri,source,messages,host);
                faults.addAll(processing.conservation(host,row,uri));
            } finally {
                host.capabilities().forEach((name, capability) -> store.observeProcessor(host.pathHash(), name, capability));
            }
            faults.addAll(host.faults());
            faults.addAll(processing.check(digest,row,uri));
            body=host.bodyObservations().withConfiguredProcessors(options.processing.names());
            if (!faults.isEmpty()) body=body.rejectReuse();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
        var bound=Arrange.body(tree,own,route,row.processor(),completed.value().supplement(completed.reads()),completed.readerReads(),store::get);
        var proof=body==null ? bound.proof() : bound.proof().withProcessorBody(body);
        faults.addAll(completed.readerFaults());
        if (!completed.metadataSupported()) {
            faults.add("unsupported for reuse: retained binary metadata reads have no exact proof projection");
            proof=proof.rejectReuse();
        }
        var indexed=proof.reusable() && (body==null || body.reusable())
                ? ProofIndex.capture(tree,own,route,proof,new ProofIndex.Inputs(basename,row.kappa(),options.hash()),store::get,proofNodes) : null;
        var aci=indexed==null?null:indexed.aci();
        boolean attributed=messages.stream().noneMatch(d -> d.kind()==0);
        var records=new TreeMap<byte[],byte[]>(Arrays::compareUnsigned);
        var classes=new ArrayList<ResultRecord.ClassFile>();
        if (attributed) completed.classes().forEach((name,content) -> {
            var id=digest.hash(content);classes.add(new ResultRecord.ClassFile(name,id));records.put(LocalStore.classFileKey(id),content);
        });
        var result=new ResultRecord(attributed,classes,messages);
        if (aci!=null) { records.put(LocalStore.resultKey(aci),result.encode());records.put(LocalStore.usesKey(aci),bound.uses().encode()); }
        publish(row,records);
        return new Computed(aci,result,proof,bound.uses(),new ArrayList<>(faults),indexed);
    }

    private Pool.Completed<ProofCollector.Body> compile(URI uri,byte[] source,List<ResultRecord.Diagnostic> messages,ProcessorHost host)
            throws InterruptedException {
        return pool.withTask(uri,source,d -> messages.add(new ResultRecord.Diagnostic(switch(d.getKind()) {
            case ERROR -> 0; case WARNING,MANDATORY_WARNING -> 1; default -> 2;
        },d.getStartPosition(),d.getEndPosition(),d.getCode(),d.getMessage(Locale.ROOT))),task -> {
            try {
                if (host!=null) host.attach(task);
                var parsed=task.parse().iterator();
                if (!parsed.hasNext()) throw new IllegalStateException("javac returned no unit for "+uri);
                var unit=parsed.next();
                if (parsed.hasNext()) throw new IllegalStateException("Attribution parsed more than one explicit unit");
                task.analyze();
                var reads=ProofCollector.bodies(unit,Trees.instance(task),task.getElements(),task.getTypes());
                task.generate();return reads;
            } catch(IOException failure) { throw new UncheckedIOException(failure); }
        }, processing==null ? null : processing.compilerInputs());
    }

    private void publish(FileRow row,TreeMap<byte[],byte[]> records) {
        // Check all existing content before publishing this result; an incomplete input identity must never overwrite it.
        // Only publication is serialized: different workers can compute the same ACI concurrently.
        synchronized(store) {
            if(store instanceof BodyGeneration) {records.forEach(store::put);store.flush();return;}
            var missing=new TreeMap<byte[],byte[]>(Arrays::compareUnsigned);
            records.forEach((key,value) -> {
                var existing=store.get(key);
                if (existing==null) missing.put(key,value);
                else if (!Arrays.equals(existing,value)) throw new IllegalStateException("Conflicting content-addressed attribution result for "+row.path());
            });
            missing.forEach(store::put);store.flush();
        }
    }
}
