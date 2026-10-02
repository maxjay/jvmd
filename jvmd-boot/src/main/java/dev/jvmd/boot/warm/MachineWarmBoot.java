package dev.jvmd.boot.warm;

import dev.jvmd.index.rocks.RocksIndexStorage;
import java.nio.file.Path;

/**
 * MACHINE warm boot entry point. TEMPORARY(warm-boot): calls today's behaviour unchanged, reopening
 * the generation's A| records and inventory; the daemon then starts today's repository scan as it
 * always has. The warm boot task replaces this with restoring the committed MACHINE tree and
 * updating what changed.
 */
public final class MachineWarmBoot {
    private final Path generation;
    private final long admissionBytes;

    public MachineWarmBoot(Path generation,long admissionBytes){
        this.generation=generation;this.admissionBytes=admissionBytes;
    }

    public RocksIndexStorage run()throws Exception{
        return RocksIndexStorage.open(generation,admissionBytes);
    }
}
