package dev.jvmd.boot.warm;

import dev.jvmd.index.IndexService;
import dev.jvmd.index.rocks.RocksIndexStorage;
import java.nio.file.Path;

/**
 * MACHINE warm boot entry point. TEMPORARY(warm-boot): calls today's behaviour unchanged, reopening
 * the generation's A| records and inventory; the daemon then starts today's repository scan as it
 * always has. The warm boot task replaces this with restoring the committed MACHINE tree and
 * updating what changed.
 */
public final class MachineWarmBoot {
    private final Path generation,repository;
    private final long admissionBytes;

    public MachineWarmBoot(Path generation,Path repository,long admissionBytes){
        this.generation=generation;this.repository=repository;this.admissionBytes=admissionBytes;
    }

    public IndexService run()throws Exception{
        var storage=RocksIndexStorage.open(generation,admissionBytes);
        try{
            return new IndexService(storage,repository);
        }catch(Exception|Error failure){
            try{storage.close();}catch(Exception close){failure.addSuppressed(close);}
            throw failure;
        }
    }
}
