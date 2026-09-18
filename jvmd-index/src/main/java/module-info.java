/** Implements 12.6: explicit production-module dependencies and qualified JSON reflection. */
module dev.jvmd.index {
    requires transitive dev.jvmd.core;
    requires java.compiler;
    requires jdk.compiler;
    requires java.sql;
    requires org.xerial.sqlitejdbc;
    exports dev.jvmd.index;
    uses dev.jvmd.index.ArtifactGenerationSinkProvider;
    opens dev.jvmd.index to com.fasterxml.jackson.databind;
}
