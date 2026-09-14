/** Implements 12.6: explicit production-module dependencies and qualified JSON reflection. */
module dev.jvmd.lsp {
    requires transitive dev.jvmd.core;
    exports dev.jvmd.lsp;
    opens dev.jvmd.lsp to com.fasterxml.jackson.databind;
}
