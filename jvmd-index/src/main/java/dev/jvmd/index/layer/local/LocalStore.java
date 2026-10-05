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
    /** {@code DD|leafSetExt}: the external disjoint definer index over JDK and jar leaves, shared across projects. */
    void putDisjoint(Identity leafSetExt, byte[] value);
    /** {@code DS|leafSetSib}: the sibling disjoint definer index over this project's leaves. */
    void putSibling(Identity leafSetSib, byte[] value);
    /** {@code DC|routeHash}: the conflict table of a definer index, shared across projects. */
    void putConflicts(Identity routeHash, byte[] value);
    /** {@code C|κ_file|leafSetExt}. */
    void putConsumer(Identity kappa, Identity leafSetExt, byte[] value);
    /** {@code X|kind|key|projectKey}: this project's list of consumers of one identity (B.9). */
    void putReverse(int kind, byte[] key, Identity projectKey, byte[] value);
    /** {@code RS|κ_file|leafSetExt}. */
    void putResult(Identity kappa, Identity leafSetExt, byte[] value);
    /** {@code S|k}: a leaf's list of its types' stubs (B.10). */
    void putStub(Identity k, byte[] value);
    /** {@code ST|stKey}: one type's stub (B.10). */
    void putStubType(Identity stKey, byte[] value);
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
    /** {@code DD|leafSetExt}, or null: a shared, derivable record, so a boot may read it before its root (4, step 2.7). */
    byte[] getDisjoint(Identity leafSetExt);
    /** {@code DS|leafSetSib}, or null: read like {@code DD|}. */
    byte[] getSibling(Identity leafSetSib);
    byte[] getConflicts(Identity routeHash);
    byte[] getConsumer(Identity kappa, Identity leafSetExt);
    byte[] getReverse(int kind, byte[] key, Identity projectKey);
    byte[] getResult(Identity kappa, Identity leafSetExt);
    /** {@code S|k}, or null: shared and derivable, so a boot may read it. */
    byte[] getStub(Identity k);
    /** {@code ST|stKey}, or null: shared and derivable, so a boot may read it. */
    byte[] getStubType(Identity stKey);
    byte[] getLocalRoot(Identity projectKey);

    // ---- key layout (section 4). Defined here so every implementation writes the same bytes. -------------------------------
    int MAIN = 0, TEST = 1;

    static byte[] moduleKey(Identity projectKey, String module) { return join("MOD|", projectKey.view(), "|", module.getBytes(StandardCharsets.UTF_8)); }
    static byte[] routeKey(Identity projectKey, String module, int scope) {
        return join("RT|", projectKey.view(), "|", module.getBytes(StandardCharsets.UTF_8), "|", new byte[] {(byte) scope});
    }
    static byte[] fileKey(Identity projectKey, String path) { return join("F|", projectKey.view(), "|", path.getBytes(StandardCharsets.UTF_8)); }
    static byte[] disjointKey(Identity leafSetExt) { return join("DD|", leafSetExt.view()); }
    static byte[] siblingKey(Identity leafSetSib) { return join("DS|", leafSetSib.view()); }
    static byte[] conflictsKey(Identity routeHash) { return join("DC|", routeHash.view()); }
    static byte[] consumerKey(Identity kappa, Identity leafSetExt) { return join("C|", kappa.view(), "|", leafSetExt.view()); }
    /**
     * {@code X|kind|key|projectKey}, the project key trailing as a fixed-width id: every project's entry for one dependency is under
     * the prefix {@code X|kind|key}, which is the cross-project read, and no two projects share a key.
     */
    static byte[] reverseKey(int kind, byte[] key, Identity projectKey) { return join("X|", new byte[] {(byte) kind}, "|", key, projectKey.view()); }
    /** The prefix of every project's entry for one dependency: a range read. */
    static byte[] reversePrefix(int kind, byte[] key) { return join("X|", new byte[] {(byte) kind}, "|", key); }
    static byte[] resultKey(Identity kappa, Identity leafSetExt) { return join("RS|", kappa.view(), "|", leafSetExt.view()); }
    static byte[] stubKey(Identity k) { return join("S|", k.view()); }
    static byte[] stubTypeKey(Identity stKey) { return join("ST|", stKey.view()); }
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
