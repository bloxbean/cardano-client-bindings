#!/usr/bin/env node
// Stamps a version into wrappers/js/package.json: the package version and every
// optionalDependencies pin (the platform packages are always published at the same version).
// CI runs this with the version read from gradle.properties (the single source of truth),
// so versions are never hand-maintained in package.json. Mirrors bloxbean/yano's
// npm/scripts/set-package-version.mjs.
//
// Usage: node set-package-version.mjs <version>
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const version = process.argv[2];
if (!version) {
  console.error("usage: set-package-version.mjs <version>");
  process.exit(1);
}

const pkgPath = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "package.json");
const pkg = JSON.parse(fs.readFileSync(pkgPath, "utf8"));
pkg.version = version;
for (const name of Object.keys(pkg.optionalDependencies ?? {})) {
  pkg.optionalDependencies[name] = version;
}
fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + "\n");
console.log(`package.json stamped to ${version} (version + ${Object.keys(pkg.optionalDependencies ?? {}).length} optionalDependencies pins)`);
