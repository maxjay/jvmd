import assert from "node:assert/strict";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { Framing, encode, type Message } from "../../shim/src/transport.ts";

type Measurement<T> = {
  result: T;
  metrics: {
    ms: number;
    rssBeforeKb: number;
    rssAfterKb: number;
    rssDeltaKb: number;
  };
};

class LspClient {
  private nextId = 0;
  private pending = new Map<number, { resolve: (value: any) => void; reject: (error: Error) => void }>();
  private notifications: Message[] = [];

  constructor(readonly child: ChildProcessWithoutNullStreams) {
    const framing = new Framing("headers", message => this.receive(message));
    child.stdout.on("data", chunk => framing.push(chunk));
    child.on("exit", code => {
      const error = new Error("Language server exited: " + code);
      for (const pending of this.pending.values()) pending.reject(error);
      this.pending.clear();
    });
  }

  private receive(message: Message) {
    if (typeof message.id === "number" && this.pending.has(message.id)) {
      const pending = this.pending.get(message.id)!;
      this.pending.delete(message.id);
      if (message.error) pending.reject(new Error(message.error.message));
      else pending.resolve(message.result);
      return;
    }

    if (message.method && message.id !== undefined) {
      let result: any = null;
      if (message.method === "workspace/configuration") {
        result = (message.params?.items ?? []).map(() => ({}));
      } else if (message.method === "workspace/applyEdit") {
        result = { applied: false };
      }
      this.send({ jsonrpc: "2.0", id: message.id, result });
      return;
    }

    if (message.method) this.notifications.push(message);
  }

  private send(message: Message) {
    this.child.stdin.write(encode(message));
  }

  request<T = any>(method: string, params: any = {}): Promise<T> {
    const id = ++this.nextId;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.send({ jsonrpc: "2.0", id, method, params });
    });
  }

  notify(method: string, params: any = {}) {
    this.send({ jsonrpc: "2.0", method, params });
  }

  async waitFor(method: string, predicate: (params: any) => boolean, timeoutMs = 120_000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const index = this.notifications.findIndex(message => message.method === method && predicate(message.params));
      if (index >= 0) {
        this.notifications.splice(index, 1);
        return;
      }
      await new Promise(resolve => setTimeout(resolve, 25));
    }
    throw new Error("Timed out waiting for " + method);
  }
}

export abstract class LspScenarioHarness {
  private static client: LspClient;
  private static fixtureRoot: string;
  private static mode: "record" | "compare";
  private static openDocuments = new Map<string, number>();

  abstract readonly name: string;
  protected abstract scenario(): Promise<unknown>;

  static async beforeAll() {
    this.fixtureRoot = path.resolve(process.env.FIXTURE_ROOT ?? "");
    assert(this.fixtureRoot, "FIXTURE_ROOT is required");
    this.mode = process.env.MODE === "record" ? "record" : "compare";

    const [command, args, env] = serverCommand(this.fixtureRoot);
    this.client = new LspClient(spawn(command, args, {
      cwd: process.cwd(),
      env: { ...process.env, ...env },
      stdio: ["pipe", "pipe", "inherit"],
    }));

    const rootUri = pathToFileURL(this.fixtureRoot).href;
    await this.client.request("initialize", {
      processId: process.pid,
      rootUri,
      workspaceFolders: [{ uri: rootUri, name: "apache-maven" }],
      capabilities: {
        workspace: { configuration: true },
        textDocument: { completion: { completionItem: { snippetSupport: false } } },
      },
    });
    this.client.notify("initialized", {});

    if ((process.env.SERVER ?? "jvmd") === "jdtls") {
      await this.client.waitFor("language/status", params => params?.type === "ServiceReady");
    }
  }

  static async afterAll() {
    const descendants = processTree(this.client.child.pid!);
    try {
      await this.client.request("shutdown");
      this.client.notify("exit");
    } finally {
      for (const pid of descendants.reverse()) {
        try { process.kill(pid, "SIGTERM"); } catch {}
      }
    }
  }

  async execute() {
    try {
      const actual = await this.scenario();
      this.recordOrCompare(actual);
    } finally {
      await this.closeDocuments();
    }
  }

  protected async open(relativePath: string) {
    const file = path.resolve(LspScenarioHarness.fixtureRoot, relativePath);
    const uri = pathToFileURL(file).href;
    const text = readFileSync(file, "utf8");
    LspScenarioHarness.openDocuments.set(uri, 1);
    LspScenarioHarness.client.notify("textDocument/didOpen", {
      textDocument: { uri, languageId: "java", version: 1, text },
    });
    return { uri, text };
  }

  protected change(uri: string, text: string) {
    const version = (LspScenarioHarness.openDocuments.get(uri) ?? 1) + 1;
    LspScenarioHarness.openDocuments.set(uri, version);
    LspScenarioHarness.client.notify("textDocument/didChange", {
      textDocument: { uri, version },
      contentChanges: [{ text }],
    });
  }

  protected request<T = any>(method: string, params: any) {
    return LspScenarioHarness.client.request<T>(method, params);
  }

  protected async measure<T, U = T>(request: () => Promise<T>, normalise: (value: T) => U = value => value as unknown as U): Promise<Measurement<U>> {
    const pid = LspScenarioHarness.client.child.pid!;
    const rssBeforeKb = processTreeRssKb(pid);
    const started = performance.now();
    const raw = await request();
    const ms = performance.now() - started;
    const rssAfterKb = processTreeRssKb(pid);
    return {
      result: normalise(raw),
      metrics: { ms, rssBeforeKb, rssAfterKb, rssDeltaKb: rssAfterKb - rssBeforeKb },
    };
  }

  private async closeDocuments() {
    for (const uri of LspScenarioHarness.openDocuments.keys()) {
      LspScenarioHarness.client.notify("textDocument/didClose", { textDocument: { uri } });
    }
    LspScenarioHarness.openDocuments.clear();
  }

  private recordOrCompare(actual: unknown) {
    const expectedDir = path.resolve("benchmarks/lsp-scenarios/expected");
    const file = path.join(expectedDir, this.name + ".json");

    if (LspScenarioHarness.mode === "record") {
      mkdirSync(expectedDir, { recursive: true });
      writeFileSync(file, JSON.stringify(actual, null, 2) + "\n");
      console.log("recorded", this.name, file);
      return;
    }

    const expected = JSON.parse(readFileSync(file, "utf8"));
    assert.deepEqual(withoutMetrics(actual), withoutMetrics(expected), this.name + " differs from JDTLS");
    printMetrics(this.name, expected, actual);
  }
}

function withoutMetrics(value: any): any {
  if (Array.isArray(value)) return value.map(withoutMetrics);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(
    Object.entries(value)
      .filter(([key]) => key !== "metrics")
      .map(([key, item]) => [key, withoutMetrics(item)]),
  );
}

function printMetrics(name: string, expected: any, actual: any) {
  const rows = Object.keys(actual)
    .filter(key => actual[key]?.metrics && expected[key]?.metrics)
    .map(key => ({
      scenario: name,
      case: key,
      jdtlsMs: expected[key].metrics.ms.toFixed(2),
      jvmdMs: actual[key].metrics.ms.toFixed(2),
      jdtlsRssDeltaKb: expected[key].metrics.rssDeltaKb,
      jvmdRssDeltaKb: actual[key].metrics.rssDeltaKb,
    }));
  if (rows.length) console.table(rows);
}

function serverCommand(fixtureRoot: string): [string, string[], NodeJS.ProcessEnv] {
  if ((process.env.SERVER ?? "jvmd") === "jvmd") {
    const state = path.resolve("benchmarks/lsp-scenarios/.state/jvmd");
    rmSync(state, { recursive: true, force: true });
    mkdirSync(state, { recursive: true });
    return [
      path.resolve("jvmd-dist/target/image/bin/jvmd-lsp"),
      ["--root", fixtureRoot],
      {
        JVMD_SOCKET: path.join(state, "jvmd.sock"),
        XDG_CACHE_HOME: path.join(state, "cache"),
        JVMD_CONFIG: path.join(state, "config.json"),
      },
    ];
  }

  const home = path.resolve(process.env.JDTLS_HOME ?? "");
  assert(home, "JDTLS_HOME is required when SERVER=jdtls");
  const launcher = readdirSync(path.join(home, "plugins")).find(name => name.startsWith("org.eclipse.equinox.launcher_") && name.endsWith(".jar"));
  assert(launcher, "JDTLS launcher not found");
  const state = path.resolve("benchmarks/lsp-scenarios/.state/jdtls");
  rmSync(state, { recursive: true, force: true });
  mkdirSync(state, { recursive: true });
  return [
    process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin/java") : "java",
    [
      "-Declipse.application=org.eclipse.jdt.ls.core.id1",
      "-Dosgi.bundles.defaultStartLevel=4",
      "-Declipse.product=org.eclipse.jdt.ls.core.product",
      "-jar", path.join(home, "plugins", launcher),
      "-configuration", path.join(home, "config_linux"),
      "-data", state,
    ],
    {},
  ];
}

function processTree(rootPid: number) {
  const pids = readdirSync("/proc").filter(name => /^\d+$/.test(name)).map(Number);
  const children = new Map<number, number[]>();
  for (const pid of pids) {
    try {
      const status = readFileSync("/proc/" + pid + "/status", "utf8");
      const parent = Number(status.match(/^PPid:\s+(\d+)/m)?.[1]);
      if (!children.has(parent)) children.set(parent, []);
      children.get(parent)!.push(pid);
    } catch {}
  }

  const result: number[] = [];
  const visit = (pid: number) => {
    for (const child of children.get(pid) ?? []) {
      result.push(child);
      visit(child);
    }
  };
  visit(rootPid);
  return result;
}

function processTreeRssKb(rootPid: number) {
  let total = 0;
  for (const pid of [rootPid, ...processTree(rootPid)]) {
    try {
      const status = readFileSync("/proc/" + pid + "/status", "utf8");
      total += Number(status.match(/^VmRSS:\s+(\d+)\s+kB/m)?.[1] ?? 0);
    } catch {}
  }
  return total;
}
