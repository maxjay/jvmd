package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import java.nio.charset.StandardCharsets;
import dev.jvmd.index.layer.machine.MachineStore;

/**
 * The store stage 2 codes against (stage 2, 9.1): {@link MachineStore} plus the LOCAL records of the table in section 4. LOCAL
 * records live in the same store as MACHINE's, under their own key prefixes; nodes of every tree and list are {@code N|hash} in
 * the one node space. A LOCAL generation is the key prefix {@code projectKey}, not a store of its own.
 *
 * <p>One put and one get, over keys from the layout below: a record kind is a key function, not a method. {@link #put} buffers in the
 * calling thread's batch and is committed by {@link #flush()}, as stage 1's. {@link #putLocalRoot} commits at once and is called only
 * after {@link #sync()}. Stage 2 itself reads only MACHINE's records ({@code L|}, {@code N|}, {@code P|}, {@code ROOT}) and the shared,
 * derivable ones ({@code DD|}, {@code DS|}, {@code S|}, {@code ST|}, {@code GEN|}, {@code GS|}), and the byte-keyed processor capability
 * record ({@code PROC|}) before its root; the others are for warm boot and attribution. PROC retains observed violations across boots.
 */
public interface LocalStore extends MachineStore {
    /** Buffers one record under {@code key}. */
    void put(byte[] key, byte[] value);

    /** The record under {@code key}, or null. */
    byte[] get(byte[] key);

    /** Visit keys beginning with this exact prefix, in store order; seek directly to the prefix, never scan the record universe. */
    void forEachKey(byte[] prefix, java.util.function.Consumer<byte[]> action);

    /** Commits at once. The previous root of this project, if any, is kept under {@code LROOT|projectKey|n} (9.1). */
    void putLocalRoot(Identity projectKey, byte[] value);

    default boolean hasLocalRoot(Identity projectKey) { return get(localRootKey(projectKey)) != null; }

    // ---- key layout (section 4). Defined here so every implementation writes the same bytes. -------------------------------
    int MAIN = 0, TEST = 1;

    static byte[] moduleKey(Identity projectKey, String module) { return join("MOD|", projectKey.view(), "|", module.getBytes(StandardCharsets.UTF_8)); }
    static byte[] sourceLeafKey(Identity projectKey, String module, int scope) {
        return join("SL|", projectKey.view(), "|", module.getBytes(StandardCharsets.UTF_8), "|", new byte[] {(byte) scope});
    }
    static byte[] routeKey(Identity projectKey, String module, int scope) {
        return join("RT|", projectKey.view(), "|", module.getBytes(StandardCharsets.UTF_8), "|", new byte[] {(byte) scope});
    }
    static byte[] fileKey(Identity projectKey, String path) { return join("F|", projectKey.view(), "|", path.getBytes(StandardCharsets.UTF_8)); }
    static byte[] disjointKey(Identity leafSetExt) { return join("DD|", leafSetExt.view()); }
    static byte[] siblingKey(Identity leafSetSib) { return join("DS|", leafSetSib.view()); }
    static byte[] conflictsKey(Identity routeHash) { return join("DC|", routeHash.view()); }
    static byte[] consumerKey(Identity kappa, Identity leafSetExt) { return join("C|", kappa.view(), "|", leafSetExt.view()); }
    static byte[] resultKey(Identity kappa, Identity leafSetExt) { return join("RS|", kappa.view(), "|", leafSetExt.view()); }
    static byte[] stubKey(Identity k) { return join("S|", k.view()); }
    static byte[] stubTypeKey(Identity stKey) { return join("ST|", stKey.view()); }
    static byte[] generatedKey(Identity derivation) { return join("GEN|", derivation.view()); }
    static byte[] generatedSourceKey(Identity content) { return join("GS|", content.view()); }
    static byte[] resourcesKey(Identity projectKey) { return join("RES|", projectKey.view()); }
    static byte[] processorDomainKey(Identity projectKey, String module, int scope, String processorClass) {
        return join("PD|", projectKey.view(), "|", module.getBytes(StandardCharsets.UTF_8), "|", new byte[] {(byte) scope}, "|", processorClass.getBytes(StandardCharsets.UTF_8));
    }
    static byte[] processorDiagnosticsKey(Identity projectKey, String module, int scope) {
        return join("PDIAG|", projectKey.view(), "|", module.getBytes(StandardCharsets.UTF_8), "|", new byte[] {(byte) scope});
    }
    static byte[] processorKey(Identity processorPathHash, String processorClass) {
        return join("PROC|", processorPathHash.view(), "|", processorClass.getBytes(StandardCharsets.UTF_8));
    }
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
