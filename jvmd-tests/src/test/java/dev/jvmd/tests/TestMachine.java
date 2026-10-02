package dev.jvmd.tests;

import dev.jvmd.boot.cold.machine.MachineColdBoot;
import dev.jvmd.index.IndexService;
import dev.jvmd.index.rocks.RocksIndexStorage;
import dev.jvmd.index.rocks.layer.RocksMachineStore;
import java.nio.file.*;

/**
 * MACHINE fixtures for tests that need an index but do not test the cold boot. The first use of a
 * location runs the real MACHINE cold boot over an empty repository and an empty JDK home, so the
 * fixture holds no artifacts until the test adds them; a later use of the same location reopens it.
 */
final class TestMachine {
    private static final long BUDGET=8L*1024*1024;
    private TestMachine(){}

    /** An index over the fixture at {@code location}; {@code repository} gives Maven coordinates. */
    static IndexService index(Path location,Path repository)throws Exception{
        return new IndexService(storage(location),repository);
    }

    static RocksIndexStorage storage(Path location)throws Exception{
        Path generation=location.toAbsolutePath().normalize().resolveSibling(location.getFileName()+".machine");
        if(RocksMachineStore.committedRoot(RocksIndexStorage.machineDirectory(generation)).isPresent())
            return RocksIndexStorage.open(generation,BUDGET);
        Path empty=Files.createDirectories(generation.resolveSibling(location.getFileName()+".empty"));
        return new MachineColdBoot(generation,empty,empty,BUDGET).run();
    }
}
