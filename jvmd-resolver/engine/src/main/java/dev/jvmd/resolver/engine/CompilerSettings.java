package dev.jvmd.resolver.engine;

import dev.jvmd.resolver.*;

import org.apache.maven.model.*;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import java.util.*;

/** Implements 4.2 and 4.3: javac options from Maven's effective compiler-plugin configuration. */
public final class CompilerSettings {
    private CompilerSettings() { }
    public static List<String> options(Model model,boolean test){
        Xpp3Dom config=configuration(model,test);
        String release=value(config,test?"testRelease":"release",model,test?"maven.compiler.testRelease":"maven.compiler.release",null);
        if(test&&(release==null||release.isBlank()))release=value(config,"release",model,"maven.compiler.release",null);
        var options=new ArrayList<String>();
        if(release!=null&&!release.isBlank()){options.add("--release");options.add(release);}
        else{
            String source=value(config,test?"testSource":"source",model,test?"maven.compiler.testSource":"maven.compiler.source",null);
            String target=value(config,test?"testTarget":"target",model,test?"maven.compiler.testTarget":"maven.compiler.target",null);
            if(source==null||source.isBlank())source=value(config,"source",model,"maven.compiler.source",model.getProperties().getProperty("java.version","25"));
            if(target==null||target.isBlank())target=value(config,"target",model,"maven.compiler.target",source);
            options.add("-source");options.add(source);options.add("-target");options.add(target);
        }
        if(Boolean.parseBoolean(value(config,"enablePreview",model,"maven.compiler.enablePreview","false")))options.add("--enable-preview");
        String encoding=value(config,"encoding",model,"project.build.sourceEncoding","UTF-8");options.add("-encoding");options.add(encoding);
        var args=config.getChild(test?"testCompilerArgs":"compilerArgs");if(args==null&&test)args=config.getChild("compilerArgs");
        if(args!=null)for(var arg:args.getChildren())if(arg.getValue()!=null)options.add(arg.getValue());
        String single=value(config,"compilerArgument",model,"maven.compiler.compilerArgument",null);
        if(single!=null&&!single.isBlank())options.addAll(dev.jvmd.core.Verifier.split(single));
        return List.copyOf(options);
    }
    public static Xpp3Dom configuration(Model model,boolean test){
        Plugin managed=null,plugin=null;
        if(model.getBuild().getPluginManagement()!=null)for(var value:model.getBuild().getPluginManagement().getPlugins())if(value.getArtifactId().equals("maven-compiler-plugin"))managed=value;
        for(var value:model.getBuild().getPlugins())if(value.getArtifactId().equals("maven-compiler-plugin"))plugin=value;
        Xpp3Dom config=new Xpp3Dom("configuration");
        if(managed!=null&&managed.getConfiguration() instanceof Xpp3Dom dom)config=new Xpp3Dom(dom);
        if(plugin!=null&&plugin.getConfiguration() instanceof Xpp3Dom dom)config=Xpp3Dom.mergeXpp3Dom(new Xpp3Dom(dom),config);
        Plugin selected=plugin==null?managed:plugin;String goal=test?"testCompile":"compile";
        if(selected!=null)for(var execution:selected.getExecutions())if(execution.getId().equals(test?"default-testCompile":"default-compile")||execution.getGoals().contains(goal))
            if(execution.getConfiguration() instanceof Xpp3Dom dom)config=Xpp3Dom.mergeXpp3Dom(new Xpp3Dom(dom),config);
        return config;
    }
    private static String value(Xpp3Dom config,String key,Model model,String property,String fallback){
        Xpp3Dom child=config.getChild(key);String value=child==null?null:child.getValue();
        if(value==null||value.startsWith("\u0024{"))value=model.getProperties().getProperty(property,fallback);
        return value;
    }
}
