# Rust API Reference

```rust
use ccl::{Bridge, Network, CclError, Result};
```

Most methods return `ccl::Result<String>` where the string is either a JSON document (parse with `serde_json`) or a bare hex/bech32 value — noted per method below.

## Bridge

```rust
impl Bridge {
    pub fn new() -> Result<Self>;
    pub fn version(&self) -> Result<String>;

    pub fn account(&self) -> AccountApi<'_>;
    pub fn address(&self) -> AddressApi<'_>;
    pub fn crypto(&self)  -> CryptoApi<'_>;
    pub fn tx(&self)      -> TxApi<'_>;
    pub fn plutus(&self)  -> PlutusApi<'_>;
    pub fn script(&self)  -> ScriptApi<'_>;
    pub fn gov(&self)     -> GovApi<'_>;
    pub fn wallet(&self)  -> WalletApi<'_>;
    pub fn quicktx(&self) -> QuickTxApi<'_>;
}
```

`Bridge::new()` creates a GraalVM isolate and verifies the native library version matches the crate.

**Lifecycle.** Teardown is RAII: `Drop` tears down the isolate. The API handles (`AccountApi<'_>` etc.) borrow the bridge, so the borrow checker statically prevents use-after-free.

**Threading.** `Bridge` is **`!Send` and `!Sync`** — moving it to another thread is a compile error. The GraalVM isolate thread is bound to the OS thread that created it; create one `Bridge` per thread.

## Networks

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Network { Mainnet, Testnet, Preprod, Preview }

impl Network { pub fn as_i32(self) -> i32 }  // Mainnet=0, Testnet=1, Preprod=2, Preview=3
```

> **Gotcha:** the ordinals are CCL enum values, **not** Cardano's on-chain network id — the two are inverted for mainnet/testnet (`Mainnet` = 0, but a mainnet address's on-chain `network_id` is `1`). The `network_id` field returned by `address().info()` is the genuine on-chain value; never map it back to a `Network`.

## Errors

```rust
pub struct CclError { pub code: i32, pub message: String }  // Display: "CCL Error {code}: {message}"
pub type Result<T> = std::result::Result<T, CclError>;
```

Error codes (`ccl::error_codes`):

| Constant | Code | Meaning |
|---|---|---|
| `CCL_ERROR_GENERAL` | -1 | Unspecified failure (also: version mismatch, HTTP provider errors) |
| `CCL_ERROR_INVALID_ARGUMENT` | -2 | Bad argument (also: interior NUL in a string) |
| `CCL_ERROR_SERIALIZATION` | -3 | (De)serialization failure |
| `CCL_ERROR_CRYPTO` | -4 | Cryptographic failure |
| `CCL_ERROR_INVALID_NETWORK` | -5 | Bad network value |
| `CCL_ERROR_INVALID_MNEMONIC` | -6 | Bad mnemonic |
| `CCL_ERROR_INVALID_ADDRESS` | -7 | Bad address |
| `CCL_ERROR_INSUFFICIENT_FUNDS` | -8 | UTXOs can't cover outputs + fee |
| `CCL_ERROR_INVALID_TRANSACTION` | -9 | Bad transaction |
| `CCL_ERROR_TX_BUILD` | -10 | TxPlan build failure (most common `quicktx().build` error — usually a malformed plan) |

Predicate methods (`validate`, `validate_mnemonic`, `verify`) return `bool` and never error.

## bridge.account()

```rust
pub fn create(&self, network: Network) -> Result<String>;  // JSON
pub fn from_mnemonic(&self, mnemonic: &str, network: Network, account_index: i32, address_index: i32) -> Result<String>;  // JSON
pub fn get_public_key(&self, mnemonic: &str, network: Network, account_index: i32, address_index: i32) -> Result<String>;  // hex
pub fn get_private_key(&self, mnemonic: &str, network: Network, account_index: i32, address_index: i32) -> Result<String>; // hex (extended)
pub fn get_drep_id(&self, mnemonic: &str, network: Network, account_index: i32) -> Result<String>;  // bech32 drep1...
pub fn sign_tx(&self, mnemonic: &str, network: Network, account_index: i32, address_index: i32, tx_cbor_hex: &str) -> Result<String>;
pub fn sign_tx_with_keys(&self, mnemonic: &str, network: Network, account_index: i32, address_index: i32, tx_cbor_hex: &str, keys: &[&str]) -> Result<String>;
```

`create`/`from_mnemonic` return JSON with `mnemonic`, `base_address`, `enterprise_address`, `stake_address` (and `change_address`).

- `create` generates a fresh 24-word mnemonic; treat it as a secret.
- `get_private_key` returns the 64-byte **extended** key as 128 hex chars. For raw Ed25519 signing (`crypto().sign`) use the first 64 hex chars (`&key[..64]`).
- `sign_tx` witnesses with the payment key only. When a transaction carries certificates that need other witnesses, use `sign_tx_with_keys` with roles in order — valid roles: `"payment"`, `"stake"`, `"drep"`, `"committee_cold"`, `"committee_hot"`:

```rust
// A DRep registration needs the payment key (fee) and the DRep key (certificate):
let signed = bridge.account().sign_tx_with_keys(
    mnemonic, Network::Testnet, 0, 0, &result.tx_cbor, &["payment", "drep"])?;
```

## bridge.address()

```rust
pub fn info(&self, bech32: &str) -> Result<String>;       // JSON
pub fn validate(&self, bech32: &str) -> bool;
pub fn to_bytes(&self, bech32: &str) -> Result<String>;   // hex
pub fn from_bytes(&self, hex_bytes: &str) -> Result<String>; // bech32
```

`info` JSON fields: `type` (`"Base"`, `"Enterprise"`, `"Pointer"`, `"Reward"`), `network_id` (on-chain: 1 = mainnet), `payment_credential_hash`, `delegation_credential_hash`, `is_pubkey_payment`, `is_script_payment`.

## bridge.crypto()

```rust
pub fn blake2b_256(&self, data_hex: &str) -> Result<String>;
pub fn blake2b_224(&self, data_hex: &str) -> Result<String>;
pub fn generate_mnemonic(&self, word_count: i32) -> Result<String>;  // 12 or 24
pub fn validate_mnemonic(&self, mnemonic: &str) -> bool;
pub fn sign(&self, message_hex: &str, sk_hex: &str) -> Result<String>;  // Ed25519; 32-byte key (64 hex chars)
pub fn verify(&self, signature_hex: &str, message_hex: &str, pk_hex: &str) -> bool;
```

```rust
let digest = bridge.crypto().blake2b_256("48656c6c6f")?; // "Hello"
let sk = bridge.account().get_private_key(mnemonic, Network::Testnet, 0, 0)?;
let sig = bridge.crypto().sign(msg_hex, &sk[..64])?;     // first 32 bytes of the extended key
```

## bridge.tx()

```rust
pub fn hash(&self, tx_cbor_hex: &str) -> Result<String>;   // 64-hex tx id
pub fn sign_with_secret_key(&self, tx_cbor_hex: &str, sk_cbor_hex: &str) -> Result<String>;
pub fn to_json(&self, tx_cbor_hex: &str) -> Result<String>;      // JSON
pub fn from_json(&self, tx_json: &str) -> Result<String>;        // CBOR hex
pub fn deserialize(&self, tx_cbor_hex: &str) -> Result<String>;  // JSON
```

`to_json`/`deserialize` return JSON with a `body` object (inputs/outputs/fee). `sign_with_secret_key` expects a CBOR-encoded secret key, not raw key hex — for mnemonic-based accounts prefer `account().sign_tx`.

## bridge.plutus()

```rust
pub fn data_hash(&self, datum_cbor_hex: &str) -> Result<String>;   // 64 hex chars
pub fn data_to_json(&self, cbor_hex: &str) -> Result<String>;
pub fn data_from_json(&self, json: &str) -> Result<String>;        // CBOR hex
```

```rust
let hash = bridge.plutus().data_hash("182a")?;  // hash of PlutusData int 42
```

## bridge.script()

```rust
pub fn native_from_json(&self, json: &str) -> Result<String>;  // JSON: { policy_id, script_hash, cbor_hex }
pub fn hash(&self, script_cbor_hex: &str, script_type: i32) -> Result<String>;  // 56 hex chars
```

`script_type`: `0` native, `1` PlutusV1, `2` PlutusV2, `3` PlutusV3.

```rust
let script_json = format!(r#"{{"type":"sig","keyHash":"{key_hash}"}}"#);
let parsed: serde_json::Value = serde_json::from_str(&bridge.script().native_from_json(&script_json)?)?;
// parsed["policy_id"], parsed["script_hash"], parsed["cbor_hex"]
```

## bridge.gov()

```rust
pub fn drep_key_from_mnemonic(&self, mnemonic: &str, network: Network, account_index: i32) -> Result<String>;
pub fn committee_cold_key_from_mnemonic(&self, mnemonic: &str, network: Network, account_index: i32) -> Result<String>;
pub fn committee_hot_key_from_mnemonic(&self, mnemonic: &str, network: Network, account_index: i32) -> Result<String>;
```

JSON results: the DRep method returns `{ drep_id: "drep1...", verification_key, verification_key_hash, ... }`; committee methods return `{ id: "cc_cold1..." / "cc_hot1...", ... }`.

## bridge.wallet()

HD wallet: one mnemonic, many sequential addresses.

```rust
pub fn create(&self, network: Network) -> Result<String>;  // JSON: { mnemonic, stake_address, addresses }
pub fn from_mnemonic(&self, mnemonic: &str, network: Network) -> Result<String>;
pub fn get_address(&self, mnemonic: &str, network: Network, index: i32) -> Result<String>;  // bech32
```

## bridge.quicktx()

```rust
#[derive(Debug, serde::Deserialize)]
pub struct TxResult { pub tx_cbor: String, pub tx_hash: String, pub fee: String }

pub fn build(&self, yaml: &str, utxos: &serde_json::Value, protocol_params: &serde_json::Value,
             exec_units: Option<&serde_json::Value>) -> Result<TxResult>;

// with `--features providers`:
pub fn build_with(&self, yaml: &str, provider: &dyn ChainDataProvider, sender: &str,
                  evaluator: Option<&dyn TransactionEvaluator>) -> Result<TxResult>;
```

- **`build`** is fully offline: you describe the transaction as [TxPlan YAML](../quicktx.md) and supply the chain data yourself as `serde_json::Value`s. UTXO selection, fee calculation, and change handling happen inside the native library. It never submits — sign the returned `tx_cbor` and submit with any HTTP client.
- `utxos` is a JSON array of CCL `Utxo` objects: `{tx_hash, output_index, address, amount: [{unit, quantity}]}`. `unit` is `"lovelace"` or `policyId + assetNameHex`. Quantities are best passed as **strings** (`"quantity": "5000000"`), matching the canonical CCL model.
- `protocol_params` is the CCL `ProtocolParams` JSON model; unknown fields are ignored.
- `exec_units` — for Plutus transactions, `Some(&json!([{"mem": ..., "steps": ...}]))`, one entry per redeemer in transaction order. Pass `None` to let the native library compute them **offline** with the embedded Scalus evaluator.
- **`build_with`** fetches UTXOs and protocol parameters from a [provider](providers.md), then builds. With an evaluator it runs two passes: draft build → remote evaluation → rebuild with the returned units.

```rust
use serde_json::json;

let result = bridge.quicktx().build(&yaml, &utxos, &params, None)?;

let plutus = bridge.quicktx().build(&yaml, &utxos, &params,
    Some(&json!([{"mem": 2000000, "steps": 500000000}])))?;
```
