package dev.jvmd.index;

import dev.jvmd.core.IdentityEncoder;
import dev.jvmd.core.Id128;
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
    public record Module(String key,Id128 api,Id128 namespace,Id128 hierarchy) implements Comparable<Module> {
        public Module {
            Objects.requireNonNull(key);
            Objects.requireNonNull(api);
            Objects.requireNonNull(namespace);
            Objects.requireNonNull(hierarchy);
            if(key.isBlank())throw new IllegalArgumentException("Module key must not be blank");
        }
        public Id128 identity(){
            return IdentityEncoder.of("workspace-module-semantic-v1",key,api,namespace,hierarchy);
        }
        @Override public int compareTo(Module other){return key.compareTo(other.key);}
    }

    private final Id128 dependencyRoot;
    private final Id128 compilerOptions;
    private final Id128 processorConfig;
    private final Id128 sourceMembership;
    private final Id128 generatedOutput;
    private final List<Module> modules;
    private final Id128 identity;

    public WorkspaceSemanticIdentity(Id128 dependencyRoot,Id128 compilerOptions,Id128 processorConfig,
                                     Id128 sourceMembership,Id128 generatedOutput,Collection<Module> modules){
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
        this.identity=IdentityEncoder.of("workspace-semantic-identity-v1",
                dependencyRoot,compilerOptions,processorConfig,sourceMembership,generatedOutput,
                this.modules.stream().map(module->new Object[]{module.key(),module.identity()}).toList());
    }

    public Id128 dependencyRoot(){return dependencyRoot;}
    public Id128 compilerOptions(){return compilerOptions;}
    public Id128 processorConfig(){return processorConfig;}
    public Id128 sourceMembership(){return sourceMembership;}
    public Id128 generatedOutput(){return generatedOutput;}
    public List<Module> modules(){return modules;}
    public Id128 identity(){return identity;}
}
