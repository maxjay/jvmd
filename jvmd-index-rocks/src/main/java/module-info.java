module dev.jvmd.index.rocks {
    requires transitive dev.jvmd.index;
    requires transitive rocksdbjni;
    exports dev.jvmd.index.rocks;
    exports dev.jvmd.index.rocks.layer;
    opens dev.jvmd.index.rocks to com.fasterxml.jackson.databind;
}
