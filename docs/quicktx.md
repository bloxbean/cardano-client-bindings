# QuickTx — TxPlan (YAML) Transaction Builder

QuickTx builds unsigned Cardano transactions **fully offline** from a CCL
[**TxPlan**](https://github.com/bloxbean/cardano-client-lib) — a YAML document describing what the
transaction should do. You pass the TxPlan YAML plus the chain data the build needs (UTXOs and
protocol parameters), and get back an unsigned transaction in CBOR hex.

The whole interface is YAML: **TxPlan YAML in → YAML result out**.

## Overview

- **Single function**: `ccl_quicktx_build(thread, yaml, utxos_json, protocol_params_json)` → returns `0` on success.
- **Result**: a YAML document with `tx_cbor` (unsigned transaction), `tx_hash`, and `fee`.
- **Fully offline**: the caller supplies UTXOs and protocol parameters — the native library makes no
  HTTP calls and never submits. (There is no provider mode; fetching chain data is the caller's job.)
- **Client-side chain data**: UTXOs and protocol parameters are passed as JSON (the standard CCL
  `Utxo` / `ProtocolParams` models).

### Entry point

```c
int ccl_quicktx_build(
    graal_isolatethread_t* thread,
    const char* yaml,                  // TxPlan YAML
    const char* utxos_json,            // JSON array of UTXOs
    const char* protocol_params_json   // JSON protocol parameters
);
```

### Return codes

| Code | Meaning |
|------|---------|
| `0`  | Success — retrieve the result via `ccl_get_result(thread)` |
| `-2` | Invalid argument (e.g. missing YAML or protocol parameters) |
| `-8` | Insufficient funds (UTXOs can't cover outputs + fees) |
| `-10`| Transaction build failure (e.g. malformed TxPlan) |

### Success result (YAML)

```yaml
tx_cbor: 84a400...
tx_hash: abcd1234...
fee: "173333"
```

---

## TxPlan — YAML structure

A TxPlan is a YAML document with an optional `version`, optional `variables` for substitution, and a
`transaction` list. Each list entry has a `tx` block with the sender (`from`) and a list of `intents`.

```yaml
version: 1.0

# Optional: ${name} placeholders are substituted before the plan is parsed.
variables:
  to: addr_test1...
  amount: "5000000"

transaction:
  - tx:
      from: addr_test1...            # sender / default fee payer
      intents:
        - type: payment
          address: ${to}
          amounts:
            - unit: lovelace
              quantity: ${amount}
```

Multiple entries in `transaction` are **composed** into a single transaction (each `tx` may have a
different `from`). The `tx` block also accepts context such as a fee payer and a validity interval;
those fields follow CCL's TxPlan serialization (see the reference link above).

### Intents

Each intent has a `type` discriminator. The full set supported by CCL's TxPlan:

| `type` | Purpose |
|--------|---------|
| `payment` | Pay ADA / native tokens to an address |
| `minting` | Mint/burn with a native script |
| `metadata` | Attach transaction metadata |
| `donation` | Treasury donation |
| `stake_registration` | Register a stake address |
| `stake_deregistration` | Deregister a stake address |
| `stake_delegation` | Delegate to a stake pool |
| `stake_withdrawal` | Withdraw staking rewards |
| `drep_registration` / `drep_deregistration` / `drep_update` | DRep lifecycle |
| `voting` | Cast a governance vote |
| `voting_delegation` | Delegate voting power to a DRep |
| `governance_proposal` | Submit a governance action |
| `pool_registration` / `pool_update` / `pool_retirement` | Stake-pool lifecycle |
| `collect_from` | Explicitly select input UTXOs |
| `reference_input` | Add read-only reference inputs |
| `native_script` | Attach a native script |
| `script_collect_from` / `script_minting` / `validator` | Plutus script operations |

> The exact YAML fields for each intent come from CCL's TxPlan serialization. This bridge passes the
> YAML through unchanged, so the authoritative field reference is the CCL `quicktx` module
> (`intent/*Intent.java` and the `TxMetadataSerializationTest` / TxPlan tests at `v0.8.0-pre4`).
> Verified `payment` and `metadata` shapes are shown below.

> **Plutus script spend/mint is deferred.** Building a Plutus transaction needs offline
> execution-unit evaluation, which `0.8.0-pre4` does not provide. Plans containing Plutus script
> intents fail with `-10`. Non-Plutus surfaces (payments, native mint, staking, governance, pools,
> metadata, treasury) build offline today.

---

## Chain data (caller-supplied)

### UTXO format

`utxos_json` is a JSON array in the standard Blockfrost/Koios/DevKit shape:

```json
[
  {
    "tx_hash": "aaaa...64hex",
    "output_index": 0,
    "address": "addr_test1...",
    "amount": [
      { "unit": "lovelace", "quantity": "100000000" },
      { "unit": "policy_hex+asset_name_hex", "quantity": "500" }
    ]
  }
]
```

### Protocol parameters

`protocol_params_json` is a JSON object in the standard protocol-parameters shape (`min_fee_a`,
`min_fee_b`, `key_deposit`, `pool_deposit`, `coins_per_utxo_size`, the Conway governance deposits,
etc.). The CCL `ProtocolParams` model deserializes it directly.

---

## Examples

### 1. Simple ADA payment

```yaml
version: 1.0
transaction:
  - tx:
      from: addr_test1qp...
      intents:
        - type: payment
          address: addr_test1qz...
          amounts:
            - unit: lovelace
              quantity: "5000000"
```

### 2. Multiple payments (one sender)

```yaml
version: 1.0
transaction:
  - tx:
      from: addr_test1qp...
      intents:
        - type: payment
          address: addr_test1_receiver1...
          amounts:
            - unit: lovelace
              quantity: "5000000"
        - type: payment
          address: addr_test1_receiver2...
          amounts:
            - unit: lovelace
              quantity: "3000000"
```

### 3. Variable substitution

```yaml
version: 1.0
variables:
  to: addr_test1qz...
  amount: "4000000"
transaction:
  - tx:
      from: addr_test1qp...
      intents:
        - type: payment
          address: ${to}
          amounts:
            - unit: lovelace
              quantity: ${amount}
```

### 4. Payment with metadata

The `metadata` intent's value is a **scalar string** that the deserializer auto-detects — pass it as
a JSON string (it may also be CBOR hex). Labels are the top-level keys:

```yaml
version: 1.0
transaction:
  - tx:
      from: addr_test1qp...
      intents:
        - type: payment
          address: addr_test1qz...
          amounts:
            - unit: lovelace
              quantity: "2000000"
        - type: metadata
          metadata: '{"674": {"msg": "Hello from CCL Bridge"}}'
```

---

## Using it from the wrappers

Each wrapper exposes a thin `build(yaml, utxos, protocolParams)` that marshals the chain data to JSON,
calls `ccl_quicktx_build`, and parses the YAML result. The result is an object/dict/struct with
`tx_cbor`, `tx_hash`, and `fee`. `ccl_quicktx_build` returns an **unsigned** transaction — sign
`tx_cbor` with the account sign API, then submit it yourself.

### Python

```python
from ccl import CclLib

lib = CclLib()
result = lib.quicktx.build(txplan_yaml, utxos, protocol_params)  # -> {"tx_cbor","tx_hash","fee"}
signed = lib.account.sign_tx(mnemonic, result["tx_cbor"], CclLib.TESTNET, 0, 0)
```

### JavaScript (Bun)

```javascript
import { CclBridge, TESTNET } from '@bloxbean/ccl';

const bridge = new CclBridge();
const result = bridge.quicktx.build(txplanYaml, utxos, protocolParams);
const signed = bridge.account.signTx(mnemonic, TESTNET, 0, 0, result.tx_cbor);
```

### Go

```go
bridge, _ := ccl.New()
defer bridge.Close()

result, _ := bridge.QuickTx.Build(txplanYaml, utxos, protocolParams)
signed, _ := bridge.Account.SignTx(mnemonic, ccl.Testnet, 0, 0, result.TxCbor)
```

### Rust

```rust
let bridge = ccl::Bridge::new().unwrap();

let result = bridge.quicktx().build(&txplan_yaml, &utxos, &protocol_params).unwrap();
let signed = bridge.account()
    .sign_tx(&mnemonic, ccl::network::TESTNET, 0, 0, &result.tx_cbor)
    .unwrap();
```

See each wrapper's `examples/transaction.*` for a complete build-and-sign program.
