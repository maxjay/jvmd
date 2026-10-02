package dev.jvmd.tests;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.spi.ToolProvider;
import java.util.zip.*;

/**
 * A deliberately tiny JDK for MACHINE fixtures that need real JDK declarations: a jlink image of
 * java.base keeping {@link #CLASSES} (and the smallest class of every other package, which jlink
 * requires), with their sources in {@code lib/src.zip}. Built once per test JVM.
 */
final class TestJdk {
    static final List<String> CLASSES=List.of("java/lang/Object","java/lang/String","java/lang/CharSequence","java/lang/Number",
            "java/lang/Comparable","java/lang/Iterable","java/lang/Integer","java/util/List","java/util/Collection","java/util/Map");
    private static Path home;
    private TestJdk(){}

    static synchronized Path home()throws Exception{
        if(home!=null)return home;
        Path parent=Files.createTempDirectory("jvmd-test-jdk-");Path image=parent.resolve("jdk");
        var keep=new TreeSet<String>();keep.add("module-info.class");CLASSES.forEach(name->keep.add(name+".class"));
        var smallest=new HashMap<String,Map.Entry<Long,String>>();
        Path base=FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules/java.base");
        try(var walk=Files.walk(base)){
            for(Path file:walk.filter(path->path.toString().endsWith(".class")).toList()){
                String relative=base.relativize(file).toString();int slash=relative.lastIndexOf('/');if(slash<0)continue;
                smallest.merge(relative.substring(0,slash),Map.entry(Files.size(file),relative),(a,b)->a.getKey()<=b.getKey()?a:b);
            }
        }
        smallest.values().forEach(entry->keep.add(entry.getValue()));
        String kept=String.join("|",keep.stream().map(name->name.replace(".","\\.").replace("$","\\$")).toList());
        var errors=new StringWriter();
        int status=ToolProvider.findFirst("jlink").orElseThrow().run(new PrintWriter(OutputStream.nullOutputStream()),new PrintWriter(errors),
                "--add-modules","java.base","--output",image.toString(),"--exclude-resources","regex:/java\\.base/(?!("+kept+")$).*\\.class");
        if(status!=0)throw new IllegalStateException("jlink failed: "+errors);
        Path sources=Path.of(System.getProperty("java.home"),"lib","src.zip");
        try(var zip=new ZipFile(sources.toFile());var out=new ZipOutputStream(Files.newOutputStream(image.resolve("lib/src.zip")))){
            for(String name:CLASSES){
                String entry="java.base/"+name+".java";
                try(var in=zip.getInputStream(Objects.requireNonNull(zip.getEntry(entry),entry))){out.putNextEntry(new ZipEntry(entry));in.transferTo(out);out.closeEntry();}
            }
        }
        return home=image;
    }
}
