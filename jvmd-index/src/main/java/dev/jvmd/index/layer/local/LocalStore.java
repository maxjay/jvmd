package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import java.nio.charset.StandardCharsets;
import dev.jvmd.index.layer.machine.MachineStore;

/**
 * The store stage 2 codes against (stage 2, 9.1): {@link MachineStore} plus the LOCAL records of the table in section 4. LOCAL
 * records live in the same store as MACHINE's, under their own key prefixes; nodes of every tree and list are {@code N|hash} in
 * the one node space. A LOCAL generation is the key prefix {@code projectKey}, not a store of its own.
 *
 * <p>Puts buffer in the calling thread's batch and are committed by {@link #flush()}, as stage 1's. {@link #putLocalRoot} commits
 * at once and is called only after {@link #sync()}. Stage 2 itself calls only the MACHINE reads ({@link #getLeaf},
 * {@link #getNode}, {@link #getPath}, {@link #getMachineRoot}) before its root; the others are for warm boot and attribution.
 */
public interface LocalStore extends MachineStore {
    // ---- writes ----------------------------------------------------------------------------------------------------------
    void putModule(Identity projectKey, String module, byte[] value);
    void putRoute(Identity projectKey, String module, int scope, byte[] value);
    void putFile(Identity projectKey, String path, byte[] value);
    /** {@code DD|leafSet}: the disjoint part of a definer index, shared across projects. */
    void putDisjoint(Identity leafSet, byte[] value);
    /** {@code DC|routeHash}: the conflict table of a definer index, shared across projects. */
    void putConflicts(Identity routeHash, byte[] value);
    void putConsumer(Identity kappa, Identity routeHash, byte[] value);
    void putReverse(int kind, byte[] key, byte[] value);
    void putResult(Identity kappa, Identity routeHash, byte[] value);
    void putStub(Identity k, byte[] value);
    /** Commits at once. The previous root of this project, if any, is kept under {@code LROOT|projectKey|n} (9.1). */
    void putLocalRoot(Identity projectKey, byte[] value);

    // ---- reads -----------------------------------------------------------------------------------------------------------
    boolean hasLocalRoot(Identity projectKey);
    byte[] getLeaf(Identity k);
    byte[] getNode(Identity hash);
    /** {@code P|location}, or null: the MACHINE record of one location. */
    byte[] getPath(String location);
    /** The {@code ROOT} record of MACHINE, or null. */
    byte[] getMachineRoot();
    byte[] getModule(Identity projectKey, String module);
    byte[] getRoute(Identity projectKey, String module, int scope);
    byte[] getFile(Identity projectKey, String path);
    byte[] getDisjoint(Identity leafSet);
    byte[] getConflicts(Identity routeHash);
    byte[] getConsumer(Identity kappa, Identity routeHash);
    byte[] getReverse(int kind, byte[] key);
    byte[] getResult(Identity kappa, Identity routeHash);
    byte[] getStub(Identity k);
    byte[] getLocalRoot(Identity projectKey);

    // ---- key layout (section 4). Defined here so every implementation writes the same bytes. -------------------------------
    int MAIN = 0, TEST = 1;

    static byte[] moduleKey(Identity projectKey, String module) { return join("MOD|", projectKey.view(), "|", module.getBytes(StandardCharsets.UTF_8)); }
    static byte[] routeKey(Identity projectKey, String module, int scope) {
        return join("RT|", projectKey.view(), "|", module.getBytes(StandardCharsets.UTF_8), "|", new byte[] {(byte) scope});
    }
    static byte[] fileKey(Identity projectKey, String path) { return join("F|", projectKey.view(), "|", path.getBytes(StandardCharsets.UTF_8)); }
    static byte[] disjointKey(Identity leafSet) { return join("DD|", leafSet.view()); }
    static byte[] conflictsKey(Identity routeHash) { return join("DC|", routeHash.view()); }
    static byte[] consumerKey(Identity kappa, Identity routeHash) { return join("C|", kappa.view(), "|", routeHash.view()); }
    static byte[] reverseKey(int kind, byte[] key) { return join("X|", new byte[] {(byte) kind}, "|", key); }
    static byte[] resultKey(Identity kappa, Identity routeHash) { return join("RS|", kappa.view(), "|", routeHash.view()); }
    static byte[] stubKey(Identity k) { return join("S|", k.view()); }
    static byte[] localRootKey(Identity projectKey) { return join("LROOT|", projectKey.view()); }
    /** A kept previous root; {@code n} counts from 1, oldest first. */
    static byte[] localRootHistoryKey(Identity projectKey, int n) {
        return join("LROOT|", projectKey.view(), "|", new byte[] {(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n});
    }

    /** Concatenates ASCII tags ({@link String}) and raw bytes ({@code byte[]}). */
    private static byte[] join(Object... parts) {
        var out = new java.io.ByteArrayOutputStream();
        for (var part : parts) {
            if (part instanceof String s) out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
            else out.writeBytes((byte[]) part);
        }
        return out.toByteArray();
    }
}
