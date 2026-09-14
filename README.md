undefined

The same image includes an editor adapter:

~~~sh
jvmd-dist/target/image/bin/jvmd-lsp --root /path/to/workspace
~~~

Configure it as the editor's Java language server over stdio. It advertises UTF-16 incremental synchronization, hover, definition, references, semantic rename with preparation, hierarchical document symbols, completion, signature help, and full semantic tokens. The editor owns open documents; queries see unsaved buffers across files. Diagnostics debounce for 200 ms, and results for superseded versions are discarded. Rename returns a versioned WorkspaceEdit, including a file operation when the client advertises that capability.

The adapter shares the daemon and workspace sessions with MCP. Closing it releases its open documents without shutting down a shared session. For very large references, outlines or token sets, clients can provide partialResultToken; each result/progress frame stays below 64 KiB. Completion marks incomplete pages using the native LSP flag. Diagnostics that exceed the display budget include an explicit omitted-count diagnostic. Save buffers before requesting a verified build or launching compiled code.
