package dev.jvmd.boot.cold.stage3;

import dev.jvmd.index.layer.local.ProcessorRecords;
import dev.jvmd.index.layer.local.ResultRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Scope presentation derived from per-source results; aggregate notes never change any file's reusable RS identity. */
public final class Diagnostics {
    private Diagnostics() { }
    public record Message(String path, ResultRecord.Diagnostic diagnostic) { }
    // Check.reportDeferredDiagnostics order in the bound javac. The runtime version is already in FORMAT.
    private static final List<String> CATEGORIES = List.of("deprecated", "removal", "unchecked");

    public static List<Message> assemble(List<Stage3.File> files, List<ProcessorRecords.Message> aggregate) {
        var result = new ArrayList<Message>();
        for (var message : aggregate) result.add(new Message(message.path(), new ResultRecord.Diagnostic(switch (message.kind()) {
            case ERROR -> 0; case WARNING, MANDATORY_WARNING -> 1; default -> 2;
        }, message.start(), message.end(), message.code(), message.text())));
        var notes = new LinkedHashMap<String, List<Message>>();
        for (String category : CATEGORIES) notes.put(category, new ArrayList<>());
        for (var file : files) for (var diagnostic : file.computed().result().diagnostics()) {
            var message = new Message(file.path(), diagnostic);
            String category = category(diagnostic);
            if (category == null) result.add(message);
            else notes.get(category).add(message);
        }
        for (var group : notes.entrySet()) {
            var summaries = group.getValue().stream().filter(m -> !m.diagnostic().code().endsWith(".recompile")).toList();
            if (summaries.isEmpty()) { result.addAll(group.getValue()); continue; }
            var first = summaries.getFirst();
            boolean plural = summaries.stream().map(Message::path).distinct().count() > 1
                    || summaries.stream().anyMatch(m -> m.diagnostic().code().contains(".plural"));
            if (!plural) result.add(first);
            else {
                boolean additional = summaries.stream().anyMatch(m -> m.diagnostic().code().endsWith(".additional"));
                String code = "compiler.note." + group.getKey() + ".plural" + (additional ? ".additional" : "");
                var original = first.diagnostic();
                result.add(new Message(first.path(), new ResultRecord.Diagnostic(original.kind(), original.start(), original.end(),
                        code, Pool.localizedMessage(code))));
            }
            group.getValue().stream().filter(m -> m.diagnostic().code().endsWith(".recompile")).findFirst()
                    .ifPresent(m -> result.add(new Message(first.path(), m.diagnostic())));
        }
        return List.copyOf(result);
    }

    private static String category(ResultRecord.Diagnostic diagnostic) {
        if (diagnostic.kind() != 2 || diagnostic.start() != -1 || diagnostic.end() != -1) return null;
        for (String category : CATEGORIES) {
            String prefix = "compiler.note." + category + ".";
            if (diagnostic.code().startsWith(prefix) && List.of("filename", "filename.additional", "plural", "plural.additional", "recompile")
                    .contains(diagnostic.code().substring(prefix.length()))) return category;
        }
        return null;
    }
}
