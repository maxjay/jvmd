package dev.jvmd.core.tree;

import java.util.List;
import java.util.Objects;

/**
 * Writes a layer so that its root exists only if everything the root covers exists: every leaf and
 * tree node goes in one synced batch, and the root is written after that batch is durable.
 */
public final class Commit {
    public record Record(byte[] key,byte[] value) {
        public Record { key=key.clone();value=value.clone(); }
        @Override public byte[] key(){return key.clone();}
        @Override public byte[] value(){return value.clone();}
    }

    /** The store a layer is committed to. */
    public interface Target {
        /** Writes all records in one batch and returns only once it is durable. */
        void writeSynced(List<Record> records)throws Exception;
        /** Durably writes the root. Called only after {@link #writeSynced} returned. */
        void writeRoot(byte[] root)throws Exception;
    }

    private Commit(){}

    public static void commit(Target target,List<Record> records,Root root)throws Exception{
        Objects.requireNonNull(target);Objects.requireNonNull(root);
        target.writeSynced(List.copyOf(records));
        target.writeRoot(root.encode());
    }
}
