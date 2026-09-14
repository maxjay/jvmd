package dev.jvmd.tests;

import dev.jvmd.core.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 1 checkpoint: open, close, list and one executor per session. */
@Tag("phase-1")
class SessionManagerTest {
    @TempDir Path temp;
    @Test void canonicalRootsReuseAnExecutorAndDifferentRootsAreIndependent() throws Exception {
        try (var sessions = new Sessions()) {
            var one = sessions.open(temp);
            assertThat(sessions.open(temp.resolve("."))).isSameAs(one);
            var two = sessions.open(Files.createDirectory(temp.resolve("other")));
            Thread owner = one.execute(Thread::currentThread);
            assertThat(owner.isVirtual()).isFalse();
            assertThat(one.execute(Thread::currentThread)).isSameAs(owner);
            assertThat(two.execute(Thread::currentThread)).isNotSameAs(owner);
            assertThat(sessions.list()).hasSize(2);
            sessions.close(one.id());
            assertThatThrownBy(() -> sessions.get(one.id())).isInstanceOf(RpcException.class);
            assertThat(sessions.list()).containsExactly(two);
        }
    }
}
