// Unit tests for how CclBridge locates the native library. These exercise only the path-resolution
// logic (resolveLibFile is a pure function), so they run without loading libccl.
import { test, expect, afterEach } from "bun:test";
import path from "path";
import os from "os";
import { resolveLibFile, platformSuffix } from "../src/index.js";

const NAME =
  os.platform() === "darwin" ? "libccl.dylib" : os.platform() === "win32" ? "libccl.dll" : "libccl.so";

const savedEnv = process.env.CCL_LIB_PATH;
afterEach(() => {
  if (savedEnv === undefined) delete process.env.CCL_LIB_PATH;
  else process.env.CCL_LIB_PATH = savedEnv;
});

test("explicit lib path wins over the env var", () => {
  process.env.CCL_LIB_PATH = "/env/dir";
  expect(resolveLibFile("/opt/dir")).toBe(path.join("/opt/dir", NAME));
});

test("CCL_LIB_PATH is used when no explicit path is given", () => {
  process.env.CCL_LIB_PATH = "/env/dir";
  expect(resolveLibFile()).toBe(path.join("/env/dir", NAME));
});

test("falls back to the bare filename when nothing is set or bundled", () => {
  delete process.env.CCL_LIB_PATH;
  const resolved = resolveLibFile();
  // With no lib staged in libs/ and no @bloxbean/cardano-client-lib-<platform> package installed, resolution ends
  // at the bare filename (or the bundled copy if one happens to be staged).
  expect(resolved === NAME || resolved.endsWith(path.join("libs", NAME))).toBe(true);
});

test("platformSuffix matches one of the published per-platform package names", () => {
  const valid = [
    "linux-x86_64",
    "linux-aarch64",
    "macos-aarch64",
    "macos-x86_64",
    "windows-x86_64",
  ];
  expect(valid).toContain(platformSuffix());
});
