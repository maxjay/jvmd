package dev.jvmd.tests;

import dev.jvmd.boot.cold.machine.MachineColdBoot;
import dev.jvmd.index.IndexService;
import dev.jvmd.index.rocks.RocksIndexStorage;
import dev.jvmd.index.rocks.layer.RocksMachineStore;
import java.nio.file.*;

/**
 * MACHINE fixtures for tests that need an index but do not test the cold boot. The first use of a
 * location runs the real MACHINE cold boot over an empty repository and an empty JDK home, so the
 * fixture holds no artifacts until the test adds them, or over {@link TestJdk} for tests that need
 * JDK declarations; a later use of the same location reopens it.
 */
final class TestMachine {
    private static final long BUDGET=8L*1024*1024;
    private TestMachine(){}

    /** An index over the fixture at {@code location}; {@code repository} gives Maven coordinates. */
    static IndexService index(Path location,Path repository)throws Exception{
        return new IndexService(storage(location),repository);
    }

    /** An index whose MACHINE also holds {@link TestJdk}'s java.base. */
    static IndexService indexWithJdk(Path location,Path repository)throws Exception{
        return new IndexService(storage(location,TestJdk.home()),repository);
    }

    static RocksIndexStorage storage(Path location)throws Exception{return storage(location,null);}

    private static RocksIndexStorage storage(Path location,Path jdk)throws Exception{
        return storageAt(location.toAbsolutePath().normalize().resolveSibling(location.getFileName()+".machine"),jdk);
    }

    /**
     * The index a daemon with {@code stateDir} will boot from. Preparing it before the daemon starts
     * gives the daemon a committed MACHINE root, so it takes the warm branch of BootDecision instead
     * of a production cold boot of the configured JDK and repository.
     */
    static IndexService daemon(Path stateDir,Path repository)throws Exception{
        return new IndexService(storageAt(RocksIndexStorage.generation(stateDir.resolve("index-v2")),null),repository);
    }
    /** Prepare a daemon's MACHINE fixture and close it. */
    static void prepareDaemon(Path stateDir)throws Exception{
        try(var storage=storageAt(RocksIndexStorage.generation(stateDir.resolve("index-v2")),null)){}
    }

    private static RocksIndexStorage storageAt(Path generation,Path jdk)throws Exception{
        if(RocksMachineStore.committedRoot(RocksIndexStorage.machineDirectory(generation)).isPresent())
            return RocksIndexStorage.open(generation,BUDGET);
        Path empty=Files.createDirectories(generation.resolveSibling(generation.getFileName()+".empty"));
        return new MachineColdBoot(generation,empty,jdk==null?empty:jdk,BUDGET).run();
    }
}
