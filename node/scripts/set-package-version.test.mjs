import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import process from "node:process";
import test from "node:test";
import { fileURLToPath, URL } from "node:url";

import { setPackageVersion } from "./set-package-version.mjs";

function fixture() {
  const root = mkdtempSync(join(tmpdir(), "elasticache-version-"));
  mkdirSync(join(root, "src"));
  writeFileSync(join(root, "package.json"), '{"name":"example","version":"0.0.1"}\n');
  writeFileSync(
    join(root, "package-lock.json"),
    '{"name":"example","version":"0.0.1","packages":{"":{"version":"0.0.1"}}}\n',
  );
  writeFileSync(join(root, "src/index.ts"), 'export const version = "0.0.1";\n');
  return root;
}

test("stamps npm and runtime metadata with the release version", (context) => {
  const root = fixture();
  context.after(() => rmSync(root, { recursive: true, force: true }));

  setPackageVersion("1.2.3-rc.4", root);

  assert.equal(JSON.parse(readFileSync(join(root, "package.json"))).version, "1.2.3-rc.4");
  const lock = JSON.parse(readFileSync(join(root, "package-lock.json")));
  assert.equal(lock.version, "1.2.3-rc.4");
  assert.equal(lock.packages[""].version, "1.2.3-rc.4");
  assert.equal(
    readFileSync(join(root, "src/index.ts"), "utf8"),
    'export const version = "1.2.3-rc.4";\n',
  );
});

test("rejects unsupported release versions", () => {
  assert.throws(() => setPackageVersion("1.2", "/unused"), /Unsupported release version/);
});

test("stamps metadata when invoked through a symlink", (context) => {
  const root = fixture();
  context.after(() => rmSync(root, { recursive: true, force: true }));
  const linkedScript = join(root, "set-package-version.mjs");
  symlinkSync(fileURLToPath(new URL("./set-package-version.mjs", import.meta.url)), linkedScript);

  execFileSync(process.execPath, [linkedScript, "1.2.3-rc.4"], { cwd: root });

  assert.equal(JSON.parse(readFileSync(join(root, "package.json"))).version, "1.2.3-rc.4");
});
