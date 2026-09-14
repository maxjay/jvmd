/** Implements 12.6: explicit production-module dependencies and qualified JSON reflection. */
module dev.jvmd.runtime {
    requires transitive dev.jvmd.core;
    requires transitive jdk.jdi;
    requires java.compiler;
    requires jdk.compiler;
    exports dev.jvmd.runtime;
    opens dev.jvmd.runtime to com.fasterxml.jackson.databind;
}
