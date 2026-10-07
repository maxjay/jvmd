package dev.jvmd.boot.cold.stage3;

import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.util.Set;

/**
 * Temporary admission for an already byte-bound processor class file.
 * Frozen annotation bytes alone do not bind the declarations deproxy queries.
 * Admit only a closed platform annotation vocabulary; all custom annotation
 * types and class literals await exact metadata-query proofs. This predicate is
 * intentionally conservative and is not an ACI projection or a dependency list.
 */
final class FixedMetadata {
    private FixedMetadata() { }
    private static final Set<String> ANNOTATIONS = Set.of(
            "Ljava/lang/Deprecated;", "Ljava/lang/Override;", "Ljava/lang/SafeVarargs;", "Ljava/lang/SuppressWarnings;",
            "Ljava/lang/annotation/Documented;", "Ljava/lang/annotation/Inherited;",
            "Ljava/lang/annotation/Repeatable;", "Ljava/lang/annotation/Retention;", "Ljava/lang/annotation/Target;",
            "Ljavax/annotation/processing/Generated;", "Ljavax/annotation/processing/SupportedAnnotationTypes;",
            "Ljavax/annotation/processing/SupportedOptions;", "Ljavax/annotation/processing/SupportedSourceVersion;");
    private static final Set<String> ENUMS = Set.of(
            "Ljava/lang/annotation/ElementType;", "Ljava/lang/annotation/RetentionPolicy;",
            "Ljavax/lang/model/SourceVersion;");

    static boolean platformOnly(byte[] bytes) {
        var model = ClassFile.of().parse(bytes);
        if (!attributes(model)) return false;
        for (var field : model.fields()) if (!attributes(field)) return false;
        for (var method : model.methods()) if (!attributes(method)) return false;
        return true;
    }

    private static boolean attributes(AttributedElement element) {
        for (var attribute : element.attributes()) {
            boolean supported = switch (attribute) {
                case RuntimeVisibleAnnotationsAttribute a -> a.annotations().stream().allMatch(FixedMetadata::annotation);
                case RuntimeInvisibleAnnotationsAttribute a -> a.annotations().stream().allMatch(FixedMetadata::annotation);
                case RuntimeVisibleTypeAnnotationsAttribute a -> a.annotations().stream().allMatch(t -> annotation(t.annotation()));
                case RuntimeInvisibleTypeAnnotationsAttribute a -> a.annotations().stream().allMatch(t -> annotation(t.annotation()));
                case RuntimeVisibleParameterAnnotationsAttribute a -> a.parameterAnnotations().stream()
                        .allMatch(p -> p.stream().allMatch(FixedMetadata::annotation));
                case RuntimeInvisibleParameterAnnotationsAttribute a -> a.parameterAnnotations().stream()
                        .allMatch(p -> p.stream().allMatch(FixedMetadata::annotation));
                case AnnotationDefaultAttribute a -> value(a.defaultValue());
                case RecordAttribute a -> a.components().stream().allMatch(FixedMetadata::attributes);
                // javac ClassReader does not deproxy annotations inside executable Code attributes.
                default -> true;
            };
            if (!supported) return false;
        }
        return true;
    }

    private static boolean annotation(Annotation annotation) {
        String type = annotation.className().stringValue();
        if (!ANNOTATIONS.contains(type)) return false;
        Set<String> names = switch (type) {
            case "Ljava/lang/Deprecated;" -> Set.of("since","forRemoval");
            case "Ljava/lang/Override;", "Ljava/lang/SafeVarargs;", "Ljava/lang/annotation/Documented;",
                    "Ljava/lang/annotation/Inherited;" -> Set.of();
            case "Ljavax/annotation/processing/Generated;" -> Set.of("value","date","comments");
            default -> Set.of("value");
        };
        // An unknown platform member can itself produce a completion-time warning. Reject it so
        // each task gets fresh native diagnostics instead of depending on a warm symbol cache.
        return annotation.elements().stream().allMatch(e -> names.contains(e.name().stringValue()) && value(e.value()));
    }

    private static boolean value(AnnotationValue value) {
        return switch (value) {
            case AnnotationValue.OfEnum e -> platformEnum(e);
            case AnnotationValue.OfClass ignored -> false;
            case AnnotationValue.OfAnnotation a -> annotation(a.annotation());
            case AnnotationValue.OfArray a -> a.values().stream().allMatch(FixedMetadata::value);
            default -> true; // primitive/string constant
        };
    }

    private static boolean platformEnum(AnnotationValue.OfEnum value) {
        String type = value.className().stringValue(), name = value.constantName().stringValue();
        if (!ENUMS.contains(type)) return false;
        try {
            switch (type) {
                case "Ljava/lang/annotation/ElementType;" -> java.lang.annotation.ElementType.valueOf(name);
                case "Ljava/lang/annotation/RetentionPolicy;" -> java.lang.annotation.RetentionPolicy.valueOf(name);
                case "Ljavax/lang/model/SourceVersion;" -> javax.lang.model.SourceVersion.valueOf(name);
                default -> throw new AssertionError(type);
            }
            return true;
        } catch (IllegalArgumentException missing) { return false; }
    }
}
