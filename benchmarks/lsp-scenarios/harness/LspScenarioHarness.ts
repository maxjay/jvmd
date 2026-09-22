import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { appendFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import {
  createMessageConnection,
  StreamMessageReader,
  StreamMessageWriter,
  type MessageConnection,
} from "vscode-jsonrpc/node";

type Memory = {
  serverKb: number;
  adapterKb: number;
  totalKb: number;
};

export type Measurement<T> = {
  result: T;
  metrics: {
    latencyMs: number;
    memory: {
      before: Memory;
      after: Memory;
      peak: Memory;
    };
  };
};

type RunningServer = {
  connection: MessageConnection;
  server: ChildProcess;
  adapter?: ChildProcess;
};

export abstract class LspScenarioHarness {
  private static fixtureRoot: string;
  private static mode: "record" | "compare";
  private static running: RunningServer;
  private static openDocuments = new Map<string, number>();

  abstract readonly name: string;
  protected abstract scenario(): Promise<Record<string, Measurement<unknown>>>;

  static async beforeAll() {
    assert(process.env.FIXTURE_ROOT, "FIXTURE_ROOT is required");
    this.fixtureRoot = path.resolve(process.env.FIXTURE_ROOT);
    this.mode = process.env.MODE === "record" ? "record" : "compare";
    this.running = await startServer(this.fixtureRoot);

    const ready = new Promise<void>(resolve => {
      this.running.connection.onNotification("language/status", (params: any) => {
        if (params?.type === "ServiceReady") resolve();
      });
    });

    this.running.connection.onRequest("workspace/configuration", (params: any) =>
      (params?.items ?? []).map(() => ({})),
    );
    this.running.connection.onRequest("client/registerCapability", () => null);
    this.running.connection.onRequest("client/unregisterCapability", () => null);
    this.running.connection.onRequest("window/workDoneProgress/create", () => null);
    this.running.connection.onRequest("workspace/applyEdit", () => ({ applied: false }));
    this.running.connection.listen();

    const rootUri = pathToFileURL(this.fixtureRoot).href;
    await this.running.connection.sendRequest("initialize", {
      processId: process.pid,
      rootUri,
      workspaceFolders: [{ uri: rootUri, name: "apache-maven" }],
      capabilities: {
        workspace: { configuration: true },
        textDocument: {
          completion: { completionItem: { snippetSupport: false } },
        },
      },
    });
    this.running.connection.sendNotification("initialized", {});

    if ((process.env.SERVER ?? "jvmd") === "jdtls") await ready;
  }

  static async afterAll() {
    try {
      await this.running.connection.sendRequest("shutdown");
      this.running.connection.sendNotification("exit");
    } finally {
      this.running.connection.dispose();
      stop(this.running.adapter);
      stop(this.running.server);
    }
  }

  async execute() {
    try {
      const actual = await this.scenario();
      this.verify(actual);
    } finally {
      this.closeDocuments();
    }
  }

  protected open(relativePath: string) {
    const file = path.resolve(LspScenarioHarness.fixtureRoot, relativePath);
    const uri = pathToFileURL(file).href;
    const text = readFileSync(file, "utf8");

    LspScenarioHarness.openDocuments.set(uri, 1);
    LspScenarioHarness.running.connection.sendNotification("textDocument/didOpen", {
      textDocument: { uri, languageId: "java", version: 1, text },
    });

    return { uri, text };
  }

  protected change(uri: string, text: string) {
    const version = (LspScenarioHarness.openDocuments.get(uri) ?? 1) + 1;
    LspScenarioHarness.openDocuments.set(uri, version);

    LspScenarioHarness.running.connection.sendNotification("textDocument/didChange", {
      textDocument: { uri, version },
      contentChanges: [{ text }],
    });
  }

  protected request<T>(method: string, params: unknown): Promise<T> {
    return LspScenarioHarness.running.connection.sendRequest(method, params);
  }

  protected async measure<T, U>(
    request: () => Promise<T>,
    normalise: (value: T) => U,
  ): Promise<Measurement<U>> {
    const running = LspScenarioHarness.running;
    const before = memory(running);
    let peak = before;

    const sampler = setInterval(() => {
      peak = maxMemory(peak, memory(running));
    }, 20);

    const started = performance.now();
    try {
      const raw = await request();
      const latencyMs = performance.now() - started;
      const after = memory(running);
      peak = maxMemory(peak, after);

      return {
        result: normalise(raw),
        metrics: {
          latencyMs,
          memory: { before, after, peak },
        },
      };
    } finally {
      clearInterval(sampler);
    }
  }

  private closeDocuments() {
    for (const uri of LspScenarioHarness.openDocuments.keys()) {
      LspScenarioHarness.running.connection.sendNotification("textDocument/didClose", {
        textDocument: { uri },
      });
    }
    LspScenarioHarness.openDocuments.clear();
  }

  private verify(actual: Record<string, Measurement<unknown>>) {
    const expectedDir = path.resolve("benchmarks/lsp-scenarios/expected");
    const file = path.join(expectedDir, this.id + ".json");

    if (LspScenarioHarness.mode === "record") {
      mkdirSync(expectedDir, { recursive: true });
      writeFileSync(file, JSON.stringify(actual, null, 2) + "\n");
      console.log("recorded", this.id, this.name, file);
      writeSummary(this.id, this.name, actual);
      return;
    }

    const expected: Record<string, Measurement<unknown>> =
      JSON.parse(readFileSync(file, "utf8"));

    assert.deepEqual(Object.keys(actual).sort(), Object.keys(expected).sort());
    for (const [name, measurement] of Object.entries(actual)) {
      assert.deepEqual(
        measurement.result,
        expected[name].result,
        this.id + "/" + name + " differs from JDTLS",
      );
    }

    console.table(Object.keys(actual).map(name => ({
      case: name,
      jdtlsMs: expected[name].metrics.latencyMs.toFixed(2),
      jvmdMs: actual[name].metrics.latencyMs.toFixed(2),
      jdtlsPeakMb: mb(expected[name].metrics.memory.peak.totalKb),
      jvmdPeakMb: mb(actual[name].metrics.memory.peak.totalKb),
    })));
    writeSummary(this.id, this.name, actual, expected);
  }
}

async function startServer(root: string): Promise<RunningServer> {
  return (process.env.SERVER ?? "jvmd") === "jdtls"
    ? startJdtls()
    : startJvmd(root);
}

async function startJvmd(root: string): Promise<RunningServer> {
  const state = path.resolve("benchmarks/lsp-scenarios/.state/jvmd");
  rmSync(state, { recursive: true, force: true });
  mkdirSync(state, { recursive: true });

  const socket = path.join(state, "jvmd.sock");
  const image = path.resolve("jvmd-dist/target/image");
  const env = {
    ...process.env,
    JVMD_SOCKET: socket,
    XDG_CACHE_HOME: path.join(state, "cache"),
    JVMD_CONFIG: path.join(state, "config.json"),
  };

  writeFileSync(path.join(state, "config.json"), "{}\n");
  const server = spawn(path.join(image, "bin/java"), [
    "-Djvmd.socket=" + socket,
    "-Djvmd.state=" + path.join(state, "state"),
    "-Djvmd.config=" + path.join(state, "config.json"),
    "-cp", path.join(image, "lib/jvmd/*"),
    "dev.jvmd.dist.Application",
  ], {
    env,
    stdio: ["ignore", "ignore", "inherit"],
  });

  await waitFor(() => existsSync(socket), "JVMD socket");

  const adapter = spawn(
    path.join(image, "bin/jvmd-lsp"),
    ["--root", root, "--socket", socket],
    { env, stdio: ["pipe", "pipe", "inherit"] },
  );

  return {
    connection: createMessageConnection(
      new StreamMessageReader(adapter.stdout!),
      new StreamMessageWriter(adapter.stdin!),
    ),
    server,
    adapter,
  };
}

function startJdtls(): RunningServer {
  assert(process.env.JDTLS_HOME, "JDTLS_HOME is required when SERVER=jdtls");
  const home = path.resolve(process.env.JDTLS_HOME);
  const launcher = readdirSync(path.join(home, "plugins"))
    .find(name => name.startsWith("org.eclipse.equinox.launcher_") && name.endsWith(".jar"));
  assert(launcher, "JDTLS launcher not found");

  const state = path.resolve("benchmarks/lsp-scenarios/.state/jdtls");
  rmSync(state, { recursive: true, force: true });
  mkdirSync(state, { recursive: true });

  const java = process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, "bin/java")
    : "java";

  const server = spawn(java, [
    "-Declipse.application=org.eclipse.jdt.ls.core.id1",
    "-Dosgi.bundles.defaultStartLevel=4",
    "-Declipse.product=org.eclipse.jdt.ls.core.product",
    "-jar", path.join(home, "plugins", launcher),
    "-configuration", path.join(home, "config_linux"),
    "-data", state,
  ], {
    stdio: ["pipe", "pipe", "inherit"],
  });

  return {
    connection: createMessageConnection(
      new StreamMessageReader(server.stdout!),
      new StreamMessageWriter(server.stdin!),
    ),
    server,
  };
}

function memory(running: RunningServer): Memory {
  const serverKb = rssKb(running.server.pid);
  const adapterKb = rssKb(running.adapter?.pid);
  return { serverKb, adapterKb, totalKb: serverKb + adapterKb };
}

function rssKb(pid?: number) {
  if (!pid) return 0;
  try {
    const status = readFileSync("/proc/" + pid + "/status", "utf8");
    return Number(status.match(/^VmRSS:\s+(\d+)\s+kB/m)?.[1] ?? 0);
  } catch {
    return 0;
  }
}

function maxMemory(a: Memory, b: Memory): Memory {
  return {
    serverKb: Math.max(a.serverKb, b.serverKb),
    adapterKb: Math.max(a.adapterKb, b.adapterKb),
    totalKb: Math.max(a.totalKb, b.totalKb),
  };
}

async function waitFor(predicate: () => boolean, description: string) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise(resolve => setTimeout(resolve, 25));
  }
  throw new Error("Timed out waiting for " + description);
}

function stop(process?: ChildProcess) {
  if (!process || process.killed) return;
  process.kill("SIGTERM");
}

function mb(kb: number) {
  return (kb / 1024).toFixed(1);
}


function writeSummary(
  id: string,
  name: string,
  actual: Record<string, Measurement<unknown>>,
  expected?: Record<string, Measurement<unknown>>,
) {
  const file = process.env.GITHUB_STEP_SUMMARY;
  if (!file) return;

  const lines = [
    `## ${id} — ${name}`,
    "",
    expected
      ? "| Case | Correct | JDTLS ms | JVMD ms | JDTLS peak MB | JVMD peak MB |"
      : "| Case | JDTLS ms | JDTLS peak MB |",
    expected
      ? "| --- | --- | ---: | ---: | ---: | ---: |"
      : "| --- | ---: | ---: |",
  ];

  for (const caseName of Object.keys(actual)) {
    const current = actual[caseName];
    if (expected) {
      const baseline = expected[caseName];
      lines.push(
        `| ${caseName} | ✅ | ${baseline.metrics.latencyMs.toFixed(2)} | ${current.metrics.latencyMs.toFixed(2)} | ${mb(baseline.metrics.memory.peak.totalKb)} | ${mb(current.metrics.memory.peak.totalKb)} |`,
      );
    } else {
      lines.push(
        `| ${caseName} | ${current.metrics.latencyMs.toFixed(2)} | ${mb(current.metrics.memory.peak.totalKb)} |`,
      );
    }
  }

  lines.push("");
  appendFileSync(file, lines.join("\n") + "\n");
}
