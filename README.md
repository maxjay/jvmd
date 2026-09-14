undefined

The same image includes an editor adapter:

~~~sh
jvmd-dist/target/image/bin/jvmd-lsp --root /path/to/workspace
~~~

Configure it as the editor's Java language server over stdio. It advertises UTF-16 incremental synchronization, hover, definition, references, semantic rename with preparation, hierarchical document symbols, completion, signature help, and full semantic tokens. The editor owns open documents; queries see unsaved buffers across files. Diagnostics debounce for 200 ms, and results for superseded versions are discarded. Rename returns a versioned WorkspaceEdit, including a file operation when the client advertises that capability.

The adapter shares the daemon and workspace sessions with MCP. Closing it releases its open documents without shutting down a shared session. For very large references, outlines or token sets, clients can provide partialResultToken; each result/progress frame stays below 64 KiB. Completion marks incomplete pages using the native LSP flag. Diagnostics that exceed the display budget include an explicit omitted-count diagnostic. Save buffers before requesting a verified build or launching compiled code.

### Compiled evaluation

Use the existing `debug` tool with `op: "eval"` and `args: {"tier": 2, "expression": "items.stream().mapToInt(Item::size).sum()"}` to compile an expression in the stopped frame's declaring source context. Tier 1 remains the default. Tier 2 supports Java generic inference and lambdas, accesses the declaring instance's private members, and copies successful local assignments back into the frame. It requires debug local-variable metadata and the declaring source. Each invocation invalidates earlier frame references; request frames again afterward.

Compiled plans are bounded to 32 entries and 32 MiB per run, expire after five minutes, and clear on hot swap, stop, or restart. Temporary target references have a 60-second pin limit. Returned object handles keep their ordinary TTL. A target exception is reported with `threw: true` and an inspectable exception handle; target side effects remain, and local assignments from a throwing expression are not copied back. Anonymous/local class declarations in expressions and closed named-module packages report their limitation explicitly.

For framework cache reloads, optionally configure `hotswap_agent` with the path to a HotswapAgent premain jar together with `jbr_home`. The target starts with JBR's external-agent mode; `status` reports `framework_reload: hotswap_agent`. It remains disabled when no agent is configured. The JavaBeans reload fixture exercises cache invalidation; support for other frameworks depends on the installed agent's plugins and their versions.
