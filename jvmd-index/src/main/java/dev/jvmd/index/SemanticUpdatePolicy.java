package dev.jvmd.index;

import java.nio.file.Path;
import java.util.*;

/** Semantic decisions independent of storage and compiler ownership. Call under the owner's executor/transaction. */
public final class SemanticUpdatePolicy {
    private SemanticUpdatePolicy() {}
    public enum Completeness { COMPLETE, FOCUSED, FAILED }
    public record Change(FileSemanticContribution before,FileSemanticContribution after) {
        public Change {
            if(before==null&&after==null)throw new IllegalArgumentException("Empty change");
            if(before!=null&&after!=null&&!before.file().equals(after.file()))throw new IllegalArgumentException("Different files");
        }
        public Path file(){return after==null?before.file():after.file();}
    }
    public record Result(Set<Path> reanalyze,Set<Path> apiChanged,Set<Path> bodyOnly,Set<Path> deleted,
                         boolean contextChanged,boolean namespaceChanged) {
        public Result { reanalyze=Set.copyOf(reanalyze);apiChanged=Set.copyOf(apiChanged);bodyOnly=Set.copyOf(bodyOnly);deleted=Set.copyOf(deleted); }
    }
    /** Derived postings of the published graph, read before replacing or deleting its contributions. */
    public interface Postings {
        Set<Path> dependants(Path file);
        Set<Path> unresolved(Set<String> exports);
        Set<Path> files();
    }
    public static Result decide(Collection<Change> changes,boolean environmentChanged,Postings postings){
        try(var trace=dev.jvmd.core.RequestScope.stage("semantic.invalidate")){
            trace.count("changed_files",changes.size());
        var api=new LinkedHashSet<Path>();var body=new LinkedHashSet<Path>();var deleted=new LinkedHashSet<Path>();
        var exports=new LinkedHashSet<String>();boolean namespace=false;
        for(var change:changes){
            var old=change.before();var now=change.after();Path file=change.file();
            boolean names=old==null||now==null||!old.exportedNames().equals(now.exportedNames());namespace|=names;
            if(now==null)deleted.add(file);
            if(names||!old.apiFingerprint().equals(now.apiFingerprint())){
                api.add(file);if(old!=null)exports.addAll(old.exportedNames());if(now!=null)exports.addAll(now.exportedNames());
            }else if(!old.equals(now))body.add(file);
        }
        var affected=new LinkedHashSet<Path>(body);affected.addAll(api);
        if(environmentChanged)affected.addAll(postings.files());
        else if(!api.isEmpty()){
            var roots=new LinkedHashSet<>(api);roots.addAll(postings.unresolved(exports));
            affected.addAll(closure(roots,postings));
        }
        affected.removeAll(deleted);
        trace.count("affected_files",affected.size());trace.count("api_files",api.size());trace.count("body_files",body.size());
        return new Result(affected,api,body,deleted,environmentChanged,namespace);
    
        }
    }
    public static Set<Path> closure(Collection<Path> roots,Postings postings){
        var result=new LinkedHashSet<Path>();var queue=new ArrayDeque<>(roots);
        while(!queue.isEmpty()){Path path=queue.removeFirst();if(result.add(path))queue.addAll(postings.dependants(path));}
        return Set.copyOf(result);
    }
    public static boolean matches(Set<String> unresolved,Set<String> exports){
        if(unresolved.contains("*")&&!exports.isEmpty())return true;
        for(String target:unresolved)for(String name:exports)
            if(target.equals(name)||target.startsWith(name+".")||name.startsWith(target+"."))return true;
        return false;
    }

    /** Actor-local canonical state. Focused observations supplement facts until a complete replacement arrives. */
    public static final class Live implements Postings {
        private final Map<Path,FileSemanticContribution> complete=new HashMap<>();
        private final Map<Path,Set<Path>> focused=new HashMap<>(),reverse=new HashMap<>();
        private final Map<Path,Set<Path>> pending=new HashMap<>();
        public FileSemanticContribution contribution(Path path){return complete.get(normalize(path));}
        public Set<Path> dependencies(Path path){
            path=normalize(path);var result=new HashSet<>(focused.getOrDefault(path,Set.of()));
            var value=complete.get(path);if(value!=null)result.addAll(value.dependencies());return Set.copyOf(result);
        }
        public Set<Path> dependants(Path path){return Set.copyOf(reverse.getOrDefault(normalize(path),Set.of()));}
        public Set<Path> files(){var result=new HashSet<>(complete.keySet());result.addAll(focused.keySet());return Set.copyOf(result);}
        public Set<Path> unresolved(Set<String> exports){
            var result=new LinkedHashSet<Path>();complete.forEach((file,value)->{if(matches(value.unresolvedTargets(),exports))result.add(file);});return Set.copyOf(result);
        }
        /** Files carrying negative/unresolved name evidence; used when source membership itself changes. */
        public Set<Path> unresolved(){
            var result=new LinkedHashSet<Path>();complete.forEach((file,value)->{if(!value.unresolvedTargets().isEmpty())result.add(file);});return Set.copyOf(result);
        }
        /** Initial attribution discovers existing files; only an observed edit is a change during bootstrap. */
        public Result resolve(FileSemanticContribution value){
            boolean initial=contribution(value.file())==null&&!pending(value.file());
            if(initial){replace(value);return new Result(Set.of(),Set.of(),Set.of(),Set.of(),false,false);}
            return update(value,Completeness.COMPLETE);
        }
        public Result update(FileSemanticContribution value,Completeness completeness){
            Objects.requireNonNull(value);Objects.requireNonNull(completeness);
            Path file=value.file();
            if(completeness==Completeness.FAILED)return new Result(Set.of(file),Set.of(),Set.of(),Set.of(),false,false);
            if(completeness==Completeness.FOCUSED){recordFocused(file,value.dependencies());return new Result(Set.of(),Set.of(),Set.of(),Set.of(),false,false);}
            var before=complete.get(file);
            // Decide with old edges still present, then replace exactly, including empty sets.
            var result=decide(List.of(new Change(before,value)),false,this);
            replace(value);pending.remove(file);
            return result;
        }
        private void replace(FileSemanticContribution value){
            Path file=value.file();unlink(file);complete.put(file,value);focused.remove(file);link(file);
        }
        public void recordFocused(Path file,Set<Path> dependencies){
            file=normalize(file);unlink(file);var merged=new HashSet<>(focused.getOrDefault(file,Set.of()));
            for(Path dependency:dependencies)if(!normalize(dependency).equals(file))merged.add(normalize(dependency));
            focused.put(file,Set.copyOf(merged));link(file);
        }
        public Result remove(Path file){
            file=normalize(file);var before=complete.get(file);
            var affected=new HashSet<>(closure(Set.of(file),this));affected.remove(file);
            var result=before==null?new Result(affected,Set.of(file),Set.of(),Set.of(file),false,true)
                    :decide(List.of(new Change(before,null)),false,this);
            unlink(file);complete.remove(file);focused.remove(file);pending.remove(file);return result;
        }
        public Set<Path> changed(Path file){
            file=normalize(file);var affected=closure(Set.of(file),this);pending.put(file,affected);return affected;
        }
        public Set<Path> prerequisites(Path file){
            file=normalize(file);var result=new HashSet<Path>();
            for(var entry:pending.entrySet())if(!file.equals(entry.getKey())&&entry.getValue().contains(file))result.add(entry.getKey());
            return Set.copyOf(result);
        }
        public boolean pending(Path file){return pending.containsKey(normalize(file));}
        public int pendingCount(){return pending.size();}
        public int conditionalCount(){
            var files=new HashSet<Path>();pending.forEach((root,affected)->affected.stream().filter(p->!p.equals(root)).forEach(files::add));return files.size();
        }
        public void clear(){complete.clear();focused.clear();reverse.clear();pending.clear();}
        private void unlink(Path file){for(Path dependency:dependencies(file)){var values=reverse.get(dependency);if(values!=null){values.remove(file);if(values.isEmpty())reverse.remove(dependency);}}}
        private void link(Path file){for(Path dependency:dependencies(file))if(!dependency.equals(file))reverse.computeIfAbsent(dependency,k->new HashSet<>()).add(file);}
        public int edgeCount(){return reverse.values().stream().mapToInt(Set::size).sum();}
        private static Path normalize(Path path){return path.toAbsolutePath().normalize();}
    }
}
