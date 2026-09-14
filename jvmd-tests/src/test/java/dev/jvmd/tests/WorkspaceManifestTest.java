package dev.jvmd.tests;

import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 6: ordered canonical roots and path ownership from the manifest. */
@Tag("phase-6")
class WorkspaceManifestTest {
    @TempDir Path root;
    @Test void canonicalRootsPreserveManifestOrder()throws Exception{
        Path a=Files.createDirectories(root.resolve("a")),b=Files.createDirectories(root.resolve("b"));
        var manifest=WorkspaceManifest.read(root,Json.MAPPER.valueToTree(Map.of("roots",List.of("b","a","b/../a"))));
        assertThat(manifest.roots()).containsExactly(b,a);assertThat(manifest.ignoreVersions()).isTrue();
        assertThat(manifest.resolve(root,"a/Source.java")).isEqualTo(a.resolve("Source.java"));
        assertThatThrownBy(()->manifest.resolve(root,"outside.java")).isInstanceOf(RpcException.class);
    }
}
