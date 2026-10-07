package dev.jvmd.index.layer.local;

import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import java.util.function.Function;

/** Immutable LOCAL/BROOT values selected by entry hash, independent of mutable current record keys. */
public final class RootedRecords {
    private RootedRecords() { }
    public static byte[] value(ContentTree tree,Function<byte[],byte[]> records,Entry entry) {
        byte[] value=ReverseIndex.isBodyKey(entry.key()) || ReverseIndex.isHeaderKey(entry.key()) ? Entry.NONE
                : records.apply(BodyRecords.tag(entry.key(),"CF")?entry.key():LocalStore.bodyValueKey(entry.h()));
        if(value==null || !tree.digest().hash(value).equals(entry.h()))throw new IllegalStateException("Rooted record digest mismatch");
        return value;
    }
}
