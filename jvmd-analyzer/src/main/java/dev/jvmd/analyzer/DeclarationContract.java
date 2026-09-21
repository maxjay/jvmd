package dev.jvmd.analyzer;

import com.fasterxml.jackson.annotation.*;
import java.util.*;
import javax.lang.model.element.*;

/**
 * Detached declaration meaning; source positions, documentation and parameter names are separate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeclarationContract(
    @JsonIgnore String kind,
    @JsonIgnore List<String> modifiers,
    String type,
    List<String> annotations,
    String constant,
    List<String> bounds,
    String superclass,
    List<String> interfaces,
    List<String> permits,
    @JsonProperty("record_components") List<String> recordComponents,
    @JsonProperty("throws") List<String> thrownTypes,
    List<String> parameters,
    @JsonIgnore String receiver,
    Boolean varargs,
    @JsonProperty("default") String defaultValue) {
  public DeclarationContract {
    modifiers = List.copyOf(modifiers);
    annotations = List.copyOf(annotations);
    bounds = immutable(bounds);
    interfaces = immutable(interfaces);
    permits = immutable(permits);
    recordComponents = immutable(recordComponents);
    thrownTypes = immutable(thrownTypes);
    parameters = immutable(parameters);
  }

  private static List<String> immutable(List<String> values) {
    return values == null ? null : List.copyOf(values);
  }

  static boolean exported(Element element) {
    if (!(element instanceof TypeElement
        || element instanceof ExecutableElement
        || element.getKind().isField())) return false;
    for (Element owner = element; owner != null; owner = owner.getEnclosingElement()) {
      if (owner.getModifiers().contains(Modifier.PRIVATE)) return false;
      if (owner != element && owner instanceof ExecutableElement) return false;
      if (owner instanceof TypeElement type
          && (type.getNestingKind() == NestingKind.LOCAL
              || type.getNestingKind() == NestingKind.ANONYMOUS)) return false;
    }
    return true;
  }

  static DeclarationContract capture(Element element) {
    var type = element instanceof TypeElement value ? value : null;
    var method = element instanceof ExecutableElement value ? value : null;
    return new DeclarationContract(
        element.getKind().name(),
        element.getModifiers().stream().map(Object::toString).sorted().toList(),
        element.asType().toString(),
        annotations(element),
        element instanceof VariableElement value && value.getConstantValue() != null
            ? value.getConstantValue().toString()
            : null,
        element instanceof Parameterizable value
            ? value.getTypeParameters().stream()
                .map(p -> p.getSimpleName() + ":" + p.getBounds())
                .toList()
            : null,
        type == null ? null : type.getSuperclass().toString(),
        type == null ? null : type.getInterfaces().stream().map(Object::toString).toList(),
        type == null
            ? null
            : type.getPermittedSubclasses().stream().map(Object::toString).sorted().toList(),
        type == null
            ? null
            : type.getRecordComponents().stream()
                .map(p -> p.getSimpleName() + ":" + p.asType() + ":" + annotations(p))
                .toList(),
        method == null
            ? null
            : method.getThrownTypes().stream().map(Object::toString).sorted().toList(),
        method == null
            ? null
            : method.getParameters().stream().map(p -> p.asType() + ":" + annotations(p)).toList(),
        method == null ? null : method.getReceiverType().toString(),
        method == null ? null : method.isVarArgs(),
        method == null || method.getDefaultValue() == null
            ? null
            : method.getDefaultValue().toString());
  }

  private static List<String> annotations(Element element) {
    return element.getAnnotationMirrors().stream().map(Object::toString).sorted().toList();
  }

  /** List counts preserve field boundaries; ordering is significant inside each sequence. */
  List<String> fields() {
    var fields =
        new ArrayList<>(
            List.of(
                kind,
                type,
                constant == null ? "absent" : "present",
                constant == null ? "" : constant,
                Objects.toString(superclass, ""),
                Objects.toString(receiver, ""),
                Objects.toString(varargs, ""),
                Objects.toString(defaultValue, "")));
    for (var sequence :
        Arrays.asList(
            modifiers,
            annotations,
            bounds,
            interfaces,
            permits,
            recordComponents,
            thrownTypes,
            parameters)) {
      fields.add(sequence == null ? "-1" : Integer.toString(sequence.size()));
      if (sequence != null) fields.addAll(sequence);
    }
    return fields;
  }
}
