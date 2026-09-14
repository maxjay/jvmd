package dev.jvmd.tests;

import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 7: signature closure pages preserve depth and terminate on graph cycles. */
@Tag("phase-7")
class SignatureClosureTest {
    @TempDir Path root;
    @Test void followsSignaturesWithJdkDocsAndResumesWithoutDuplicates()throws Exception{
        Path jar=IndexFixtures.jar(root,"sample",IndexFixtures.generic(),true);
        try(var index=new IndexService(root.resolve("index.db"),root)){
            index.indexJar(jar,"fixture:sample:1","jar");index.indexSources(root.resolve("sample-sources.jar"));index.linkEdges();
            var symbol=index.find("transform",null,false,10,0).getFirst();long id=((Number)symbol.get("id")).longValue();
            // A self-cycle must not duplicate the root or prevent pagination.
            index.database().write(c->{try(var q=c.prepareStatement("INSERT OR IGNORE INTO edges VALUES(?,?,'return_type')")){q.setLong(1,id);q.setLong(2,id);q.executeUpdate();}return null;});
            var docs=new Documentation(index,Path.of(System.getProperty("java.home")));var identities=new LinkedHashSet<String>();int cursor=0,pages=0;
            while(true){
                var page=docs.describe(symbol,null,"summary",3,2,cursor);var result=dev.jvmd.core.Json.MAPPER.valueToTree(page.result());var closure=result.path("closure");assertThat(closure.size()).isLessThanOrEqualTo(2);
                for(var member:closure){assertThat(identities.add(member.path("scip").asText())).isTrue();assertThat(member.path("signature").asText()).isNotBlank();}
                if(!page.truncated())break;cursor=Integer.parseInt(page.cursor());assertThat(++pages).isLessThan(30);
            }
            assertThat(identities).doesNotContain(symbol.get("scip").toString()).anyMatch(s->s.contains("java/util/List#")).anyMatch(s->s.contains("java/lang/CharSequence#")).anyMatch(s->s.contains("java/lang/Number#"));
            var list=index.find("java.util.List",null,false,10,0).stream().filter(s->s.get("kind").equals("interface")).findFirst().orElseThrow();assertThat(list.get("doc")).isNotNull();
            assertThat(dev.jvmd.core.Json.MAPPER.valueToTree(docs.describe(symbol,null,"full",0,20,0).result()).path("closure").isEmpty()).isTrue();
        }
    }
}
