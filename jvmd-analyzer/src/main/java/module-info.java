/** Implements 12.6: explicit production-module dependencies and qualified JSON reflection. */
module dev.jvmd.analyzer {
    requires transitive dev.jvmd.core;
    requires dev.jvmd.index;
    requires transitive java.compiler;
    requires transitive jdk.compiler;
    requires java.management;
    exports dev.jvmd.analyzer;
    opens dev.jvmd.analyzer to com.fasterxml.jackson.databind;
}
