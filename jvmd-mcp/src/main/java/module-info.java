/** Implements 12.6: explicit production-module dependencies and qualified JSON reflection. */
module dev.jvmd.mcp {
    requires transitive dev.jvmd.core;
    exports dev.jvmd.mcp;
    opens dev.jvmd.mcp to com.fasterxml.jackson.databind;
}
