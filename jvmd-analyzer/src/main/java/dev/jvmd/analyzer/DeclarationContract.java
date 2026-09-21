package dev.jvmd.analyzer;

import java.util.*;
import javax.lang.model.element.*;

/**
 * Detached declaration meaning; source positions, documentation and parameter names are separate.
 */
public record DeclarationContract(
    String kind,
    List<String> modifiers,
    String type,
    List<String> annotations,
    String constant,
    List<String> bounds,
    String superclass,
    List<String> interfaces,
    List<String> permits,
    List<String> record_components,
    @com.fasterxml.jackson.annotation.JsonProperty("throws") List<String> thrownTypes,
    List<String> parameters,
    String receiver,
    boolean varargs,
    @com.fasterxml.jackson.annotation.JsonProperty("default") String defaultValue) {
  public DeclarationContract {
    modifiers = List.copyOf(modifiers);
    annotations = List.copyOf(annotations);
    bounds = List.copyOf(bounds);
    interfaces = List.copyOf(interfaces);
    permits = List.copyOf(permits);
    record_components = List.copyOf(record_components);
    thrownTypes = List.copyOf(thrownTypes);
    parameters = List.copyOf(parameters);
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
            : List.of(),
        type == null ? "" : type.getSuperclass().toString(),
        type == null ? List.of() : type.getInterfaces().stream().map(Object::toString).toList(),
        type == null
            ? List.of()
            : type.getPermittedSubclasses().stream().map(Object::toString).sorted().toList(),
        type == null
            ? List.of()
            : type.getRecordComponents().stream()
                .map(p -> p.getSimpleName() + ":" + p.asType() + ":" + annotations(p))
                .toList(),
        method == null
            ? List.of()
            : method.getThrownTypes().stream().map(Object::toString).sorted().toList(),
        method == null
            ? List.of()
            : method.getParameters().stream().map(p -> p.asType() + ":" + annotations(p)).toList(),
        method == null ? "" : method.getReceiverType().toString(),
        method != null && method.isVarArgs(),
        method == null || method.getDefaultValue() == null
            ? ""
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
                superclass,
                receiver,
                Boolean.toString(varargs),
                defaultValue));
    for (var sequence :
        List.of(
            modifiers,
            annotations,
            bounds,
            interfaces,
            permits,
            record_components,
            thrownTypes,
            parameters)) {
      fields.add(Integer.toString(sequence.size()));
      fields.addAll(sequence);
    }
    return fields;
  }
}
