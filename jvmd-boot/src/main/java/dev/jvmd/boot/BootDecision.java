package dev.jvmd.boot;

import dev.jvmd.boot.cold.machine.MachineColdBoot;
import dev.jvmd.index.rocks.RocksIndexStorage;
import dev.jvmd.index.rocks.layer.RocksMachineStore;
import java.nio.file.Path;

/**
 * The one place that asks whether a layer has prior state. It reads a layer's committed root and
 * picks the cold boot when the root is absent or unreadable and the warm boot when it is present.
 * Nothing below this class checks again.
 */
public final class BootDecision {
    /** Thrown when a layer has a committed root, until the warm boot exists. */
    public static final String WARM_BOOT_MISSING="committed root found; warm boot is not implemented";

    private BootDecision(){}

    /**
     * MACHINE at daemon start: a cold boot of the current generation under {@code indexRoot} from
     * {@code repository} and {@code jdkHome} when no MACHINE root is committed.
     */
    public static RocksIndexStorage machine(Path indexRoot,Path repository,Path jdkHome,long admissionBytes)throws Exception{
        Path generation=RocksIndexStorage.generation(indexRoot);
        if(RocksMachineStore.committedRoot(RocksIndexStorage.machineDirectory(generation)).isPresent())
            throw new IllegalStateException(WARM_BOOT_MISSING);
        return new MachineColdBoot(generation,repository,jdkHome,admissionBytes).run();
    }
}
