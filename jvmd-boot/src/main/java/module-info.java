/** Stage 1 of the layered index: the MACHINE cold boot. Depends on the layer types, never the other way round. */
module dev.jvmd.boot {
    requires transitive dev.jvmd.core;
    requires transitive dev.jvmd.index;
    requires dev.jvmd.index.rocks;
    requires java.logging;
    exports dev.jvmd.boot;
    exports dev.jvmd.boot.cold.stage1;
}
