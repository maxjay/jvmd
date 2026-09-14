package dev.jvmd.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Implements 4.3 and 4.4: content identities for graphs, artifacts and local source. */
public final class Hashing {
    private Hashing() { }
    public static String sha256(byte[] data) { return HexFormat.of().formatHex(digest().digest(data)); }
    public static String sha256(Path file) throws IOException {
        var digest = digest();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
