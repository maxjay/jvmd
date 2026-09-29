'use strict';
// This is a launcher smoke check, not a benchmark operation or warmup.
const assert = require('node:assert/strict');
const { realpathSync } = require('node:fs');
const { execFileSync } = require('node:child_process');

const [expectedExecutable, expectedVersion] = process.argv.slice(2);
assert.ok(expectedExecutable && expectedVersion, 'expected Node path and version are required');
assert.notEqual(process.getuid(), 0, 'delegated command must not run as root');
assert.equal(realpathSync(process.execPath), realpathSync(expectedExecutable), 'delegation changed Node executable');
assert.equal(process.version, expectedVersion, 'delegation changed Node version');
const child = JSON.parse(execFileSync('node', ['-p',
  'JSON.stringify({executable:process.execPath,version:process.version,uid:process.getuid()})'],
  { encoding: 'utf8', timeout: 10000 }));
assert.equal(realpathSync(child.executable), realpathSync(process.execPath), 'child PATH changed Node executable');
assert.equal(child.version, process.version, 'child PATH changed Node version');
assert.equal(child.uid, process.getuid(), 'child changed runner identity');
console.log(JSON.stringify({ schemaVersion: 1, executable: process.execPath, version: process.version,
  uid: process.getuid(), child, outcome: 'pass', scope: 'delegation runtime smoke only' }));
