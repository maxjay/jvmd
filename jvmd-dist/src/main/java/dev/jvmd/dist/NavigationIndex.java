package dev.jvmd.dist;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.PersistentMap;
import dev.jvmd.core.SemanticNode;
import java.nio.file.Path;
import java.util.*;

/** Immutable publication root. File replacement shares every untouched posting and map subtree. */
final class NavigationIndex {
  private record Owner(Path file, boolean declaration) implements Comparable<Owner> {
    public int compareTo(Owner other) {
      int order = Boolean.compare(declaration, other.declaration);
      return order == 0 ? file.compareTo(other.file) : order;
    }
  }

  private PersistentMap<String, PersistentMap<Owner, Map<String, Object>>> owners =
      PersistentMap.empty();
  private PersistentMap<String, Map<String, Object>> symbols = PersistentMap.empty(),
      declarations = PersistentMap.empty();
  private PersistentMap<String, PersistentMap<String, Boolean>> names = PersistentMap.empty();
  private PersistentMap<Bindings.Edge, PersistentMap<Path, Boolean>> edges = PersistentMap.empty();
  private PersistentMap<String, PersistentMap<Bindings.Edge, Boolean>>
      outgoing = PersistentMap.empty(),
      incoming = PersistentMap.empty();
  private PersistentMap<String, PersistentMap<Path, List<Bindings.Occurrence>>> references =
      PersistentMap.empty();
  private PersistentMap<Path, List<Bindings.Occurrence>> occurrences = PersistentMap.empty();
  private PersistentMap<Path, List<CompilerPool.Problem>> diagnostics = PersistentMap.empty();
  private PersistentMap<Path, List<String>> warnings = PersistentMap.empty();
  private PersistentMap<Path, Integer> degraded = PersistentMap.empty();
  private int occurrenceCount, diagnosticCount;
  private long updates;
  private SemanticNode apiRoot = SemanticNode.empty("workspace-api");

  SemanticNode apiRoot() {
    return apiRoot;
  }

  NavigationIndex withApiRoot(SemanticNode root) {
    if (root == apiRoot) return this;
    var next = new NavigationIndex(this);
    next.apiRoot = root;
    return next;
  }

  NavigationIndex() {}

  private NavigationIndex(NavigationIndex prior) {
    apiRoot = prior.apiRoot;
    owners = prior.owners;
    symbols = prior.symbols;
    declarations = prior.declarations;
    names = prior.names;
    edges = prior.edges;
    outgoing = prior.outgoing;
    incoming = prior.incoming;
    references = prior.references;
    occurrences = prior.occurrences;
    diagnostics = prior.diagnostics;
    warnings = prior.warnings;
    degraded = prior.degraded;
    occurrenceCount = prior.occurrenceCount;
    diagnosticCount = prior.diagnosticCount;
    updates = prior.updates;
  }

  private static <K extends Comparable<? super K>, O extends Comparable<? super O>, V>
      PersistentMap<K, PersistentMap<O, V>> freezePostings(
          Map<K, ? extends Map<O, V>> values) {
    var frozen = new TreeMap<K, PersistentMap<O, V>>();
    values.forEach((key, posting) -> frozen.put(key, PersistentMap.copyOf(posting)));
    return PersistentMap.copyOf(frozen);
  }

  static NavigationIndex rebuild(
      Map<Path, CompilerPool.Outcome<Bindings.Snapshot>> values, SemanticNode apiRoot) {
    var ownerRows = new TreeMap<String, TreeMap<Owner, Map<String, Object>>>();
    var edgeRows = new TreeMap<Bindings.Edge, TreeMap<Path, Boolean>>();
    var referenceRows =
        new TreeMap<String, TreeMap<Path, List<Bindings.Occurrence>>>();
    var occurrenceRows = new TreeMap<Path, List<Bindings.Occurrence>>();
    var diagnosticRows = new TreeMap<Path, List<CompilerPool.Problem>>();
    var warningRows = new TreeMap<Path, List<String>>();
    var degradedRows = new TreeMap<Path, Integer>();
    int occurrenceCount = 0, diagnosticCount = 0;

    for (var entry : values.entrySet()) {
      Path file = entry.getKey();
      var outcome = entry.getValue();
      var graph = outcome == null ? null : outcome.result();
      if (graph != null) {
        var declared = declarations(graph, file);
        for (var symbol : graph.symbols().entrySet())
          ownerRows
              .computeIfAbsent(symbol.getKey(), _ -> new TreeMap<>())
              .put(new Owner(file, declared.contains(symbol.getKey())), symbol.getValue());
        for (var edge : graph.edges())
          edgeRows.computeIfAbsent(edge, _ -> new TreeMap<>()).put(file, Boolean.TRUE);
        for (var posting : grouped(graph.occurrences()).entrySet())
          referenceRows
              .computeIfAbsent(posting.getKey(), _ -> new TreeMap<>())
              .put(file, List.copyOf(posting.getValue()));
        if (!graph.occurrences().isEmpty())
          occurrenceRows.put(file, List.copyOf(graph.occurrences()));
        occurrenceCount += graph.occurrences().size();
      }

      var problems = outcome == null ? List.<CompilerPool.Problem>of() : outcome.diagnostics();
      if (!problems.isEmpty()) diagnosticRows.put(file, List.copyOf(problems));
      diagnosticCount += problems.size();

      var messages =
          outcome == null ? new ArrayList<String>() : new ArrayList<>(outcome.warnings());
      if (outcome != null && graph == null)
        messages.add("incomplete_workspace_bindings: " + file);
      if (!messages.isEmpty()) warningRows.put(file, List.copyOf(messages));
      if (outcome != null && !(outcome.tier() == 2 && graph != null))
        degradedRows.put(file, Math.min(1, outcome.tier()));
    }

    var symbolRows = new TreeMap<String, Map<String, Object>>();
    var declarationRows = new TreeMap<String, Map<String, Object>>();
    for (var entry : ownerRows.entrySet()) {
      var selected = entry.getValue().lastEntry();
      symbolRows.put(entry.getKey(), selected.getValue());
      if (selected.getKey().declaration())
        declarationRows.put(entry.getKey(), selected.getValue());
    }

    var nameRows = new TreeMap<String, TreeMap<String, Boolean>>();
    for (var entry : symbolRows.entrySet())
      for (String name : names(entry.getValue()))
        nameRows.computeIfAbsent(name, _ -> new TreeMap<>()).put(entry.getKey(), Boolean.TRUE);

    var outgoingRows = new TreeMap<String, TreeMap<Bindings.Edge, Boolean>>();
    var incomingRows = new TreeMap<String, TreeMap<Bindings.Edge, Boolean>>();
    for (var edge : edgeRows.keySet()) {
      outgoingRows.computeIfAbsent(edge.src(), _ -> new TreeMap<>()).put(edge, Boolean.TRUE);
      incomingRows.computeIfAbsent(edge.dst(), _ -> new TreeMap<>()).put(edge, Boolean.TRUE);
    }

    var result = new NavigationIndex();
    result.owners = freezePostings(ownerRows);
    result.symbols = PersistentMap.copyOf(symbolRows);
    result.declarations = PersistentMap.copyOf(declarationRows);
    result.names = freezePostings(nameRows);
    result.edges = freezePostings(edgeRows);
    result.outgoing = freezePostings(outgoingRows);
    result.incoming = freezePostings(incomingRows);
    result.references = freezePostings(referenceRows);
    result.occurrences = PersistentMap.copyOf(occurrenceRows);
    result.diagnostics = PersistentMap.copyOf(diagnosticRows);
    result.warnings = PersistentMap.copyOf(warningRows);
    result.degraded = PersistentMap.copyOf(degradedRows);
    result.occurrenceCount = occurrenceCount;
    result.diagnosticCount = diagnosticCount;
    result.updates = values.size();
    result.apiRoot = Objects.requireNonNull(apiRoot);
    return result;
  }

  private static <K extends Comparable<? super K>, O extends Comparable<? super O>, V>
      PersistentMap<K, PersistentMap<O, V>> contribution(
          PersistentMap<K, PersistentMap<O, V>> index, K key, O file, V value) {
    var posting = index.getOrDefault(key, PersistentMap.empty());
    posting = value == null ? posting.without(file) : posting.with(file, value);
    return posting.isEmpty() ? index.without(key) : index.with(key, posting);
  }

  private static <K extends Comparable<? super K>, V> PersistentMap<K, List<V>> chunk(
      PersistentMap<K, List<V>> map, K key, List<V> values) {
    if (values.equals(map.get(key))) return map;
    return values.isEmpty() ? map.without(key) : map.with(key, List.copyOf(values));
  }

  private static Set<String> names(Map<String, Object> row) {
    var result = new LinkedHashSet<String>();
    if (row != null)
      for (String field : List.of("name_path", "qualified_name_path", "name", "fqn")) {
        String value = Objects.toString(row.get(field), "");
        if (!value.isBlank()) result.add(value);
      }
    return result;
  }

  private void select(String id) {
    var prior = symbols.get(id);
    var contributions = owners.get(id);
    var selected = contributions == null ? null : contributions.lastEntry();
    Map<String, Object> current = selected == null ? null : selected.getValue();
    declarations =
        selected == null || !selected.getKey().declaration()
            ? declarations.without(id)
            : declarations.with(id, current);
    if (Objects.equals(prior, current)) return;
    for (String name : names(prior)) {
      var posting = names.get(name).without(id);
      names = posting.isEmpty() ? names.without(name) : names.with(name, posting);
    }
    symbols = current == null ? symbols.without(id) : symbols.with(id, current);
    for (String name : names(current))
      names = names.with(name, names.getOrDefault(name, PersistentMap.empty()).with(id, true));
  }

  private static PersistentMap<String, PersistentMap<Bindings.Edge, Boolean>> edgePosting(
      PersistentMap<String, PersistentMap<Bindings.Edge, Boolean>> map,
      String symbol,
      Bindings.Edge edge,
      boolean add) {
    var posting = map.getOrDefault(symbol, PersistentMap.empty());
    posting = add ? posting.with(edge, true) : posting.without(edge);
    return posting.isEmpty() ? map.without(symbol) : map.with(symbol, posting);
  }

  private void edge(Path file, Bindings.Edge edge, boolean add) {
    boolean existed = edges.containsKey(edge);
    edges = contribution(edges, edge, file, add ? Boolean.TRUE : null);
    if (existed != edges.containsKey(edge)) {
      outgoing = edgePosting(outgoing, edge.src(), edge, add);
      incoming = edgePosting(incoming, edge.dst(), edge, add);
    }
  }

  private static Map<String, List<Bindings.Occurrence>> grouped(List<Bindings.Occurrence> values) {
    var result = new LinkedHashMap<String, List<Bindings.Occurrence>>();
    for (var occurrence : values)
      result.computeIfAbsent(occurrence.scip(), _ -> new ArrayList<>()).add(occurrence);
    return result;
  }

  private static Set<String> declarations(Bindings.Snapshot graph, Path file) {
    var result = new HashSet<String>();
    if (graph != null)
      for (var occurrence : graph.occurrences()) {
        var row = graph.symbols().get(occurrence.scip());
        if (occurrence.role().equals("declaration")
            && Path.of(occurrence.file()).toAbsolutePath().normalize().equals(file)
            && row != null
            && !Set.of(
                    "local_variable",
                    "parameter",
                    "exception_parameter",
                    "binding_variable",
                    "resource_variable",
                    "type_parameter")
                .contains(row.get("kind"))) result.add(occurrence.scip());
      }
    return result;
  }

  private static <K> Set<K> keys(Map<K, ?> before, Map<K, ?> after) {
    var keys = new LinkedHashSet<K>(before.keySet());
    keys.addAll(after.keySet());
    return keys;
  }

  NavigationIndex replace(
      Path file,
      CompilerPool.Outcome<Bindings.Snapshot> before,
      CompilerPool.Outcome<Bindings.Snapshot> after) {
    var next = new NavigationIndex(this);
    next.updates++;
    var old = before == null ? null : before.result();
    var now = after == null ? null : after.result();
    var oldSymbols = old == null ? Map.<String, Map<String, Object>>of() : old.symbols();
    var newSymbols = now == null ? Map.<String, Map<String, Object>>of() : now.symbols();
    var oldDeclarations = declarations(old, file);
    var newDeclarations = declarations(now, file);
    for (String id : keys(oldSymbols, newSymbols)) {
      var previous = oldSymbols.get(id);
      var current = newSymbols.get(id);
      boolean wasDeclaration = oldDeclarations.contains(id),
          isDeclaration = newDeclarations.contains(id);
      if (Objects.equals(previous, current) && wasDeclaration == isDeclaration) continue;
      if (previous != null)
        next.owners = contribution(next.owners, id, new Owner(file, wasDeclaration), null);
      if (current != null)
        next.owners = contribution(next.owners, id, new Owner(file, isDeclaration), current);
      next.select(id);
    }
    var oldEdges = old == null ? Set.<Bindings.Edge>of() : new HashSet<>(old.edges());
    var newEdges = now == null ? Set.<Bindings.Edge>of() : new HashSet<>(now.edges());
    for (var edge : oldEdges) if (!newEdges.contains(edge)) next.edge(file, edge, false);
    for (var edge : newEdges) if (!oldEdges.contains(edge)) next.edge(file, edge, true);
    var oldReferences =
        old == null ? Map.<String, List<Bindings.Occurrence>>of() : grouped(old.occurrences());
    var newReferences =
        now == null ? Map.<String, List<Bindings.Occurrence>>of() : grouped(now.occurrences());
    for (String id : keys(oldReferences, newReferences)) {
      var previous = oldReferences.get(id);
      var current = newReferences.get(id);
      if (!Objects.equals(previous, current))
        next.references =
            contribution(next.references, id, file, current == null ? null : List.copyOf(current));
    }
    var occurrenceList = now == null ? List.<Bindings.Occurrence>of() : now.occurrences();
    next.occurrenceCount +=
        occurrenceList.size() - next.occurrences.getOrDefault(file, List.of()).size();
    next.occurrences = chunk(next.occurrences, file, occurrenceList);
    var problems = after == null ? List.<CompilerPool.Problem>of() : after.diagnostics();
    next.diagnosticCount += problems.size() - next.diagnostics.getOrDefault(file, List.of()).size();
    next.diagnostics = chunk(next.diagnostics, file, problems);
    var messages = after == null ? List.<String>of() : after.warnings();
    if (after != null && now == null) {
      var copy = new ArrayList<>(messages);
      copy.add("incomplete_workspace_bindings: " + file);
      messages = List.copyOf(copy);
    }
    next.warnings = chunk(next.warnings, file, messages);
    next.degraded =
        after == null || after.tier() == 2 && now != null
            ? next.degraded.without(file)
            : next.degraded.with(file, Math.min(1, after.tier()));
    return next;
  }

  Map<String, Map<String, Object>> symbols() {
    return symbols;
  }

  Map<String, Map<String, Object>> declarations() {
    return declarations;
  }

  int tier() {
    return degraded.isEmpty()
        ? 2
        : degraded.values().stream().mapToInt(Integer::intValue).min().orElse(1);
  }

  long updates() {
    return updates;
  }

  List<String> warnings() {
    var result = new LinkedHashSet<String>();
    warnings.values().forEach(result::addAll);
    return List.copyOf(result);
  }

  List<Map<String, Object>> lookup(String ref) {
    var posting = names.get(ref);
    return posting == null ? List.of() : posting.keySet().stream().map(symbols::get).toList();
  }

  List<Bindings.Edge> adjacent(Set<String> frontier, boolean forward) {
    var selected = new TreeSet<Bindings.Edge>();
    var index = forward ? outgoing : incoming;
    for (String symbol : frontier) {
      var posting = index.get(symbol);
      if (posting != null) selected.addAll(posting.keySet());
    }
    return List.copyOf(selected);
  }

  List<Bindings.Occurrence> references(Set<Bindings.Edge> selected) {
    var targets = new LinkedHashSet<String>();
    for (var edge : selected) targets.add(edge.dst());
    return occurrences(
        targets,
        value ->
            value.container() != null
                && selected.contains(
                    new Bindings.Edge(value.container(), value.scip(), value.role())));
  }

  List<Bindings.Occurrence> occurrences(Set<String> targets) {
    return occurrences(targets, value -> true);
  }

  private List<Bindings.Occurrence> occurrences(
      Set<String> targets, java.util.function.Predicate<Bindings.Occurrence> include) {
    var result = new ArrayList<Bindings.Occurrence>();
    for (String target : targets) {
      var posting = references.get(target);
      if (posting != null)
        for (var chunk : posting.values())
          for (var value : chunk) if (include.test(value)) result.add(value);
    }
    result.sort(
        Comparator.comparing(Bindings.Occurrence::file)
            .thenComparingInt(Bindings.Occurrence::start)
            .thenComparing(Bindings.Occurrence::scip)
            .thenComparing(Bindings.Occurrence::role));
    return List.copyOf(result);
  }

  Set<String> importedSymbols(String file, int start) {
    var result = new HashSet<String>();
    for (var occurrence : occurrences.getOrDefault(Path.of(file), List.of()))
      if (occurrence.importSite() != null && occurrence.start() == start)
        result.add(occurrence.scip());
    return result;
  }

  List<Bindings.Edge> edges() {
    return new AbstractList<>() {
      public int size() {
        return edges.size();
      }

      public Bindings.Edge get(int index) {
        Objects.checkIndex(index, size());
        var iterator = edges.keySet().iterator();
        while (index-- > 0) iterator.next();
        return iterator.next();
      }

      @Override
      public Iterator<Bindings.Edge> iterator() {
        return edges.keySet().iterator();
      }
    };
  }

  private static <T> List<T> flattened(PersistentMap<Path, List<T>> chunks, int size) {
    return new AbstractList<>() {
      public int size() {
        return size;
      }

      public T get(int index) {
        Objects.checkIndex(index, size);
        for (var chunk : chunks.values()) {
          if (index < chunk.size()) return chunk.get(index);
          index -= chunk.size();
        }
        throw new AssertionError();
      }

      @Override
      public Iterator<T> iterator() {
        return new Iterator<>() {
          final Iterator<List<T>> outer = chunks.values().iterator();
          Iterator<T> inner = Collections.emptyIterator();

          public boolean hasNext() {
            while (!inner.hasNext() && outer.hasNext()) inner = outer.next().iterator();
            return inner.hasNext();
          }

          public T next() {
            if (!hasNext()) throw new NoSuchElementException();
            return inner.next();
          }
        };
      }
    };
  }

  List<Bindings.Occurrence> occurrences() {
    return flattened(occurrences, occurrenceCount);
  }

  List<CompilerPool.Problem> diagnostics() {
    return flattened(diagnostics, diagnosticCount);
  }
}
