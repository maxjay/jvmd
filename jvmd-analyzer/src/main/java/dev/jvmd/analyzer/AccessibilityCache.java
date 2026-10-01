package dev.jvmd.analyzer;

import dev.jvmd.index.*;
import java.nio.file.Path;
import java.util.*;

final class AccessibilityCache {
    private static final int MAX_ENTRIES=16,MAX_MEMBER_IDS=32768;
    private final LinkedHashMap<String,Set<String>> entries=new LinkedHashMap<>(16,.75f,true);
    private long memberIds,hits,misses,evictions;
    void put(String key,Set<String> members){
        if(key==null||key.isBlank())return;
        var copy=Set.copyOf(members);var old=entries.remove(key);
        if(old!=null)memberIds-=old.size();
        entries.put(key,copy);memberIds+=copy.size();
        while((entries.size()>MAX_ENTRIES||memberIds>MAX_MEMBER_IDS)&&entries.size()>1){
            var first=entries.entrySet().iterator().next();entries.remove(first.getKey());memberIds-=first.getValue().size();evictions++;
        }
    }
    Set<String> get(String key){
        var value=entries.get(key);
        if(value==null)misses++;else hits++;
        return value;
    }
    boolean contains(String key){return key!=null&&!key.isBlank()&&entries.containsKey(key);}
    void clear(){entries.clear();memberIds=0;}
    Map<String,Object> status(){return Map.of("entries",entries.size(),"member_ids",memberIds,"hits",hits,"misses",misses,"evictions",evictions,
            "max_entries",MAX_ENTRIES,"max_member_ids",MAX_MEMBER_IDS);}
}
