import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { appendFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { isDeepStrictEqual } from "node:util";
import net from "node:net";
import { RpcClient } from "../../../shim/src/transport.ts";
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

  abstract readonly id: string;
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

    const cases = [...new Set([...Object.keys(expected), ...Object.keys(actual)])];
    const correctness = Object.fromEntries(
      cases.map(name => [
        name,
        expected[name] !== undefined &&
          actual[name] !== undefined &&
          isDeepStrictEqual(actual[name].result, expected[name].result),
      ]),
    );

    console.table(cases.map(name => ({
      case: name,
      correct: correctness[name] ? "yes" : "NO",
      jdtlsMs: expected[name] ? expected[name].metrics.latencyMs.toFixed(2) : "-",
      jvmdMs: actual[name] ? actual[name].metrics.latencyMs.toFixed(2) : "-",
      jdtlsPeakMb: expected[name] ? mb(expected[name].metrics.memory.peak.totalKb) : "-",
      jvmdPeakMb: actual[name] ? mb(actual[name].metrics.memory.peak.totalKb) : "-",
    })));

    for (const caseName of cases) {
      if (!correctness[caseName]) {
        console.log(formatMismatch(caseName, expected[caseName], actual[caseName]));
      }
    }

    writeSummary(this.id, this.name, actual, expected, correctness);
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
  await waitForJvmdIndex(socket);

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

async function waitForJvmdIndex(socketPath: string) {
  const socket = await new Promise<net.Socket>((resolve, reject) => {
    const connection = net.createConnection(socketPath);
    connection.once("connect", () => resolve(connection));
    connection.once("error", reject);
  });

  const client = new RpcClient(socket);
  const deadline = Date.now() + 120_000;

  try {
    while (Date.now() < deadline) {
      const status = await client.call("daemon.status");
      const index = status?.result?.index;

      if (
        index?.phase === "ready" &&
        Number(index?.timings?.scans ?? 0) >= 1
      ) {
        return;
      }

      await new Promise(resolve => setTimeout(resolve, 50));
    }
  } finally {
    client.close();
  }

  throw new Error("Timed out waiting for JVMD repository index");
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

function formatMismatch(
  caseName: string,
  expected?: Measurement<unknown>,
  actual?: Measurement<unknown>,
) {
  const lines = [`\n=== mismatch: ${caseName} ===`];

  if (!expected) {
    lines.push("JDTLS case: missing");
    lines.push("JVMD:", JSON.stringify(actual?.result, null, 2));
    return lines.join("\n");
  }

  if (!actual) {
    lines.push("JVMD case: missing");
    lines.push("JDTLS:", JSON.stringify(expected.result, null, 2));
    return lines.join("\n");
  }

  if (Array.isArray(expected.result) && Array.isArray(actual.result)) {
    const diff = arrayDiff(expected.result, actual.result);
    lines.push(`JDTLS items: ${expected.result.length}`);
    lines.push(`JVMD items: ${actual.result.length}`);
    lines.push(`Missing from JVMD: ${diff.missing.length}`);
    lines.push(JSON.stringify(diff.missing.slice(0, 20), null, 2));
    lines.push(`Extra in JVMD: ${diff.extra.length}`);
    lines.push(JSON.stringify(diff.extra.slice(0, 20), null, 2));
    return lines.join("\n");
  }

  lines.push("JDTLS:", JSON.stringify(expected.result, null, 2));
  lines.push("JVMD:", JSON.stringify(actual.result, null, 2));
  return lines.join("\n");
}

function arrayDiff(expected: unknown[], actual: unknown[]) {
  const actualCounts = counts(actual);
  const missing: unknown[] = [];
  for (const item of expected) {
    const key = stableKey(item);
    const remaining = actualCounts.get(key) ?? 0;
    if (remaining > 0) actualCounts.set(key, remaining - 1);
    else missing.push(item);
  }

  const expectedCounts = counts(expected);
  const extra: unknown[] = [];
  for (const item of actual) {
    const key = stableKey(item);
    const remaining = expectedCounts.get(key) ?? 0;
    if (remaining > 0) expectedCounts.set(key, remaining - 1);
    else extra.push(item);
  }

  return { missing, extra };
}

function counts(values: unknown[]) {
  const result = new Map<string, number>();
  for (const value of values) {
    const key = stableKey(value);
    result.set(key, (result.get(key) ?? 0) + 1);
  }
  return result;
}

function stableKey(value: unknown) {
  return JSON.stringify(value);
}

function writeSummary(
  id: string,
  name: string,
  actual: Record<string, Measurement<unknown>>,
  expected?: Record<string, Measurement<unknown>>,
  correctness: Record<string, boolean> = {},
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

  const cases = expected
    ? [...new Set([...Object.keys(expected), ...Object.keys(actual)])]
    : Object.keys(actual);

  for (const caseName of cases) {
    const current = actual[caseName];
    if (expected) {
      const baseline = expected[caseName];
      lines.push(
        `| ${caseName} | ${correctness[caseName] ? "✅" : "❌"} | ${baseline ? baseline.metrics.latencyMs.toFixed(2) : "-"} | ${current ? current.metrics.latencyMs.toFixed(2) : "-"} | ${baseline ? mb(baseline.metrics.memory.peak.totalKb) : "-"} | ${current ? mb(current.metrics.memory.peak.totalKb) : "-"} |`,
      );
    } else if (current) {
      lines.push(
        `| ${caseName} | ${current.metrics.latencyMs.toFixed(2)} | ${mb(current.metrics.memory.peak.totalKb)} |`,
      );
    }
  }

  if (expected) {
    const mismatches = cases.filter(caseName => !correctness[caseName]);
    if (mismatches.length) {
      lines.push("", "### Correctness mismatches", "");
      for (const caseName of mismatches) {
        const baseline = expected[caseName];
        const current = actual[caseName];
        lines.push(`#### ${caseName}`, "");

        if (!baseline) {
          lines.push("JDTLS case is missing.", "");
          continue;
        }

        if (!current) {
          lines.push("JVMD case is missing.", "");
          lines.push("JDTLS result:", "", "    " + JSON.stringify(baseline.result, null, 2).replace(/\n/g, "\n    "), "");
          continue;
        }

        if (Array.isArray(baseline.result) && Array.isArray(current.result)) {
          const diff = arrayDiff(baseline.result, current.result);
          lines.push(
            `- JDTLS items: **${baseline.result.length}**`,
            `- JVMD items: **${current.result.length}**`,
            `- Missing from JVMD: **${diff.missing.length}**`,
            `- Extra in JVMD: **${diff.extra.length}**`,
            "",
          );
          if (diff.missing.length) {
            lines.push("Missing from JVMD (first 20):", "", "    " + JSON.stringify(diff.missing.slice(0, 20), null, 2).replace(/\n/g, "\n    "), "");
          }
          if (diff.extra.length) {
            lines.push("Extra in JVMD (first 20):", "", "    " + JSON.stringify(diff.extra.slice(0, 20), null, 2).replace(/\n/g, "\n    "), "");
          }
        } else {
          lines.push(
            "JDTLS result:",
            "",
            "    " + JSON.stringify(baseline.result, null, 2).replace(/\n/g, "\n    "),
            "",
            "JVMD result:",
            "",
            "    " + JSON.stringify(current.result, null, 2).replace(/\n/g, "\n    "),
            "",
          );
        }
      }
    }
  }

  lines.push("");
  appendFileSync(file, lines.join("\n") + "\n");
}
