package dev.jvmd.analyzer;

import java.nio.file.Path;
import java.util.List;

/**
 * Annotation processing the external processor process ran for a compile context.
 * Processors never run in-process; this binds what ran into the attributed memo static key.
 *
 * @param enabled whether processing runs for the context
 * @param path    the resolved processor path jars
 * @param names   explicit processor class names, empty for service discovery
 * @param mode    the effective mode of the external run ({@code only}, or {@code full} for Lombok)
 */
public record Processing(boolean enabled,List<Path> path,List<String> names,String mode) {
    public static final Processing NONE=new Processing(false,List.of(),List.of(),"none");
    public Processing { path=List.copyOf(path);names=List.copyOf(names); }
}
