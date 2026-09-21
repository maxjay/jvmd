package dev.jvmd.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Live input observations. Instances are module/session scoped; only disk observations may be shared. */
public final class CompilerInputs {
    public record SourceIdentity(String value) { }
    public record MembershipIdentity(String value) { }
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
    public record Snapshot(Map<Path,String> sources,MembershipIdentity membership,EnvironmentIdentity environment,long observation) {
        public boolean sameInputs(Snapshot other){return other!=null&&sources.equals(other.sources)&&membership.equals(other.membership)&&environment.equals(other.environment);}
        public SourceIdentity source(Path file){return new SourceIdentity(sources.get(file.toAbsolutePath().normalize()));}
        /** Identify the bytes actually supplied, never a hash reread after compilation. */
        public String text(Path file,Documents documents)throws Exception {
            String text=documents.text(file);
            if(!Objects.equals(sources.get(file.toAbsolutePath().normalize()),Hashing.sha256(text.getBytes(StandardCharsets.UTF_8))))
                throw new Superseded("Source changed before analysis: "+file);
            return text;
        }
        public Set<Path> changedSince(Snapshot prior){
            var changed=new LinkedHashSet<Path>();
            sources.forEach((file,hash)->{if(prior==null||!hash.equals(prior.sources.get(file)))changed.add(file);});
            if(prior!=null)for(Path file:prior.sources.keySet())if(!sources.containsKey(file))changed.add(file);
            return Set.copyOf(changed);
        }
    }
    public static final class Superseded extends IOException { public Superseded(String message){super(message);} }
    private final FileStateRegistry files;
    private Configuration configuration,environmentConfiguration;
    private EnvironmentIdentity environmentIdentity;
    private Snapshot snapshot;
    private Map<Path,String> environmentFiles=Map.of();
    private long observations,rebuilds,validationNanos,observation;
    private final Map<Path,Object> evidence=new HashMap<>();
    private List<List<Path>> inventories=List.of();
    private List<Path> discovered=List.of();
    private Set<Path> candidates=Set.of(), overlayPaths=Set.of();
    private List<Path> priorDisk=List.of();
    private Set<Path> environmentPaths=Set.of();
    private List<List<Path>> environmentInventories=List.of();
    public CompilerInputs(FileStateRegistry files){this.files=Objects.requireNonNull(files);}

    public synchronized Snapshot capture(Configuration config,Documents documents)throws IOException {
        var paths=new ArrayList<List<Path>>();
        for(Path root:config.roots())paths.add(files.inventory(root,".java"));
        if(!paths.equals(inventories)){
            discovered=paths.stream().flatMap(Collection::stream).distinct().toList();inventories=List.copyOf(paths);
        }
        return capture(config,documents,discovered);
    }
    /** Explicit inventories are for callers which already own discovery, not a second freshness policy. */
    public synchronized Snapshot capture(Configuration config,Documents documents,Collection<Path> diskFiles)throws IOException {
        long start=System.nanoTime();observations++;
        try {
            boolean sameConfig=config.equals(configuration);
            var open=documents.paths();
            if(!sameConfig||!diskFiles.equals(priorDisk)||!open.equals(overlayPaths)){
                var paths=new LinkedHashSet<Path>();diskFiles.forEach(p->paths.add(p.toAbsolutePath().normalize()));
                for(Path file:open)if(config.roots().isEmpty()||config.roots().stream().anyMatch(file::startsWith))paths.add(file);
                candidates=Collections.unmodifiableSet(paths);priorDisk=List.copyOf(diskFiles);overlayPaths=open;
            }
            Map<Path,String> prior=snapshot==null?Map.of():snapshot.sources();
            Map<Path,String> sources=observe(candidates,prior,documents);
            var environment=environment(config);
            MembershipIdentity membership=snapshot!=null&&sameConfig&&sources.keySet().equals(prior.keySet())?snapshot.membership():
                    new MembershipIdentity(compose("membership-v1",config.roots(),new TreeSet<>(sources.keySet())));
            if(snapshot==null||sources!=prior||!environment.equals(snapshot.environment())||!membership.equals(snapshot.membership())||snapshot.observation()!=observation){
                snapshot=new Snapshot(sources,membership,environment,observation);rebuilds++;
            }
            configuration=config;return snapshot;
        } finally {validationNanos+=System.nanoTime()-start;}
    }
    /** The same environment boundary is usable without rediscovering source inputs. */
    public synchronized EnvironmentIdentity environment(Configuration config)throws IOException {
        var paths=new ArrayList<List<Path>>();
        for(Path path:config.classpath()){
            if(Files.isDirectory(path)){paths.add(files.inventory(path,".class"));paths.add(files.inventory(path,".jar"));}
            else paths.add(List.of(path));
        }
        var entries=new ArrayList<Path>();
        var pathOptions=Set.of("--module-path","-p","--upgrade-module-path","--class-path","-classpath","-cp","--processor-path","-processorpath","--processor-module-path","--patch-module","--system");
        for(int i=0;i<config.options().size();i++){
            String option=config.options().get(i),name=option.contains("=")?option.substring(0,option.indexOf('=')):option;
            if(!pathOptions.contains(name))continue;
            String value=option.contains("=")?option.substring(option.indexOf('=')+1):i+1<config.options().size()?config.options().get(++i):"";
            if(name.equals("--patch-module")&&value.contains("="))value=value.substring(value.indexOf('=')+1);
            for(String entry:value.split(java.util.regex.Pattern.quote(File.pathSeparator)))if(!entry.isBlank()&&!entry.equals("none"))entries.add(Path.of(entry).toAbsolutePath().normalize());
        }
        for(Path path:entries)paths.add(Files.isDirectory(path)?files.inventory(path,""):List.of(path));
        Path home=Path.of(System.getProperty("java.home"));
        paths.add(List.of(home.resolve("release"),home.resolve("lib/modules"),home.resolve("lib/ct.sym")));
        if(!config.equals(environmentConfiguration)||!paths.equals(environmentInventories)){
            var keys=new LinkedHashSet<Path>();paths.forEach(keys::addAll);environmentPaths=Collections.unmodifiableSet(keys);environmentInventories=List.copyOf(paths);
        }
        var env=observe(environmentPaths,environmentFiles,null);
        if(environmentIdentity==null||!config.equals(environmentConfiguration)||env!=environmentFiles){
            environmentIdentity=environment(config.generation(),config.roots(),config.options(),List.of(),Map.of(),config.classpath().stream().map(Path::toString).toList(),config.platform(),env);
        }
        environmentConfiguration=config;environmentFiles=env;return environmentIdentity;
    }
    private Map<Path,String> observe(Set<Path> paths,Map<Path,String> prior,Documents documents)throws IOException {
        Map<Path,String> updated=null;
        for(Path file:paths){
            String hash=documents==null?null:documents.hash(file);if(hash==null)hash=files.hash(file);
            Object token=documents==null?files.evidence(file):documents.observation(file);
            if(token==null)token=files.evidence(file);
            if(evidence.put(file,token)!=token)observation++;
            boolean missing=documents!=null&&"missing".equals(hash);
            if(missing?prior.containsKey(file):!hash.equals(prior.get(file))){
                if(updated==null)updated=new LinkedHashMap<>(prior);
                if(missing)updated.remove(file);else updated.put(file,hash);
            }
        }
        for(Path file:prior.keySet())if(!paths.contains(file)){
            if(updated==null)updated=new LinkedHashMap<>(prior);updated.remove(file);
        }
        return updated==null?prior:Collections.unmodifiableMap(updated);
    }
    public boolean current(Snapshot expected,Configuration config,Documents documents)throws IOException{return expected.equals(capture(config,documents));}
    public static EnvironmentIdentity environment(String generation,List<Path> roots,List<String> options,List<String> processors,
            Map<String,String> generated,List<String> classpath,String platform,Map<Path,String> contents){
        return new EnvironmentIdentity(compose("environment-v1",generation,roots,options,processors,new TreeMap<>(generated),classpath,platform,new TreeMap<>(contents)));
    }
    /** Deterministic length-prefixed composition. Lists retain order; callers canonicalize genuine sets. */
    public static String compose(String version,Object... components){
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);write(out,version);
            for(Object value:components)write(out,value);
            return Hashing.sha256(bytes.toByteArray());
        }catch(IOException impossible){throw new AssertionError(impossible);}
    }
    private static void write(DataOutputStream out,Object value)throws IOException {
        if(value instanceof Map<?,?> map){out.writeByte(1);out.writeInt(map.size());for(var entry:map.entrySet()){write(out,entry.getKey());write(out,entry.getValue());}}
        else if(value instanceof Collection<?> values){out.writeByte(2);out.writeInt(values.size());for(Object item:values)write(out,item);}
        else {byte[] bytes=Objects.toString(value,"").getBytes(StandardCharsets.UTF_8);out.writeByte(3);out.writeInt(bytes.length);out.write(bytes);}
    }
    public synchronized Map<String,Object> status(){return Map.of("observations",observations,"snapshot_rebuilds",rebuilds,"validation_ns",validationNanos);}
    private static List<Path> normalize(List<Path> paths){return paths.stream().map(p->p.toAbsolutePath().normalize()).toList();}
}
