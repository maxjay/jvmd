package dev.jvmd.index.layer.local;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Exact persisted definer multimap plus a multiple-definer projection. Reopened states read only requested tree paths,
 * and derived states apply touched types. A balanced map assembles the first uncached state before its trees are persisted.
 */
final class DefinerCounts extends AbstractMap<String, List<DefinerIndex.Def>> {
    static final DefinerCounts EMPTY = new DefinerCounts(null);
    private final Node root;
    private Stored stored;
    private record Stored(dev.jvmd.core.tree.ContentTree tree, dev.jvmd.core.tree.Root all,
                          dev.jvmd.core.tree.Root multiple, java.util.function.Function<dev.jvmd.core.hash.Identity, byte[]> reader,
                          dev.jvmd.core.tree.NodeSink sink) { }

    static DefinerCounts stored(dev.jvmd.core.tree.ContentTree tree, dev.jvmd.core.tree.Root all,
                                dev.jvmd.core.tree.Root multiple, java.util.function.Function<dev.jvmd.core.hash.Identity, byte[]> reader,
                                dev.jvmd.core.tree.NodeSink sink) {
        var counts = new DefinerCounts(null);
        counts.stored = new Stored(tree, all, multiple, reader, sink);
        return counts;
    }

    dev.jvmd.core.tree.Root allRoot() { return stored.all; }
    dev.jvmd.core.tree.Root multipleRoot() { return stored.multiple; }

    DefinerCounts persist(dev.jvmd.core.tree.ContentTree tree,
                          java.util.function.Function<dev.jvmd.core.hash.Identity, byte[]> reader, dev.jvmd.core.tree.NodeSink sink) {
        if (stored != null) return this;
        var all = new ArrayList<dev.jvmd.core.tree.Entry>();
        var multiple = new ArrayList<dev.jvmd.core.tree.Entry>();
        for (var entry : entrySet()) {
            var encoded = entry(tree, entry.getKey(), entry.getValue());
            all.add(encoded); if (entry.getValue().size() > 1) multiple.add(encoded);
        }
        all.sort((a,b) -> java.util.Arrays.compareUnsigned(a.key(), b.key()));
        multiple.sort((a,b) -> java.util.Arrays.compareUnsigned(a.key(), b.key()));
        var allRoot = tree.build(all, sink); var multiRoot = tree.build(multiple, sink); sink.flush();
        return stored(tree, allRoot, multiRoot, reader, sink);
    }

    private static dev.jvmd.core.tree.Entry entry(dev.jvmd.core.tree.ContentTree tree, String type, List<DefinerIndex.Def> defs) {
        var key = dev.jvmd.index.layer.machine.Keys.ownerKey(type);
        var out = new dev.jvmd.core.tree.Codec.Writer().u32(defs.size());
        defs.stream().sorted(java.util.Comparator.comparing(DefinerIndex.Def::k)).forEach(d -> out.id(d.k()).id(d.oSum()));
        var value = out.toBytes();
        return new dev.jvmd.core.tree.Entry(key, value, tree.digest().hash(key, value));
    }

    private List<DefinerIndex.Def> decode(byte[] bytes) {
        var in = new dev.jvmd.core.tree.Codec.Reader(bytes); var result = new ArrayList<DefinerIndex.Def>();
        for (int i = 0, count = in.count(); i < count; i++)
            result.add(new DefinerIndex.Def(in.id(stored.tree.digest().width()), in.id(stored.tree.digest().width())));
        if (in.remaining() != 0) throw new IllegalArgumentException("Trailing definer state bytes");
        return List.copyOf(result);
    }

    private DefinerCounts(Node root) { this.root = root; }

    /** Empty lists delete a key. Only the supplied keys are looked up in a nonempty base. */
    DefinerCounts with(Map<String, ? extends List<DefinerIndex.Def>> changed) {
        if (changed.isEmpty()) return this;
        if (stored != null) {
            var removed = new ArrayList<byte[]>();
            var all = new ArrayList<dev.jvmd.core.tree.Entry>(); var multiple = new ArrayList<dev.jvmd.core.tree.Entry>();
            changed.forEach((type, defs) -> {
                removed.add(dev.jvmd.index.layer.machine.Keys.ownerKey(type));
                if (!defs.isEmpty()) {
                    var encoded = entry(stored.tree, type, defs); all.add(encoded);
                    if (defs.size() > 1) multiple.add(encoded);
                }
            });
            var next = stored.tree.apply(stored.all, removed, all, stored.reader, stored.sink);
            var multi = stored.tree.apply(stored.multiple, removed, multiple, stored.reader, stored.sink);
            stored.sink.flush();
            return stored(stored.tree, next, multi, stored.reader, stored.sink);
        }
        if (root == null) {
            var keys = new ArrayList<>(changed.keySet());
            keys.removeIf(k -> changed.get(k).isEmpty());
            keys.sort(String::compareTo);
            return new DefinerCounts(build(keys, changed, 0, keys.size()));
        }
        var next = root;
        for (var entry : changed.entrySet()) next = put(next, entry.getKey(), entry.getValue());
        return next == root ? this : new DefinerCounts(next);
    }

    private static Node build(List<String> keys, Map<String, ? extends List<DefinerIndex.Def>> values, int from, int to) {
        if (from == to) return null;
        int middle = (from + to) >>> 1;
        String key = keys.get(middle);
        return new Node(key, values.get(key), build(keys, values, from, middle), build(keys, values, middle + 1, to));
    }

    @Override public List<DefinerIndex.Def> get(Object key) {
        if (!(key instanceof String name)) return null;
        if (stored != null) {
            var entry = stored.tree.get(stored.all.hash(), stored.reader, dev.jvmd.index.layer.machine.Keys.ownerKey(name));
            return entry == null ? null : decode(entry.value());
        }
        for (var node = root; node != null; ) {
            int comparison = name.compareTo(node.getKey());
            if (comparison == 0) return node.getValue();
            node = comparison < 0 ? node.left : node.right;
        }
        return null;
    }

    @Override public int size() { return stored == null ? size(root) : (int) stored.all.count(); }

    // AbstractMap.containsKey iterates entrySet; Map.getOrDefault invokes it on a miss. Both must remain point lookups.
    @Override public boolean containsKey(Object key) { return get(key) != null; }

    @Override public List<DefinerIndex.Def> getOrDefault(Object key, List<DefinerIndex.Def> fallback) {
        var value = get(key);
        return value == null ? fallback : value;
    }

    @Override public Set<Map.Entry<String, List<DefinerIndex.Def>>> entrySet() {
        if (stored != null) {
            var entries = new java.util.LinkedHashSet<Map.Entry<String, List<DefinerIndex.Def>>>();
            stored.tree.forEach(stored.all.hash(), stored.reader, e ->
                    entries.add(Map.entry(dev.jvmd.index.layer.machine.Keys.ownerOf(e.key()), decode(e.value()))));
            return java.util.Collections.unmodifiableSet(entries);
        }
        return new AbstractSet<>() {
            @Override public int size() { return DefinerCounts.this.size(); }
            @Override public Iterator<Map.Entry<String, List<DefinerIndex.Def>>> iterator() {
                return new Iterator<>() {
                    private final ArrayDeque<Node> pending = new ArrayDeque<>();
                    { left(root); }
                    private void left(Node node) { for (; node != null; node = node.left) pending.push(node); }
                    @Override public boolean hasNext() { return !pending.isEmpty(); }
                    @Override public Map.Entry<String, List<DefinerIndex.Def>> next() {
                        if (pending.isEmpty()) throw new NoSuchElementException();
                        var node = pending.pop();
                        left(node.right);
                        return node;
                    }
                };
            }
        };
    }

    void forEachMultiple(Consumer<String> action) {
        if (stored == null) multiple(root, action);
        else stored.tree.forEach(stored.multiple.hash(), stored.reader, e -> action.accept(dev.jvmd.index.layer.machine.Keys.ownerOf(e.key())));
    }

    private static void multiple(Node node, Consumer<String> action) {
        if (node == null || node.multiple == 0) return;
        multiple(node.left, action);
        if (node.getValue().size() > 1) action.accept(node.getKey());
        multiple(node.right, action);
    }

    private static Node put(Node node, String key, List<DefinerIndex.Def> value) {
        if (node == null) return value.isEmpty() ? null : new Node(key, value, null, null);
        int comparison = key.compareTo(node.getKey());
        if (comparison < 0) {
            var left = put(node.left, key, value);
            return left == node.left ? node : balance(node.getKey(), node.getValue(), left, node.right);
        }
        if (comparison > 0) {
            var right = put(node.right, key, value);
            return right == node.right ? node : balance(node.getKey(), node.getValue(), node.left, right);
        }
        if (value.equals(node.getValue())) return node;
        if (!value.isEmpty()) return new Node(key, value, node.left, node.right);
        if (node.left == null) return node.right;
        if (node.right == null) return node.left;
        var successor = node.right;
        while (successor.left != null) successor = successor.left;
        return balance(successor.getKey(), successor.getValue(), node.left, put(node.right, successor.getKey(), List.of()));
    }

    private static Node balance(String key, List<DefinerIndex.Def> value, Node left, Node right) {
        if (height(left) > height(right) + 1) {
            if (height(left.left) < height(left.right)) {
                var pivot = left.right;
                left = new Node(pivot.getKey(), pivot.getValue(), new Node(left.getKey(), left.getValue(), left.left, pivot.left), pivot.right);
            }
            return new Node(left.getKey(), left.getValue(), left.left, new Node(key, value, left.right, right));
        }
        if (height(right) > height(left) + 1) {
            if (height(right.right) < height(right.left)) {
                var pivot = right.left;
                right = new Node(pivot.getKey(), pivot.getValue(), pivot.left, new Node(right.getKey(), right.getValue(), pivot.right, right.right));
            }
            return new Node(right.getKey(), right.getValue(), new Node(key, value, left, right.left), right.right);
        }
        return new Node(key, value, left, right);
    }

    private static int height(Node node) { return node == null ? 0 : node.height; }
    private static int size(Node node) { return node == null ? 0 : node.size; }
    private static int multiples(Node node) { return node == null ? 0 : node.multiple; }

    private static final class Node extends SimpleImmutableEntry<String, List<DefinerIndex.Def>> {
        final Node left, right;
        final int height, size, multiple;

        Node(String key, List<DefinerIndex.Def> value, Node left, Node right) {
            super(key, List.copyOf(value));
            this.left = left;
            this.right = right;
            height = 1 + Math.max(height(left), height(right));
            size = 1 + size(left) + size(right);
            multiple = (value.size() > 1 ? 1 : 0) + multiples(left) + multiples(right);
        }
    }
}
