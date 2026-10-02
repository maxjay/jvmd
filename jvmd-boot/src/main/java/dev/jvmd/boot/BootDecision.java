package dev.jvmd.boot;

import dev.jvmd.boot.cold.machine.MachineColdBoot;
import dev.jvmd.boot.warm.MachineWarmBoot;
import dev.jvmd.index.IndexService;
import dev.jvmd.index.rocks.RocksIndexStorage;
import dev.jvmd.index.rocks.layer.RocksMachineStore;
import java.nio.file.Path;

/**
 * The one place that asks whether a layer has prior state. It reads a layer's committed root and
 * picks the cold boot when the root is absent or unreadable and the warm boot when it is present.
 * Nothing below this class checks again.
 */
public final class BootDecision {
    private BootDecision(){}

    /**
     * MACHINE at daemon start, in the current generation under {@code indexRoot}: a cold boot from
     * {@code repository} and {@code jdkHome} when no MACHINE root is committed, otherwise the warm boot.
     */
    public static IndexService machine(Path indexRoot,Path repository,Path jdkHome,long admissionBytes)throws Exception{
        Path generation=RocksIndexStorage.generation(indexRoot);
        if(RocksMachineStore.committedRoot(RocksIndexStorage.machineDirectory(generation)).isPresent())
            return new MachineWarmBoot(generation,repository,admissionBytes).run();
        var storage=new MachineColdBoot(generation,repository,jdkHome,admissionBytes).run();
        return new IndexService(storage,repository);
    }
}
