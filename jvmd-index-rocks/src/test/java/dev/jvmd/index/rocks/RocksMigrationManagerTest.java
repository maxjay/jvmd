package dev.jvmd.index.rocks;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RocksMigrationManagerTest {
    @TempDir Path temp;

    @Test void onlyValidatedGenerationCanBecomeActiveAndPreviousCanRollback()throws Exception{
        var manager=new RocksMigrationManager(temp.resolve("index-v2"));
        manager.candidate("v1");
        assertThat(manager.active()).isEmpty();
        assertThatThrownBy(()->manager.activate("v1")).isInstanceOf(IllegalStateException.class);

        manager.markValidated("v1");
        var first=manager.activate("v1");
        assertThat(first.active()).isEqualTo("v1");assertThat(first.previous()).isEmpty();

        manager.candidate("v2");
        // Interrupted/unvalidated candidate is invisible.
        assertThat(manager.manifest().active()).isEqualTo("v1");
        manager.markValidated("v2");
        var second=manager.activate("v2");
        assertThat(second.active()).isEqualTo("v2");assertThat(second.previous()).isEqualTo("v1");
        assertThat(manager.active()).contains(manager.root().resolve("generations/v2"));
        assertThat(manager.previous()).contains(manager.root().resolve("generations/v1"));

        var rolled=manager.rollback();
        assertThat(rolled.active()).isEqualTo("v1");assertThat(rolled.previous()).isEqualTo("v2");
    }

    @Test void malformedManifestDoesNotSilentlySelectCandidate()throws Exception{
        Path root=temp.resolve("corrupt");var manager=new RocksMigrationManager(root);
        Files.writeString(root.resolve("active.manifest"),"format=99\nactive=broken\n");
        assertThatThrownBy(manager::manifest).isInstanceOf(java.io.IOException.class);
    }
    @Test void obsoleteGenerationsAreReclaimedOnlyAfterReaderPinsRelease()throws Exception{
        var manager=new RocksMigrationManager(temp.resolve("reclaim"));
        for(String generation:java.util.List.of("v1","v2","v3")){
            manager.candidate(generation);manager.markValidated(generation);
        }
        manager.activate("v1");manager.activate("v2");
        var pin=manager.pinActive().orElseThrow();
        assertThat(pin.generation()).isEqualTo("v2");
        manager.activate("v3");
        assertThat(manager.pruneObsolete()).containsExactly("v1");
        manager.candidate("v4");manager.markValidated("v4");manager.activate("v4");
        assertThat(manager.pruneObsolete()).isEmpty();
        assertThat(manager.pins()).containsEntry("v2",1);
        assertThat(Files.isDirectory(manager.root().resolve("generations/v2"))).isTrue();
        pin.close();
        assertThat(manager.pruneObsolete()).containsExactly("v2");
        assertThat(Files.exists(manager.root().resolve("generations/v2"))).isFalse();
        assertThat(Files.isDirectory(manager.root().resolve("generations/v3"))).isTrue();
        assertThat(Files.isDirectory(manager.root().resolve("generations/v4"))).isTrue();
    }

}
