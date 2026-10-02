package dev.jvmd.analyzer;

import java.nio.file.Path;
import java.util.*;

/**
 * Logical source identities: {@code moduleCoordinates|sourceRootRole|relativePath}.
 *
 * Absolute paths remain runtime addressing. Persisted memo identity uses the module's logical
 * coordinates, the source root's role relative to its module directory (for example
 * {@code src/main/java}) and the root-relative path. The same logical identity therefore
 * resolves in a moved checkout or another worktree of the same project.
 */
final class LogicalSources {
    record Root(Path path,String gav,String role) {
        String logical(){return gav+"|"+role;}
    }
    private final List<Root> roots;

    private LogicalSources(List<Root> roots){this.roots=List.copyOf(roots);}

    /**
     * Derive roots from an analyzer context. A root participates only when its owning module's
     * coordinates and module directory are known; otherwise its files have no logical identity.
     */
    static LogicalSources of(Analyzer.Context context){
        var candidates=new LinkedHashSet<Path>();
        for(Path root:context.sources())candidates.add(root.toAbsolutePath().normalize());
        for(Path root:context.navigationSources())candidates.add(root.toAbsolutePath().normalize());
        var roots=new ArrayList<Root>();
        for(Path root:candidates){
            String gav=context.coordinates().get(root.toString());
            if(gav==null||gav.isBlank())continue;
            // Processor output roots live outside the module; the context names their logical role.
            String declared=context.coordinates().get("role:"+root);
            if(declared!=null&&!declared.isBlank()&&!declared.contains("|")){roots.add(new Root(root,gav,declared));continue;}
            Path module=null;
            for(var entry:context.coordinates().entrySet()){
                if(!gav.equals(entry.getValue())||entry.getKey().contains("://"))continue;
                Path candidate;
                try{candidate=Path.of(entry.getKey()).toAbsolutePath().normalize();}catch(Exception invalid){continue;}
                if(!root.startsWith(candidate)||root.equals(candidate))continue;
                if(module==null||candidate.getNameCount()>module.getNameCount())module=candidate;
            }
            if(module==null)continue;
            String role=separators(module.relativize(root).toString());
            if(role.isBlank()||role.startsWith(".."))continue;
            roots.add(new Root(root,gav,role));
        }
        // Distinct logical roots only: an ambiguous logical root cannot identify one location.
        var counts=new HashMap<String,Integer>();for(var root:roots)counts.merge(root.logical(),1,Integer::sum);
        roots.removeIf(root->counts.get(root.logical())>1);
        return new LogicalSources(roots);
    }

    List<Root> roots(){return roots;}

    Optional<String> logical(Path file){
        Path normalized=file.toAbsolutePath().normalize();Root owner=null;
        for(var root:roots)if(normalized.startsWith(root.path())&&(owner==null||root.path().getNameCount()>owner.path().getNameCount()))owner=root;
        if(owner==null)return Optional.empty();
        String relative=separators(owner.path().relativize(normalized).toString());
        return relative.isBlank()?Optional.empty():Optional.of(owner.logical()+"|"+relative);
    }

    Optional<Path> physical(String logical){
        int last=logical.lastIndexOf('|');if(last<=0)return Optional.empty();
        String root=logical.substring(0,last),relative=logical.substring(last+1);
        if(relative.isBlank()||relative.startsWith("/")||relative.contains(".."))return Optional.empty();
        for(var candidate:roots)if(candidate.logical().equals(root))
            return Optional.of(candidate.path().resolve(relative).normalize());
        return Optional.empty();
    }

    /** Replace physical root prefixes in free text with logical placeholders, and back. */
    String toLogicalText(String text){
        if(text==null)return null;String value=text;
        var ordered=new ArrayList<>(roots);ordered.sort(Comparator.comparingInt((Root root)->root.path().toString().length()).reversed());
        for(var root:ordered)value=value.replace(root.path().toString(),"${root:"+root.logical()+"}");
        return value;
    }
    String toPhysicalText(String text){
        if(text==null)return null;String value=text;
        for(var root:roots)value=value.replace("${root:"+root.logical()+"}",root.path().toString());
        return value;
    }

    private static String separators(String value){return value.replace(java.io.File.separatorChar,'/');}
}
