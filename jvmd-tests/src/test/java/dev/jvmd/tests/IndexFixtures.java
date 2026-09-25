package dev.jvmd.tests;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
/** Implements 12.4: compiled jar/source pairs that exercise generic and multi-release metadata. */
final class IndexFixtures {
    static Path jar(Path directory,String name,String source,boolean parameters)throws Exception{
        return jar(directory,name,"Sample.java",source,parameters);
    }
    static Path jar(Path directory,String name,String fileName,String source,boolean parameters)throws Exception{
        Files.createDirectories(directory);Path input=directory.resolve(fileName),classes=Files.createDirectories(directory.resolve(name+"-classes"));Files.writeString(input,source);
        var args=new ArrayList<>(List.of("-g","-d",classes.toString()));if(parameters)args.add("-parameters");args.add(input.toString());
        if(ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new))!=0)throw new AssertionError("fixture compiler failed");
        Path jar=directory.resolve(name+".jar");try(var output=new JarOutputStream(Files.newOutputStream(jar));var files=Files.walk(classes)){for(var p:files.filter(Files::isRegularFile).toList()){output.putNextEntry(new JarEntry(classes.relativize(p).toString()));Files.copy(p,output);output.closeEntry();}}
        Path sources=directory.resolve(name+"-sources.jar");try(var output=new JarOutputStream(Files.newOutputStream(sources))){output.putNextEntry(new JarEntry("fixture/Sample.java"));output.write(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));output.closeEntry();}
        return jar;
    }
    static String generic(){return """
        package fixture;
        /** A generic sample. */
        public class Sample<T extends Number> implements Comparable<Sample<T>> {
          public int compareTo(Sample<T> other) { return 0; }
          /** Transform the value.\n @param input the input value\n @return the transformed value */
          public <U extends CharSequence> java.util.List<U> transform(T input, U text) { return null; }
          protected T protectedValue;
          private String hidden;
          public static class Nested { public int value; }
        }
        """;}
}
