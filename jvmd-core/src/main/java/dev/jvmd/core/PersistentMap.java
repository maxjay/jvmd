package dev.jvmd.core;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable ordered treap. Summarized maps use content-derived priorities for canonical shape. */
public final class PersistentMap<K extends Comparable<? super K>, V> extends AbstractMap<K, V> {
  @FunctionalInterface
  public interface Summary<K, V> {
    String compose(K key, V value, String left, String right);
  }

  private record Node<K, V>(
      K key, V value, long priority, Node<K, V> left, Node<K, V> right, int size, String summary) {}

  private final Node<K, V> root;
  private final Summary<K, V> summary;

  private PersistentMap(Node<K, V> root, Summary<K, V> summary) {
    this.root = root;
    this.summary = summary;
  }

  public static <K extends Comparable<? super K>, V> PersistentMap<K, V> empty() {
    return new PersistentMap<>(null, null);
  }

  public static <K extends Comparable<? super K>, V> PersistentMap<K, V> empty(
      Summary<K, V> summary) {
    return new PersistentMap<>(null, Objects.requireNonNull(summary));
  }

  private static int size(Node<?, ?> node) {
    return node == null ? 0 : node.size;
  }

  private static String summary(Node<?, ?> node) {
    return node == null ? "" : node.summary;
  }

  public String summary() {
    return summary(root);
  }

  private Node<K, V> node(K key, V value, long priority, Node<K, V> left, Node<K, V> right) {
    return new Node<>(
        key,
        value,
        priority,
        left,
        right,
        1 + size(left) + size(right),
        summary == null ? "" : summary.compose(key, value, summary(left), summary(right)));
  }

  private boolean above(Node<K, V> first, Node<K, V> second) {
    int order = Long.compareUnsigned(first.priority, second.priority);
    return order > 0 || order == 0 && first.key.compareTo(second.key) < 0;
  }

  private Node<K, V> put(Node<K, V> current, K key, V value) {
    if (current == null) {
      long priority =
          summary == null
              ? java.util.concurrent.ThreadLocalRandom.current().nextLong()
              : Long.parseUnsignedLong(
                  Hashing.sha256(key.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16),
                  16);
      return node(key, value, priority, null, null);
    }
    int order = key.compareTo(current.key);
    if (order == 0)
      return value == current.value
          ? current
          : node(key, value, current.priority, current.left, current.right);
    if (order < 0) {
      var child = put(current.left, key, value);
      if (child == current.left) return current;
      return above(child, current)
          ? node(
              child.key,
              child.value,
              child.priority,
              child.left,
              node(current.key, current.value, current.priority, child.right, current.right))
          : node(current.key, current.value, current.priority, child, current.right);
    }
    var child = put(current.right, key, value);
    if (child == current.right) return current;
    return above(child, current)
        ? node(
            child.key,
            child.value,
            child.priority,
            node(current.key, current.value, current.priority, current.left, child.left),
            child.right)
        : node(current.key, current.value, current.priority, current.left, child);
  }

  private Node<K, V> merge(Node<K, V> left, Node<K, V> right) {
    if (left == null) return right;
    if (right == null) return left;
    return above(left, right)
        ? node(left.key, left.value, left.priority, left.left, merge(left.right, right))
        : node(right.key, right.value, right.priority, merge(left, right.left), right.right);
  }

  private Node<K, V> remove(Node<K, V> current, K key) {
    if (current == null) return null;
    int order = key.compareTo(current.key);
    if (order == 0) return merge(current.left, current.right);
    if (order < 0) {
      var child = remove(current.left, key);
      return child == current.left
          ? current
          : node(current.key, current.value, current.priority, child, current.right);
    }
    var child = remove(current.right, key);
    return child == current.right
        ? current
        : node(current.key, current.value, current.priority, current.left, child);
  }

  public PersistentMap<K, V> with(K key, V value) {
    var next = put(root, Objects.requireNonNull(key), Objects.requireNonNull(value));
    return next == root ? this : new PersistentMap<>(next, summary);
  }

  public PersistentMap<K, V> without(K key) {
    var next = remove(root, key);
    return next == root ? this : new PersistentMap<>(next, summary);
  }

  public Entry<K, V> lastEntry() {
    if (root == null) return null;
    var current = root;
    while (current.right != null) current = current.right;
    return Map.entry(current.key, current.value);
  }

  @Override
  public int size() {
    return size(root);
  }

  @Override
  @SuppressWarnings("unchecked")
  public V get(Object key) {
    K typed = (K) key;
    for (var current = root; current != null; ) {
      int order = typed.compareTo(current.key);
      if (order == 0) return current.value;
      current = order < 0 ? current.left : current.right;
    }
    return null;
  }

  @Override
  public boolean containsKey(Object key) {
    return get(key) != null;
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    return new AbstractSet<>() {
      @Override
      public int size() {
        return PersistentMap.this.size();
      }

      @Override
      public Iterator<Entry<K, V>> iterator() {
        return new Iterator<>() {
          final ArrayDeque<Node<K, V>> stack = new ArrayDeque<>();

          {
            push(root);
          }

          private void push(Node<K, V> current) {
            while (current != null) {
              stack.push(current);
              current = current.left;
            }
          }

          public boolean hasNext() {
            return !stack.isEmpty();
          }

          public Entry<K, V> next() {
            if (stack.isEmpty()) throw new NoSuchElementException();
            var current = stack.pop();
            push(current.right);
            return Map.entry(current.key, current.value);
          }
        };
      }
    };
  }
}
