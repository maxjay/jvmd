/** Implements 12.6: explicit production-module dependencies and qualified JSON reflection. */
module dev.jvmd.index {
    requires transitive dev.jvmd.core;
    requires java.compiler;
    requires jdk.compiler;
    exports dev.jvmd.index;
    exports dev.jvmd.index.layer.machine;
    opens dev.jvmd.index to com.fasterxml.jackson.databind;
}
