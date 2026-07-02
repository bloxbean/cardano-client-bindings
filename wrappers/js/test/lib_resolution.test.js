// Unit tests for how CclBridge locates the native library. These exercise only the path-resolution
// logic (resolveLibFile is a pure function), so they run without loading libccl.
import { test, expect, afterEach } from "bun:test";
import path from "path";
import os from "os";
import { resolveLibFile } from "../src/index.js";

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
  // Either the bundled copy (if a lib was staged into libs/) or the bare filename.
  expect(resolved === NAME || resolved.endsWith(path.join("libs", NAME))).toBe(true);
});
