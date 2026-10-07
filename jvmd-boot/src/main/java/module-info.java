/** Cold stages of the layered index. Depends on the layer types, never the other way round. */
module dev.jvmd.boot {
    requires transitive dev.jvmd.core;
    requires transitive dev.jvmd.index;
    requires dev.jvmd.index.rocks;
    requires java.compiler;
    requires java.instrument;
    requires jdk.compiler;
    requires java.logging;
    exports dev.jvmd.boot;
    exports dev.jvmd.boot.cold.stage1;
    exports dev.jvmd.boot.cold.stage2;
    exports dev.jvmd.boot.cold.stage3;
}
