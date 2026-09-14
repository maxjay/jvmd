package dev.jvmd.tests;

import dev.jvmd.resolver.*;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements 4.3: resolver implementation dependencies cannot leak into the daemon's application loader. */
@Tag("phase-2")
class MavenBundleIsolationTest {
    @TempDir Path temp;
    @Test void nativeMaven3RunsBehindAPlatformParentAndClosesWithTheFacade() throws Exception {
        for(String name:new String[]{"org.apache.maven.model.Model","org.eclipse.aether.RepositorySystem","dev.jvmd.resolver.engine.MavenEngine"})
            assertThatThrownBy(()->Class.forName(name,false,MavenResolver.class.getClassLoader())).isInstanceOf(ClassNotFoundException.class);
        var config=TestSupport.config(temp,Duration.ofHours(4));
        var resolver=new MavenResolver(config);
        try {
            assertThat(resolver.status()).containsEntry("maven_major",3).containsEntry("maven_version","3.9.16").containsEntry("resolver_version","1.9.27");
            var graph=resolver.resolve(MavenFixtures.project(temp.resolve("project"),""));
            assertThat(graph.modules()).hasSize(1);assertThat(graph.offline()).isTrue();
        } finally { resolver.close(); }
        assertThatThrownBy(()->resolver.resolve(temp)).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
    }
}
