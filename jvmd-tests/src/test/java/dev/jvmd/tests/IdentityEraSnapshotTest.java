package dev.jvmd.tests;

import dev.jvmd.analyzer.DiagnosticSnapshots;
import dev.jvmd.analyzer.DiagnosticStore;
import dev.jvmd.core.CompilerInputs;
import dev.jvmd.core.Hashing;
import dev.jvmd.core.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * Persisted fingerprints are compared by equality, so a snapshot written while runtime identities
 * were 64-hex SHA-256 can never match a 32-hex XXH3 identity: the first restore after upgrade is a
 * clean miss, never corruption or an error.
 */
@Tag("phase-6")
class IdentityEraSnapshotTest {
    @TempDir Path root;

    @Test void shaEraDiagnosticSnapshotIsAMissNotAnError()throws Exception{
        Path file=root.resolve("src/A.java");
        String source=Hashing.sha256("class A{}".getBytes(StandardCharsets.UTF_8));
        String oldContext=Hashing.sha256("context".getBytes(StandardCharsets.UTF_8)),oldClasspath=Hashing.sha256("classpath".getBytes(StandardCharsets.UTF_8));
        var oldKey=new DiagnosticStore.Key(file,source,oldContext,oldClasspath);
        writeShaEraSnapshot(root.resolve("snapshots"),oldKey);

        String context=CompilerInputs.compose("context"),classpath=CompilerInputs.compose("classpath");
        assertThat(context).hasSize(32);
        var key=new DiagnosticStore.Key(file,source,context,classpath);
        try(var snapshots=new DiagnosticSnapshots(root.resolve("snapshots"))){
            assertThat(snapshots.restore(oldKey)).as("fixture is a valid snapshot of its own era").isNotNull();
            assertThat(snapshots.restore(key)).isNull();
            assertThat(snapshots.status()).containsEntry("hits",1L).containsEntry("misses",1L).containsEntry("corrupt",0L);
        }
    }

    /** The on-disk layout DiagnosticSnapshots writes (schema 3), with 64-hex SHA-era fingerprints. */
    private static void writeShaEraSnapshot(Path store,DiagnosticStore.Key key)throws Exception{
        var data=new LinkedHashMap<String,Object>();
        data.put("schema",3);data.put("file",key.file().toString());data.put("source_hash",key.sourceHash());
        data.put("context",key.contextFingerprint());data.put("classpath",key.classpathFingerprint());
        data.put("api",Hashing.sha256("api".getBytes(StandardCharsets.UTF_8)));data.put("contribution",null);
        data.put("dependencies",Map.of());data.put("tier",1);data.put("warnings",List.of());data.put("diagnostics",List.of());
        byte[] payload=Json.MAPPER.writeValueAsBytes(data);
        String object=Hashing.sha256(payload);
        Path objectPath=store.resolve("objects").resolve(object.substring(0,2)).resolve(object);
        Files.createDirectories(objectPath.getParent());Files.write(objectPath,payload);
        String manifest=Hashing.sha256((key.file()+"\0"+key.contextFingerprint()).getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(store.resolve("manifests"));
        Files.writeString(store.resolve("manifests").resolve(manifest),object);
    }
}
