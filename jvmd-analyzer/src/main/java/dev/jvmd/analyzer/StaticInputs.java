package dev.jvmd.analyzer;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.FileStateRegistry;
import dev.jvmd.core.Hash256;
import dev.jvmd.index.ClasspathSlots;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/**
 * Logical, restart-stable binding of a compile context's ambient inputs for the attributed memo
 * static key (strict task W6): classpath and path-option entries become logical slots with content
 * identities, and annotation processing is bound by processor path slots, processor classes,
 * {@code -A} options and mode. Anything that cannot be bound yields one specific refusal code.
 */
final class StaticInputs {
    /** Either the canonical bound value or the reason it cannot be bound. */
    record Binding(Object value,String refusal) {
        static Binding of(Object value){return new Binding(value,null);}
        static Binding refuse(String reason){return new Binding(null,reason);}
        boolean refused(){return refusal!=null;}
    }

    /** Options whose entries become logical slots. */
    private static final Set<String> SLOT_OPTIONS=Set.of("--module-path","-p","--processor-path","-processorpath",
            "--processor-module-path","-classpath","-cp","--class-path");
    /** Options that stay refused, each with its own reason code. */
    private static final Set<String> REFUSED_OPTIONS=Set.of("--system","--patch-module","-bootclasspath","--boot-class-path",
            "-extdirs","-endorseddirs","--source-path","-sourcepath","--module-source-path","--upgrade-module-path");

    private final FileStateRegistry files;
    private final Map<String,String> coordinates;
    StaticInputs(FileStateRegistry files,Map<String,String> coordinates){this.files=files;this.coordinates=coordinates;}

    /** Ordered logical slots of one path list; a directory is a reactor or processor output identified by its class files. */
    Binding slots(List<Path> entries)throws Exception{
        var keys=new ArrayList<String>();var identities=new ArrayList<Hash256>();
        for(Path entry:entries){
            Path normalized=entry.toAbsolutePath().normalize();
            String gav=coordinates.get(normalized.toString());
            if(gav==null)gav=coordinates.get(entry.toString());
            if(gav==null)return Binding.refuse("non-logical-classpath-entry");
            String role=coordinates.get("role:"+normalized);
            if(Files.isDirectory(normalized)){
                keys.add(role!=null?role+":"+gav:"reactor:"+gav+"|"+normalized.getFileName());
                identities.add(directoryIdentity(normalized));
            }else{
                keys.add(ClasspathSlots.logicalKey(gav,"artifact",normalized.toString()));
                String hash=files.hash(normalized);
                identities.add(CanonicalDigestWriter.digest("classpath-file-content-v1",hash));
            }
        }
        var unique=ClasspathSlots.unique(keys);var value=new ArrayList<Object>();
        for(int i=0;i<unique.size();i++)value.add(List.of(unique.get(i),identities.get(i).hex()));
        return Binding.of(value);
    }

    /**
     * Content identity of a class output directory: every {@code .class} file by path relative to the
     * directory, hashed through the journaled registry (unchanged files are not rehashed). A moved
     * checkout gives the same identity.
     */
    Hash256 directoryIdentity(Path directory)throws Exception{
        var entries=new ArrayList<Object>();
        for(Path file:new TreeSet<>(files.inventory(directory,".class",true)))
            entries.add(List.of(directory.relativize(file).toString().replace(java.io.File.separatorChar,'/'),files.hash(file)));
        return CanonicalDigestWriter.digest("reactor-classes-content-v1",entries);
    }

    /**
     * Compiler options with every path-valued slot option replaced by its logical slots; refused
     * options yield {@code path-option:<name>}. Values of other options are kept verbatim.
     */
    Binding options(List<String> options)throws Exception{
        var result=new ArrayList<Object>();
        for(int i=0;i<options.size();i++){
            String option=options.get(i);
            String name=option.contains("=")?option.substring(0,option.indexOf('=')):option;
            if(REFUSED_OPTIONS.contains(name)||option.startsWith("-Xbootclasspath")||option.startsWith("-Djava.ext.dirs")||option.startsWith("-Djava.endorsed.dirs"))
                return Binding.refuse("path-option:"+(option.startsWith("-Xbootclasspath")?"-Xbootclasspath":name));
            if(SLOT_OPTIONS.contains(name)){
                String value=option.contains("=")?option.substring(option.indexOf('=')+1):i+1<options.size()?options.get(++i):null;
                if(value==null)return Binding.refuse("path-option:"+name+":missing-value");
                var entries=Arrays.stream(value.split(java.io.File.pathSeparator)).filter(part->!part.isBlank()).map(Path::of).toList();
                var slots=slots(entries);if(slots.refused())return Binding.refuse("path-option:"+name+":"+slots.refusal());
                result.add(List.of(name,slots.value()));
            }else result.add(option);
        }
        return Binding.of(result);
    }

    /** Processor classes named explicitly, or else every processor the processor path registers for discovery. */
    static List<String> processorClasses(List<Path> path,List<String> names)throws Exception{
        if(!names.isEmpty())return List.copyOf(names);
        var result=new ArrayList<String>();
        for(Path entry:path){
            if(!Files.isRegularFile(entry))continue;
            try(var jar=new JarFile(entry.toFile())){
                var service=jar.getEntry("META-INF/services/javax.annotation.processing.Processor");if(service==null)continue;
                new String(jar.getInputStream(service).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).lines().map(String::strip)
                        .filter(line->!line.isEmpty()&&!line.startsWith("#")).forEach(result::add);
            }
        }
        return result;
    }

    /**
     * Annotation processing binding: processor path content by slot, processor classes (each must be
     * allowlisted), every {@code -A} option, and the processing mode.
     */
    Binding processors(Processing processing,List<String> options)throws Exception{
        var arguments=options.stream().filter(option->option.startsWith("-A")).sorted().toList();
        if(processing==null||!processing.enabled()){
            // -A without processing has no effect, but is still part of what the user configured.
            return Binding.of(List.of("none",arguments));
        }
        var classes=processorClasses(processing.path(),processing.names());
        if(classes.isEmpty())return Binding.refuse("processor-unknown");
        var extra=new TreeSet<String>();
        for(String processor:classes){
            var entry=ProcessorAllowlist.entry(processor);
            if(entry.isEmpty())return Binding.refuse("processor-not-allowlisted:"+processor);
            extra.add(entry.get().input().name());
        }
        var path=new ArrayList<Object>();
        for(Path entry:processing.path()){
            if(!Files.isRegularFile(entry))return Binding.refuse("processor-path-not-a-jar");
            path.add(List.of(entry.getFileName().toString(),files.hash(entry)));
        }
        return Binding.of(List.of(processing.mode(),classes.stream().sorted().toList(),path,arguments,List.copyOf(extra)));
    }
}
