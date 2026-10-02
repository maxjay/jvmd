package dev.jvmd.boot;

import dev.jvmd.boot.cold.local.LocalColdBoot;
import dev.jvmd.boot.cold.machine.MachineColdBoot;
import dev.jvmd.boot.warm.LocalWarmBoot;
import dev.jvmd.boot.warm.MachineWarmBoot;
import dev.jvmd.index.IndexService;
import dev.jvmd.index.rocks.RocksIndexStorage;
import dev.jvmd.index.rocks.layer.RocksLocalStore;
import dev.jvmd.index.rocks.layer.RocksMachineStore;
import dev.jvmd.resolver.Resolution;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * The one place that asks whether a layer has prior state. It reads a layer's committed root and
 * picks the cold boot when the root is absent or unreadable and the warm boot when it is present.
 * Nothing below this class checks again.
 */
public final class BootDecision {
    private BootDecision(){}

    /** The booted MACHINE: its storage, the index serving it, and whether it came from the warm boot. */
    public record Machine(RocksIndexStorage storage,IndexService index,boolean warm) { }

    /** A project's LOCAL: exactly one of the cold boot to run and the warm boot. */
    public record Local(LocalColdBoot cold,LocalWarmBoot warmBoot) {
        public Local {
            if((cold==null)==(warmBoot==null))throw new IllegalArgumentException("A project's LOCAL is either cold or warm");
        }
        public boolean warm(){return warmBoot!=null;}
    }

    /**
     * MACHINE at daemon start, in the current generation under {@code indexRoot}: a cold boot from
     * {@code repository} and {@code jdkHome} when no MACHINE root is committed, otherwise the warm boot.
     */
    public static Machine machine(Path indexRoot,Path repository,Path jdkHome,long admissionBytes)throws Exception{
        Path generation=RocksIndexStorage.generation(indexRoot);
        if(RocksMachineStore.committedRoot(RocksIndexStorage.machineDirectory(generation)).isPresent()){
            var storage=new MachineWarmBoot(generation,admissionBytes).run();
            return new Machine(storage,index(storage,repository),true);
        }
        var boot=new MachineColdBoot(generation,repository,jdkHome,admissionBytes);
        var storage=boot.run();
        var index=index(storage,repository);
        index.repositoryEnumerated(boot.faults().size());
        return new Machine(storage,index,false);
    }

    /**
     * LOCAL at project open, once MACHINE is committed: a cold boot of the project at
     * {@code projectRoot} when it has no committed LOCAL root, otherwise the warm boot.
     */
    public static Local local(Machine machine,Path projectRoot,Callable<Resolution> resolve,LocalColdBoot.Processors processors,
                              long compilerBudget)throws Exception{
        Path generation=machine.storage().generation();
        if(RocksLocalStore.committedRoot(RocksLocalStore.directory(generation,projectRoot)).isPresent())
            return new Local(null,new LocalWarmBoot(machine.index()));
        return new Local(new LocalColdBoot(generation,projectRoot,resolve,processors,machine.storage().machine(),machine.storage(),compilerBudget),null);
    }

    private static IndexService index(RocksIndexStorage storage,Path repository)throws Exception{
        try{
            return new IndexService(storage,repository);
        }catch(Exception|Error failure){
            try{storage.close();}catch(Exception close){failure.addSuppressed(close);}
            throw failure;
        }
    }
}
