package dev.jvmd.dist;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;

/**
 * Implements 4.2 and 4.8: bounded detached workspace graphs, reused only while every input identity
 * remains current.
 */
public final class WorkspaceBindings implements AutoCloseable {
  /** Implements 4.2: attribution remains on the caller's session executor. */
  @FunctionalInterface
  public interface Loader {
    CompilerPool.Outcome<Bindings.Snapshot> load(Path file, String text) throws Exception;
  }

  @FunctionalInterface
  public interface BatchLoader {
    Map<Path, CompilerPool.Outcome<Bindings.Snapshot>> load(Map<Path, String> sources)
        throws Exception;
  }

  /** Implements 4.2: re-enumerate sources to detect namespace changes during attribution. */
  @FunctionalInterface
  public interface SourceFiles {
    List<Path> files() throws Exception;
  }

  @FunctionalInterface
  public interface Validation {
    ValidationToken current() throws Exception;
  }

  public record ValidationToken(
      String generation,
      long documentsGeneration,
      Map<String, Long> sourceGenerations,
      Map<String, String> merkleFingerprints) {
    public ValidationToken {
      sourceGenerations = Map.copyOf(sourceGenerations);
      merkleFingerprints = Map.copyOf(merkleFingerprints);
    }

    /**
     * Merkle state is optional while the index starts. If all authoritative live epochs are
     * unchanged, adopting a newly available persisted Merkle root does not require rereading every
     * source. Once both sides have Merkle roots, they must agree exactly.
     */
    boolean fastCompatible(ValidationToken prior) {
      if (prior == null
          || !Objects.equals(generation, prior.generation)
          || documentsGeneration != prior.documentsGeneration
          || !sourceGenerations.equals(prior.sourceGenerations)) return false;
      // Persisted Merkle state may appear lazily after a cold snapshot. Treat that as
      // additive evidence only: every previously authoritative root must still exist and
      // match. A disappearing or changed root always falls back to full validation.
      for (var entry : prior.merkleFingerprints.entrySet())
        if (!Objects.equals(merkleFingerprints.get(entry.getKey()), entry.getValue())) return false;
      return true;
    }
  }

  /**
   * Implements 4.8: one immutable source graph shared by navigation, references and semantic edits.
   */
  public record Snapshot(
      Map<String, Map<String, Object>> symbols,
      Map<String, Map<String, Object>> declarations,
      List<Bindings.Edge> edges,
      List<Bindings.Occurrence> occurrences,
      List<CompilerPool.Problem> diagnostics,
      int tier,
      List<String> warnings,
      @com.fasterxml.jackson.annotation.JsonIgnore NavigationIndex navigation,
      @com.fasterxml.jackson.annotation.JsonIgnore SemanticNode apiRoot) {
    public List<Bindings.Edge> adjacent(Set<String> frontier, boolean forward) {
      return navigation.adjacent(frontier, forward);
    }

    public List<Bindings.Occurrence> references(Set<Bindings.Edge> selected) {
      return navigation.references(selected);
    }

    public List<Map<String, Object>> lookup(String ref) {
      return navigation.lookup(ref);
    }

    public List<Bindings.Occurrence> occurrences(Set<String> targets) {
      return navigation.occurrences(targets);
    }

    public Set<String> importedSymbols(String file, int start) {
      return navigation.importedSymbols(file, start);
    }
  }

  private record Inputs(
      String generation,
      Map<Path, String> sourceHashes,
      Map<Path, String> classpathHashes,
      List<Path> files,
      List<Path> classpath) {}

  private record Fragment(CompilerPool.Outcome<Bindings.Snapshot> outcome, long estimatedBytes) {
    SemanticApi api() {
      return outcome.result() == null ? SemanticApi.EMPTY : outcome.result().api();
    }
  }

  private PersistentMap<Path, Fragment> fragments = PersistentMap.empty();
  private final FileStateRegistry classpathFiles;

  public WorkspaceBindings() {
    this(new FileStateRegistry());
  }

  public WorkspaceBindings(FileStateRegistry classpathFiles) {
    this.classpathFiles = Objects.requireNonNull(classpathFiles);
  }

  private final DependencyGraph<Path> dependencyGraph = new DependencyGraph<>();
  private NavigationIndex navigation = new NavigationIndex();
  private long estimatedBytes, navigationFileUpdates;
  private long lastAttemptedRetainedBytes, lastAdmissionLimitBytes, discards;
  private String lastDiscardReason = "";
  private long enumerationNanos, inputNanos, attributionNanos, navigationNanos;
  private Inputs inputs;
  private Snapshot snapshot;
  private ValidationToken validationToken;
  private long hits,
      builds,
      fullBuilds,
      incrementalBuilds,
      filesReanalysed,
      filesReused,
      apiInvalidations,
      fastValidationHits,
      fullValidations;
  private int lastReanalysedFiles;

  private Inputs observe(
      SourceFiles sources, List<Path> classpath, Documents documents, String generation)
      throws Exception {
    long started = System.nanoTime();
    var files = sources.files();
    enumerationNanos += System.nanoTime() - started;
    return inputs(files, classpath, documents, generation);
  }

  private Inputs inputs(
      List<Path> files, List<Path> classpath, Documents documents, String generation)
      throws Exception {
    long started = System.nanoTime();
    try {
      var normalizedFiles =
          files.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
      var sourceValues = new LinkedHashMap<Path, String>();
      for (Path file : normalizedFiles) sourceValues.put(file, documents.sourceHash(file));
      var classpathValues = new LinkedHashMap<Path, String>();
      var normalizedClasspath = new ArrayList<Path>();
      for (Path raw : classpath) {
        Path path = raw.toAbsolutePath().normalize();
        normalizedClasspath.add(path);
        if (Files.isDirectory(path))
          try (var children =
              Files.find(
                  path,
                  Integer.MAX_VALUE,
                  (file, attributes) ->
                      (file.toString().endsWith(".class") || file.toString().endsWith(".jar"))
                          && (attributes.isRegularFile()
                              || attributes.isSymbolicLink() && Files.isRegularFile(file)))) {
            for (Path file : children.sorted().toList())
              classpathValues.put(file.toAbsolutePath().normalize(), classpathFiles.hash(file));
          }
        else classpathValues.put(path, classpathFiles.hash(path));
      }
      return new Inputs(
          generation,
          Map.copyOf(sourceValues),
          Map.copyOf(classpathValues),
          List.copyOf(normalizedFiles),
          List.copyOf(normalizedClasspath));
    } finally {
      inputNanos += System.nanoTime() - started;
    }
  }

  private static boolean sameContext(Inputs first, Inputs second) {
    return first != null
        && second != null
        && Objects.equals(first.generation(), second.generation())
        && first.classpath().equals(second.classpath())
        && first.classpathHashes().equals(second.classpathHashes())
        && first.sourceHashes().keySet().equals(second.sourceHashes().keySet());
  }

  public Snapshot peek(
      List<Path> files, List<Path> classpath, Documents documents, String generation)
      throws Exception {
    if (snapshot == null) return null;
    fullValidations++;
    var current = inputs(files, classpath, documents, generation);
    if (!current.equals(inputs)) {
      snapshot = null;
      validationToken = null;
      return null;
    }
    hits++;
    return snapshot;
  }

  private static boolean stable(ValidationToken before, ValidationToken after) {
    return before == null ? after == null : after != null && after.fastCompatible(before);
  }

  public Snapshot peek(
      SourceFiles sources,
      List<Path> classpath,
      Documents documents,
      String generation,
      Validation validation)
      throws Exception {
    if (snapshot == null) return null;
    var token = validation == null ? null : validation.current();
    if (token != null && token.fastCompatible(validationToken)) {
      hits++;
      fastValidationHits++;
      validationToken = token;
      return snapshot;
    }
    fullValidations++;
    var current = observe(sources, classpath, documents, generation);
    if (!current.equals(inputs)) {
      snapshot = null;
      validationToken = null;
      return null;
    }
    var after = validation == null ? null : validation.current();
    if (!stable(token, after)) {
      snapshot = null;
      validationToken = null;
      return null;
    }
    hits++;
    validationToken = after;
    return snapshot;
  }

  public Snapshot get(
      SourceFiles sources,
      List<Path> classpath,
      Documents documents,
      String generation,
      long byteBudget,
      Loader loader)
      throws Exception {
    return getBatch(
        sources,
        classpath,
        documents,
        generation,
        byteBudget,
        null,
        files -> {
          var results = new LinkedHashMap<Path, CompilerPool.Outcome<Bindings.Snapshot>>();
          for (var entry : files.entrySet())
            results.put(entry.getKey(), loader.load(entry.getKey(), entry.getValue()));
          return results;
        });
  }

  private static boolean hasErrors(Fragment fragment) {
    return fragment != null
        && fragment.outcome().diagnostics().stream()
            .anyMatch(problem -> problem.kind().equals("ERROR"));
  }

  private Set<Path> reverseClosure(
      Map<Path, Fragment> prior, Set<Path> roots, Set<Path> currentFiles) {
    var causes = new LinkedHashSet<>(roots);
    // Failed lookups have no complete javac read set; retain conservative error recovery.
    for (var entry : prior.entrySet()) if (hasErrors(entry.getValue())) causes.add(entry.getKey());
    var selected = new LinkedHashSet<>(dependencyGraph.affected(causes));
    selected.retainAll(currentFiles);
    selected.removeAll(roots);
    return selected;
  }

  private Map<Path, Fragment> load(
      Set<Path> files, Inputs current, Documents documents, BatchLoader loader) throws Exception {
    if (files.isEmpty()) return Map.of();
    var texts = new LinkedHashMap<Path, String>();
    for (Path file : current.files())
      if (files.contains(file)) texts.put(file, documents.text(file));
    long started = System.nanoTime();
    var loaded = loader.load(Collections.unmodifiableMap(texts));
    attributionNanos += System.nanoTime() - started;
    var result = new LinkedHashMap<Path, Fragment>();
    for (Path file : texts.keySet()) {
      var outcome =
          Objects.requireNonNull(loaded.get(file), "Missing file in binding batch: " + file);
      result.put(file, new Fragment(outcome, estimate(outcome)));
    }
    return result;
  }

  private static long estimate(CompilerPool.Outcome<Bindings.Snapshot> outcome) {
    var graph = outcome.result();
    if (graph == null) return 1024;
    long bytes =
        2048L
            + 512L * graph.edges().size()
            + 512L * graph.occurrences().size()
            + 256L * outcome.diagnostics().size();
    for (var row : graph.symbols().values()) {
      bytes += 1024L + 96L * row.size();
      for (var value : row.values()) {
        if (value instanceof String text) bytes += 48L + 2L * text.length();
        else if (value instanceof Collection<?> values) bytes += 64L + 96L * values.size();
      }
    }
    return bytes;
  }

  private static Snapshot aggregate(NavigationIndex graph, boolean consistent) {
    var warnings = new LinkedHashSet<>(graph.warnings());
    int tier = graph.tier();
    if (!consistent) {
      warnings.add("workspace_changed_during_query: retry for a consistent graph");
      tier = Math.min(tier, 1);
    }
    return new Snapshot(
        graph.symbols(),
        graph.declarations(),
        graph.edges(),
        graph.occurrences(),
        graph.diagnostics(),
        tier,
        List.copyOf(warnings),
        graph,
        graph.apiRoot());
  }

  public Snapshot getBatch(
      SourceFiles sources,
      List<Path> classpath,
      Documents documents,
      String generation,
      long byteBudget,
      BatchLoader loader)
      throws Exception {
    return getBatch(sources, classpath, documents, generation, byteBudget, null, loader);
  }

  public Snapshot getBatch(
      SourceFiles sources,
      List<Path> classpath,
      Documents documents,
      String generation,
      long byteBudget,
      Validation validation,
      BatchLoader loader)
      throws Exception {
    var token = validation == null ? null : validation.current();
    if (snapshot != null && token != null && token.fastCompatible(validationToken)) {
      hits++;
      fastValidationHits++;
      validationToken = token;
      lastReanalysedFiles = 0;
      filesReused += inputs == null ? 0 : inputs.files().size();
      return snapshot;
    }
    fullValidations++;
    var current = observe(sources, classpath, documents, generation);
    if (snapshot != null && current.equals(inputs)) {
      var checked = validation == null ? null : validation.current();
      if (stable(token, checked)) {
        hits++;
        lastReanalysedFiles = 0;
        filesReused += current.files().size();
        validationToken = checked;
        return snapshot;
      }
      // Do not bless old inputs with an epoch observed only after their validation.
      snapshot = null;
      validationToken = null;
      return getBatch(sources, classpath, documents, generation, byteBudget, null, loader);
    }

    var priorInputs = inputs;
    var priorFragments = fragments;
    boolean full =
        !sameContext(priorInputs, current) || priorFragments.size() != current.files().size();
    if (!full)
      full =
          current.sourceHashes().entrySet().stream()
              .anyMatch(
                  e ->
                      SemanticApi.contextSource(e.getKey())
                          && !Objects.equals(
                              e.getValue(), priorInputs.sourceHashes().get(e.getKey())));
    var dirty = new LinkedHashSet<Path>();
    if (full) dirty.addAll(current.files());
    else
      for (Path file : current.files())
        if (!Objects.equals(priorInputs.sourceHashes().get(file), current.sourceHashes().get(file)))
          dirty.add(file);

    long workingEstimate = estimatedBytes;
    builds++;
    snapshot = null;
    if (full) fullBuilds++;
    else incrementalBuilds++;

    var working = priorFragments;
    var nextApi = navigation.apiRoot();
    var removed = new LinkedHashSet<>(priorFragments.keySet());
    removed.removeAll(current.sourceHashes().keySet());
    for (Path file : removed) {
      nextApi = SemanticApi.replaceFile(nextApi, file, priorFragments.get(file).api(), null);
      working = working.without(file);
    }
    var first = load(dirty, current, documents, loader);
    for (var entry : first.entrySet()) {
      working = working.with(entry.getKey(), entry.getValue());
      var prior = priorFragments.get(entry.getKey());
      nextApi =
          SemanticApi.replaceFile(
              nextApi, entry.getKey(), prior == null ? null : prior.api(), entry.getValue().api());
    }
    var apiChanged = new LinkedHashSet<Path>();
    if (!full && nextApi != navigation.apiRoot())
      for (Path file : dirty) {
        var before = priorFragments.get(file);
        var after = first.get(file);
        if (before == null
            || after == null
            || !before.api().root().identity().equals(after.api().root().identity()))
          apiChanged.add(file);
      }
    if (!apiChanged.isEmpty()) {
      apiInvalidations += apiChanged.size();
      var dependants =
          reverseClosure(priorFragments, apiChanged, new LinkedHashSet<>(current.files()));
      dependants.removeAll(dirty);
      for (var entry : load(dependants, current, documents, loader).entrySet()) {
        var prior = working.get(entry.getKey());
        nextApi =
            SemanticApi.replaceFile(
                nextApi,
                entry.getKey(),
                prior == null ? null : prior.api(),
                entry.getValue().api());
        working = working.with(entry.getKey(), entry.getValue());
      }
      dirty.addAll(dependants);
    }

    lastReanalysedFiles = dirty.size();
    filesReanalysed += dirty.size();
    filesReused += Math.max(0, current.files().size() - dirty.size());
    var publicationStart = validation == null ? null : validation.current();
    var after = observe(sources, classpath, documents, generation);
    boolean consistent = current.equals(after);
    long maintenanceStart = System.nanoTime();
    NavigationIndex nextNavigation;
    if (full) {
      var outcomes = new LinkedHashMap<Path, CompilerPool.Outcome<Bindings.Snapshot>>();
      workingEstimate = 0;
      for (Path file : current.files()) {
        var fragment = working.get(file);
        if (fragment == null) continue;
        outcomes.put(file, fragment.outcome());
        workingEstimate += fragment.estimatedBytes();
      }
      nextNavigation = NavigationIndex.rebuild(outcomes, nextApi);
      navigationFileUpdates += outcomes.size();
    } else {
      nextNavigation = navigation;
      for (Path file : removed) {
        var old = priorFragments.get(file);
        nextNavigation = nextNavigation.replace(file, old.outcome(), null);
        workingEstimate -= old.estimatedBytes();
      }
      for (Path file : dirty) {
        var old = priorFragments.get(file);
        var updated = working.get(file);
        nextNavigation =
            nextNavigation.replace(file, old == null ? null : old.outcome(), updated.outcome());
        workingEstimate += updated.estimatedBytes() - (old == null ? 0 : old.estimatedBytes());
      }
      navigationFileUpdates += dirty.size() + removed.size();
      nextNavigation = nextNavigation.withApiRoot(nextApi);
    }
    navigationNanos += System.nanoTime() - maintenanceStart;
    var publicationEnd = validation == null ? null : validation.current();
    consistent &= stable(publicationStart, publicationEnd);
    var result = aggregate(nextNavigation, consistent);
    long admissionLimit = Math.min(128L * 1024 * 1024, Math.max(0, byteBudget));
    lastAttemptedRetainedBytes = workingEstimate;
    lastAdmissionLimitBytes = admissionLimit;
    boolean analyzerFault =
        result.warnings().stream().anyMatch(w -> w.startsWith("analyzer_fault"));
    if (consistent && result.tier() == 2 && !analyzerFault && workingEstimate <= admissionLimit) {
      snapshot = result;
      inputs = current;
      fragments = working;
      estimatedBytes = workingEstimate;
      validationToken = publicationEnd;
      navigation = nextNavigation;
      lastDiscardReason = "";
      if (full) dependencyGraph.clear();
      for (Path file : dirty) {
        var graph = working.get(file).outcome().result();
        dependencyGraph.record(file, graph == null ? Set.of() : graph.dependencies(), true);
      }
    } else {
      lastDiscardReason =
          !consistent
              ? "inconsistent_publication"
              : result.tier() != 2
                  ? "degraded_tier"
                  : analyzerFault ? "analyzer_fault" : "over_budget";
      discards++;
      discard();
    }
    return result;
  }

  public Map<String, Object> status() {
    var result = new LinkedHashMap<String, Object>();
    result.put("source_enumeration_ms", enumerationNanos / 1e6);
    result.put("input_validation_ms", inputNanos / 1e6);
    result.put("attribution_ms", attributionNanos / 1e6);
    result.put("fragment_serialization_ms", 0);
    result.put("navigation_maintenance_ms", navigationNanos / 1e6);
    result.put("navigation_file_updates", navigationFileUpdates);
    result.put("api_root", navigation.apiRoot().identity().encoded());
    result.put("fragment_bytes_serialized", 0);
    result.put("estimated_retained_bytes", estimatedBytes);
    result.put("last_attempted_retained_bytes", lastAttemptedRetainedBytes);
    result.put("last_admission_limit_bytes", lastAdmissionLimitBytes);
    result.put("last_discard_reason", lastDiscardReason);
    result.put("discards", discards);
    result.put("builds", builds);
    result.put("cache_hits", hits);
    result.put("serialized_bytes", 0);
    result.put("cached_files", inputs == null ? 0 : inputs.files().size());
    result.put("fragment_files", fragments.size());
    result.put("full_builds", fullBuilds);
    result.put("incremental_builds", incrementalBuilds);
    result.put("files_reanalysed", filesReanalysed);
    result.put("files_reused", filesReused);
    result.put("last_reanalysed_files", lastReanalysedFiles);
    result.put("api_invalidations", apiInvalidations);
    result.put("fast_validation_hits", fastValidationHits);
    result.put("full_validations", fullValidations);
    result.put("fast_validation_ready", validationToken != null);
    return Collections.unmodifiableMap(result);
  }

  private void discard() {
    snapshot = null;
    inputs = null;
    validationToken = null;
    fragments = PersistentMap.empty();
    dependencyGraph.clear();
    navigation = new NavigationIndex();
    estimatedBytes = 0;
  }

  @Override
  public void close() {
    discard();
  }
}
