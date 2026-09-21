module dev.jvmd.index.rocks {
    requires transitive dev.jvmd.index;
    requires transitive rocksdbjni;
    exports dev.jvmd.index.rocks;
    opens dev.jvmd.index.rocks to com.fasterxml.jackson.databind;
    provides dev.jvmd.index.IndexStorageProvider with dev.jvmd.index.rocks.RocksIndexStorageProvider;
}
