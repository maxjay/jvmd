package dev.jvmd.index;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import java.util.*;

/**
 * Compositional structural identity for the semantic inputs accepted by one workspace.
 *
 * This root is useful for exact observation/debugging and for locating which semantic domain
 * changed. It is not itself permission to invalidate every semantic conclusion. Consumers should
 * depend on the precise sub-identities their conclusions actually require.
 *
 * Notably, there is no global machine root here. {@code dependencyRoot} must be the selected,
 * ordered workspace dependency root produced from this workspace's actual classpath.
 */
public final class WorkspaceSemanticIdentity {
    public record Module(String key,Hash256 api,Hash256 namespace,Hash256 hierarchy) implements Comparable<Module> {
        public Module {
            Objects.requireNonNull(key);
            Objects.requireNonNull(api);
            Objects.requireNonNull(namespace);
            Objects.requireNonNull(hierarchy);
            if(key.isBlank())throw new IllegalArgumentException("Module key must not be blank");
        }
        public Hash256 identity(){
            return CanonicalDigestWriter.digest("workspace-module-semantic-v1",key,api,namespace,hierarchy);
        }
        @Override public int compareTo(Module other){return key.compareTo(other.key);}
    }

    private final Hash256 dependencyRoot;
    private final Hash256 compilerOptions;
    private final Hash256 processorConfig;
    private final Hash256 sourceMembership;
    private final Hash256 generatedOutput;
    private final List<Module> modules;
    private final Hash256 identity;

    public WorkspaceSemanticIdentity(Hash256 dependencyRoot,Hash256 compilerOptions,Hash256 processorConfig,
                                     Hash256 sourceMembership,Hash256 generatedOutput,Collection<Module> modules){
        this.dependencyRoot=Objects.requireNonNull(dependencyRoot);
        this.compilerOptions=Objects.requireNonNull(compilerOptions);
        this.processorConfig=Objects.requireNonNull(processorConfig);
        this.sourceMembership=Objects.requireNonNull(sourceMembership);
        this.generatedOutput=Objects.requireNonNull(generatedOutput);
        Objects.requireNonNull(modules);
        var ordered=new TreeMap<String,Module>();
        for(Module module:modules){
            Objects.requireNonNull(module);
            if(ordered.putIfAbsent(module.key(),module)!=null)
                throw new IllegalArgumentException("Duplicate workspace module: "+module.key());
        }
        this.modules=List.copyOf(ordered.values());
        this.identity=CanonicalDigestWriter.digest("workspace-semantic-identity-v1",
                dependencyRoot,compilerOptions,processorConfig,sourceMembership,generatedOutput,
                this.modules.stream().map(module->new Object[]{module.key(),module.identity()}).toList());
    }

    public Hash256 dependencyRoot(){return dependencyRoot;}
    public Hash256 compilerOptions(){return compilerOptions;}
    public Hash256 processorConfig(){return processorConfig;}
    public Hash256 sourceMembership(){return sourceMembership;}
    public Hash256 generatedOutput(){return generatedOutput;}
    public List<Module> modules(){return modules;}
    public Hash256 identity(){return identity;}
}
