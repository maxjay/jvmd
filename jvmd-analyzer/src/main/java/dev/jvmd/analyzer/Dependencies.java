package dev.jvmd.analyzer;

import java.nio.file.*;
import java.util.*;

/** Implements 4.2: content-checked reverse source dependencies and lazy invalidation. */
public final class Dependencies {
  private final dev.jvmd.core.DependencyGraph<Path> graph = new dev.jvmd.core.DependencyGraph<>();
  private final Map<Path, String> hashes = new HashMap<>();
  private java.util.function.Function<Path, String> documentHash = _ -> null;
  private dev.jvmd.core.FileStateRegistry files = new dev.jvmd.core.FileStateRegistry();

  public void fileStates(dev.jvmd.core.FileStateRegistry files) {
    this.files = files;
  }

  public void documentHash(java.util.function.Function<Path, String> lookup) {
    documentHash = lookup;
  }

  private final Set<Path> stale = new LinkedHashSet<>();

  public Set<Path> observe(Path path, String hash) {
    path = path.toAbsolutePath().normalize();
    String previous = hashes.put(path, hash);
    if (previous != null && !previous.equals(hash)) return changed(path);
    return Set.of();
  }

  public void record(Path file, Set<Path> dependencies) throws Exception {
    record(file, dependencies, false);
  }

  public void record(Path file, Set<Path> dependencies, boolean complete) throws Exception {
    file = file.toAbsolutePath().normalize();
    var normalized = new LinkedHashSet<Path>();
    for (Path dependency : dependencies) {
      dependency = dependency.toAbsolutePath().normalize();
      if (dependency.equals(file)) continue;
      normalized.add(dependency);
      if (!hashes.containsKey(dependency)) hashes.put(dependency, hash(dependency));
    }
    graph.record(file, normalized, complete);
    stale.remove(file);
  }

  public Set<Path> check(Path file) throws Exception {
    var changed = new LinkedHashSet<Path>();
    var visit = new ArrayDeque<Path>();
    var seen = new HashSet<Path>();
    visit.add(file.toAbsolutePath().normalize());
    while (!visit.isEmpty()) {
      Path path = visit.removeFirst();
      if (!seen.add(path)) continue;
      changed.addAll(observe(path, hash(path)));
      visit.addAll(graph.dependencies(path));
    }
    return changed;
  }

  public Set<Path> changed(Path path) {
    path = path.toAbsolutePath().normalize();
    String authoritative = documentHash.apply(path);
    return changed(path, authoritative);
  }

  public Set<Path> changed(Path path, String currentHash) {
    path = path.toAbsolutePath().normalize();
    boolean authoritative = currentHash != null;
    if (authoritative) hashes.put(path, currentHash);
    var result = graph.affected(Set.of(path));
    if (authoritative) stale.add(path);
    else stale.addAll(result);
    return Set.copyOf(result);
  }

  private String hash(Path file) throws Exception {
    String memory = documentHash.apply(file);
    return memory != null ? memory : files.hash(file);
  }

  public boolean stale(Path file) {
    return stale.contains(file.toAbsolutePath().normalize());
  }

  public Map<String, Object> status() {
    return Map.of(
        "tracked_files",
        hashes.size(),
        "reverse_edges",
        graph.edges(),
        "stale_files",
        stale.stream().map(Path::toString).sorted().toList());
  }
}
