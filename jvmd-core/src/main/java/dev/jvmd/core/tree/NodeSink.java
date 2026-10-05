package dev.jvmd.core.tree;

/** Where finished nodes go. A sink may drop a node it has already seen; flush makes everything written so far durable-ready. */
public interface NodeSink {
    void write(Node node);
    void flush();
    /** Optional build instrumentation; naming a sink never changes a node's bytes or identity. */
    default NodeSink named(String tree) { return this; }
}
