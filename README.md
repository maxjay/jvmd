# jvmd

**Java tooling that keeps up.**

Give your coding agent and editor a shared view of your code, dependencies, and running app. jvmd stays running between requests, so the next question starts warm.

[Get started](#get-started) · [Connect](#connect) · [Integration guide](docs/integration.md)

| | What you can do |
| --- | --- |
| **Explore** | Find methods, follow callers, and read docs across your code and dependencies. |
| **Edit** | Rename symbols, replace method bodies, and check changes against a real build. |
| **Debug** | Set breakpoints, inspect objects, evaluate Java, and hot-swap method bodies. |
| **Work across repos** | Navigate Maven 3 and 4 projects together, including local modules you haven't built yet. |

## Get started

Want a ready-to-run download? See [installing prebuilt distributions](docs/install.md) for Linux, macOS, and Windows through WSL 2. Archives bundle Java, Node.js, and the trained cache.

To build from source:

Build from the repository root with **Temurin 25.0.4.1+1**, **Maven 3.8.3 or 3.9.16**, and `JAVA_HOME` set to the full JDK. The adapters also need **Node.js 24.21.0+**; no npm install.

```sh
mvn -B -DskipTests install
bash jvmd-dist/assemble.sh
bash jvmd-dist/train-aot.sh
```

Create `~/.config/jvmd/config.json`, pointing to that full JDK. Set `maven_major` to match your projects.

```json
{
  "jdk_home": "/absolute/path/to/jdk-25.0.4.1+1",
  "maven_major": 3
}
```

## Connect

Add jvmd to your MCP client's server configuration, adjusting both paths:

```json
{
  "mcpServers": {
    "jvmd": {
      "command": "/absolute/path/to/jvmd/jvmd-dist/target/image/bin/jvmd-mcp",
      "args": ["--root", "/absolute/path/to/your-project"]
    }
  }
}
```

The adapter starts the daemon automatically. Multiple clients share it.

<details>
<summary>Use it in an editor</summary>

Set this as your Java language server command over stdio:

```sh
jvmd-dist/target/image/bin/jvmd-lsp --root /absolute/path/to/your-project
```

Completion, hover, go-to-definition, references, rename, and diagnostics work with unsaved files. [Editor details →](docs/integration.md#lsp-client-contract)

</details>

## Put it to work

> Find who calls this method, update its implementation, then verify the build.

An agent can handle that with the built-in tools. These MCP tool-call parameters illustrate the flow using Spring PetClinic:

**Find the callers**

```json
{
  "name": "references",
  "arguments": {"ref": "Owner/getPets()", "direction": "in"}
}
```

**Update the method**

```json
{
  "name": "replace_body",
  "arguments": {
    "ref": "Owner/getPets()",
    "body": "{ return java.util.Objects.requireNonNull(this.pets); }"
  }
}
```

**Verify the build**

```json
{"name": "diagnostics", "arguments": {"verified": true}}
```

Edits return immediate feedback. A successful verified build is the completion check; save editor buffers first. [All 14 tools →](docs/integration.md#agent-tool-contract)

## Go further

- [Open and use a workspace](docs/integration.md#open-and-use-a-workspace)
- [Debugging, hot swap & recordings](docs/integration.md#runtime-and-debugging)
- [Build a client: integration guide & API](docs/integration.md)
- [Dependencies](DEPENDENCIES.md)

## License

Copyright © 2026 Max. jvmd is available under the [MIT License](LICENSE),
including for commercial and workplace use.
