/** Implements 12.6: explicit production-module dependencies and qualified JSON reflection. */
module dev.jvmd.core {
    requires transitive com.fasterxml.jackson.databind;
    requires java.management;
    exports dev.jvmd.core;
    opens dev.jvmd.core to com.fasterxml.jackson.databind;
}
