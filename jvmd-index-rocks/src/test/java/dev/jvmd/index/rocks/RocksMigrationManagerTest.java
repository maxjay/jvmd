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
}
