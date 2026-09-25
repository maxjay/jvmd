package dev.jvmd.index;

/** Authoritativeness of one retained semantic declaration surface. */
public enum SemanticCompleteness {
    COMPLETE,
    PARTIAL,
    UNKNOWN;

    public boolean authoritative(){return this==COMPLETE;}
}
