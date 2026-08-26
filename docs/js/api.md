# JavaScript API Reference

All functionality hangs off a `CclBridge` instance. Import what you need from the package root:

```js
import {
  CclBridge, CclError, CclClosedError,
  MAINNET, TESTNET, PREPROD, PREVIEW,
  YaciProvider, BlockfrostProvider, BlockfrostEvaluator,
} from "@bloxbean/cardano-client-lib";
```

The package ships TypeScript definitions (`index.d.ts`) for every class, method, and result shape shown below.

## CclBridge

```ts
constructor(libPath?: string)
version(): string
close(): void
[Symbol.dispose](): void
```

Constructing a bridge loads the native library (see [resolution order](troubleshooting.md#how-the-native-library-is-found)), creates a GraalVM isolate, and verifies the library version matches the wrapper. The API groups are properties: `bridge.account`, `bridge.address`, `bridge.crypto`, `bridge.tx`, `bridge.plutus`, `bridge.script`, `bridge.gov`, `bridge.wallet`, `bridge.quicktx`.

**Lifecycle.** `close()` tears down the isolate and is idempotent. Any call after `close()` throws `CclClosedError` — this is deliberate: passing a stale isolate handle to the native side would abort the whole process uncatchably, so the wrapper converts it into a catchable error. Use `try/finally` or the `using` declaration:

```js
using bridge = new CclBridge();   // closed automatically at end of scope
```

**Threading.** A bridge is bound to the thread that created it. In Bun's single-threaded model this rarely matters; if you use workers, create one bridge per worker.

## Networks

```ts
type Network = 0 | 1 | 2 | 3
MAINNET = 0, TESTNET = 1, PREPROD = 2, PREVIEW = 3
```

Every method that derives keys (`account.*`, `wallet.*`, `gov.*`) requires a `network` argument. Passing `undefined`/`null` throws `TypeError`; an out-of-range value throws `RangeError`.

> **Gotcha:** these constants are CCL enum ordinals, **not** Cardano's on-chain network id — the two are inverted for mainnet/testnet (`MAINNET = 0`, but a mainnet address's on-chain `network_id` is `1`). Never feed `address.info().network_id` back into an API that takes a network.

## Errors

| Class | When |
|---|---|
| `CclError` | A native call failed. Has `.code` (see table below) and `.message` (the native error text). |
| `CclClosedError` | Any API call after `close()`. |
| `TypeError` / `RangeError` | Missing / out-of-range `network` argument. |
| `Error` | Library load failure, isolate creation failure, version mismatch, provider HTTP failures. |

Error codes on `CclError.code`:

| Constant | Code | Meaning |
|---|---|---|
| `CCL_ERROR_GENERAL` | -1 | Unspecified failure |
| `CCL_ERROR_INVALID_ARGUMENT` | -2 | Bad argument |
| `CCL_ERROR_SERIALIZATION` | -3 | (De)serialization failure |
| `CCL_ERROR_CRYPTO` | -4 | Cryptographic failure |
| `CCL_ERROR_INVALID_NETWORK` | -5 | Bad network value |
| `CCL_ERROR_INVALID_MNEMONIC` | -6 | Bad mnemonic |
| `CCL_ERROR_INVALID_ADDRESS` | -7 | Bad address |
| `CCL_ERROR_INSUFFICIENT_FUNDS` | -8 | UTXOs can't cover outputs + fee |
| `CCL_ERROR_INVALID_TRANSACTION` | -9 | Bad transaction |
| `CCL_ERROR_TX_BUILD` | -10 | TxPlan build failure (most common `quicktx.build` error — usually a malformed plan) |

Validation-style methods (`address.validate`, `crypto.validateMnemonic`, `crypto.verify`) return `false` instead of throwing.

## bridge.account

```ts
create(network: Network): AccountInfo
fromMnemonic(mnemonic: string, network: Network, accountIndex = 0, addressIndex = 0): AccountInfo
getPrivateKey(mnemonic: string, network: Network, accountIndex = 0, addressIndex = 0): string
getPublicKey(mnemonic: string, network: Network, accountIndex = 0, addressIndex = 0): string
getDrepId(mnemonic: string, network: Network, accountIndex = 0): string
signTx(mnemonic: string, network: Network, accountIndex: number, addressIndex: number, txCborHex: string): string
signTxWithKeys(mnemonic: string, network: Network, accountIndex: number, addressIndex: number,
               txCborHex: string, keys: SigningKeyRole[] | SigningKeyRole): string
```

`AccountInfo` = `{ mnemonic, base_address, enterprise_address, stake_address, change_address }`.

- `create` generates a fresh 24-word mnemonic; treat the returned `mnemonic` as a secret.
- `getPrivateKey` returns the 64-byte **extended** key as 128 hex chars. For raw Ed25519 signing (`crypto.sign`) use the first 64 hex chars.
- `signTx` witnesses with the payment key only. When a transaction carries certificates that need other witnesses, use `signTxWithKeys` with the roles in order:

```ts
type SigningKeyRole = "payment" | "stake" | "drep" | "committee_cold" | "committee_hot"
```

```js
// A DRep registration needs the payment key (fee) and the DRep key (certificate):
const signed = bridge.account.signTxWithKeys(mnemonic, TESTNET, 0, 0, result.tx_cbor, ["payment", "drep"]);
```

## bridge.address

```ts
info(bech32: string): AddressInfo
validate(bech32: string): boolean
toBytes(bech32: string): string     // hex
fromBytes(hexBytes: string): string // bech32
```

`AddressInfo` = `{ type, network_id, payment_credential_hash?, delegation_credential_hash?, is_pubkey_payment, is_script_payment }`. `type` is e.g. `"Base"`, `"Enterprise"`, `"Pointer"`, `"Reward"`. `network_id` is the genuine on-chain id (mainnet = 1).

## bridge.crypto

```ts
blake2b256(dataHex: string): string
blake2b224(dataHex: string): string
generateMnemonic(wordCount = 24): string
validateMnemonic(mnemonic: string): boolean
sign(messageHex: string, skHex: string): string      // Ed25519; 32-byte key (64 hex chars)
verify(signatureHex: string, messageHex: string, pkHex: string): boolean
```

```js
const digest = bridge.crypto.blake2b256("48656c6c6f");          // "Hello"
const sk = bridge.account.getPrivateKey(mnemonic, TESTNET).slice(0, 64);
const sig = bridge.crypto.sign("68656c6c6f", sk);
```

## bridge.tx

```ts
hash(txCborHex: string): string
signWithSecretKey(txCborHex: string, skCborHex: string): string
toJson(txCborHex: string): string          // JSON string
fromJson(txJson: string): string           // CBOR hex
deserialize(txCborHex: string): TransactionJson   // parsed object
```

`toJson` returns a JSON **string**; `deserialize` returns the parsed object (with a `body` field holding inputs/outputs/fee). `signWithSecretKey` expects a CBOR-encoded secret key, not raw key hex — for mnemonic-based accounts prefer `account.signTx`.

## bridge.plutus

```ts
dataHash(datumCborHex: string): string    // 64 hex chars
dataToJson(cborHex: string): string       // JSON string
dataFromJson(json: string): string        // CBOR hex
```

```js
bridge.plutus.dataHash("182a");   // hash of PlutusData int 42
```

## bridge.script

```ts
nativeFromJson(json: string): string           // JSON: { policy_id, script_hash, cbor_hex }
hash(scriptCborHex: string, scriptType = 0): string
```

`scriptType`: `0` native, `1` PlutusV1, `2` PlutusV2, `3` PlutusV3.

```js
const script = JSON.parse(bridge.script.nativeFromJson(JSON.stringify({ type: "sig", keyHash })));
// script.policy_id, script.script_hash, script.cbor_hex
```

## bridge.gov

```ts
drepKeyFromMnemonic(mnemonic: string, network: Network, accountIndex = 0): DrepKeyInfo
committeeColdKeyFromMnemonic(mnemonic: string, network: Network, accountIndex = 0): CommitteeKeyInfo
committeeHotKeyFromMnemonic(mnemonic: string, network: Network, accountIndex = 0): CommitteeKeyInfo
```

`DrepKeyInfo` = `{ drep_id /* drep1... */, verification_key, verification_key_hash, bech32_verification_key, bech32_verification_key_hash }`. Committee results carry `id` (`cc_cold1...` / `cc_hot1...`) instead of `drep_id`.

## bridge.wallet

HD wallet: one mnemonic, many sequential addresses.

```ts
create(network: Network): WalletInfo
fromMnemonic(mnemonic: string, network: Network): WalletInfo
getAddress(mnemonic: string, network: Network, index = 0): string
```

`WalletInfo` = `{ mnemonic, stake_address, addresses: string[] }`.

## bridge.quicktx

```ts
build(txplanYaml: string, utxos: Utxo[], protocolParams: ProtocolParams,
      execUnits?: ExecUnits[] | null): TxResult
buildWith(txplanYaml: string, provider: ChainDataProvider, sender: string,
          evaluator?: TransactionEvaluator | null): Promise<TxResult>
```

`TxResult` = `{ tx_cbor, tx_hash, fee }` (all strings).

- **`build`** is fully offline: you describe the transaction as [TxPlan YAML](../quicktx.md) and supply the chain data yourself. UTXO selection, fee calculation, and change handling happen inside the native library. It never submits — sign the returned `tx_cbor` and submit with any HTTP client.
- `utxos` is an array of CCL `Utxo` objects: `{ tx_hash, output_index, address, amount: [{ unit, quantity }], data_hash?, inline_datum?, reference_script_hash? }`. `unit` is `"lovelace"` or `policyId + assetNameHex`.
- `protocolParams` is the CCL `ProtocolParams` JSON model. Cost models in the deprecated numerically-keyed map form are normalized automatically (`normalizeCostModels`), preventing `PPViewHashesDontMatch` on Plutus transactions.
- **Large numbers are safe.** Inputs are serialized with `lossless-json`, so quantities above 2^53 survive exactly.
- `execUnits` — for Plutus transactions, `[{ mem, steps }]`, one entry per redeemer in transaction order. When omitted, the native library computes them **offline** with the embedded Scalus evaluator, so script transactions build with no network access. Supply your own to override, or use an [evaluator](providers.md#evaluators) for node-backed costing.
- **`buildWith`** fetches UTXOs and protocol parameters from a [provider](providers.md), then builds. With an evaluator it runs two passes: draft build → remote evaluation → rebuild with the returned units.

```js
const result = bridge.quicktx.build(yaml, utxos, params);
const plutusResult = bridge.quicktx.build(yaml, utxos, params, [{ mem: 2000000, steps: 500000000 }]);
```

## Utility exports

```ts
normalizeCostModels(protocolParams): ProtocolParams  // applied automatically inside build()
parseEvaluation(resp): ExecUnits[]                   // parse Ogmios/Blockfrost evaluate responses
resolveLibFile(libPath?: string): string             // the native library path that would be loaded
platformSuffix(): string                             // e.g. "macos-aarch64", "linux-musl-x86_64"
```
