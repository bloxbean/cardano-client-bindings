# CCL Bridge — TODO

WISHLIST (Satya):
- YAML support for TX building (TxPlan)
- UTxO capture on the client side, callback maybe an issue (e.g. BloxBean) - UTxO selection
- UTxO selection on the client
- Protocol Parameters should be fetched via provider (cost calculation)
- Script Supplier?

A living, categorized backlog of work for CCL Bridge. CCL Bridge compiles
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

> **Coverage note:** All four wrappers expose all 34 `@CEntryPoint` functions. Python
> is currently the most complete and best-tested wrapper (the de-facto reference);
> Go and Rust trail on test breadth; **JavaScript is the laggard** on QuickTx features.

---

## 1. Development — Wrapper Parity & Features

- [x] `P0` ~~Audit & confirm JS QuickTx/ScriptTx/compose parity vs Python.~~ **Done (verified against source):** JS is feature-complete — `mintPlutusAssets`, `collectFromScript`, `readFrom`, the full `ScriptTxBuilder`, and `compose()`/`ComposeTxBuilder` all exist in `wrappers/js/src/index.js`. No feature gap. The real gap is test coverage — see §3.
- [ ] `P1` Designate Python as the documented "reference wrapper" and write a parity checklist so all four wrappers stay in lockstep as the API grows.
- [ ] `P2` Split the monolithic Go `wrappers/go/ccl/ccl.go` (~2k LOC) and Rust `wrappers/rust/src/lib.rs` into focused modules for maintainability.
- [ ] `P2` Cross-wrapper error-handling review for consistent `CclError` semantics (codes, messages, idiomatic types).
- [ ] `P2` Give the Go wrapper a clear build-time message when `CGO_ENABLED=0` (a `//go:build cgo` guard + a stub that explains cgo is required), instead of a cryptic linker error.
- [x] `P2` ~~Expose **stake-key signing**~~ **Done** — added `ccl_account_sign_tx_multi(…, keys)`, which signs with any subset of `payment` / `stake` / `drep` / `committee_cold` / `committee_hot` (CCL's `Account.signWith*Key`), wired through all four wrappers (`sign_tx_with_keys` / `SignTxWithKeys` / `signTxWithKeys`). Fixes the `MissingVKeyWitnessesUTXOW` rejection for stake/vote/DRep certs; the original `ccl_account_sign_tx` (payment only) is unchanged.

## 2. Development — Build, CI & Distribution

- [x] `P0` ~~Fix the Go wrapper's thread affinity on Linux x86_64.~~ **Done** — all FFI calls now run on a single dedicated OS thread that owns the isolate for the `Bridge`'s lifetime (`runtime.LockOSThread` + a channel-served executor goroutine in `wrappers/go/ccl/ccl.go`). This keeps the executing OS thread and the GraalVM `IsolateThread` in sync, eliminating the Linux "yellow zone" `StackOverflowError`. Linux Go CI is blocking again and green.
- [x] `P0` ~~Add a **Windows** native build (`libccl.dll`) to CI and the release pipeline.~~ **Done** — CI has a `windows-latest` job that builds `libccl.dll` (`:core:nativeCompile`) and runs the JVM tests; `release.yml` produces a `windows-x86_64` artifact (DLL + `libccl.lib` import library + headers). Verified green on CI.
- [ ] `P1` Add **Windows wrapper test coverage** to CI (Python/Rust/JS/Go). The Windows job currently only builds the DLL + runs JVM tests; the wrapper test tasks assume a bash/`python3` shell and Unix `*_LIBRARY_PATH` semantics, and Go cgo + the C `native-test` Makefile need a Windows C toolchain. Each needs Windows-specific wiring.
- [ ] `P0` Bundle or auto-fetch the native lib per wrapper (wheel platform tags / Rust `build.rs` / npm `postinstall`) so users no longer hand-set `CCL_LIB_PATH` / `DYLD_LIBRARY_PATH` / `LD_LIBRARY_PATH`.
- [ ] `P1` **Investigate static linking** — can `native-image` emit a static archive (`libccl.a`) instead of only a shared library? If so, the Go (cgo) and Rust wrappers could statically link it into a single self-contained binary: no runtime `.so`/`.dylib`, no `*_LIBRARY_PATH`, `scratch`/Alpine Docker images possible. This is the single biggest ergonomic win for Go and partly subsumes the bundling item above. A focused `native-image` spike answers feasibility (static lib output + musl for fully-static Linux).
- [ ] `P1` Add **linux-arm64** and **macos-x86_64** to the build/release matrix (currently only `ubuntu-latest` x86_64 + `macos-14` ARM64).
- [ ] `P1` Add **musl / Alpine Linux** native builds. The current glibc-linked `.so` fails on musl-based images (common in Go/Docker). Ship a musl variant (and document the glibc baseline for the standard build).
- [ ] `P1` Publish wrappers to registries: PyPI (`ccl`), crates.io (`ccl`), npm (`@bloxbean/ccl`), and tag the Go module for the proxy.
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

- [ ] `P1` **Evaluator abstraction + examples (pick-and-choose).** Give users a clear, per-language
  story for *obtaining* the exec units to pass in, with helper/service classes and runnable
  examples for each supported evaluator:
  - **HTTP / Blockfrost** `…/utils/txs/evaluate` (online)
  - **Ogmios** `EvaluateTx` (online)
  - **Aiken** UPLC evaluator (offline; e.g. `aiken-java-binding` server-side, or a wrapper-native
    binding)
  - **Scalus** UPLC evaluator (offline, JVM/Scala)
  The bridge stays evaluator-agnostic (it only consumes `[{mem, steps}]`); these are thin,
  swappable client-side helpers + docs showing the two-pass flow (build → evaluate → rebuild with
  units). Cover Python, Go, Rust, JS.
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

- [ ] `P1` **Optional per-wrapper chain-data provider helpers (UTXOs + protocol params).** A thin,
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
    hit), and document a `buildWithProvider(yaml, provider, sender)` convenience that composes
    fetch → build.
  - Compose cleanly with §2b's exec-unit evaluators so a Plutus build is `fetch → evaluate → build`.
- [ ] `P2` **Reconcile the WISHLIST vs Non-Goals tension.** Satya's wishlist wants provider-fetched
  protocol params + client-side UTXO capture; Non-Goals excludes "HTTP provider modules". The
  resolution is the split above: *optional wrapper-side helpers are in scope; baking a provider
  into the native `libccl` is not.* The Non-Goals note now says this explicitly.

## 3. Testing

- [ ] `P1` Add JS integration tests for the script/Plutus paths — these are implemented in `wrappers/js/src/index.js` but have **zero** test coverage: `ScriptTxBuilder` validators + redeemers, `collectFromScript`, `mintPlutusAssets`, `readFrom` (reference inputs), and compose-with-`ScriptTx`. Python's `tests/` are the reference for what to assert.
- [ ] `P1` Raise Go and Rust test breadth toward Python's (~100 cases vs ~61); port Python's per-module unit tests.
- [ ] `P1` Add a cross-wrapper parity test matrix asserting every `@CEntryPoint` is exercised in every language.
- [ ] `P2` Run the Yaci DevKit integration tests in CI (containerized DevKit) instead of skip-if-not-running.
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
