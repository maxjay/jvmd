import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {existsSync,readFileSync} from "node:fs";
import path from "node:path";
import {sha} from "./fixture.ts";

/** Re-enumerate on every snapshot: hashing only the initial list misses files
 * added during collection. NUL framing preserves spaces and newlines in paths. */
export function sourceInventory(root=process.cwd()):Record<string,string>{
  const result=spawnSync("git",["ls-files","-z","--cached","--others","--exclude-standard"],{cwd:root,encoding:"utf8"});
  assert.equal(result.status,0,result.stderr);
  const files=[...new Set(result.stdout.split("\0").filter(Boolean))].sort();
  return Object.fromEntries(files.filter(file=>existsSync(path.join(root,file))).map(file=>[file,sha(readFileSync(path.join(root,file)))]));
}
