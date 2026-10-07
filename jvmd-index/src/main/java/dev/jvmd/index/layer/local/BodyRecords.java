package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.index.layer.machine.MachineStore;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Snapshot reads resolve immutable values named by the selected entry, never mutable current bindings. */
public final class BodyRecords {
    private BodyRecords() { }
    public static byte[] read(ContentTree tree, LocalStore store, Identity root, byte[] key) {
        var entry = tree.get(root, h -> store.get(MachineStore.nodeKey(h)), key);
        return entry == null ? null : value(tree, store, entry);
    }
    public static byte[] value(ContentTree tree, LocalStore store, Entry entry) {
        return RootedRecords.value(tree,store::get,entry);
    }
    public static boolean isBody(byte[] key) { return tag(key,"C") || tag(key,"RS") || tag(key,"CF") || tag(key,"OUT") || ReverseIndex.isBodyKey(key); }
    public static boolean tag(byte[] key, String tag) {
        var prefix = (tag + "|").getBytes(StandardCharsets.US_ASCII);
        return key.length >= prefix.length && Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length);
    }
}
