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
        @Override public Hasher hasher() {
            final MessageDigest md;
            try { md = MessageDigest.getInstance("SHA3-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
            return new Hasher() {
                @Override public void update(byte[] bytes, int offset, int length) { md.update(bytes, offset, length); }
                @Override public Identity finish() { return Identity.of(md.digest()); }
            };
        }
    }
}
