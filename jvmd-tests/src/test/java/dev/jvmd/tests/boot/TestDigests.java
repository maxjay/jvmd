package dev.jvmd.tests.boot;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** A second {@link Digest} for invariant 9: the design must not depend on SHA-256. */
final class TestDigests {
    private TestDigests() { }

    static final class Sha3 implements Digest {
        @Override public String name() { return "SHA3-256"; }
        @Override public int width() { return 32; }
        @Override public Identity hash(byte[]... parts) {
            try {
                var md = MessageDigest.getInstance("SHA3-256");
                for (var part : parts) md.update(part);
                return Identity.of(md.digest());
            } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
    }
}
