# jvmd stdio adapters

For custom clients, including an OpenCode fork or VS Code extension, see the [integration guide](../docs/integration.md) and [tool schemas](../docs/api/mcp-tools.json).

Use Node.js 24.21.0 or newer. No npm installation or compilation is required.

After building and training the daemon image:

```sh
jvmd-dist/target/image/bin/jvmd-mcp --root /absolute/path/to/project
```

The command speaks MCP JSON-RPC, one JSON message per line. Register it as the MCP server
command in the client, with `--root` and the workspace directory as arguments.
The client receives exactly fourteen tools. Workspace setup and socket management stay inside
the adapter.

The adapter connects to the machine's Unix socket and starts the resident daemon if it is absent.
For a source checkout, run `node shim/src/main.ts --mcp --root /project`;
set `JVMD_LAUNCHER` to the built daemon launcher, or use `--launcher`.
`--socket` overrides the socket for both connection and automatic launch.

Responses are tiered envelopes in MCP text content. If `truncated` is true, call the same
tool with the same arguments and its returned `cursor`. Response-budget cursors replay
the completed operation; they do not repeat edits, builds or debug actions. Budget pages expire
after 60 seconds. The `_jvmd_segments` metadata describes fragments of large strings,
arrays or objects by JSON pointer and half-open offsets. Array offsets count elements; string
offsets count UTF-16 units.

Edits return live diagnostics for touched declarations. Finish an edit with
`diagnostics({verified:true})` and inspect the build result before declaring it complete.

```sh
cd shim
node --test test/*.test.ts
```
