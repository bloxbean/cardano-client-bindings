import { dlopen, FFIType, ptr, CString } from 'bun:ffi';
import path from 'path';
import os from 'os';
import { existsSync } from 'fs';
import { parse as parseYaml } from 'yaml';

// Optional chain-data provider helpers (re-exported for convenience).
export { ChainDataProvider, YaciProvider, BlockfrostProvider } from './providers.js';
export { TransactionEvaluator, BlockfrostEvaluator } from './providers.js';

// Error codes
export const CCL_SUCCESS = 0;
export const CCL_ERROR_GENERAL = -1;
export const CCL_ERROR_INVALID_ARGUMENT = -2;
export const CCL_ERROR_SERIALIZATION = -3;
export const CCL_ERROR_CRYPTO = -4;
export const CCL_ERROR_INVALID_NETWORK = -5;
export const CCL_ERROR_INVALID_MNEMONIC = -6;
export const CCL_ERROR_INVALID_ADDRESS = -7;
export const CCL_ERROR_INSUFFICIENT_FUNDS = -8;
export const CCL_ERROR_INVALID_TRANSACTION = -9;
export const CCL_ERROR_TX_BUILD = -10;

// Network IDs
export const MAINNET = 0;
export const TESTNET = 1;
export const PREPROD = 2;
export const PREVIEW = 3;

export class CclError extends Error {
  constructor(code, message) {
    super(`CCL Error ${code}: ${message}`);
    this.code = code;
  }
}

// Helper: encode a JS string to a null-terminated Buffer for FFI
function cstr(str) {
  return Buffer.from(str + '\0', 'utf-8');
}

// Plutus cost models: prefer the ordered `cost_models_raw` array form.
//
// Per upstream guidance (bloxbean/cardano-client-lib#633), `cost_models_raw` — a per-language ordered
// list of costs that CCL consumes directly — is the preferred way to carry cost models. The map form
// `cost_models` is deprecated: after recent cost-model changes its entries are no longer ordered, so
// relying on their order is unsafe. Providers that already return `cost_models_raw` (e.g. real
// Blockfrost, and yaci-store's own API) pass straight through here untouched.
//
// Only when a provider returns *just* the deprecated `cost_models` (a map keyed by numeric indices)
// do we convert it here: JavaScript object semantics iterate canonical integer-string keys ("100")
// ahead of zero-padded ones ("000"), so JSON.stringify would emit the map out of order and the node
// would reject Plutus txs with PPViewHashesDontMatch. We sort by numeric key and emit
// `cost_models_raw`, which serializes order-stably. (Go/Python are unaffected: Go's json.Marshal
// sorts keys, Python preserves the provider's order.) Languages with named-operation keys (which JS
// does not reorder) are left as a `cost_models` map untouched.
//
// This conversion is still load-bearing because the endpoint our tests/provider-helpers use — the
// Yaci DevKit :10000 local-cluster proxy — returns numeric `cost_models` only (empirically verified:
// no `cost_models_raw`), even though the DevKit's own yaci-store serves the ordered form on :8080.
// Remove this whole function once every endpoint we fetch params from returns `cost_models_raw` —
// tracked in bloxbean/cardano-client-bindings#11.
export function normalizeCostModels(protocolParams) {
  if (!protocolParams || typeof protocolParams !== 'object') return protocolParams;

  // Preferred form already present — CCL consumes cost_models_raw ahead of the deprecated map, so
  // pass the params through unchanged rather than touch the deprecated cost_models.
  const existingRaw = protocolParams.cost_models_raw ?? protocolParams.costModelsRaw;
  if (existingRaw && typeof existingRaw === 'object' && Object.keys(existingRaw).length > 0) {
    return protocolParams;
  }

  const costModels = protocolParams.cost_models ?? protocolParams.costModels;
  if (!costModels || typeof costModels !== 'object') return protocolParams;

  const raw = {};
  const named = {};
  let converted = false;
  for (const [lang, model] of Object.entries(costModels)) {
    const keys = model && typeof model === 'object' && !Array.isArray(model) ? Object.keys(model) : null;
    if (keys && keys.length > 0 && keys.every((k) => /^\d+$/.test(k))) {
      raw[lang] = keys.sort((a, b) => Number(a) - Number(b)).map((k) => model[k]);
      converted = true;
    } else {
      named[lang] = model;
    }
  }
  if (!converted) return protocolParams;

  const out = { ...protocolParams };
  delete out.cost_models;
  delete out.costModels;
  if (Object.keys(named).length > 0) out.cost_models = named;
  out.cost_models_raw = raw;
  return out;
}

function libFilename() {
  const platform = os.platform();
  if (platform === 'darwin') return 'libccl.dylib';
  if (platform === 'win32') return 'libccl.dll';
  return 'libccl.so';
}

// Is this a musl-based Linux (Alpine)? os.platform() reports "linux" for both glibc and musl, so —
// exactly as the Go loader does — detect musl by its dynamic loader, a file only musl systems ship.
// Without this an Alpine user resolves to the glibc package and gets a load failure, because the
// glibc libccl.so cannot load under musl.
function isMuslLinux() {
  if (os.platform() !== 'linux') return false;
  const loader = os.arch() === 'arm64'
    ? '/lib/ld-musl-aarch64.so.1'
    : '/lib/ld-musl-x86_64.so.1';
  return existsSync(loader);
}

// The per-platform npm package suffix for the current runtime, e.g. "macos-aarch64". Mirrors the
// native build/release matrix; used to locate the `@bloxbean/cardano-client-lib-<suffix>` optionalDependency.
export function platformSuffix() {
  const p = os.platform();
  const a = os.arch();
  if (p === 'darwin') return a === 'arm64' ? 'macos-aarch64' : 'macos-x86_64';
  if (p === 'win32') return 'windows-x86_64';
  if (isMuslLinux()) {
    // GraalVM's --libc=musl is x86_64-only (it hardcodes x86_64-linux-musl-gcc), so there is no
    // musl/aarch64 build to fall back to — say so, rather than handing back a glibc package that
    // cannot load here. See ADR-0008.
    if (a === 'arm64') {
      throw new Error(
        'No prebuilt libccl for musl/aarch64 (Alpine on ARM): GraalVM\'s --libc=musl is x86_64-only. ' +
        'Build libccl from source and set CCL_LIB_PATH.'
      );
    }
    return 'linux-musl-x86_64';
  }
  return a === 'arm64' ? 'linux-aarch64' : 'linux-x86_64';
}

// Locate the native library, in priority order:
//   1. an explicit `libPath` argument (a directory), if given;
//   2. the CCL_LIB_PATH env var (a directory) — for development against a locally built lib;
//   3. a copy bundled directly in this package (`libs/`) — a local `pack` or single-package install;
//   4. the `@bloxbean/cardano-client-lib-<platform>` optionalDependency package — the published layout;
//   5. the bare filename, letting the OS loader search its default paths.
export function resolveLibFile(libPath) {
  const name = libFilename();
  if (libPath) return path.join(libPath, name);
  if (process.env.CCL_LIB_PATH) return path.join(process.env.CCL_LIB_PATH, name);
  const bundled = path.join(import.meta.dir, '..', 'libs', name);
  if (existsSync(bundled)) return bundled;
  try {
    const fromPkg = Bun.resolveSync(`@bloxbean/cardano-client-lib-${platformSuffix()}/${name}`, import.meta.dir);
    if (fromPkg && existsSync(fromPkg)) return fromPkg;
  } catch {
    // platform package not installed — fall through to the bare filename
  }
  return name;
}

// Native libccl version this wrapper expects, kept in lockstep with the package version. On
// construction the wrapper compares it against ccl_version() and fails fast on a skew.
const EXPECTED_LIB_VERSION = '0.1.0';

// Strip any pre-release / build suffix: '0.1.0-preview1' -> '0.1.0'.
function baseVersion(v) {
  return v.split(/[-+]/, 1)[0].trim();
}
export class CclBridge {
  constructor(libPath) {
    const libFile = resolveLibFile(libPath);

    let lib;
    try {
      lib = dlopen(libFile, {
      graal_create_isolate: {
        args: [FFIType.ptr, FFIType.ptr, FFIType.ptr],
        returns: FFIType.i32,
      },
      graal_tear_down_isolate: {
        args: [FFIType.ptr],
        returns: FFIType.i32,
      },
      ccl_version: { args: [FFIType.ptr], returns: FFIType.i32 },
      ccl_get_result: { args: [FFIType.ptr], returns: FFIType.ptr },
      ccl_get_last_error: { args: [FFIType.ptr], returns: FFIType.ptr },
      ccl_free_string: { args: [FFIType.ptr, FFIType.ptr], returns: FFIType.void },

      // Account
      ccl_account_create: { args: [FFIType.ptr, FFIType.i32], returns: FFIType.i32 },
      ccl_account_from_mnemonic: { args: [FFIType.ptr, FFIType.i32, FFIType.cstring, FFIType.i32, FFIType.i32], returns: FFIType.i32 },
      ccl_account_get_private_key: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32, FFIType.i32], returns: FFIType.i32 },
      ccl_account_get_public_key: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32, FFIType.i32], returns: FFIType.i32 },
      ccl_account_get_drep_id: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32], returns: FFIType.i32 },
      ccl_account_sign_tx: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32, FFIType.i32, FFIType.cstring], returns: FFIType.i32 },
      ccl_account_sign_tx_multi: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32, FFIType.i32, FFIType.cstring, FFIType.cstring], returns: FFIType.i32 },

      // Address
      ccl_address_info: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_address_validate: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_address_to_bytes: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_address_from_bytes: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },

      // Crypto
      ccl_crypto_blake2b_256: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_crypto_blake2b_224: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_crypto_generate_mnemonic: { args: [FFIType.ptr, FFIType.i32], returns: FFIType.i32 },
      ccl_crypto_validate_mnemonic: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_crypto_sign: { args: [FFIType.ptr, FFIType.cstring, FFIType.cstring], returns: FFIType.i32 },
      ccl_crypto_verify: { args: [FFIType.ptr, FFIType.cstring, FFIType.cstring, FFIType.cstring], returns: FFIType.i32 },

      // Transaction
      ccl_tx_sign_with_secret_key: { args: [FFIType.ptr, FFIType.cstring, FFIType.cstring], returns: FFIType.i32 },
      ccl_tx_hash: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_tx_to_json: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_tx_from_json: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_tx_deserialize: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },

      // Plutus
      ccl_plutus_data_hash: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_plutus_data_to_json: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_plutus_data_from_json: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },

      // Script
      ccl_script_native_from_json: { args: [FFIType.ptr, FFIType.cstring], returns: FFIType.i32 },
      ccl_script_hash: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32], returns: FFIType.i32 },

      // Governance
      ccl_gov_drep_key_from_mnemonic: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32], returns: FFIType.i32 },
      ccl_gov_committee_cold_key_from_mnemonic: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32], returns: FFIType.i32 },
      ccl_gov_committee_hot_key_from_mnemonic: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32], returns: FFIType.i32 },

      // Wallet
      ccl_wallet_create: { args: [FFIType.ptr, FFIType.i32], returns: FFIType.i32 },
      ccl_wallet_from_mnemonic: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32], returns: FFIType.i32 },
      ccl_wallet_get_address: { args: [FFIType.ptr, FFIType.cstring, FFIType.i32, FFIType.i32], returns: FFIType.i32 },

      // QuickTx
      ccl_quicktx_build: { args: [FFIType.ptr, FFIType.cstring, FFIType.cstring, FFIType.cstring, FFIType.cstring], returns: FFIType.i32 },
    });
    } catch (e) {
      throw new Error(
        `Failed to load the CCL native library (${libFile}): ${e.message}\n` +
        `Install a package that bundles it, or set CCL_LIB_PATH to the directory containing ${libFilename()}.`
      );
    }

    this._lib = lib.symbols;

    // Create isolate
    const isolateBuf = new BigInt64Array(1);
    const threadBuf = new BigInt64Array(1);
    const rc = this._lib.graal_create_isolate(null, ptr(isolateBuf), ptr(threadBuf));
    if (rc !== 0) {
      throw new Error(`Failed to create GraalVM isolate: ${rc}`);
    }
    this._thread = Number(threadBuf[0]);

    this._checkVersion();

    // Namespace APIs
    this.account = new AccountApi(this);
    this.address = new AddressApi(this);
    this.crypto = new CryptoApi(this);
    this.tx = new TxApi(this);
    this.plutus = new PlutusApi(this);
    this.script = new ScriptApi(this);
    this.gov = new GovApi(this);
    this.wallet = new WalletApi(this);
    this.quicktx = new QuickTxApi(this);
  }

  _getResult() {
    const resultPtr = this._lib.ccl_get_result(this._thread);
    if (!resultPtr) return '';
    const result = new CString(resultPtr);
    const str = result.toString();
    this._lib.ccl_free_string(this._thread, resultPtr);
    return str;
  }

  _getError() {
    const errorPtr = this._lib.ccl_get_last_error(this._thread);
    if (!errorPtr) return '';
    const error = new CString(errorPtr);
    const str = error.toString();
    this._lib.ccl_free_string(this._thread, errorPtr);
    return str;
  }

  _check(rc) {
    if (rc !== CCL_SUCCESS) {
      throw new CclError(rc, this._getError());
    }
    return this._getResult();
  }

  close() {
    if (this._thread) {
      this._lib.graal_tear_down_isolate(this._thread);
      this._thread = null;
    }
  }

  version() {
    return this._check(this._lib.ccl_version(this._thread));
  }

  // Fail fast on a native-lib / wrapper version skew (bypass with CCL_SKIP_VERSION_CHECK).
  _checkVersion() {
    if (process.env.CCL_SKIP_VERSION_CHECK) return;
    const libVer = this.version();
    if (baseVersion(libVer) !== baseVersion(EXPECTED_LIB_VERSION)) {
      throw new Error(
        `libccl version '${libVer}' is incompatible with the @bloxbean/cardano-client-lib wrapper ` +
        `(expects '${EXPECTED_LIB_VERSION}'). The native library and wrapper must be the same version ` +
        `— reinstall the package, or set CCL_LIB_PATH to a matching libccl. ` +
        `Set CCL_SKIP_VERSION_CHECK=1 to bypass.`
      );
    }
  }
}

// --- Namespace API classes ---

class AccountApi {
  constructor(bridge) { this._b = bridge; }

  create(networkId = MAINNET) {
    return JSON.parse(this._b._check(this._b._lib.ccl_account_create(this._b._thread, networkId)));
  }

  fromMnemonic(mnemonic, networkId = MAINNET, accountIndex = 0, addressIndex = 0) {
    return JSON.parse(this._b._check(
      this._b._lib.ccl_account_from_mnemonic(this._b._thread, networkId, cstr(mnemonic), accountIndex, addressIndex)));
  }

  getPrivateKey(mnemonic, networkId = MAINNET, accountIndex = 0, addressIndex = 0) {
    return this._b._check(
      this._b._lib.ccl_account_get_private_key(this._b._thread, cstr(mnemonic), networkId, accountIndex, addressIndex));
  }

  getPublicKey(mnemonic, networkId = MAINNET, accountIndex = 0, addressIndex = 0) {
    return this._b._check(
      this._b._lib.ccl_account_get_public_key(this._b._thread, cstr(mnemonic), networkId, accountIndex, addressIndex));
  }

  getDrepId(mnemonic, networkId = MAINNET, accountIndex = 0) {
    return this._b._check(
      this._b._lib.ccl_account_get_drep_id(this._b._thread, cstr(mnemonic), networkId, accountIndex));
  }

  signTx(mnemonic, networkId, accountIndex, addressIndex, txCborHex) {
    return this._b._check(
      this._b._lib.ccl_account_sign_tx(this._b._thread, cstr(mnemonic), networkId, accountIndex, addressIndex, cstr(txCborHex)));
  }

  // Sign with one or more of the account's keys, selected by role (any of: payment, stake, drep,
  // committee_cold, committee_hot, applied in order). Use for transactions whose certificates also
  // need the stake or DRep key — stake registration/delegation/withdrawal and DRep/vote operations.
  signTxWithKeys(mnemonic, networkId, accountIndex, addressIndex, txCborHex, keys) {
    const keysStr = Array.isArray(keys) ? keys.join(",") : keys;
    return this._b._check(
      this._b._lib.ccl_account_sign_tx_multi(this._b._thread, cstr(mnemonic), networkId, accountIndex, addressIndex, cstr(txCborHex), cstr(keysStr)));
  }
}

class AddressApi {
  constructor(bridge) { this._b = bridge; }

  info(bech32) {
    return JSON.parse(this._b._check(this._b._lib.ccl_address_info(this._b._thread, cstr(bech32))));
  }

  validate(bech32) {
    return this._b._lib.ccl_address_validate(this._b._thread, cstr(bech32)) === CCL_SUCCESS;
  }

  toBytes(bech32) {
    return this._b._check(this._b._lib.ccl_address_to_bytes(this._b._thread, cstr(bech32)));
  }

  fromBytes(hexBytes) {
    return this._b._check(this._b._lib.ccl_address_from_bytes(this._b._thread, cstr(hexBytes)));
  }
}

class CryptoApi {
  constructor(bridge) { this._b = bridge; }

  blake2b256(dataHex) {
    return this._b._check(this._b._lib.ccl_crypto_blake2b_256(this._b._thread, cstr(dataHex)));
  }

  blake2b224(dataHex) {
    return this._b._check(this._b._lib.ccl_crypto_blake2b_224(this._b._thread, cstr(dataHex)));
  }

  generateMnemonic(wordCount = 24) {
    return this._b._check(this._b._lib.ccl_crypto_generate_mnemonic(this._b._thread, wordCount));
  }

  validateMnemonic(mnemonic) {
    return this._b._lib.ccl_crypto_validate_mnemonic(this._b._thread, cstr(mnemonic)) === CCL_SUCCESS;
  }

  sign(messageHex, skHex) {
    return this._b._check(this._b._lib.ccl_crypto_sign(this._b._thread, cstr(messageHex), cstr(skHex)));
  }

  verify(signatureHex, messageHex, pkHex) {
    return this._b._lib.ccl_crypto_verify(this._b._thread, cstr(signatureHex), cstr(messageHex), cstr(pkHex)) === CCL_SUCCESS;
  }
}

class TxApi {
  constructor(bridge) { this._b = bridge; }

  hash(txCborHex) {
    return this._b._check(this._b._lib.ccl_tx_hash(this._b._thread, cstr(txCborHex)));
  }

  signWithSecretKey(txCborHex, skCborHex) {
    return this._b._check(this._b._lib.ccl_tx_sign_with_secret_key(this._b._thread, cstr(txCborHex), cstr(skCborHex)));
  }

  toJson(txCborHex) {
    return this._b._check(this._b._lib.ccl_tx_to_json(this._b._thread, cstr(txCborHex)));
  }

  fromJson(txJson) {
    return this._b._check(this._b._lib.ccl_tx_from_json(this._b._thread, cstr(txJson)));
  }

  deserialize(txCborHex) {
    return JSON.parse(this._b._check(this._b._lib.ccl_tx_deserialize(this._b._thread, cstr(txCborHex))));
  }
}

class PlutusApi {
  constructor(bridge) { this._b = bridge; }

  dataHash(datumCborHex) {
    return this._b._check(this._b._lib.ccl_plutus_data_hash(this._b._thread, cstr(datumCborHex)));
  }

  dataToJson(cborHex) {
    return this._b._check(this._b._lib.ccl_plutus_data_to_json(this._b._thread, cstr(cborHex)));
  }

  dataFromJson(json) {
    return this._b._check(this._b._lib.ccl_plutus_data_from_json(this._b._thread, cstr(json)));
  }
}

class ScriptApi {
  constructor(bridge) { this._b = bridge; }

  nativeFromJson(json) {
    return this._b._check(this._b._lib.ccl_script_native_from_json(this._b._thread, cstr(json)));
  }

  hash(scriptCborHex, scriptType = 0) {
    return this._b._check(this._b._lib.ccl_script_hash(this._b._thread, cstr(scriptCborHex), scriptType));
  }
}

class GovApi {
  constructor(bridge) { this._b = bridge; }

  drepKeyFromMnemonic(mnemonic, networkId = MAINNET, accountIndex = 0) {
    return JSON.parse(this._b._check(
      this._b._lib.ccl_gov_drep_key_from_mnemonic(this._b._thread, cstr(mnemonic), networkId, accountIndex)));
  }

  committeeColdKeyFromMnemonic(mnemonic, networkId = MAINNET, accountIndex = 0) {
    return JSON.parse(this._b._check(
      this._b._lib.ccl_gov_committee_cold_key_from_mnemonic(this._b._thread, cstr(mnemonic), networkId, accountIndex)));
  }

  committeeHotKeyFromMnemonic(mnemonic, networkId = MAINNET, accountIndex = 0) {
    return JSON.parse(this._b._check(
      this._b._lib.ccl_gov_committee_hot_key_from_mnemonic(this._b._thread, cstr(mnemonic), networkId, accountIndex)));
  }
}

class WalletApi {
  constructor(bridge) { this._b = bridge; }

  create(networkId = MAINNET) {
    return JSON.parse(this._b._check(this._b._lib.ccl_wallet_create(this._b._thread, networkId)));
  }

  fromMnemonic(mnemonic, networkId = MAINNET) {
    return JSON.parse(this._b._check(
      this._b._lib.ccl_wallet_from_mnemonic(this._b._thread, cstr(mnemonic), networkId)));
  }

  getAddress(mnemonic, networkId = MAINNET, index = 0) {
    return this._b._check(
      this._b._lib.ccl_wallet_get_address(this._b._thread, cstr(mnemonic), networkId, index));
  }
}

export class QuickTxApi {
  constructor(bridge) {
    this._b = bridge;
  }

  /**
   * Build an unsigned transaction from a CCL TxPlan (YAML), fully offline.
   *
   * @param {string} txplanYaml - the TxPlan YAML document defining the transaction(s).
   * @param {Array<object>} utxos - UTXOs (CCL Utxo model) available to the sender.
   * @param {object} protocolParams - protocol parameters (CCL ProtocolParams model).
   * @param {Array<{mem: (number|string), steps: (number|string)}>} [execUnits] - optional redeemer
   *   execution units (one per redeemer, in transaction order) for Plutus script transactions.
   *   Compute these with any evaluator (Ogmios, Blockfrost, Aiken, Scalus); the bridge does not run
   *   the script.
   * @returns {{tx_cbor: string, tx_hash: string, fee: string}}
   */
  build(txplanYaml, utxos, protocolParams, execUnits = null) {
    const rc = this._b._lib.ccl_quicktx_build(
      this._b._thread,
      cstr(txplanYaml),
      cstr(JSON.stringify(utxos)),
      cstr(JSON.stringify(normalizeCostModels(protocolParams))),
      execUnits != null ? cstr(JSON.stringify(execUnits)) : null,
    );
    // The build result is a YAML document.
    return parseYaml(this._b._check(rc));
  }

  /**
   * Convenience: fetch chain data from a provider and build, in one call.
   *
   * Composes `provider.utxos(sender)` + `provider.protocolParams()` with {@link QuickTxApi#build}.
   * The bridge stays offline — this only moves the optional HTTP fetch into wrapper code. See
   * `src/providers.js` for available providers (Yaci DevKit, Blockfrost) or supply your own object
   * with `utxos(address)` and `protocolParams()`.
   *
   * @param {string} txplanYaml - the TxPlan YAML document defining the transaction(s).
   * @param {{utxos: (a: string) => Promise<object[]>, protocolParams: () => Promise<object>}} provider
   * @param {string} sender - the address whose UTXOs fund the transaction.
   * @param {Array<{mem: (number|string), steps: (number|string)}>} [execUnits]
   * @returns {Promise<{tx_cbor: string, tx_hash: string, fee: string}>}
   */
  async buildWith(txplanYaml, provider, sender, evaluator = null) {
    const utxos = await provider.utxos(sender);
    const protocolParams = await provider.protocolParams();
    let execUnits = null;
    if (evaluator != null) {
      // Two-pass: draft (units computed offline by Scalus) -> remote evaluate -> rebuild.
      const draft = this.build(txplanYaml, utxos, protocolParams);
      execUnits = await evaluator.evaluate(draft.tx_cbor, utxos);
    }
    return this.build(txplanYaml, utxos, protocolParams, execUnits);
  }
}
