package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.index.rocks.RocksIndexStorage;
import dev.jvmd.index.rocks.layer.RocksMachineStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * M5 and M6 for the daemon process: READY is printed only once the MACHINE root is committed; a
 * daemon killed after the root write restarts warm; deleting the MACHINE store makes the next start
 * a cold boot, never a READY over an empty index.
 */
@Tag("phase-8")
class DaemonReadyTest {
    @TempDir Path root;

    @Test void readyFollowsTheMachineRootAndAKilledOrStrippedDaemonRestartsAccordingly()throws Exception{
        Path repository=Files.createDirectories(root.resolve("repository"));
        IndexFixtures.jar(repository.resolve("fixture/sample/1"),"sample-1",IndexFixtures.generic(),false);
        var settings=Map.<String,Object>of("jdk_home",TestJdk.home().toString(),"m2_repo",repository.toString());
        Path machine=RocksIndexStorage.machineDirectory(RocksIndexStorage.generation(root.resolve("state/index-v2")));
        assertThat(RocksMachineStore.committedRoot(machine)).isEmpty();

        var cold=AotDaemon.unprepared(root,settings);
        assertThat(RocksMachineStore.committedRoot(machine)).isPresent();
        assertThat(readiness(cold).path("repository_scan_requested").asBoolean()).isFalse();
        assertThat(readiness(cold).path("repository_reconciled").asBoolean()).isTrue();
        cold.kill();

        var warm=AotDaemon.unprepared(root,settings);
        assertThat(readiness(warm).path("repository_scan_requested").asBoolean()).isTrue();
        warm.kill();

        try(var paths=Files.walk(machine)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        try(var again=AotDaemon.unprepared(root,settings)){
            assertThat(RocksMachineStore.committedRoot(machine)).isPresent();
            assertThat(readiness(again).path("repository_scan_requested").asBoolean()).isFalse();
        }
    }

    private static JsonNode readiness(AotDaemon daemon)throws Exception{
        return daemon.request("daemon.status",Map.of()).path("result").path("readiness");
    }
}
