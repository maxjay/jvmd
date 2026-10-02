package dev.jvmd.index;

import java.util.*;

/**
 * Logical classpath slot identities.
 *
 * A slot key must be stable across checkout movement and compatible worktrees, unique within one
 * effective classpath and tied to the logical dependency/module slot. It is built from coordinates
 * and the artifact's role, never from an absolute path:
 * <ul>
 *   <li>{@code artifact:<g:a:v>|<file name>} — a resolved dependency; the file name carries the
 *       classifier/type;</li>
 *   <li>{@code module:<g:a:v>|<output role>} — a reactor module output such as {@code classes} or
 *       {@code test-classes};</li>
 *   <li>{@code jdk:<module or release key>} — a platform module.</li>
 * </ul>
 * The current physical path remains separate location metadata.
 */
public final class ClasspathSlots {
    private ClasspathSlots(){}

    public static String logicalKey(String gav,String kind,String location){
        Objects.requireNonNull(gav);Objects.requireNonNull(kind);Objects.requireNonNull(location);
        if(gav.startsWith("jdk:"))return gav;
        String name=lastSegment(location);
        if(kind.equals("local"))return "module:"+gav+"|"+name;
        return "artifact:"+gav+"|"+name;
    }
    public static String logicalKey(ArtifactContext context){
        return logicalKey(context.gav(),context.kind(),context.path());
    }

    /**
     * Assign unique keys in order. A repeated logical slot (the same coordinates occurring twice)
     * receives a deterministic occurrence suffix, which is stable for the same logical classpath.
     */
    public static List<String> unique(List<String> keys){
        var seen=new HashMap<String,Integer>();var result=new ArrayList<String>(keys.size());
        for(String key:keys){
            int occurrence=seen.merge(key,1,Integer::sum);
            result.add(occurrence==1?key:key+"#"+occurrence);
        }
        return List.copyOf(result);
    }

    private static String lastSegment(String location){
        String value=location;
        while(value.length()>1&&(value.endsWith("/")||value.endsWith("\\")))value=value.substring(0,value.length()-1);
        int split=Math.max(value.lastIndexOf('/'),value.lastIndexOf('\\'));
        return split<0?value:value.substring(split+1);
    }
}
