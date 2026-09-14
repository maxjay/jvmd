package dev.jvmd.resolver.engine;

import dev.jvmd.resolver.*;

import org.apache.maven.model.*;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import java.nio.file.*;
import java.util.*;

/** Implements phase 5: processor configuration read from Maven's effective model. */
public final class ProcessorSettings {
    private ProcessorSettings() { }
    public static String value(Xpp3Dom parent,String name,String fallback) {
        var child=parent==null?null:parent.getChild(name);return child==null||child.getValue()==null?fallback:child.getValue();
    }
    public static List<Dependency> dependencies(Model model,boolean test) {
        var config=CompilerSettings.configuration(model,test);var paths=config.getChild("annotationProcessorPaths");
        if(paths==null)return List.of();var result=new ArrayList<Dependency>();
        for(var path:paths.getChildren()){
            var dependency=new Dependency();dependency.setGroupId(value(path,"groupId",null));dependency.setArtifactId(value(path,"artifactId",null));
            dependency.setClassifier(value(path,"classifier",null));dependency.setType(value(path,"type","jar"));dependency.setScope("runtime");
            String version=value(path,"version",null);
            if(version==null&&model.getDependencyManagement()!=null)for(var managed:model.getDependencyManagement().getDependencies())
                if(Objects.equals(managed.getGroupId(),dependency.getGroupId())&&Objects.equals(managed.getArtifactId(),dependency.getArtifactId())&&Objects.equals(managed.getClassifier(),dependency.getClassifier())&&Objects.equals(managed.getType(),dependency.getType()))version=managed.getVersion();
            if(version==null)throw new IllegalArgumentException("Processor version is not managed: "+dependency.getGroupId()+":"+dependency.getArtifactId());
            dependency.setVersion(version);var exclusions=path.getChild("exclusions");
            if(exclusions!=null)for(var excluded:exclusions.getChildren()){var exclusion=new Exclusion();exclusion.setGroupId(value(excluded,"groupId","*"));exclusion.setArtifactId(value(excluded,"artifactId","*"));dependency.addExclusion(exclusion);}
            result.add(dependency);
        }return List.copyOf(result);
    }
    public static List<String> names(Model model,boolean test){
        var names=CompilerSettings.configuration(model,test).getChild("annotationProcessors");
        return names==null?List.of():Arrays.stream(names.getChildren()).map(Xpp3Dom::getValue).filter(Objects::nonNull).toList();
    }
    public static List<String> sourceRoots(Model model,boolean test,Path module){
        var build=model.getBuild();var roots=new LinkedHashSet<String>();
        roots.add(absolute(module,test?build.getTestSourceDirectory():build.getSourceDirectory()));
        String generated=absolute(module,Path.of(build.getDirectory()).resolve(test?"generated-test-sources":"generated-sources").toString());roots.add(generated);
        roots.add(generatedDirectory(model,test,module));
        for(var plugin:build.getPlugins()) if(plugin.getGroupId().equals("org.codehaus.mojo") && plugin.getArtifactId().equals("build-helper-maven-plugin")) {
            for(var execution:plugin.getExecutions()) if(execution.getGoals().contains(test?"add-test-source":"add-source")) {
                Xpp3Dom configuration=execution.getConfiguration() instanceof Xpp3Dom c?new Xpp3Dom(c):new Xpp3Dom("configuration");
                if(plugin.getConfiguration() instanceof Xpp3Dom c) configuration=Xpp3Dom.mergeXpp3Dom(configuration,new Xpp3Dom(c));
                var additions=configuration.getChild("sources");
                if(additions!=null) for(var addition:additions.getChildren()) if(addition.getValue()!=null) roots.add(absolute(module,addition.getValue()));
            }
        }
        // Build helper generators conventionally create one source root below this directory.
        if(Files.isDirectory(Path.of(generated)))try(var children=Files.list(Path.of(generated))){children.filter(Files::isDirectory).sorted().map(Path::toString).forEach(roots::add);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        return List.copyOf(roots);
    }
    public static String generatedDirectory(Model model,boolean test,Path module){
        String fallback=Path.of(model.getBuild().getDirectory()).resolve(test?"generated-test-sources/test-annotations":"generated-sources/annotations").toString();
        return absolute(module,value(CompilerSettings.configuration(model,test),test?"generatedTestSourcesDirectory":"generatedSourcesDirectory",fallback));
    }
    private static String absolute(Path module,String path){return module.resolve(path).toAbsolutePath().normalize().toString();}
}
