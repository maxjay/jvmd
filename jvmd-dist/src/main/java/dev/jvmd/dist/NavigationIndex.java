package dev.jvmd.dist;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.PersistentMap;
import java.nio.file.Path;
import java.util.*;

/** Immutable publication root. File replacement shares every untouched posting and map subtree. */
final class NavigationIndex {
    private record Symbol(Map<String,Object> row,boolean declaration){}
    private PersistentMap<String,PersistentMap<Path,Symbol>> owners=PersistentMap.empty();
    private PersistentMap<String,Map<String,Object>> symbols=PersistentMap.empty(),declarations=PersistentMap.empty();
    private PersistentMap<String,PersistentMap<String,Boolean>> names=PersistentMap.empty();
    private PersistentMap<Bindings.Edge,PersistentMap<Path,Boolean>> edges=PersistentMap.empty();
    private PersistentMap<String,PersistentMap<Bindings.Edge,Boolean>> outgoing=PersistentMap.empty(),incoming=PersistentMap.empty();
    private PersistentMap<Bindings.Edge,PersistentMap<Path,List<Bindings.Occurrence>>> references=PersistentMap.empty();
    private PersistentMap<Path,List<Bindings.Occurrence>> occurrences=PersistentMap.empty();
    private PersistentMap<Path,List<CompilerPool.Problem>> diagnostics=PersistentMap.empty();
    private PersistentMap<Path,List<String>> warnings=PersistentMap.empty();
    private PersistentMap<Path,Integer> degraded=PersistentMap.empty();
    private int occurrenceCount,diagnosticCount;
    private long updates;
    NavigationIndex(){}
    private NavigationIndex(NavigationIndex prior){
        owners=prior.owners;symbols=prior.symbols;declarations=prior.declarations;names=prior.names;
        edges=prior.edges;outgoing=prior.outgoing;incoming=prior.incoming;references=prior.references;
        occurrences=prior.occurrences;diagnostics=prior.diagnostics;warnings=prior.warnings;degraded=prior.degraded;
        occurrenceCount=prior.occurrenceCount;diagnosticCount=prior.diagnosticCount;updates=prior.updates;
    }
    private static <K extends Comparable<? super K>,V> PersistentMap<K,PersistentMap<Path,V>> contribution(
            PersistentMap<K,PersistentMap<Path,V>> index,K key,Path file,V value){
        var posting=index.getOrDefault(key,PersistentMap.empty());posting=value==null?posting.without(file):posting.with(file,value);
        return posting.isEmpty()?index.without(key):index.with(key,posting);
    }
    private static <K extends Comparable<? super K>,V> PersistentMap<K,List<V>> chunk(PersistentMap<K,List<V>> map,K key,List<V> values){
        return values.isEmpty()?map.without(key):map.with(key,List.copyOf(values));
    }
    private static Set<String> names(Map<String,Object> row){
        var result=new LinkedHashSet<String>();if(row!=null)for(String field:List.of("name_path","qualified_name_path","name","fqn")){
            String value=Objects.toString(row.get(field),"");if(!value.isBlank())result.add(value);
        }return result;
    }
    private void select(String id){
        var prior=symbols.get(id);var contributions=owners.get(id);Symbol selected=null,declaration=null;
        if(contributions!=null)for(Symbol value:contributions.values()){selected=value;if(value.declaration())declaration=value;}
        if(declaration!=null)selected=declaration;
        Map<String,Object> current=selected==null?null:selected.row();
        declarations=declaration==null?declarations.without(id):declarations.with(id,declaration.row());
        if(Objects.equals(prior,current))return;
        for(String name:names(prior)){var posting=names.get(name).without(id);names=posting.isEmpty()?names.without(name):names.with(name,posting);}
        symbols=current==null?symbols.without(id):symbols.with(id,current);
        for(String name:names(current))names=names.with(name,names.getOrDefault(name,PersistentMap.empty()).with(id,true));
    }
    private static PersistentMap<String,PersistentMap<Bindings.Edge,Boolean>> edgePosting(
            PersistentMap<String,PersistentMap<Bindings.Edge,Boolean>> map,String symbol,Bindings.Edge edge,boolean add){
        var posting=map.getOrDefault(symbol,PersistentMap.empty());posting=add?posting.with(edge,true):posting.without(edge);
        return posting.isEmpty()?map.without(symbol):map.with(symbol,posting);
    }
    private void edge(Path file,Bindings.Edge edge,boolean add){
        boolean existed=edges.containsKey(edge);edges=contribution(edges,edge,file,add?Boolean.TRUE:null);
        if(existed!=edges.containsKey(edge)){
            outgoing=edgePosting(outgoing,edge.src(),edge,add);incoming=edgePosting(incoming,edge.dst(),edge,add);
        }
    }
    private static Map<Bindings.Edge,List<Bindings.Occurrence>> grouped(List<Bindings.Occurrence> values){
        var result=new LinkedHashMap<Bindings.Edge,List<Bindings.Occurrence>>();
        for(var o:values)if(o.container()!=null)result.computeIfAbsent(new Bindings.Edge(o.container(),o.scip(),o.role()),_->new ArrayList<>()).add(o);
        return result;
    }
    NavigationIndex replace(Path file,CompilerPool.Outcome<Bindings.Snapshot> before,CompilerPool.Outcome<Bindings.Snapshot> after){
        var next=new NavigationIndex(this);next.updates++;
        var old=before==null?null:before.result();var now=after==null?null:after.result();var touched=new LinkedHashSet<String>();
        if(old!=null){
            for(String id:old.symbols().keySet()){next.owners=contribution(next.owners,id,file,null);touched.add(id);}
            for(var edge:old.edges())next.edge(file,edge,false);
            for(var edge:grouped(old.occurrences()).keySet())next.references=contribution(next.references,edge,file,null);
        }
        if(now!=null){
            var ownDeclarations=new HashSet<String>();
            for(var occurrence:now.occurrences())if(occurrence.role().equals("declaration")&&Path.of(occurrence.file()).toAbsolutePath().normalize().equals(file)){
                var row=now.symbols().get(occurrence.scip());if(row!=null&&!Set.of("local_variable","parameter","exception_parameter","binding_variable","resource_variable","type_parameter").contains(row.get("kind")))ownDeclarations.add(occurrence.scip());
            }
            for(var entry:now.symbols().entrySet()){next.owners=contribution(next.owners,entry.getKey(),file,new Symbol(entry.getValue(),ownDeclarations.contains(entry.getKey())));touched.add(entry.getKey());}
            for(var edge:now.edges())next.edge(file,edge,true);
            for(var entry:grouped(now.occurrences()).entrySet())next.references=contribution(next.references,entry.getKey(),file,List.copyOf(entry.getValue()));
        }
        for(String id:touched)next.select(id);
        var occurrenceList=now==null?List.<Bindings.Occurrence>of():now.occurrences();
        next.occurrenceCount+=occurrenceList.size()-next.occurrences.getOrDefault(file,List.of()).size();next.occurrences=chunk(next.occurrences,file,occurrenceList);
        var problems=after==null?List.<CompilerPool.Problem>of():after.diagnostics();
        next.diagnosticCount+=problems.size()-next.diagnostics.getOrDefault(file,List.of()).size();next.diagnostics=chunk(next.diagnostics,file,problems);
        var messages=after==null?List.<String>of():after.warnings();
        if(after!=null&&now==null){var copy=new ArrayList<>(messages);copy.add("incomplete_workspace_bindings: "+file);messages=List.copyOf(copy);}
        next.warnings=chunk(next.warnings,file,messages);
        next.degraded=after==null||after.tier()==2&&now!=null?next.degraded.without(file):next.degraded.with(file,Math.min(1,after.tier()));
        return next;
    }
    Map<String,Map<String,Object>> symbols(){return symbols;}
    Map<String,Map<String,Object>> declarations(){return declarations;}
    int tier(){return degraded.isEmpty()?2:degraded.values().stream().mapToInt(Integer::intValue).min().orElse(1);}
    long updates(){return updates;}
    List<String> warnings(){var result=new LinkedHashSet<String>();warnings.values().forEach(result::addAll);return List.copyOf(result);}
    List<Map<String,Object>> lookup(String ref){var posting=names.get(ref);return posting==null?List.of():posting.keySet().stream().map(symbols::get).toList();}
    List<Bindings.Edge> adjacent(Set<String> frontier,boolean forward){
        var selected=new TreeSet<Bindings.Edge>();var index=forward?outgoing:incoming;
        for(String symbol:frontier){var posting=index.get(symbol);if(posting!=null)selected.addAll(posting.keySet());}return List.copyOf(selected);
    }
    List<Bindings.Occurrence> references(Set<Bindings.Edge> selected){
        var result=new ArrayList<Bindings.Occurrence>();for(var edge:selected){var posting=references.get(edge);if(posting!=null)posting.values().forEach(result::addAll);}
        result.sort(Comparator.comparing(Bindings.Occurrence::file).thenComparingInt(Bindings.Occurrence::start).thenComparing(Bindings.Occurrence::scip).thenComparing(Bindings.Occurrence::role));return List.copyOf(result);
    }
    List<Bindings.Edge> edges(){return new AbstractList<>(){
        public int size(){return edges.size();}
        public Bindings.Edge get(int index){Objects.checkIndex(index,size());var iterator=edges.keySet().iterator();while(index-->0)iterator.next();return iterator.next();}
        @Override public Iterator<Bindings.Edge> iterator(){return edges.keySet().iterator();}
    };}
    private static <T> List<T> flattened(PersistentMap<Path,List<T>> chunks,int size){return new AbstractList<>(){
        public int size(){return size;}
        public T get(int index){Objects.checkIndex(index,size);for(var chunk:chunks.values()){if(index<chunk.size())return chunk.get(index);index-=chunk.size();}throw new AssertionError();}
        @Override public Iterator<T> iterator(){return new Iterator<>(){
            final Iterator<List<T>> outer=chunks.values().iterator();Iterator<T> inner=Collections.emptyIterator();
            public boolean hasNext(){while(!inner.hasNext()&&outer.hasNext())inner=outer.next().iterator();return inner.hasNext();}
            public T next(){if(!hasNext())throw new NoSuchElementException();return inner.next();}
        };}
    };}
    List<Bindings.Occurrence> occurrences(){return flattened(occurrences,occurrenceCount);}
    List<CompilerPool.Problem> diagnostics(){return flattened(diagnostics,diagnosticCount);}
}
