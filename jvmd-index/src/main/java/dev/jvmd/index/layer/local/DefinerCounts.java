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
 * Boot-local persistent counts. A cold fold builds a balanced tree once; each changed type then copies O(log types) nodes,
 * retaining every other branch. Nodes count their multiple-definer descendants so conflict enumeration skips singleton branches.
 * This is working state, not a stored identity: DD/DS/DC remain ordinary ContentTrees with their existing codecs.
 */
final class DefinerCounts extends AbstractMap<String, List<DefinerIndex.Def>> {
    static final DefinerCounts EMPTY = new DefinerCounts(null);
    private final Node root;

    private DefinerCounts(Node root) { this.root = root; }

    /** Empty lists delete a key. Only the supplied keys are looked up in a nonempty base. */
    DefinerCounts with(Map<String, ? extends List<DefinerIndex.Def>> changed) {
        if (changed.isEmpty()) return this;
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
        for (var node = root; node != null; ) {
            int comparison = name.compareTo(node.getKey());
            if (comparison == 0) return node.getValue();
            node = comparison < 0 ? node.left : node.right;
        }
        return null;
    }

    @Override public int size() { return size(root); }

    // AbstractMap.containsKey iterates entrySet; Map.getOrDefault invokes it on a miss. Both must remain point lookups.
    @Override public boolean containsKey(Object key) { return get(key) != null; }

    @Override public List<DefinerIndex.Def> getOrDefault(Object key, List<DefinerIndex.Def> fallback) {
        var value = get(key);
        return value == null ? fallback : value;
    }

    @Override public Set<Map.Entry<String, List<DefinerIndex.Def>>> entrySet() {
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

    void forEachMultiple(Consumer<String> action) { multiple(root, action); }

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
