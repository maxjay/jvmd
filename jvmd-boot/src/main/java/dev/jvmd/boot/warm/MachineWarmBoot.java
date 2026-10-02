package dev.jvmd.boot.warm;

import dev.jvmd.index.IndexService;
import dev.jvmd.index.rocks.RocksIndexStorage;
import java.nio.file.Path;

/**
 * MACHINE warm boot entry point. TEMPORARY(warm-boot): calls today's behaviour unchanged, reopening
 * the generation's A| records and inventory and starting the existing repository scan; the warm
 * boot task replaces it with restoring the committed MACHINE tree and updating what changed.
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
            var index=new IndexService(storage,repository);
            index.start();
            return index;
        }catch(Exception|Error failure){
            try{storage.close();}catch(Exception close){failure.addSuppressed(close);}
            throw failure;
        }
    }
}
