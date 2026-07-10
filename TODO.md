# Cardano Client Bindings — TODO

WISHLIST (Satya):
- YAML support for TX building (TxPlan)
- UTxO capture on the client side, callback maybe an issue (e.g. BloxBean) - UTxO selection
- UTxO selection on the client
- Protocol Parameters should be fetched via provider (cost calculation)
- Script Supplier?

A living, categorized backlog of work for Cardano Client Bindings. Cardano Client Bindings compiles
[Cardano Client Lib](https://github.com/bloxbean/cardano-client-lib) into a GraalVM
native shared library and exposes it to **Python, Go, Rust, and JavaScript (Bun)**.
The project is functionally mature (v0.1.0-preview1) but had no roadmap — this file
is the starting point. **It is meant to be extended**: add, re-prioritize, or check
off items freely as the project evolves.

**Priority legend:**
- `P0` — blocks real-world adoption / advertised but missing
- `P1` — important; needed for a solid 1.0
- `P2` — nice-to-have / future polish

**Supported languages today:** Python, Go, Rust, JavaScript (Bun only).
C is test-only (smoke tests in `native-test/`); C headers ship for raw FFI consumers,
but there is no standalone "C wrapper" product.

> **Coverage note:** All four wrappers are first-class — the aim is *equal* completeness, not a
> hierarchy (see [ADR-0015](docs/adr/0015-no-reference-wrapper-parity.md)). All four bind the same 38
> `@CEntryPoint` functions (CI-enforced) and cover the same on-chain use cases — now at **comparable
> per-module unit + integration coverage** across all four (the Go/Rust unit-test-breadth gap is
> closed — see §3), and JS is feature-complete on QuickTx.

---

## 1. Development — Wrapper Parity & Features

- [x] `P0` ~~Audit & confirm JS QuickTx/ScriptTx/compose parity vs Python.~~ **Done (verified against source):** JS is feature-complete — `mintPlutusAssets`, `collectFromScript`, `readFrom`, the full `ScriptTxBuilder`, and `compose()`/`ComposeTxBuilder` all exist in `wrappers/js/src/index.js`. No feature gap. The real gap is test coverage — see §3.
- [x] `P1` ~~Designate Python as the documented "reference wrapper" and write a parity checklist~~ **Done, reframed:** the "reference wrapper" framing was **dropped** — all four wrappers are first-class and the aim is *equal* completeness, not a hierarchy. [ADR-0015](docs/adr/0015-no-reference-wrapper-parity.md) instead documents the language-neutral **parity principle** (a change isn't done until all four wrappers have it) + the **operational checklist** for keeping them in lockstep on any FFI/API change, backed by CI-enforced binding parity (`check_entrypoint_parity.py`) + use-case parity (`integration-tests.yml`). _(Closing the remaining unit-test-breadth gap so all four are equally complete is tracked separately in §3.)_
- [ ] `P2` Split the monolithic Go `wrappers/go/ccl/ccl.go` (~2k LOC) and Rust `wrappers/rust/src/lib.rs` into focused modules for maintainability.
- [ ] `P2` Cross-wrapper error-handling review for consistent `CclError` semantics (codes, messages, idiomatic types).
- [x] `P2` ~~Give the Go wrapper a clear build-time message when `CGO_ENABLED=0`~~ **Obsolete** — the Go wrapper no longer uses cgo. It was migrated to **purego** (pure-Go `dlopen`, builds with `CGO_ENABLED=0`; see [ADR-0014](docs/adr/0014-go-distribution-purego-runtime-resolution.md)), so cgo is *not* required and there is no cgo linker error to guard against. The item's premise no longer holds.
- [x] `P2` ~~Expose **stake-key signing**~~ **Done** — added `ccl_account_sign_tx_multi(…, keys)`, which signs with any subset of `payment` / `stake` / `drep` / `committee_cold` / `committee_hot` (CCL's `Account.signWith*Key`), wired through all four wrappers (`sign_tx_with_keys` / `SignTxWithKeys` / `signTxWithKeys`). Fixes the `MissingVKeyWitnessesUTXOW` rejection for stake/vote/DRep certs; the original `ccl_account_sign_tx` (payment only) is unchanged.

## 2. Development — Build, CI & Distribution

- [x] `P0` ~~Fix the Go wrapper's thread affinity on Linux x86_64.~~ **Done** — all FFI calls now run on a single dedicated OS thread that owns the isolate for the `Bridge`'s lifetime (`runtime.LockOSThread` + a channel-served executor goroutine in `wrappers/go/ccl/ccl.go`). This keeps the executing OS thread and the GraalVM `IsolateThread` in sync, eliminating the Linux "yellow zone" `StackOverflowError`. Linux Go CI is blocking again and green.
- [x] `P0` ~~Add a **Windows** native build (`libccl.dll`) to CI and the release pipeline.~~ **Done** — CI has a `windows-latest` job that builds `libccl.dll` (`:core:nativeCompile`) and runs the JVM tests; `release.yml` produces a `windows-x86_64` artifact (DLL + `libccl.lib` import library + headers). Verified green on CI.
- [x] `P1` ~~Add **Windows wrapper test coverage** to CI (Python/Rust/JS/Go).~~ **Done (PR #35)** — the `windows` CI job now runs all four wrapper test suites (green on `windows-latest`). Fixes: the `test` gradle tasks no longer shell out via `bash` on Windows (invoke `python`/`go`/`cargo` directly; `cmd /c` for JS's `&&` chain — a bare `bash` resolves to WSL there); the Go wrapper loads `libccl.dll` via `syscall.LoadLibrary` since `purego.Dlopen` is Unix-only (`ffi_windows.go`); Python `os.add_dll_directory` for DLL sibling deps; Rust `build.rs` stages GraalVM's `libccl.lib` import lib as `ccl.lib`; and the Rust step runs under **PowerShell** so rustc uses the MSVC linker instead of git-bash's coreutils `link.exe`. (The old cgo blocker is gone — Go is purego.) Windows covers the offline/unit paths; DevKit integration stays on the Linux job.
- [x] `P0` ~~Bundle or auto-fetch the native lib per wrapper so users no longer hand-set `CCL_LIB_PATH` / `DYLD_LIBRARY_PATH` / `LD_LIBRARY_PATH`~~ **Done (all four wrappers)** — *decided; see [ADR-0012](docs/adr/0012-native-lib-bundled-in-wrapper-packages.md).* **Python + JS + Rust: done.** Python — `CclLib` loads a `libccl.*` bundled inside the package (`ccl/_libs/`), falling back to `CCL_LIB_PATH` for local dev; `./gradlew :wrappers:python:wheel` builds a platform-tagged `py3-none-<platform>` wheel that ships the matching lib, so `pip install` needs no env vars (verified: install in a clean venv → `import ccl; CclLib()` works). JS — `CclBridge` uses the same resolution order and loads a lib bundled in the package (`libs/`); `./gradlew :wrappers:js:pack` builds an npm tarball shipping the matching lib, so `npm install` needs no env vars (verified: install the tarball in a clean project → `new CclBridge()` loads with no `CCL_LIB_PATH`). Rust — `build.rs` sources `libccl.*` (`CCL_LIB_PATH` / in-tree / GitHub-release download), stages it into `OUT_DIR`, rewrites the macOS install name to `@rpath`, and sets an `rpath`, so `cargo add cardano-client-lib` + build needs no env vars (crates.io can't host the binary, so it's fetched at build time). All three are guarded in CI (build package → clean install/run → load with env unset). **Go: done too** — a pure-Go loader (purego, no cgo) resolves `libccl` at runtime (`CCL_LIB_PATH` → per-version cache → GitHub-release download), no install hook needed; see [ADR-0014](docs/adr/0014-go-distribution-purego-runtime-resolution.md). So **all four wrappers now load with no env vars.** _Remaining: the CI job to build+publish the per-platform wheels/packages from the release artifacts (PyPI/npm/crates) — tracked in the Publish item below (#15/#16 staged)._
- [x] `P1` **Investigate static linking** — *decided + done; see [ADR-0008](docs/adr/0008-linux-glibc-baseline-portability.md).* **Finding:** `native-image` **cannot** emit a static library (`.a`) — oracle/graal#3053 is still open on GraalVM 25 — and musl's run-anywhere property applies only to static *executables*, not shared libraries. So a fully-static, no-`.so` distribution that keeps the in-process FFI is not possible without re-architecting to a static musl executable behind IPC (rejected as too invasive). **Decision + done: distro/glibc independence via a glibc-baseline build.** Building the Linux `.so` in `manylinux_2_28` yields a lib that requires only **`GLIBC_2.17`** — verified green in CI, and proven to load + run a real key-derivation on `centos:7` (glibc 2.17). Rolled out: `portable-linux-lib.yml` guards it on every PR/develop (objdump floor + centos:7 run), and `release.yml` ships the Linux artifact from the same container. Runs on RHEL/CentOS 7+, Amazon Linux 2, Ubuntu 18.04+, Debian 9+. _Follow-ups both **done**: linux-arm64 baseline build (same manylinux baseline on `ubuntu-24.04-arm`); and the **musl/Alpine variant** — shipped as `linux-musl-x86_64` via `--libc=musl` (PR #28), see the musl item below._
- [x] `P1` ~~Add **linux-arm64** and **macos-x86_64** to the build/release matrix.~~ **Done** — `release.yml` now ships five native artifacts: `linux-x86_64`, `linux-aarch64`, `macos-aarch64`, `macos-x86_64`, `windows-x86_64`. The `linux-aarch64` lib is built to the same glibc-2.17 baseline (`manylinux_2_28_aarch64` on `ubuntu-24.04-arm`) and `portable-linux-lib.yml` now verifies **both** arches (objdump floor + a real run on `centos:7` aarch64). `macos-x86_64` (Intel) builds on `macos-13`; both macOS arches now run the full wrapper suite in `ci.yml`. _(Intel Macs previously had **no** working build — an arm64 `.dylib` can't load into an x86_64 process, so this unblocks them, not just adds a convenience.)_ _Update: `macos-x86_64` (Intel) was later **dropped** (PR #27 — Oracle GraalVM ends Intel-Mac support, and its 25.1 line ships no Intel build) and `linux-musl-x86_64` **added** (PR #28). The release now ships **5**: `linux-x86_64`, `linux-aarch64`, `linux-musl-x86_64`, `macos-aarch64`, `windows-x86_64`._ Remaining arch gap: `windows-arm64` (immature GraalVM support).
- [x] `P1` ~~Add **musl / Alpine Linux** native builds.~~ **Done (x86_64, PR #28).** `linux-musl-x86_64` is built with native-image `--libc=musl` (a musl toolchain: `musl-gcc` + a musl-linked `zlib`), so it loads + runs on Alpine / musl-based images that the glibc-baseline `.so` can't. `musl-alpine.yml` guards it on every PR/develop (build → assert musl-linkage → a functional isolate run inside Alpine → Go/Rust wrapper auto-selection), and `release.yml` ships it. The **Go + Rust loaders auto-select** the musl artifact (Go: runtime detection via the musl dynamic loader; Rust: `CARGO_CFG_TARGET_ENV == "musl"`). **aarch64 musl is deferred** — GraalVM's `--libc=musl` hardcodes `x86_64-linux-musl-gcc` and doesn't support aarch64 (see [ADR-0008](docs/adr/0008-linux-glibc-baseline-portability.md)).
- [ ] `P1` Publish wrappers to registries: PyPI (`cardano-client-lib`), crates.io (`cardano-client-lib`), npm (`@bloxbean/cardano-client-lib`), and tag the Go module for the proxy. _(Renamed from `ccl` in PR #24. Python wheel (#15) and npm (#16) publish workflows are staged; crates.io + Go-tag flows still to write. A first release also unblocks the deferred download-path E2E tests — Go/Rust/musl currently seed the lib in CI because no release exists yet.)_
- [x] `P1` Pin CI to Oracle GraalVM `25.0.3` exactly (CI currently floats `java-version: '25'`) for reproducible builds.
- [ ] `P2` Fill in wrapper manifest metadata (`[project.urls]`, `repository`, `homepage`, `documentation`) in `pyproject.toml` / `Cargo.toml` / `package.json` / `go.mod`.
- [ ] `P2` Automate version bumping from a single source of truth (the version is duplicated across `gradle.properties` and each wrapper manifest).
- [ ] `P2` **Runtime lib↔wrapper version check.** A native lib a version behind its wrapper fails confusingly; have each wrapper call `ccl_version` on init and error clearly on mismatch.
- [ ] `P2` **Sign release artifacts** (cosign/sigstore) for supply-chain trust when pulling a prebuilt native lib. The release already emits `SHA256SUMS`; add signatures + verification docs.

## 2b. Plutus script evaluation — pluggable evaluators

The bridge builds Plutus script transactions offline by accepting the redeemers' **execution
units** (mem + CPU steps) as a fourth caller-supplied input to `ccl_quicktx_build` — exactly like
UTXOs and protocol parameters. Internally it wires CCL's `StaticTransactionEvaluator`, so the
bridge never runs the script; the caller computes the units with whatever evaluator they prefer.
This is shipped and tested (`QuickTxApiTest.plutusMint*`).

- [~] `P1` **Evaluator abstraction + examples (pick-and-choose).** Give users a clear, per-language
  story for *obtaining* the exec units to pass in, with helper/service classes and runnable
  examples for each supported evaluator:
  - **HTTP / Blockfrost** `…/utils/txs/evaluate` (online) — ✓ **done** (all 4 wrappers)
  - **Ogmios** `EvaluateTx` (online) — remains
  - **Aiken** UPLC evaluator (offline; e.g. `aiken-java-binding` server-side, or a wrapper-native
    binding) — remains
  - **Scalus** UPLC evaluator (offline, JVM/Scala) — ✓ **done** (in-core default)
  The bridge stays evaluator-agnostic (it only consumes `[{mem, steps}]`); these are thin,
  swappable client-side helpers + docs showing the two-pass flow (build → evaluate → rebuild with
  units). Cover Python, Go, Rust, JS.
  **Status:** the two-tier evaluator design shipped (see [ADR-0013](docs/adr/0013-transaction-evaluators.md)):
  **Scalus** is the offline default baked into `libccl` (`ScalusTransactionEvaluator`), and a
  wrapper-side **`Evaluator` interface + `BlockfrostEvaluator`** (remote `/utils/txs/evaluate`) ships in
  all four languages with examples + tests, plus a `buildWith(...)` two-pass convenience. **Remaining:
  Ogmios + Aiken helpers.**
- [ ] `P2` **Self-contained offline evaluation spike — `aiken-java-binding` inside the GraalVM
  native image.** If the Aiken Rust UPLC evaluator can be loaded via JNI from within `libccl`
  (the blockers: the binding extracts its `.so` from the classpath jar at runtime — absent in a
  native image — plus JNI config and per-platform Rust binaries), the bridge could run scripts
  itself and callers would supply *nothing* extra. Prove feasibility before committing.

## 2c. Chain-data provider helpers — make the API easy in all 4 languages

`ccl_quicktx_build` is offline by design: the caller supplies **UTXOs**, **protocol parameters**,
and (for Plutus) **execution units**. Today every wrapper is a pure pass-through — it marshals
those three inputs and calls the native lib, but does **nothing** to obtain them. The user has to
make their own HTTP calls to a backend first. That is the single biggest friction point for a
first-time user, in every language.

The fix keeps the **native lib provider-free** (offline stays offline) and adds the convenience
*entirely in wrapper code*, using each language's own HTTP client — so the offline contract is
untouched and the helpers are optional and swappable. This is the sibling of §2b: §2b obtains the
*exec units*; this obtains the *UTXOs + protocol parameters*. Together they cover all three inputs.

- [x] `P1` ~~**Optional per-wrapper chain-data provider helpers (UTXOs + protocol params).**~~ **Done
  (all four wrappers).** Each ships a `ChainDataProvider` interface (`utxos(address)` /
  `protocol_params()`) plus `YaciProvider` (DevKit/yaci-store, CI-tested live) and `BlockfrostProvider`
  (project-id header, pagination, address injection; unit-tested against mock servers — not live in
  CI), and a `build_with(yaml, provider, sender, exec_units?)` convenience on the QuickTx
  API. The native lib stays offline/provider-free: helpers are pure wrapper code using each
  language's own HTTP client (urllib / net/http / Bun fetch / ureq). Rust gates it behind a
  `providers` feature so the core crate needs no HTTP client. Cost models from these providers flow
  through the JS cost-model normalization (see §3). Original spec for reference:
  A thin,
  optional helper in Python/Go/Rust/JS that fetches the data `build()` needs and returns it in the
  exact shape the wrapper already accepts, e.g.:
  ```
  provider = BlockfrostProvider(project_id)        # or Koios / Ogmios / Yaci DevKit
  utxos    = provider.utxos(sender_addr)           # all UTXOs at the address
  pp       = provider.protocol_params()
  result   = quicktx.build(yaml, utxos, pp)        # unchanged offline core call
  ```
  Notes:
  - **No UTXO *selection* needed** — the bridge already selects internally (it hands all sender
    UTXOs to `QuickTxBuilder`/`StaticUtxoSupplier`). The helper only needs "UTXOs at address X".
  - Define a small provider interface per language (`utxos(addr)`, `protocol_params()`), ship at
    least one concrete impl (Blockfrost-style + Yaci DevKit, which the integration tests already
    hit), and document a `buildWith(yaml, provider, sender)` convenience that composes
    fetch → build.
  - Compose cleanly with §2b's exec-unit evaluators so a Plutus build is `fetch → evaluate → build`.
- [ ] `P2` **Reconcile the WISHLIST vs Non-Goals tension.** Satya's wishlist wants provider-fetched
  protocol params + client-side UTXO capture; Non-Goals excludes "HTTP provider modules". The
  resolution is the split above: *optional wrapper-side helpers are in scope; baking a provider
  into the native `libccl` is not.* The Non-Goals note now says this explicitly.

## 3. Testing

- [x] `P1` ~~Add JS integration tests for the script/Plutus paths.~~ **Done (and the item's premise was superseded by the TxPlan refactor):** the old fluent `ScriptTxBuilder` / `collectFromScript` / `mintPlutusAssets` / `readFrom` API was deleted — script/Plutus paths are now TxPlan YAML fixtures, covered at the build level in `test/intents.e2e.test.js`: all 20 top-level intents (incl. `reference_input`, `compose`, `native_script`) plus the three `plutus/` fixtures — **mint**, **spend**, and **lock** — each asserting non-empty CBOR + 64-char hash + positive fee, that mint/spend **require** caller-supplied exec units (build throws without them), and that `plutus.dataHash` reproduces the lock fixture's datum hash. Node-level (DevKit): a Plutus-mint **build → sign → submit → assert the minted asset landed on-chain** round-trip in `test/quicktx.integration.test.js`, mirroring Go's `TestIntegrationPlutusMint`.
- [x] `P1` ~~**Fix JS cost-model key ordering for Plutus builds.**~~ **Done.** Passing cost models fetched from a Blockfrost-style provider (`/epochs/parameters` returns them as a map keyed by zero-padded indices `"000".."165"`) into a Plutus `build()` yielded a tx the node rejected with `PPViewHashesDontMatch` — JS's JSON parse reorders the non-padded integer-like keys (`"100".."165"`) ahead of the padded ones, scrambling the cost-model order vs the ledger's canonical order and corrupting the script-integrity hash. (Go's `json.Marshal` sorts keys lexicographically, which for zero-padded keys equals numeric order, so Go is unaffected; Python preserves the provider's order.) Fixed in the JS wrapper (`normalizeCostModels` in `wrappers/js/src/index.js`): numerically-keyed cost models are converted to CCL's ordered `cost_models_raw` array form (a `List<Long>` CCL consumes in order, ahead of the order-sensitive named map), which serializes order-stably. The Plutus-mint DevKit round-trip now submits with the devnet's real fetched cost models (no workaround), and unit tests cover the conversion. _(Other wrappers are unaffected. Per upstream guidance ([bloxbean/cardano-client-lib#633](https://github.com/bloxbean/cardano-client-lib/issues/633)), `cost_models_raw` is the preferred, ordered form and `cost_models` is deprecated — `normalizeCostModels` now prefers an existing `cost_models_raw` and passes it through untouched, only converting the deprecated numeric-keyed `cost_models` as a fallback for providers that don't yet return raw. Empirically the Yaci DevKit `:10000` proxy (what our tests use) returns numeric only, while its yaci-store `:8080` API returns `cost_models_raw`; removal of the workaround is tracked in [#11](https://github.com/bloxbean/cardano-client-bindings/issues/11).)_
- [x] `P1` ~~Raise Go and Rust test breadth toward Python's; port Python's per-module unit tests. **Integration parity done:** all four wrappers now cover the same on-chain scenarios end-to-end — an audit found Go had ~20 DevKit integration tests while Python/Rust/JS had ~4–6, so Go's `intents_integration_test.go` suite (metadata, native/Plutus mint, Plutus spend, and the full governance suite: stake ×4, DRep ×4, voting ×2, pool, proposal) was ported to Python (+15), Rust (+16, incl. a shared `tests/common/` harness), and JS (+16). Every use-case category — **simple payments, metadata, smart contracts, governance** — is now proven build→sign→submit in all four languages. **Unit breadth now closed too:** Python's per-module edge/error-case unit tests were ported to **Go (+19 → 70)** and **Rust (+29 → 89)** — invalid/empty mnemonics, bad addresses/CBOR, per-operation coverage, and real exact-value assertions (Blake2b `"Hello"` vectors, an Ed25519 cross-implementation vector vs Go's stdlib, derived-address prefixes). All four wrappers are now at comparable per-module unit + integration coverage (see [ADR-0015](docs/adr/0015-no-reference-wrapper-parity.md)).
- [~] `P1` Add a cross-wrapper parity test matrix asserting every `@CEntryPoint` is covered in every language. **Done (binding parity):** [`scripts/check_entrypoint_parity.py`](scripts/check_entrypoint_parity.py) — CI-guarded (a fast `entrypoint-parity` job, no native build) — extracts the canonical `ccl_*` `@CEntryPoint` set from core and asserts each wrapper (Python/JS/Rust/Go) binds *exactly* that set, so a new/renamed entry point can't silently be missing from one language. _Remaining: test-coverage parity (assert each entry point is actually **exercised by a test**), which pairs with the Go/Rust test-breadth item above._
- [x] `P2` ~~Run the Yaci DevKit integration tests in CI (containerized DevKit) instead of skip-if-not-running.~~ **Done** — `integration-tests.yml` installs + starts a Yaci DevKit devnet and runs all four wrappers' build→sign→submit round-trips against it on every PR to main/develop. (The tests still self-skip locally when DevKit is down, but CI always runs them with it up.)
- [ ] `P2` Add on-chain (DevKit submit) coverage for the three governance certificates that are currently **build-tested only in every wrapper** — **pool update**, **pool retirement**, and **stake deregistration**. They're exercised offline via the shared `quicktx-intents` fixtures but never submitted end-to-end in *any* language (not even Go), so their ledger acceptance is unproven. Add a build→sign→submit test per cert across all four wrappers. (Everything else in the four core use-case categories is now integration-covered at parity — see the test-breadth item above.)
- [ ] `P2` Expand the C smoke tests and add an FFI memory-leak / valgrind check across the native boundary.
- [ ] `P2` Add benchmarks for FFI call overhead and JSON (de)serialization cost.

## 4. User Documentation

- [x] `P1` Per-wrapper `README.md` (install, load the lib, first call) for python / go / rust / js. **Done** — added `wrappers/{python,go,rust,js}/README.md`.
- [x] `P1` Add per-wrapper `examples/` with runnable offline samples. **Done** — each wrapper has account / primitives / transaction examples (offline build+sign, no DevKit). All four verified running locally (Python, Go, Rust, JS/Bun). _Follow-up: richer samples (NFT mint, staking, governance)._
- [ ] `P2` Generated API reference per language (Sphinx / rustdoc / godoc / JSDoc or TypeDoc).
- [ ] `P2` Add project-meta docs: `CONTRIBUTING.md`, `CHANGELOG.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, and GitHub issue/PR templates.
- [ ] `P2` Expand the 7-line `devkit.md` into a proper Yaci DevKit integration-testing guide.
- [ ] `P2` Add an **end-to-end "build → sign → submit" example** per language. The bridge is offline-only, so users get stuck at broadcasting; show submitting the signed CBOR with the language's own HTTP client (e.g. Go `net/http`).
- [ ] `P2` Add CI status + DevKit-integration badges to the README so the working round trips are visible at a glance.

## 5. Website

- [ ] `P1` Stand up a **GitHub Pages documentation site** (MkDocs Material or Docusaurus) hosting the README content, per-language guides, and `docs/quicktx.md`.
- [ ] `P2` Auto-deploy the site from CI on release and wire in the generated per-language API references.

## 6. Upstream CCL — New Modules to Evaluate

Surfaced by scanning upstream CCL. The bridge now targets **0.8.0-pre4**, so all of these are
available as a current dependency — no further upgrade needed.

### CIP modules (already a bridge dependency)

- [ ] `P2` **CIP-30 data signing** — wrap `DataSignature` / `CIP30DataSigner` (COSE_Sign1 `signData` create + verify). Offline. Complements existing CIP-8 message signing with the wallet/dApp data-signature format.
- [ ] `P2` **CIP-27 royalty metadata** — wrap royalty metadata construction/parsing for NFTs. Offline; complements the bridge's existing CIP-25 support.

### Now available on CCL 0.8.0-pre4

- [x] `P1` ~~**Upgrade CCL 0.7.2 → 0.8.0**~~ **Done** — the bridge is on `0.8.0-pre4` (the TxPlan refactor). The QuickTx wrapper was rewritten to TxPlan YAML; the 0.8.0 unified `Tx`/`ScriptTx` + `DepositMode` are exercised by the intent E2E suite. Re-pin to the stable `0.8.0` when it releases.
- [ ] `P2` **`plutus-aiken` blueprint handling** — expose runtime CIP-57 blueprint parsing and apply-params-to-script (parameterized validators). Offline. (The compile-time `@MetadataType` annotation processor is build-time Java codegen and is **not** FFI-able, so it is out of scope for the wrappers.)
- [ ] `P2` **`txflow` multi-step orchestration** — evaluate exposing the offline flow-composition parts. Caveat: confirmation tracking is online/stateful and fits the bridge's stateless-FFI model awkwardly; wrap only the pure-composition surface, if any.
- [ ] `P2` **CIP-102 royalty datum (v2)** — inline royalty datum on UTXOs; extends CIP-27. Offline datum (de)serialization.
- [ ] `P2` **`crypto-ext` VRF/KES** — niche (block-producer / consensus simulation, experimental). Offline. Only if devnet simulation becomes a goal.

## 7. Maintenance — Existing Wrappers (audit, likely already covered)

- [ ] `P2` Audit governance key derivation parity (`DRepKey`, `CommitteeColdKey`, `CommitteeHotKey`, gov-action IDs) — the bridge already exposes these; confirm nothing new in CCL is missing.
- [ ] `P2` Audit QuickTx deposit handling against CCL's `DepositMode` (AUTO / CHANGE_OUTPUT / ANY_OUTPUT / NEW_UTXO_SELECTION) when on 0.8.0.

---

## Non-Goals (intentional, for now)

- **Verified data structures** (`verified-structures`: Merkle Patricia Forestry,
  Jellyfish Merkle Tree, RocksDB/RDBMS backends) — out of scope. They require
  persistent, stateful storage backends, which is incompatible with the bridge's
  stateless, side-effect-free FFI model. The pure-compute proof core could be
  reconsidered only if there is explicit demand for Merkle-proof APIs.

- **Node.js support** — *wanted but blocked.* Node FFI libraries (ffi-napi, koffi) crash
  with the GraalVM native-image library due to stack-boundary detection issues on macOS
  ARM64. Bun (built-in FFI) is the supported JS runtime. Tracked as a `P2` investigation
  item, not a committed deliverable.
- **Backend / HTTP provider modules *in the native `libccl`*** (Blockfrost, Koios, Ogmios) —
  deliberately excluded; the native lib stays offline and side-effect-free. **This does not
  exclude optional, wrapper-side provider helpers** that fetch UTXOs / protocol params / exec
  units using each language's own HTTP client and feed them into the offline `build()` — those
  are explicitly in scope and tracked in §2b (exec units) and §2c (UTXOs + protocol params).
  The line is: convenience in wrapper code = yes; a provider baked into `libccl` = no.
