package dev.jvmd.tests;

import dev.jvmd.index.*;
import dev.jvmd.index.rocks.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class RocksArtifactSinkIntegrationTest {
    @TempDir Path temp;

    @Test void repositoryScanCanDualPublishWithoutChangingSqliteSemantics()throws Exception{
        Path repository=temp.resolve("repository");
        Path jar=IndexFixtures.jar(repository.resolve("fixture/sample/1"),"sample-1",IndexFixtures.generic(),false);
        var sink=new RocksArtifactGenerationSink(temp.resolve("rocks"),8L*1024*1024);
        try(var index=new IndexService(temp.resolve("index.db"),repository,sink)){
            index.indexJar(jar,"fixture:sample:1","jar");
            assertThat(index.find("transform",null,false,10,0)).hasSize(1);
            @SuppressWarnings("unchecked") var status=(java.util.Map<String,Object>)index.status().get("generation_sink");
            assertThat(((Number)status.get("published")).longValue()).isEqualTo(1L);

            index.indexJar(jar,"fixture:sample:1","jar");
            @SuppressWarnings("unchecked") var repeated=(java.util.Map<String,Object>)index.status().get("generation_sink");
            assertThat(((Number)repeated.get("published")).longValue()).isEqualTo(1L);
        }
    }
}
