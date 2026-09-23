package dev.jvmd.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Compiler input identities. Source identity is read from the mutation-maintained
 * {@link LiveSourceState}; environment identity retains the existing classpath/JDK observation
 * boundary until its own owner is cut over.
 */
public final class CompilerInputs {
    public record SourceIdentity(String value) { }
    public record MembershipIdentity(String value) { }
    public record ContentIdentity(String value) { }
    public record ApiIdentity(String value) { }
    public record NamespaceIdentity(String value) { }
    public record EnvironmentIdentity(String value) { }
    public record Configuration(String generation,List<Path> roots,List<Path> classpath,List<String> options,String platform) {
        public Configuration {
            roots=normalize(roots);classpath=normalize(classpath);options=List.copyOf(options);
            Objects.requireNonNull(generation);Objects.requireNonNull(platform);
        }
        public Configuration(String generation,List<Path> roots,List<Path> classpath,List<String> options){
            this(generation,roots,classpath,options,System.getProperty("java.home")+"|"+Runtime.version());
        }
    }

    /**
     * Constant-sized captured compiler source state. Stable cache equality intentionally compares
     * source membership/content plus environment, while transactionCurrent() additionally fences on
     * the monotonic epoch so A→B→A during one javac transaction is still detected.
     */
    public record Snapshot(LiveSourceState live,LiveSourceState.Snapshot sourceState,EnvironmentIdentity environment,long environmentEpoch) {
        public Snapshot {
            Objects.requireNonNull(live);Objects.requireNonNull(sourceState);Objects.requireNonNull(environment);
        }
        private LiveStateTree.State state(){return sourceState.state();}
        public MembershipIdentity membership(){return new MembershipIdentity(state().membership().fingerprint().value());}
        public ContentIdentity content(){return new ContentIdentity(state().content().fingerprint().value());}
        public ApiIdentity api(){return new ApiIdentity(state().api().fingerprint().value());}
        public NamespaceIdentity namespace(){return new NamespaceIdentity(state().namespace().fingerprint().value());}
        public String merkle(){return state().merkle().value();}
        public long observation(){return sourceState.inputEpoch();}
        public boolean trusted(){return sourceState.trusted();}
        public boolean sameInputs(Snapshot other){
            return other!=null&&membership().equals(other.membership())&&content().equals(other.content())&&environment.equals(other.environment);
        }
        /** Strict compiler transaction fence: any intervening live transition supersedes the task. */
        public boolean transactionCurrent(){
            var current=live.snapshot();
            return sourceState.trusted()&&current.trusted()&&current.inputEpoch()==sourceState.inputEpoch()
                    &&current.state().membership().equals(state().membership())
                    &&current.state().content().equals(state().content());
        }
        private void requireStableSource()throws Superseded{
            var current=live.snapshot();
            if(!sourceState.trusted()||!current.trusted()
                    ||!current.state().membership().equals(state().membership())
                    ||!current.state().content().equals(state().content()))
                throw new Superseded("Source state changed after compiler input capture");
        }
        public SourceIdentity source(Path file)throws Superseded{
            requireStableSource();String value=live.contentHash(file);
            return new SourceIdentity(value);
        }
        /** Identify the bytes actually supplied, never a hash reread after compilation. */
        public String text(Path file,Documents documents)throws Exception{return checkText(file,documents.text(file));}
        public String checkText(Path file,String text)throws Superseded{
            requireStableSource();
            // Explicit compilation units may intentionally sit outside configured source roots.
            // Their immutable request bytes are the transaction input, not workspace membership.
            if(!live.accepts(file))return text;
            String expected=live.contentHash(file);
            String actual=Hashing.sha256(text.getBytes(StandardCharsets.UTF_8));
            if(expected==null||!expected.equals(actual))throw new Superseded("Source changed before analysis: "+file);
            requireStableSource();return text;
        }
        /**
         * Transitional compatibility for consumers whose own cutover follows this phase.
         * This is an explicit O(workspace) materialization, not compiler validation state.
         */
        public Map<Path,String> sources()throws Superseded{
            requireStableSource();var result=new TreeMap<Path,String>();
            for(Path path:live.paths()){String hash=live.contentHash(path);if(hash!=null)result.put(path,hash);}
            requireStableSource();return Collections.unmodifiableMap(result);
        }
        /** Incremental changed-path lookup from the maintained source journal. */
        public Set<Path> changedSince(Snapshot prior)throws Superseded{
            if(prior==null)return live.paths();
            if(membership().equals(prior.membership())&&content().equals(prior.content()))return Set.of();
            if(live!=prior.live())throw new Superseded("Source-state owner changed");
            return live.changedPathsSince(prior.observation()).orElseThrow(()->new Superseded("Source change history requires reconciliation"));
        }
    }

    public static final class Superseded extends IOException { public Superseded(String message){super(message);} }
    private final FileStateRegistry files;
    private Configuration environmentConfiguration;
    private LiveEnvironmentState environmentState;
    private Snapshot snapshot;
    private long observations,rebuilds,validationNanos;
    // PR-local live-state-tree evidence. Remove after the final before/after capture.
    private long captureCalls,sourceInventoryCalls,sourceCandidatesInspected,environmentCandidatesInspected;

    public CompilerInputs(FileStateRegistry files){this.files=Objects.requireNonNull(files);}

    /** Request path: source membership/content is already maintained; no source inventory is rebuilt here. */
    public synchronized Snapshot capture(Configuration config,Documents documents)throws IOException{
        try(var trace=RequestScope.stage("inputs.validate")){
            trace.count("captures",1);captureCalls++;observations++;long started=System.nanoTime();
            try{
                var live=documents.liveState(config.roots());live.verifyTransactionBoundary();var source=live.snapshot();
                var environment=environmentSnapshot(config);
                var current=new Snapshot(live,source,environment.identity(),environment.epoch());
                if(snapshot==null||snapshot.observation()!=current.observation()||snapshot.environmentEpoch()!=current.environmentEpoch()||!snapshot.sameInputs(current)){
                    snapshot=current;rebuilds++;RequestScope.count("snapshot_rebuilds",1);
                }
                return snapshot;
            }finally{validationNanos+=System.nanoTime()-started;}
        }
    }

    /**
     * Transitional explicit-inventory signature retained for non-compiler callers while their
     * ownership is removed. The source list is no longer used to construct compiler identity.
     */
    public synchronized Snapshot capture(Configuration config,Documents documents,Collection<Path> ignoredDiskFiles)throws IOException{
        return capture(config,documents);
    }

    /** Validate a compiler transaction without reconstructing source membership/content. */
    public synchronized boolean current(Snapshot expected,Configuration config,Documents documents)throws IOException{
        if(expected==null)return false;
        expected.live().verifyTransactionBoundary();
        if(!expected.transactionCurrent())return false;
        var environment=environmentSnapshot(config);
        return expected.environmentEpoch()==environment.epoch()&&expected.environment().equals(environment.identity());
    }

    /** The environment boundary is mutation-maintained; warm reads drain only queued filesystem events. */
    public synchronized EnvironmentIdentity environment(Configuration config)throws IOException{return environmentSnapshot(config).identity();}
    private synchronized LiveEnvironmentState.Snapshot environmentSnapshot(Configuration config)throws IOException{
        if(environmentState==null||!config.equals(environmentConfiguration)){
            if(environmentState!=null)environmentState.close();
            environmentState=new LiveEnvironmentState(files,config);environmentConfiguration=config;
        }
        return environmentState.verifyBoundary();
    }

    public static EnvironmentIdentity environment(String generation,List<Path> roots,List<String> options,List<String> processors,
            Map<String,String> generated,List<String> classpath,String platform,Map<Path,String> contents){
        return new EnvironmentIdentity(compose("environment-v1",generation,roots,options,processors,new TreeMap<>(generated),classpath,platform,new TreeMap<>(contents)));
    }

    /** Deterministic length-prefixed composition. Lists retain order; callers canonicalize genuine sets. */
    public static String compose(String version,Object... components){
        try{
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            var out=new DataOutputStream(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),digest));write(out,version);
            for(Object value:components)write(out,value);
            return HexFormat.of().formatHex(digest.digest());
        }catch(IOException|java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static void write(DataOutputStream out,Object value)throws IOException{
        if(value instanceof Map<?,?> map){out.writeByte(1);out.writeInt(map.size());for(var entry:map.entrySet()){write(out,entry.getKey());write(out,entry.getValue());}}
        else if(value instanceof Collection<?> values){out.writeByte(2);out.writeInt(values.size());for(Object item:values)write(out,item);}
        else{byte[] bytes=Objects.toString(value,"").getBytes(StandardCharsets.UTF_8);out.writeByte(3);out.writeInt(bytes.length);out.write(bytes);}
    }

    public synchronized Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        result.put("observations",observations);result.put("snapshot_rebuilds",rebuilds);result.put("validation_ns",validationNanos);
        result.put("capture_calls",captureCalls);result.put("source_inventory_calls",sourceInventoryCalls);result.put("source_candidates_inspected",sourceCandidatesInspected);
        result.put("environment_candidates_inspected",environmentCandidatesInspected);
        result.put("environment_live",environmentState==null?Map.of("initialized",false):environmentState.status());
        return Map.copyOf(result);
    }
    public synchronized void close(){if(environmentState!=null){environmentState.close();environmentState=null;}}
    private static List<Path> normalize(List<Path> paths){return paths.stream().map(p->p.toAbsolutePath().normalize()).toList();}
}
