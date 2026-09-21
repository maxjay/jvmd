package dev.jvmd.analyzer;

import dev.jvmd.core.SemanticNode;
import java.nio.file.Path;
import java.util.*;

/** One file's authoritative API ownership tree, shared by analysis, navigation and publication. */
public record SemanticApi(
    String module,
    SemanticNode root,
    Map<String, DeclarationContract> declarations,
    Map<String, String> owners,
    Set<String> exportedNames) {
  public static final SemanticApi EMPTY =
      new SemanticApi("", SemanticNode.empty("file-api"), Map.of(), Map.of(), Set.of());

  public String fingerprint() {
    return root.identity().encoded();
  }

  public static boolean contextSource(Path file) {
    String name = file.getFileName().toString();
    return name.equals("module-info.java") || name.equals("package-info.java");
  }

  public static SemanticNode replaceFile(
      SemanticNode workspace, Path file, SemanticApi before, SemanticApi after) {
    if (before != null && (after == null || !before.module.equals(after.module)))
      workspace = replaceFile(workspace, file, before.module, null);
    return after == null ? workspace : replaceFile(workspace, file, after.module, after.root);
  }

  private static SemanticNode replaceFile(
      SemanticNode workspace, Path file, String owner, SemanticNode fileApi) {
    var module = workspace.children().get(owner);
    if (module == null) module = SemanticNode.empty("module-api");
    module = module.withChild(file.toString(), fileApi);
    return workspace.withChild(owner, module.children().isEmpty() ? null : module);
  }

  static SemanticApi build(
      String module,
      Map<String, DeclarationContract> declarations,
      Map<String, String> owners,
      Set<String> names,
      SemanticApi prior) {
    if (prior == null || !prior.module.equals(module)) prior = EMPTY;
    var children = new HashMap<String, List<String>>();
    for (String symbol : declarations.keySet()) {
      String owner = owners.get(symbol);
      children
          .computeIfAbsent(
              declarations.containsKey(owner) ? owner : "", ignored -> new ArrayList<>())
          .add(symbol);
    }
    var root = compose("", prior.root, declarations, children);
    if (root == prior.root
        && declarations.equals(prior.declarations)
        && names.equals(prior.exportedNames)) return prior;
    return new SemanticApi(
        module, root, Map.copyOf(declarations), Map.copyOf(owners), Set.copyOf(names));
  }

  private static SemanticNode compose(
      String symbol,
      SemanticNode prior,
      Map<String, DeclarationContract> declarations,
      Map<String, List<String>> children) {
    var declaration = declarations.get(symbol);
    var node =
        SemanticNode.leaf(
            declaration == null ? "file-api" : "declaration-api",
            declaration == null ? List.of() : declaration.fields(),
            prior);
    var current = new LinkedHashSet<>(children.getOrDefault(symbol, List.of()));
    for (String removed : node.children().keySet())
      if (!declarations.containsKey(removed) || !current.contains(removed))
        node = node.withChild(removed, null);
    for (String child : current)
      node =
          node.withChild(child, compose(child, node.children().get(child), declarations, children));
    return node;
  }
}
