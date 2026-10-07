package dev.jvmd.index.layer.local;

import java.util.List;

/** Native descriptor diagnostics owned by the committed header generation, separate from successful descriptor CF identity. */
public record HeaderDiagnostics(List<ResultRecord.Diagnostic> messages) {
    public HeaderDiagnostics { messages = List.copyOf(messages); }
    public boolean failed() { return messages.stream().anyMatch(d -> d.kind() == 0); }
    public byte[] encode() { return new ResultRecord(!failed(), List.of(), messages).encode(); }
    public static HeaderDiagnostics decode(byte[] bytes, int width) {
        var result = ResultRecord.decode(bytes, width);
        var diagnostics = new HeaderDiagnostics(result.diagnostics());
        if (!result.classFiles().isEmpty() || result.attributed() == diagnostics.failed())
            throw new IllegalArgumentException("Invalid header diagnostics record");
        return diagnostics;
    }
}
