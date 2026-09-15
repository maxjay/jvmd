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
    @Test void aliasesResolveToOwnedFilesAndCannotEscapeThroughLinks()throws Exception{
        Path actual=Files.createDirectories(root.resolve("workspace")).toRealPath();
        Path alias=Files.createSymbolicLink(root.resolve("alias"),actual);
        Path file=Files.writeString(actual.resolve("Example.java"),"class Example {}");
        var manifest=new WorkspaceManifest(List.of(actual),true);
        assertThat(manifest.resolve(actual,alias.resolve("Example.java").toString())).isEqualTo(file);
        assertThat(manifest.resolve(actual,alias.resolve("new/Unsaved.java").toString())).isEqualTo(actual.resolve("new/Unsaved.java"));
        Path outside=Files.createDirectories(root.resolve("outside")).toRealPath();
        Files.createSymbolicLink(actual.resolve("escape"),outside);
        assertThatThrownBy(()->manifest.resolve(actual,"escape/New.java")).isInstanceOf(RpcException.class).hasMessageContaining("outside workspace");
        Files.createSymbolicLink(actual.resolve("dangling"),outside.resolve("missing"));
        assertThatThrownBy(()->manifest.resolve(actual,"dangling/New.java")).isInstanceOf(RpcException.class);
    }
}
