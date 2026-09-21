/** Implements 12.6: explicit production-module dependencies and qualified JSON reflection. */
module dev.jvmd.dist {
    requires dev.jvmd.core;
    requires dev.jvmd.index;
    requires dev.jvmd.index.rocks;
    requires dev.jvmd.analyzer;
    requires dev.jvmd.resolver;
    requires dev.jvmd.runtime;
    requires dev.jvmd.mcp;
    requires dev.jvmd.lsp;
    requires java.compiler;
    requires java.management;
    exports dev.jvmd.dist;
    opens dev.jvmd.dist to com.fasterxml.jackson.databind;
}
