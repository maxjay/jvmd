package dev.jvmd.core;

import java.util.*;

/** Owner-confined dependency relation. Complete observations replace edges; partial ones only add. */
public final class DependencyGraph<K> {
    private final Map<K,Set<K>> forward=new HashMap<>(),reverse=new HashMap<>();
    public void record(K owner,Collection<K> dependencies,boolean complete){
        var next=new LinkedHashSet<K>();if(!complete)next.addAll(forward.getOrDefault(owner,Set.of()));
        next.addAll(dependencies);next.remove(owner);
        for(K old:forward.getOrDefault(owner,Set.of()))if(!next.contains(old)){
            var users=reverse.get(old);if(users!=null){users.remove(owner);if(users.isEmpty())reverse.remove(old);}
        }
        for(K dependency:next)reverse.computeIfAbsent(dependency,_->new LinkedHashSet<>()).add(owner);
        forward.put(owner,Set.copyOf(next));
    }
    public Set<K> dependencies(K owner){return forward.getOrDefault(owner,Set.of());}
    public Set<K> affected(Collection<K> roots){
        var result=new LinkedHashSet<K>();var queue=new ArrayDeque<K>(roots);
        while(!queue.isEmpty()){K next=queue.removeFirst();if(result.add(next))queue.addAll(reverse.getOrDefault(next,Set.of()));}
        return Set.copyOf(result);
    }
    public void remove(K owner){record(owner,List.of(),true);forward.remove(owner);}
    public void clear(){forward.clear();reverse.clear();}
    public int edges(){return forward.values().stream().mapToInt(Set::size).sum();}
}
