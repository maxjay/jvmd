package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RocksArtifactGenerationProviderTest {
    @TempDir Path temp;

    @Test void serviceLoaderSelectsRocksAndSupportsExplicitRollback()throws Exception{
        try(var sink=ArtifactGenerationSink.open("rocksdb-sst",temp.resolve("rocks"),8L*1024*1024)){
            assertThat(sink.status()).containsEntry("backend","rocksdb-sst");
        }
        try(var sink=ArtifactGenerationSink.open("none",temp.resolve("none"),8L*1024*1024)){
            assertThat(sink.status()).containsEntry("backend","none");
        }
    }
}
