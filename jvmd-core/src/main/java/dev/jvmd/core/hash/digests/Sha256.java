package dev.jvmd.core.hash.digests;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** The default {@link Digest}: SHA-256. */
public final class Sha256 implements Digest {
    public static final Sha256 INSTANCE = new Sha256();

    private Sha256() { }

    @Override public String name() { return "SHA-256"; }
    @Override public int width() { return 32; }
    @Override public Identity hash(byte[]... parts) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            for (var part : parts) md.update(part);
            return Identity.of(md.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
