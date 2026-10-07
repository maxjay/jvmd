package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.digests.Sha256;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("phase-3")
class BodyIdentityTest {
    @Test void compilerPatchBuildsCannotShareAResultAddressEvenWithEqualProofs() {
        var digest=Sha256.INSTANCE;var id=digest.hash(new byte[]{1});
        var proof=new Proof(new Proof.Header(id,id,id,id,id,null),List.of(),List.of(),null);
        var first=proof.aci(digest,"App.java",id,id,"25.0.3+1");
        var next=proof.aci(digest,"App.java",id,id,"25.0.4+1");
        assertThat(next).isNotEqualTo(first);
        assertThat(proof.aci(digest,"App.java",id,id)).isEqualTo(proof.aci(digest,"App.java",id,id,Runtime.version().toString()));
        // Root/route projections are skip witnesses, not ordinary result inputs.
        var other=digest.hash(new byte[]{2});
        var sameReads=new Proof(new Proof.Header(other,other,other,other,other,null),List.of(),List.of(),null);
        assertThat(sameReads.aci(digest,"App.java",id,id,"25.0.3+1")).isEqualTo(first);
    }
}
