package dev.jvmd.tests;

import dev.jvmd.dist.Application;
import dev.jvmd.resolver.MavenResolver;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 5: Lombok uses external class APIs with an explicit reduced-fidelity warning. */
@Tag("phase-5")
class LombokPolicyTest {
    @TempDir Path root;
    @Test void policyIsVisibleAtOpenBeforeAnyAttribution()throws Exception{
        MavenFixtures.project(root,"<dependencies>"+AnnotationFixtures.dependency("org.projectlombok","lombok",AnnotationFixtures.LOMBOK)+"</dependencies>"+AnnotationFixtures.processor("org.projectlombok","lombok",AnnotationFixtures.LOMBOK));
        var config=AnnotationFixtures.config(root);
        try(var resolver=new MavenResolver(config)){var module=resolver.resolve(root).modules().getFirst();assertThat(module.processing().lombok()).isTrue();assertThat(module.processing().path()).anyMatch(p->p.endsWith("lombok-"+AnnotationFixtures.LOMBOK+".jar"));}
        try(var app=new Application(config)){
            var opened=TestSupport.request(app.dispatcher(),"session.open",Map.of("root",root.toString())).path("result");assertThat(opened.path("warnings").toString()).contains("lombok_reduced_fidelity");
            String session=opened.path("result").path("session").asText();
            var status=TestSupport.request(app.dispatcher(),"session.status",Map.of("session",session)).path("result").path("result");
            assertThat(status.path("annotation_processing").path("initialized").asBoolean()).isFalse();
        }
    }
}
