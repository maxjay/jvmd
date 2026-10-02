package dev.jvmd.index.rocks;

import org.rocksdb.Options;

/** Options that create a missing database, for stores a test opens on its own. */
final class TestOptions {
    private TestOptions(){}
    static Options creating(){return new Options().setCreateIfMissing(true).setMaxOpenFiles(64);}
}
