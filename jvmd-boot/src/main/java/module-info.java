/** Boots the layers: decides cold or warm, and holds each boot as named stages. */
module dev.jvmd.boot {
    requires dev.jvmd.core;
    requires dev.jvmd.index;
    requires transitive dev.jvmd.index.rocks;
    requires dev.jvmd.analyzer;
    requires dev.jvmd.resolver;
    exports dev.jvmd.boot;
    exports dev.jvmd.boot.cold.local;
    exports dev.jvmd.boot.cold.machine;
    exports dev.jvmd.boot.warm;
}
