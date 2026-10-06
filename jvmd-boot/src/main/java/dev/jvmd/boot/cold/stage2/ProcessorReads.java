package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.tree.Codec;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.AnnotationValueVisitor;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.TypeVisitor;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Observes the public processor model graph during one header compile; proof() retains only detached answer bytes. */
final class ProcessorReads {
    record Read(String operation, List<String> types, List<String> missingTypes) { }
    final Elements elements;
    final Types types;
    private final Elements nativeElements;
    private final Types nativeTypes;
    private final Consumer<Read> observations;
    private final Consumer<String> unsupported;
    private final Map<Object, Object> wrappers = new IdentityHashMap<>();
    private final Map<Object, Integer> references = new IdentityHashMap<>();
    private final Map<String, Class<? extends java.lang.annotation.Annotation>> annotationInterfaces = new LinkedHashMap<>();
    private final List<byte[]> answers = new ArrayList<>();
    private final Map<Query, Observed> queries = new LinkedHashMap<>();
    private final Set<Query> ambiguous = new java.util.HashSet<>();
    private final ProcessorReads captured;
    private int phase = -1;

    private record Query(int phase, String operation, java.nio.ByteBuffer arguments) { }
    private record Observed(Object value, Throwable failure, Read read, byte[] identity) { }
    private record Dispatch(Method method, Object value) { }
    static final class ReplayUnavailable extends RuntimeException {
        ReplayUnavailable(String reason) { super(reason); }
    }

    ProcessorReads(Elements elements, Types types, Consumer<Read> observations, Consumer<String> unsupported) {
        this(elements, types, observations, unsupported, null);
    }

    private ProcessorReads(Elements elements, Types types, Consumer<Read> observations, Consumer<String> unsupported, ProcessorReads captured) {
        nativeElements = elements;
        nativeTypes = types;
        this.observations = observations;
        this.unsupported = unsupported;
        this.captured = captured;
        this.elements = proxy(Elements.class, elements);
        this.types = proxy(Types.class, types);
    }

    /** In-memory replay of observations from this invocation, before the host releases its model objects. No native query fallback. */
    ProcessorReads replay(Consumer<Read> observations, Consumer<String> unsupported) {
        if (captured != null) throw new IllegalStateException("Cannot capture a replay");
        return new ProcessorReads(nativeElements, nativeTypes, observations, unsupported, this);
    }

    void phase(int phase) { this.phase = phase; }

    private Query query(String operation, Object receiver, Object args) {
        var out = new Codec.Writer();
        var source = captured == null ? this : captured;
        source.encode(out, receiver);
        source.encode(out, args);
        return new Query(phase, operation, java.nio.ByteBuffer.wrap(out.toBytes()));
    }

    private Observed lookup(Query query) {
        String reason = captured.ambiguous.contains(query) ? "ambiguous" : !captured.queries.containsKey(query) ? "unobserved" : null;
        if (reason != null) throw new ReplayUnavailable(reason + " processor query in phase " + phase + ": " + query.operation());
        return captured.queries.get(query);
    }

    private void retain(Query query, Object value, Throwable failure, Read read) {
        var out = new Codec.Writer();
        if (value instanceof Dispatch dispatch) {
            out.str(dispatch.method().toGenericString());
            encode(out, dispatch.value());
        } else encode(out, value);
        if (failure instanceof MirroredTypeException mirror) {
            out.str(MirroredTypeException.class.getName()); encode(out, mirror.getTypeMirror());
        } else if (failure instanceof MirroredTypesException mirrors) {
            out.str(MirroredTypesException.class.getName()); encode(out, mirrors.getTypeMirrors());
        } else out.str(failure == null ? "" : failure.getClass().getName());
        var next = new Observed(freeze(value), failure, read, out.toBytes());
        var previous = queries.putIfAbsent(query, next);
        if (previous != null && !java.util.Arrays.equals(previous.identity(), next.identity())) ambiguous.add(query);
    }

    /** Containers are snapshots, model objects are opaque handles. A replay can only ask recorded methods of those handles. */
    private Object freeze(Object value) {
        if (value == null || modelApi(value) != null || value instanceof java.lang.annotation.Annotation || value instanceof Name) return value;
        if (value instanceof CharSequence text) return text.toString();
        if (value instanceof Dispatch dispatch) return new Dispatch(dispatch.method(), freeze(dispatch.value()));
        if (value instanceof List<?> list) {
            var items = new ArrayList<Object>();
            for (var item : list) items.add(freeze(item));
            return new java.util.AbstractList<>() {
                @Override public int size() { return items.size(); }
                @Override public Object get(int index) { return items.get(index); }
                @Override public String toString() { return list.toString(); } // invoked only during capture, through listText
            };
        }
        if (value instanceof Set<?> set) {
            var items = new LinkedHashSet<>();
            for (var item : set) items.add(freeze(item));
            return Collections.unmodifiableSet(items);
        }
        if (value instanceof Map<?, ?> map) {
            var items = new LinkedHashMap<>();
            map.forEach((key, item) -> items.put(freeze(key), freeze(item)));
            return Collections.unmodifiableMap(items);
        }
        if (value.getClass().isArray()) {
            var copy = Array.newInstance(value.getClass().getComponentType(), Array.getLength(value));
            for (int i = 0; i < Array.getLength(value); i++) Array.set(copy, i, freeze(Array.get(value, i)));
            return copy;
        }
        return value;
    }

    private String listText(List<?> list) {
        var key = query("List.toString", null, new Object[] {list});
        String value;
        if (captured == null) { value = list.toString(); retain(key, value, null, null); }
        else value = (String) lookup(key).value();
        return (String) answer("List.toString", new Object[] {list}, value);
    }

    private <T> T proxy(Class<T> api, Object delegate) {
        return api.cast(wrappers.computeIfAbsent(delegate, value -> {
            reference(value);
            // A nested annotation is both AnnotationMirror and AnnotationValue in javac. Record components can also
            // implement VariableElement. Preserve every public model interface instead of losing the second view.
            var interfaces = java.util.Arrays.stream(MODEL_APIS).filter(type -> type.isInstance(value)).toArray(Class<?>[]::new);
            if (interfaces.length == 0) interfaces = new Class<?>[] {api};
            return Proxy.newProxyInstance(api.getClassLoader(), interfaces, new ModelObject(api, value));
        }));
    }

    /** Only public query answers are encoded. References do not eagerly serialize declarations or annotations. */
    byte[] proof() {
        var out = new Codec.Writer().u8(1).u32(answers.size());
        for (var answer : answers) out.lenBytes(answer);
        return out.toBytes();
    }

    Object answer(String operation, Object[] args, Object result) {
        var out = new Codec.Writer().u8(1).str(operation);
        encode(out, args == null ? null : unwrap(args));
        encode(out, result);
        answers.add(out.toBytes());
        return wrap(result);
    }

    private int reference(Object value) { return references.computeIfAbsent(value, ignored -> references.size()); }

    private final class ModelObject implements InvocationHandler {
        final Class<?> api;
        final Object delegate;
        final boolean identityEquality;
        ModelObject(Class<?> api, Object delegate) {
            this.api = api; this.delegate = delegate;
            try { identityEquality = delegate.getClass().getMethod("equals", Object.class).getDeclaringClass() == Object.class; }
            catch (NoSuchMethodException impossible) { throw new AssertionError(impossible); }
        }
        @Override public Object invoke(Object receiver, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("accept") && args != null && args.length == 2)
                return accept(method, args);
            var nativeArgs = args == null ? null : (Object[]) unwrap(args);
            // Object's implementation is exactly handle identity, already represented by opaque references. Recording its
            // incidental hash-table collision calls makes an equal model depend on a task's allocation addresses. An
            // overridden equals (annotations, structural TypeMirror implementations, etc.) remains an observed query.
            if (identityEquality && method.getName().equals("equals") && method.getParameterCount() == 1)
                return delegate == nativeArgs[0];
            if ((method.getName().equals("getAnnotation") || method.getName().equals("getAnnotationsByType"))
                    && nativeArgs != null && nativeArgs.length == 1 && nativeArgs[0] instanceof Class<?> annotation
                    && java.lang.annotation.Annotation.class.isAssignableFrom(annotation))
                annotationInterfaces.put(annotation.getName(), annotation.asSubclass(java.lang.annotation.Annotation.class));
            var query = new Codec.Writer().u8(0).u32(reference(delegate)).str(method.toGenericString());
            boolean objectIdentity = method.getName().equals("hashCode") && method.getParameterCount() == 0
                    && !(delegate instanceof java.lang.annotation.Annotation);
            if (!objectIdentity) encode(query, nativeArgs);
            var key = query(method.toGenericString(), delegate, nativeArgs);
            Object result = null;
            Throwable cause = null;
            Read read = null;
            if (captured != null) {
                var observed = lookup(key);
                result = observed.value(); cause = observed.failure(); read = observed.read();
            } else {
                try { result = method.invoke(delegate, nativeArgs); }
                catch (InvocationTargetException failure) { cause = failure.getCause(); }
                if (cause == null && method.getDeclaringClass() != Object.class) read = observation(api, method, nativeArgs, result);
                retain(key, result, cause, read);
            }
            if (cause != null) {
                if (cause instanceof MirroredTypeException mirror) {
                    query.u8(1).str(MirroredTypeException.class.getName());
                    encode(query, mirror.getTypeMirror());
                    answers.add(query.toBytes());
                    throw new MirroredTypeException((TypeMirror) wrap(mirror.getTypeMirror()));
                }
                if (cause instanceof MirroredTypesException mirrors) {
                    query.u8(1).str(MirroredTypesException.class.getName());
                    encode(query, mirrors.getTypeMirrors());
                    answers.add(query.toBytes());
                    @SuppressWarnings("unchecked") var values = (List<? extends TypeMirror>) wrap(mirrors.getTypeMirrors());
                    throw new MirroredTypesException(values);
                }
                unsupported.accept("unmodelled exception from " + method + ": " + cause.getClass().getName());
                throw cause;
            }
            if (!objectIdentity) {
                query.u8(0);
                encode(query, result);
                answers.add(query.toBytes());
            }
            if (read != null) observations.accept(read);
            return wrap(result);
        }

        private Object accept(Method method, Object[] args) throws Throwable {
            var visitorApi = method.getParameterTypes()[0];
            if (visitorApi != ElementVisitor.class && visitorApi != TypeVisitor.class
                    && visitorApi != AnnotationValueVisitor.class && visitorApi != ModuleElement.DirectiveVisitor.class)
                throw new IllegalStateException("Unexpected model visitor: " + visitorApi);
            var key = query("dispatch:" + method.toGenericString(), delegate, null);
            if (captured != null) return dispatch((Dispatch) lookup(key).value(), args);
            var visitor = Proxy.newProxyInstance(visitorApi.getClassLoader(), new Class<?>[] {visitorApi}, (proxy, visit, values) -> {
                var selected = new Dispatch(visit, values[0]);
                retain(key, selected, null, null);
                return dispatch(selected, args);
            });
            try { return method.invoke(delegate, visitor, args[1]); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }

        private Object dispatch(Dispatch selected, Object[] args) throws Throwable {
            var dispatch = new Codec.Writer().u8(2).u32(reference(delegate)).str(selected.method().getName());
            encode(dispatch, selected.value());
            answers.add(dispatch.toBytes());
            try { return selected.method().invoke(args[0], wrap(selected.value()), args[1]); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }

    /** Native javac utilities and Filer/Messager expect native symbols; proxies never cross that boundary. */
    Object unwrap(Object value) {
        if (value == null) return null;
        var nativeValue = nativeObject(value);
        if (nativeValue != value) return nativeValue;
        if (value.getClass().isArray()) {
            var copy = Array.newInstance(value.getClass().getComponentType(), Array.getLength(value));
            for (int i = 0; i < Array.getLength(value); i++) Array.set(copy, i, unwrap(Array.get(value, i)));
            return copy;
        }
        if (value instanceof List<?> list) return list.stream().map(this::unwrap).toList();
        if (value instanceof Set<?> set) {
            var copy = new LinkedHashSet<>();
            for (var item : set) copy.add(unwrap(item));
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(unwrap(key), unwrap(item)));
            return copy;
        }
        return value;
    }

    @SuppressWarnings("unchecked") static <T> T nativeObject(T value) {
        if (value != null && Proxy.isProxyClass(value.getClass()) && Proxy.getInvocationHandler(value) instanceof ProcessorReads.ModelObject object)
            return (T) object.delegate;
        return value;
    }

    Object wrap(Object value) {
        if (value == null) return null;
        if (value instanceof Name) return value; // immutable character sequence; encode its content at the returning query
        if (value instanceof Class<?> type) return annotationInterface(type);
        var api = modelApi(value);
        if (api != null) return proxy(api, value);
        if (value instanceof java.lang.annotation.Annotation annotation) return proxy(annotationInterface(annotation.annotationType()), value);
        if (value instanceof List<?> list) return new java.util.AbstractList<>() {
            @Override public int size() { return list.size(); }
            @Override public Object get(int index) { return wrap(list.get(index)); }
            // javac's List prints comma-separated entries without brackets. That string is observable too.
            @Override public String toString() { return listText(list); }
        };
        if (value instanceof Set<?> set) {
            var copy = new LinkedHashSet<>();
            for (var item : set) copy.add(wrap(item));
            return Collections.unmodifiableSet(copy);
        }
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(wrap(key), wrap(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value.getClass().isArray()) {
            var copy = Array.newInstance(annotationInterface(value.getClass().getComponentType()), Array.getLength(value));
            for (int i = 0; i < Array.getLength(value); i++) Array.set(copy, i, wrap(Array.get(value, i)));
            return copy;
        }
        return value;
    }

    private Class<?> annotationInterface(Class<?> type) {
        var current = annotationInterfaces.get(type.getName());
        return current == null ? type : current;
    }

    private void encode(Codec.Writer out, Object value) {
        if (value == null) { out.u8(0); return; }
        if (value instanceof CharSequence text) {
            // Java annotation strings may contain unpaired UTF-16 surrogates; UTF-8 replacement would lose information.
            out.u8(1).u32(text.length());
            for (int i = 0; i < text.length(); i++) out.u16(text.charAt(i));
            return;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            out.u8(2).str(value.getClass().getName());
            if (value instanceof Float number) out.u32(Float.floatToRawIntBits(number));
            else if (value instanceof Double number) out.u64(Double.doubleToRawLongBits(number));
            else if (value instanceof Character character) out.u16(character);
            else out.str(value.toString());
            return;
        }
        if (value instanceof Enum<?> item) { out.u8(3).str(item.getDeclaringClass().getName()).str(item.name()); return; }
        if (value instanceof Class<?> type) { out.u8(4).str(type.getName()); return; }
        var api = modelApi(value);
        if (api != null || value instanceof java.lang.annotation.Annotation) { out.u8(5).u32(reference(value)); return; }
        if (value instanceof Map<?, ?> map) {
            out.u8(6).u32(map.size());
            map.forEach((key, item) -> { encode(out, key); encode(out, item); });
            return;
        }
        if (value instanceof java.util.Collection<?> values) {
            out.u8(7).u32(values.size());
            for (var item : values) encode(out, item);
            return;
        }
        if (value.getClass().isArray()) {
            out.u8(8).u32(Array.getLength(value));
            for (int i = 0; i < Array.getLength(value); i++) encode(out, Array.get(value, i));
            return;
        }
        unsupported.accept("unmodelled processor query value " + value.getClass().getName());
        out.u8(255);
    }

    private static Class<?> modelApi(Object value) {
        // Most specific public model contract, including ErrorType before DeclaredType.
        for (var api : MODEL_APIS) if (api.isInstance(value)) return api;
        return null;
    }
    private static final Class<?>[] MODEL_APIS = {
        Elements.class, Types.class,
        javax.lang.model.element.TypeElement.class, javax.lang.model.element.ExecutableElement.class,
        javax.lang.model.element.VariableElement.class, javax.lang.model.element.TypeParameterElement.class,
        javax.lang.model.element.PackageElement.class, javax.lang.model.element.ModuleElement.class,
        javax.lang.model.element.RecordComponentElement.class, Element.class,
        javax.lang.model.type.ErrorType.class, DeclaredType.class, ArrayType.class, TypeVariable.class, WildcardType.class,
        javax.lang.model.type.ExecutableType.class, javax.lang.model.type.IntersectionType.class, javax.lang.model.type.UnionType.class,
        javax.lang.model.type.PrimitiveType.class, javax.lang.model.type.NullType.class, javax.lang.model.type.NoType.class, TypeMirror.class,
        AnnotationMirror.class, AnnotationValue.class,
        ModuleElement.RequiresDirective.class, ModuleElement.ExportsDirective.class, ModuleElement.OpensDirective.class,
        ModuleElement.UsesDirective.class, ModuleElement.ProvidesDirective.class, ModuleElement.Directive.class
    };

    private Read observation(Class<?> api, Method method, Object[] args, Object result) {
        var targets = new TreeSet<String>();
        Set<TypeMirror> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean closure = method.getName().equals("getAllMembers") || method.getName().equals("isSubtype") || method.getName().equals("isAssignable");
        if (args != null) for (var arg : args) collect(arg, targets, seen, closure);
        collect(result, targets, seen, false);
        var missing = new ArrayList<String>();
        if (method.getName().equals("getTypeElement") && result == null && args != null && args.length != 0)
            missing.add(args[args.length - 1].toString());
        return new Read(api.getSimpleName() + "." + method.getName(), List.copyOf(targets), List.copyOf(missing));
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
