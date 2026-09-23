package dev.jvmd.resolver;

import java.util.*;

/** Accepted Maven project-model identity exported once from the isolated resolver bundle. */
public record ProjectModelState(String identity,List<Input> inputs) {
    public ProjectModelState {
        Objects.requireNonNull(identity);inputs=List.copyOf(inputs);
    }
    public record Input(String path,long size,long modified,String hash,boolean strong) {
        public Input { Objects.requireNonNull(path);Objects.requireNonNull(hash); }
    }
    /** Cold/changed resolver result: graph plus the exact input manifest that produced it. */
    public record Resolved(Resolution resolution,ProjectModelState model) {
        public Resolved { Objects.requireNonNull(resolution);Objects.requireNonNull(model); }
    }
}
