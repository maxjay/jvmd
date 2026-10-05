/** Stages 1 and 2 of the layered index: the MACHINE and LOCAL cold boots. Depends on the layer types, never the other way round. */
module dev.jvmd.boot {
    requires transitive dev.jvmd.core;
    requires transitive dev.jvmd.index;
    requires dev.jvmd.index.rocks;
    requires java.compiler;
    requires jdk.compiler;
    requires java.logging;
    exports dev.jvmd.boot;
    exports dev.jvmd.boot.cold.stage1;
    exports dev.jvmd.boot.cold.stage2;
}
