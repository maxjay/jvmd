package dev.jvmd.boot.cold.stage2;

import dev.jvmd.index.layer.local.ProjectModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code Order} (stage 2, 2.6 and 3.10): the modules in topological order of their inter-module dependencies, ties broken by the
 * model's own order so that the result is a function of the model. A cycle is a fault of the model: it is reported and the boot
 * stops, because any heuristic that broke it would produce a classpath the build never used.
 *
 * @param modules      every module, dependencies first
 * @param dependencies the modules each module depends on, over both scopes: a test route binds its dependencies' {@code main} leaves too
 */
public record Order(List<ProjectModel.Module> modules, Map<String, Set<String>> dependencies) {
    /** @throws ProjectModel.Fault naming the modules of a cycle */
    public static Order of(ProjectModel model) {
        var deps = new LinkedHashMap<String, Set<String>>();
        for (var module : model.modules()) {
            var names = new LinkedHashSet<String>();
            for (var scope : List.of(module.main(), module.test()))
                for (var d : scope.dependencies()) if (d.module() != null) names.add(d.module());
            deps.put(module.name(), names);
        }
        var byName = new HashMap<String, ProjectModel.Module>();
        for (var m : model.modules()) byName.put(m.name(), m);
        var done = new HashSet<String>();
        var visiting = new ArrayList<String>();
        var out = new ArrayList<ProjectModel.Module>();
        for (var m : model.modules()) visit(m.name(), deps, byName, done, visiting, out);
        return new Order(List.copyOf(out), deps);
    }

    private static void visit(String name, Map<String, Set<String>> deps, Map<String, ProjectModel.Module> byName, Set<String> done,
                              List<String> visiting, List<ProjectModel.Module> out) {
        if (done.contains(name)) return;
        int at = visiting.indexOf(name);
        if (at >= 0) {
            var cycle = new ArrayList<>(visiting.subList(at, visiting.size()));
            cycle.add(name);
            throw new ProjectModel.Fault("Module dependency cycle: " + String.join(" -> ", cycle));
        }
        visiting.add(name);
        for (var dep : deps.get(name)) visit(dep, deps, byName, done, visiting, out);
        visiting.remove(visiting.size() - 1);
        done.add(name);
        out.add(byName.get(name));
    }
}
