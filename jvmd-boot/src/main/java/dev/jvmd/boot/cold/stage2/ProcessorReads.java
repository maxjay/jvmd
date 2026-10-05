package dev.jvmd.boot.cold.stage2;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Observations at the processing environment's Elements/Types query boundary, with no retained javac objects. */
final class ProcessorReads {
    record Read(String operation, List<String> types, List<String> missingTypes) { }
    final Elements elements;
    final Types types;
    private final Elements nativeElements;
    private final Types nativeTypes;
    private final Consumer<Read> observations;

    ProcessorReads(Elements elements, Types types, Consumer<Read> observations) {
        nativeElements = elements;
        nativeTypes = types;
        this.observations = observations;
        this.elements = proxy(Elements.class, elements);
        this.types = proxy(Types.class, types);
    }

    private <T> T proxy(Class<T> api, T delegate) {
        return api.cast(Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api}, (proxy, method, args) -> {
            Object result;
            try { result = method.invoke(delegate, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
            if (method.getDeclaringClass() != Object.class) observe(api, method, args, result);
            return result;
        }));
    }

    private void observe(Class<?> api, Method method, Object[] args, Object result) {
        var targets = new TreeSet<String>();
        Set<TypeMirror> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean closure = method.getName().equals("getAllMembers") || method.getName().equals("isSubtype") || method.getName().equals("isAssignable");
        if (args != null) for (var arg : args) collect(arg, targets, seen, closure);
        collect(result, targets, seen, false);
        var missing = new ArrayList<String>();
        if (method.getName().equals("getTypeElement") && result == null && args != null && args.length != 0)
            missing.add(args[args.length - 1].toString());
        observations.accept(new Read(api.getSimpleName() + "." + method.getName(), List.copyOf(targets), List.copyOf(missing)));
    }

    private void collect(Object value, Set<String> targets, Set<TypeMirror> seen, boolean closure) {
        if (value instanceof TypeElement type) {
            targets.add(nativeElements.getBinaryName(type).toString().replace('.', '/'));
            if (closure) collect(type.asType(), targets, seen, true);
        } else if (value instanceof Element element) {
            if (element.getEnclosingElement() instanceof TypeElement owner) collect(owner, targets, seen, closure);
        } else if (value instanceof TypeMirror mirror) {
            if (!seen.add(mirror)) return;
            switch (mirror) {
                case DeclaredType declared -> {
                    collect(declared.asElement(), targets, seen, false);
                    collect(declared.getEnclosingType(), targets, seen, false);
                    for (var argument : declared.getTypeArguments()) collect(argument, targets, seen, false);
                    if (closure) for (var parent : nativeTypes.directSupertypes(mirror)) collect(parent, targets, seen, true);
                }
                case ArrayType array -> collect(array.getComponentType(), targets, seen, closure);
                case TypeVariable variable -> collect(variable.getUpperBound(), targets, seen, closure);
                case WildcardType wildcard -> {
                    collect(wildcard.getExtendsBound(), targets, seen, closure);
                    collect(wildcard.getSuperBound(), targets, seen, closure);
                }
                default -> { }
            }
        } else if (value instanceof Iterable<?> values) {
            for (var item : values) collect(item, targets, seen, closure);
        }
    }

    /** Qualified Element lookups may resolve nested classes; every package/type split is an expected-zero candidate. */
    static List<String> absentCandidates(String name) {
        var parts = name.split("\\.");
        var out = new ArrayList<String>();
        for (int packageParts = 0; packageParts < parts.length; packageParts++) {
            var binary = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i != 0) binary.append(i <= packageParts ? '/' : '$');
                binary.append(parts[i]);
            }
            out.add(binary.toString());
        }
        return List.copyOf(out);
    }
}
