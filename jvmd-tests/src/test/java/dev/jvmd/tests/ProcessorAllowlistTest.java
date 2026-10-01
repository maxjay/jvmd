package dev.jvmd.tests;

import dev.jvmd.analyzer.ProcessorAllowlist;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Every allowlisted product has a test that runs its processor. Each case compiles a
 * small fixture with the real processor jar (pinned version, from the Maven repository), checks the
 * processor's observable output, and checks that every processor class the jar registers for
 * discovery is on the allowlist under that product.
 */
class ProcessorAllowlistTest {
    @TempDir Path root;

    record Product(String name,List<String> jars,String source,String unit,String expected) {
        @Override public String toString(){return name;}
    }
    static List<Product> products(){
        return List.of(
                new Product("Lombok",List.of("org.projectlombok:lombok:1.18.48"),
                        "package p; @lombok.Value public class Point { int x; int y; }","p/Point.java","Point.class:getX"),
                new Product("MapStruct",List.of("org.mapstruct:mapstruct-processor:1.6.3","org.mapstruct:mapstruct:1.6.3"),
                        "package p; @org.mapstruct.Mapper public interface PointMapper { Target map(Source s);"
                                +" class Source { public int x; } class Target { public int x; } }","p/PointMapper.java","p/PointMapperImpl.java"),
                new Product("Immutables",List.of("org.immutables:value:2.10.1"),
                        "package p; @org.immutables.value.Value.Immutable public abstract class Point { public abstract int x(); }","p/Point.java","p/ImmutablePoint.java"),
                new Product("AutoValue",List.of("com.google.auto.value:auto-value:1.11.0","com.google.auto.value:auto-value-annotations:1.11.0"),
                        "package p; @com.google.auto.value.AutoValue public abstract class Point { public abstract int x();"
                                +" public static Point of(int x){ return new AutoValue_Point(x); } }","p/Point.java","p/AutoValue_Point.java"),
                new Product("Hibernate JPA metamodel",List.of("org.hibernate.orm:hibernate-jpamodelgen:6.6.0.Final","jakarta.persistence:jakarta.persistence-api:3.1.0"),
                        "package p; @jakarta.persistence.Entity public class Person { @jakarta.persistence.Id Long id; String name; }","p/Person.java","p/Person_.java"),
                new Product("Spring Boot configuration metadata",List.of("org.springframework.boot:spring-boot-configuration-processor:2.7.18","org.springframework.boot:spring-boot:2.7.18"),
                        "package p; @org.springframework.boot.context.properties.ConfigurationProperties(prefix=\"demo\") public class DemoProperties {"
                                +" private String name; public String getName(){ return name; } public void setName(String name){ this.name=name; } }",
                        "p/DemoProperties.java","output:META-INF/spring-configuration-metadata.json"));
    }

    static Path repository(){return Path.of(System.getProperty("maven.repo.local",System.getProperty("user.home")+"/.m2/repository"));}
    /** The pinned jar from the local repository, fetched through Maven when absent. */
    static Path jar(String coordinates)throws Exception{
        String[] parts=coordinates.split(":");
        Path jar=repository().resolve(parts[0].replace('.','/')).resolve(parts[1]).resolve(parts[2]).resolve(parts[1]+"-"+parts[2]+".jar");
        if(!Files.isRegularFile(jar)){
            var process=new ProcessBuilder("mvn","-B","-q","dependency:get","-Dtransitive=false","-Dartifact="+coordinates,"-Dmaven.repo.local="+repository())
                    .redirectErrorStream(true).start();
            String output=new String(process.getInputStream().readAllBytes());
            assertThat(process.waitFor()).as("fetch "+coordinates+": "+output).isZero();
        }
        assertThat(jar).isRegularFile();
        return jar;
    }
    /** The full processor path of one processor artifact, resolved transitively through Maven like {@code annotationProcessorPaths}. */
    static List<Path> processorPath(Path work,String coordinates)throws Exception{
        String[] parts=coordinates.split(":");
        Path project=Files.createDirectories(work.resolve("resolve-"+parts[1]));
        Files.writeString(project.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>t</artifactId><version>1</version>"
                +"<dependencies>"+AnnotationFixtures.dependency(parts[0],parts[1],parts[2])+"</dependencies></project>");
        Path output=project.resolve("classpath.txt");
        var process=new ProcessBuilder("mvn","-B","-q","-f",project.resolve("pom.xml").toString(),"dependency:build-classpath",
                "-Dmdep.outputFile="+output,"-Dmaven.repo.local="+repository()).redirectErrorStream(true).start();
        String log=new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as("resolve "+coordinates+": "+log).isZero();
        return Arrays.stream(Files.readString(output).strip().split(java.io.File.pathSeparator)).filter(entry->!entry.isBlank()).map(Path::of).toList();
    }
    static List<String> registered(Path jar)throws Exception{
        try(var file=new JarFile(jar.toFile())){
            var entry=file.getEntry("META-INF/services/javax.annotation.processing.Processor");if(entry==null)return List.of();
            return new String(file.getInputStream(entry).readAllBytes()).lines().map(String::strip)
                    .filter(line->!line.isEmpty()&&!line.startsWith("#")).toList();
        }
    }

    @ParameterizedTest @MethodSource("products")
    void allowlistedProcessorRunsAndEveryRegisteredProcessorIsListed(Product product)throws Exception{
        var jars=new ArrayList<Path>(processorPath(root,product.jars().getFirst()));
        for(String coordinates:product.jars().subList(1,product.jars().size()))jars.add(jar(coordinates));
        var registered=registered(jar(product.jars().getFirst()));
        assertThat(registered).as("the processor jar registers processors").isNotEmpty();
        for(String processor:registered)
            assertThat(ProcessorAllowlist.entry(processor)).as(processor).hasValueSatisfying(entry->assertThat(entry.product()).isEqualTo(product.name()));

        Path sources=Files.createDirectories(root.resolve("src")),generated=Files.createDirectories(root.resolve("generated")),classes=Files.createDirectories(root.resolve("classes"));
        Path file=sources.resolve(product.unit());Files.createDirectories(file.getParent());Files.writeString(file,product.source());
        String path=String.join(java.io.File.pathSeparator,jars.stream().map(Path::toString).toList());
        var output=new java.io.StringWriter();
        boolean ok=ToolProvider.getSystemJavaCompiler().getTask(output,null,null,
                List.of("--release","25","-proc:full","-processorpath",path,"-classpath",path,"-s",generated.toString(),"-d",classes.toString()),
                null,ToolProvider.getSystemJavaCompiler().getStandardFileManager(null,null,null).getJavaFileObjects(file)).call();
        assertThat(ok).as(output.toString()).isTrue();
        if(product.expected().startsWith("output:"))
            assertThat(classes.resolve(product.expected().substring("output:".length()))).as("processor output").isRegularFile();
        else if(product.expected().contains(":")){
            String[] expected=product.expected().split(":");
            Path type=classes.resolve("p").resolve(expected[0]);assertThat(type).isRegularFile();
            assertThat(new String(Files.readAllBytes(type),java.nio.charset.StandardCharsets.ISO_8859_1)).as("generated member").contains(expected[1]);
        }else assertThat(generated.resolve(product.expected())).as("generated source").isRegularFile();
    }
}
