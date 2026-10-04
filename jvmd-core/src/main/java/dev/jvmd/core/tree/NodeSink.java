package dev.jvmd.core.tree;

/** Where finished nodes go. A sink may drop a node it has already seen; flush makes everything written so far durable-ready. */
public interface NodeSink {
    void write(Node node);
    void flush();
}
