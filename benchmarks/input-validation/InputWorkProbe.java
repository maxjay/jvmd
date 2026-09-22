package dev.jvmd.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

/** Benchmark-only instrumentation applied identically to isolated baseline/candidate copies. */
public final class InputWorkProbe {
    private static final Map<String,Long> counts=new TreeMap<>();
    public static synchronized void add(String key,long n){counts.merge(key,n,Long::sum);}
    public static synchronized Map<String,Long> status(){return Map.copyOf(counts);}
    public static void enumeration(Path root){add(root.toString().contains("/src")?"source_enumerations":"classpath_enumerations",1);}
    public static Stream<Path> list(Path root)throws IOException{enumeration(root);return Files.list(root);}
    public static Stream<Path> find(Path root,int depth,BiPredicate<Path,BasicFileAttributes> predicate,FileVisitOption... options)throws IOException{enumeration(root);return Files.find(root,depth,predicate,options);}
    public static Map<String,Object> readAttributes(Path p,String attrs,LinkOption... opts)throws IOException{add("metadata_checks",1);return Files.readAttributes(p,attrs,opts);}
    public static <T extends BasicFileAttributes> T readAttributes(Path p,Class<T> type,LinkOption... opts)throws IOException{add("metadata_checks",1);return Files.readAttributes(p,type,opts);}
    public static boolean isRegularFile(Path p,LinkOption... opts){add("metadata_checks",1);return Files.isRegularFile(p,opts);}
    public static boolean isDirectory(Path p,LinkOption... opts){add("metadata_checks",1);return Files.isDirectory(p,opts);}
    public static boolean exists(Path p,LinkOption... opts){add("metadata_checks",1);return Files.exists(p,opts);}
    public static long size(Path p)throws IOException{add("metadata_checks",1);return Files.size(p);}
    public static FileTime getLastModifiedTime(Path p,LinkOption... opts)throws IOException{add("metadata_checks",1);return Files.getLastModifiedTime(p,opts);}
    public static <K,V> LinkedHashMap<K,V> copyMap(Map<K,V> source){add("identity_map_rebuilds",1);return new LinkedHashMap<>(source);}
}
