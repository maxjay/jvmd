package dev.jvmd.index.layer.machine;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.NodeSink;
import java.nio.charset.StandardCharsets;

/**
 * The store stage 1 codes against (stage 1, 9.1): nodes (through {@link NodeSink}), leaves, paths, the root, a sync and one read.
 * Four record kinds and nothing else: {@code L|k}, {@code N|hash}, {@code P|location}, {@code ROOT}.
 *
 * <p>{@link #write}, {@link #putLeaf} and {@link #putPath} buffer in the calling thread's batch and {@link #flush()} commits that
 * thread's batch atomically, so each job's nodes land as one batch. {@link #putRoot} commits at once and is only called after
 * {@link #sync()}.
 */
public interface MachineStore extends NodeSink {
    void putLeaf(Identity k, byte[] leaf);
    void putPath(String location, byte[] value);
    void putRoot(byte[] value);
    void sync();
    /** True if this generation holds a ROOT. The only read; it is made by the boot decision, never by stage 1. */
    boolean hasRoot();

    // Key layout (B.5). Defined here so every implementation writes the same bytes.
    byte[] ROOT_KEY = "ROOT".getBytes(StandardCharsets.US_ASCII);

    static byte[] leafKey(Identity k) { return prefixed(0x4C, k.view()); }
    static byte[] nodeKey(Identity hash) { return prefixed(0x4E, hash.view()); }
    static byte[] pathKey(String location) { return prefixed(0x50, location.getBytes(StandardCharsets.UTF_8)); }

    private static byte[] prefixed(int tag, byte[] body) {
        var out = new byte[body.length + 1];
        out[0] = (byte) tag;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }
}
