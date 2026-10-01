package dev.jvmd.analyzer;

import java.util.*;

/**
 * Annotation processors whose effect on a unit's diagnostics is fully bound by the attributed
 * memo's static key plus the per-processor inputs listed here (strict task W6). Every processor a
 * compile context runs must be listed, otherwise the context is refused with
 * {@code processor-not-allowlisted:<class>}.
 *
 * The static key always binds, for every context with processing: the processor path content by
 * logical slot, the processor class names, every {@code -A} option and the {@code -proc} mode.
 * Generated sources are units in a logical {@code generated} root and enter certificates like any
 * other completed unit. Adding an entry requires a test that runs the processor
 * ({@code ProcessorAllowlistTest}).
 */
public final class ProcessorAllowlist {
    /** What, beyond the static key, a processor reads. */
    public enum ExtraInput {
        /** {@code lombok.config} files from the unit's directory up to the filesystem root. */
        LOMBOK_CONFIG,
        /** JPA XML mappings: {@code META-INF/persistence.xml} and {@code META-INF/orm.xml} in the module's resources. */
        JPA_XML,
        /** Nothing beyond its {@code -A} options and processor path, which the static key binds. */
        NONE
    }
    public record Entry(String processor,String product,ExtraInput input) { }

    private static final Map<String,Entry> ENTRIES=new LinkedHashMap<>();
    private static void add(String processor,String product,ExtraInput input){ENTRIES.put(processor,new Entry(processor,product,input));}
    static{
        // Lombok: rewrites the unit's own AST; reads lombok.config files up the directory tree and its
        // -A options. Its output reaches the in-process compiler as class files of the same unit.
        add("lombok.launch.AnnotationProcessorHider$AnnotationProcessor","Lombok",ExtraInput.LOMBOK_CONFIG);
        add("lombok.launch.AnnotationProcessorHider$ClaimingProcessor","Lombok",ExtraInput.LOMBOK_CONFIG);
        // MapStruct: generates *Impl sources from mapper interfaces; reads only its -A options
        // (mapstruct.defaultComponentModel, unmappedTargetPolicy, ...) and the element model.
        add("org.mapstruct.ap.MappingProcessor","MapStruct",ExtraInput.NONE);
        // Immutables: generates Immutable* sources from abstract value types; reads -A options and
        // the element model (style annotations are part of the element model).
        add("org.immutables.processor.ProxyProcessor","Immutables",ExtraInput.NONE);
        // AutoValue and its companions: generate AutoValue_* sources; read -A options and the
        // element model only.
        add("com.google.auto.value.processor.AutoValueProcessor","AutoValue",ExtraInput.NONE);
        add("com.google.auto.value.processor.AutoAnnotationProcessor","AutoValue",ExtraInput.NONE);
        add("com.google.auto.value.processor.AutoOneOfProcessor","AutoValue",ExtraInput.NONE);
        add("com.google.auto.value.processor.AutoBuilderProcessor","AutoValue",ExtraInput.NONE);
        add("com.google.auto.value.extension.memoized.processor.MemoizedValidator","AutoValue",ExtraInput.NONE);
        add("com.google.auto.value.extension.toprettystring.processor.ToPrettyStringValidator","AutoValue",ExtraInput.NONE);
        add("com.google.auto.value.processor.AutoValueBuilderProcessor","AutoValue",ExtraInput.NONE);
        // Hibernate JPA static metamodel (hibernate-jpamodelgen 6.x, hibernate-processor 7.x): generates
        // Entity_ sources from @Entity types; reads -A options and the JPA XML mappings.
        add("org.hibernate.processor.HibernateProcessor","Hibernate JPA metamodel",ExtraInput.JPA_XML);
    }
    private ProcessorAllowlist(){}

    public static Optional<Entry> entry(String processor){return Optional.ofNullable(ENTRIES.get(processor));}
    public static Collection<Entry> entries(){return Collections.unmodifiableCollection(ENTRIES.values());}
}
