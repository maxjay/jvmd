package dev.jvmd.runtime;

import dev.jvmd.core.RpcException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Implements 4.7 and 12.5: detect the configured JBR without bundling or changing the daemon JDK. */
public final class JavaRuntime {
    /** Implements 4.7: checked debuggee VM selection and its explicit launch options. */
    public record Selection(Path home,List<String> options,boolean enhanced,String version,List<String> warnings) { }
    private JavaRuntime() { }
    public static Selection select(Path jdk,Path jbr,Path agent)throws Exception{
        var runtime=select(jdk,jbr);if(agent==null)return runtime;
        if(!runtime.enhanced())throw new RpcException(-32003,"unsupported_capability",Map.of("capability","framework_reload","reason","HotswapAgent requires a configured enhanced JBR"));
        Path file=agent.toAbsolutePath().normalize();
        if(!Files.isRegularFile(file))throw RpcException.invalid("Configured hotswap_agent jar does not exist: "+file);
        try(var jar=new java.util.jar.JarFile(file.toFile())){
            var manifest=jar.getManifest();
            if(manifest==null||!"org.hotswap.agent.HotswapAgent".equals(manifest.getMainAttributes().getValue("Premain-Class")))throw RpcException.invalid("Configured hotswap_agent is not a HotswapAgent premain jar");
        }
        var options=new ArrayList<>(runtime.options());options.add("-XX:HotswapAgent=external");options.add("-javaagent:"+file);
        return new Selection(runtime.home(),List.copyOf(options),true,runtime.version(),runtime.warnings());
    }
    private static String property(Properties release,String key){String value=release.getProperty(key,"");return value.startsWith("\"")&&value.endsWith("\"")?value.substring(1,value.length()-1):value;}
    public static Selection select(Path jdk,Path jbr)throws Exception{
        if(jbr==null)return new Selection(jdk,List.of(),false,"",List.of());
        Path executable=jbr.resolve("bin/java"),metadata=jbr.resolve("release");
        if(!Files.isExecutable(executable)||!Files.isRegularFile(metadata))return new Selection(jdk,List.of(),false,"",List.of("jbr_unavailable: configured runtime is not installed at "+jbr));
        var release=new Properties();try(var input=Files.newInputStream(metadata)){release.load(input);}
        if(!property(release,"IMPLEMENTOR").contains("JetBrains"))return new Selection(jdk,List.of(),false,"",List.of("jbr_unavailable: configured runtime is not JetBrains Runtime"));
        String version=property(release,"JAVA_RUNTIME_VERSION");if(version.isEmpty())version=property(release,"JAVA_VERSION");
        var process=new ProcessBuilder(executable.toString(),"-XX:+AllowEnhancedClassRedefinition","-version").redirectErrorStream(true).start();
        if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();throw new RpcException(-32003,"unsupported_capability",Map.of("capability","jbr","reason","JBR flag probe timed out"));}
        String output=new String(process.getInputStream().readNBytes(8192),java.nio.charset.StandardCharsets.UTF_8);
        if(process.exitValue()!=0)return new Selection(jdk,List.of(),false,version,List.of("jbr_unavailable: enhanced redefinition flag rejected: "+output));
        return new Selection(jbr,List.of("-XX:+AllowEnhancedClassRedefinition"),true,version,List.of());
    }
}
