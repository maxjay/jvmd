/** Implements 12.6: explicit production-module dependencies and qualified JSON reflection. */
module dev.jvmd.resolver {
    requires transitive dev.jvmd.core;
    exports dev.jvmd.resolver;
    opens dev.jvmd.resolver to com.fasterxml.jackson.databind;
}
