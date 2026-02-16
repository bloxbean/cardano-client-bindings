# QuickTx — Transaction Builder

QuickTx is a JSON-driven offline transaction builder exposed through a single C function: `ccl_quicktx_build`. You pass a JSON specification describing what the transaction should do — payments, staking, governance, Plutus scripts — and get back an unsigned transaction in CBOR hex.

## Overview

- **Single function**: `ccl_quicktx_build(thread, spec_json)` → returns `0` on success
- **Result**: JSON with `tx_cbor` (unsigned transaction), `tx_hash`, and `fee`
- **Offline by default**: supply UTXOs and protocol params inline — no HTTP calls from the native library
- **Provider mode**: optionally point to a Yaci DevKit (or compatible) API to fetch UTXOs automatically
- **Two transaction types**: `tx` (regular) and `script_tx` (Plutus script transactions)
- **Compose mode**: combine multiple sub-transactions (even from different senders) into one

### Return Codes

| Code | Meaning |
|------|---------|
| `0` | Success — retrieve result via `ccl_get_result(thread)` |
| `-2` | Invalid argument or validation failure |
| `-8` | Insufficient funds (UTXOs can't cover outputs + fees) |
| `-10` | Transaction build failure |

### Success Result

```json
{
  "tx_cbor": "84a400...",
  "tx_hash": "abcd1234...",
  "fee": "173333"
}
```

---

## TxSpec — Top-Level JSON Structure

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `operations` | array | Yes (single mode) | List of operations to perform |
| `from` | string | Yes (`tx` mode) | Sender bech32 address |
| `change_address` | string | No | Change output address (defaults to `from`) |
| `fee_payer` | string | Yes (compose mode) | Address that pays the fee |
| `utxos` | array | Yes (inline mode) | Pre-supplied UTXOs for coin selection |
| `protocol_params` | object | Yes (inline mode) | Protocol parameters (Blockfrost/Koios/DevKit format) |
| `validity` | object | No | Slot-based validity interval |
| `signer_count` | integer | No | Number of signers for fee estimation (default: 1) |
| `merge_outputs` | boolean | No | Merge outputs to the same address |
| `transactions` | array | Yes (compose mode) | Array of sub-transactions |
| `provider` | object | No | Provider config for HTTP-based UTXO fetching |
| `tx_type` | string | No | `"tx"` (default) or `"script_tx"` |
| `change_datum_cbor_hex` | string | No | Inline datum CBOR hex for change output (script_tx) |
| `change_datum_hash` | string | No | Datum hash for change output (script_tx) |

### Validity

```json
"validity": {
  "valid_from": 1000000,
  "valid_to": 2000000
}
```

### UTXO Format

UTXOs use the standard Blockfrost/Koios/DevKit format:

```json
{
  "tx_hash": "aaaa...64hex",
  "output_index": 0,
  "address": "addr_test1...",
  "amount": [
    { "unit": "lovelace", "quantity": "100000000" },
    { "unit": "policy_hex+asset_name_hex", "quantity": "500" }
  ]
}
```

---

## Operations Reference

### Payments

#### `pay_to_address`

Send ADA or tokens to an address.

```json
{
  "type": "pay_to_address",
  "address": "addr_test1...",
  "amounts": [
    { "unit": "lovelace", "quantity": "5000000" },
    { "unit": "aabb...policy...asset_hex", "quantity": "100" }
  ]
}
```

Optional fields: `script_ref_cbor_hex`, `script_ref_type` (attach a reference script to the output).

#### `pay_to_contract`

Send to a script address with a datum.

```json
{
  "type": "pay_to_contract",
  "address": "addr_test1wz...",
  "amounts": [{ "unit": "lovelace", "quantity": "10000000" }],
  "datum_cbor_hex": "d87980"
}
```

Use either `datum_cbor_hex` (inline datum) or `datum_hash`. Optional: `script_ref_cbor_hex`, `script_ref_type`.

### Minting

#### `mint_assets` (native scripts)

Mint tokens using a native script (regular `tx` mode only).

```json
{
  "type": "mint_assets",
  "script_json": "{\"type\":\"all\",\"scripts\":[{\"type\":\"sig\",\"keyHash\":\"ab12...\"}]}",
  "assets": [
    { "name": "MyToken", "quantity": "1000" }
  ],
  "receiver": "addr_test1..."
}
```

#### `mint_plutus_assets` (Plutus scripts)

Mint tokens using a Plutus script (`script_tx` mode only).

```json
{
  "type": "mint_plutus_assets",
  "script_cbor_hex": "46450101002499",
  "script_type": "plutus_v3",
  "assets": [
    { "name": "TestToken", "quantity": "100" }
  ],
  "redeemer_cbor_hex": "d87980",
  "receiver": "addr_test1..."
}
```

Optional: `output_datum_cbor_hex` (attach datum to mint output).

### Metadata

#### `attach_metadata`

Attach transaction metadata.

```json
{
  "type": "attach_metadata",
  "label": 674,
  "metadata": {
    "msg": ["Hello from CCL Bridge"]
  }
}
```

The `metadata` field accepts strings, numbers, lists, and maps.

### UTXO Collection

#### `collect_from`

Explicitly select input UTXOs (bypasses coin selection). In `script_tx` mode, include `redeemer_cbor_hex` and `datum_cbor_hex` for script-locked UTXOs.

```json
{
  "type": "collect_from",
  "collect_utxos": [
    {
      "tx_hash": "aaaa...",
      "output_index": 0,
      "address": "addr_test1...",
      "amount": [{ "unit": "lovelace", "quantity": "10000000" }]
    }
  ],
  "redeemer_cbor_hex": "d87980",
  "datum_cbor_hex": "d87980"
}
```

#### `read_from` (script_tx only)

Add reference inputs (read-only, not consumed).

```json
{
  "type": "read_from",
  "reference_inputs": [
    { "tx_hash": "abcd...", "output_index": 0 }
  ]
}
```

### Staking

#### `register_stake_address`

Register a stake address (regular `tx` mode only — not available in `script_tx`).

```json
{ "type": "register_stake_address", "address": "stake_test1..." }
```

#### `deregister_stake_address`

```json
{
  "type": "deregister_stake_address",
  "address": "stake_test1...",
  "refund_address": "addr_test1..."
}
```

In `script_tx` mode, also requires `redeemer_cbor_hex`.

#### `delegate_to`

Delegate to a stake pool.

```json
{
  "type": "delegate_to",
  "address": "stake_test1...",
  "pool_id": "pool1..."
}
```

In `script_tx` mode, also requires `redeemer_cbor_hex`.

#### `withdraw`

Withdraw staking rewards.

```json
{
  "type": "withdraw",
  "reward_address": "stake_test1...",
  "amount": "5000000",
  "receiver": "addr_test1..."
}
```

In `script_tx` mode, also requires `redeemer_cbor_hex`.

### DRep Operations

#### `register_drep`

```json
{
  "type": "register_drep",
  "credential_hash": "ab12...56hex",
  "credential_type": "key",
  "anchor_url": "https://example.com/drep.json",
  "anchor_data_hash": "cd34...64hex"
}
```

`credential_type`: `"key"` (default) or `"script"`. In `script_tx` mode, also requires `redeemer_cbor_hex`.

#### `unregister_drep`

```json
{
  "type": "unregister_drep",
  "credential_hash": "ab12...56hex",
  "refund_address": "addr_test1...",
  "refund_amount": "500000000"
}
```

#### `update_drep`

```json
{
  "type": "update_drep",
  "credential_hash": "ab12...56hex",
  "anchor_url": "https://example.com/drep-updated.json",
  "anchor_data_hash": "ef56...64hex"
}
```

### Voting

#### `delegate_voting_power_to`

```json
{
  "type": "delegate_voting_power_to",
  "address": "stake_test1...",
  "drep_type": "key_hash",
  "drep_hash": "ab12...56hex"
}
```

`drep_type` values: `"key_hash"` (default), `"script_hash"`, `"abstain"`, `"no_confidence"`.

#### `create_vote`

```json
{
  "type": "create_vote",
  "voter_type": "drep_key_hash",
  "voter_hash": "ab12...56hex",
  "gov_action_tx_hash": "aaaa...64hex",
  "gov_action_index": 0,
  "vote": "yes",
  "anchor_url": "https://example.com/rationale.json",
  "anchor_data_hash": "cd34...64hex"
}
```

`voter_type` values: `"drep_key_hash"`, `"drep_script_hash"`, `"staking_pool_key_hash"`, `"constitutional_committee_hot_key_hash"`, `"constitutional_committee_hot_script_hash"`.

`vote` values: `"yes"`, `"no"`, `"abstain"`.

### Governance Proposals

#### `create_proposal`

All proposals require `gov_action_type`, `return_address`, and optionally `anchor_url` / `anchor_data_hash`. Action-specific fields vary by type:

**`info_action`** — No additional fields.

```json
{
  "type": "create_proposal",
  "gov_action_type": "info_action",
  "return_address": "stake_test1...",
  "anchor_url": "https://example.com/proposal.json",
  "anchor_data_hash": "ab12...64hex"
}
```

**`treasury_withdrawals`**

```json
{
  "type": "create_proposal",
  "gov_action_type": "treasury_withdrawals",
  "return_address": "stake_test1...",
  "withdrawals": [
    { "reward_address": "stake_test1...", "amount": "50000000" }
  ]
}
```

**`no_confidence`**

```json
{
  "type": "create_proposal",
  "gov_action_type": "no_confidence",
  "return_address": "stake_test1...",
  "gov_action_tx_hash": "prev_action_tx_hash",
  "gov_action_index": 0
}
```

**`update_committee`**

```json
{
  "type": "create_proposal",
  "gov_action_type": "update_committee",
  "return_address": "stake_test1...",
  "members_to_remove": [{ "hash": "ab12...", "type": "key" }],
  "new_members": [{ "hash": "cd34...", "type": "key", "epoch": 500 }],
  "quorum_numerator": "2",
  "quorum_denominator": "3"
}
```

**`new_constitution`**

```json
{
  "type": "create_proposal",
  "gov_action_type": "new_constitution",
  "return_address": "stake_test1...",
  "constitution_anchor_url": "https://example.com/constitution.json",
  "constitution_anchor_data_hash": "ef56...64hex",
  "constitution_script_hash": "ab12...56hex"
}
```

**`hard_fork_initiation`**

```json
{
  "type": "create_proposal",
  "gov_action_type": "hard_fork_initiation",
  "return_address": "stake_test1...",
  "protocol_version_major": 10,
  "protocol_version_minor": 0
}
```

**`parameter_change`**

```json
{
  "type": "create_proposal",
  "gov_action_type": "parameter_change",
  "return_address": "stake_test1...",
  "policy_hash": "ab12...56hex"
}
```

### Pool Operations

#### `register_pool`

```json
{
  "type": "register_pool",
  "operator": "ab12...56hex",
  "vrf_key_hash": "cd34...64hex",
  "pledge": "100000000",
  "cost": "340000000",
  "margin_numerator": "1",
  "margin_denominator": "100",
  "reward_address": "e0ab...58hex",
  "pool_owners": ["ab12...56hex"],
  "relays": [
    { "type": "single_host_addr", "port": 6000, "ipv4": "127.0.0.1" },
    { "type": "single_host_name", "port": 6000, "dns_name": "relay.example.com" },
    { "type": "multi_host_name", "dns_name": "pool.example.com" }
  ],
  "pool_metadata_url": "https://example.com/pool.json",
  "pool_metadata_hash": "ef56...64hex"
}
```

#### `update_pool`

Same fields as `register_pool`.

#### `retire_pool`

```json
{
  "type": "retire_pool",
  "pool_id": "pool1...",
  "epoch": 500
}
```

### Treasury

#### `donate_to_treasury`

```json
{
  "type": "donate_to_treasury",
  "treasury_value": "1000000000",
  "donation_amount": "50000000"
}
```

### Script Validators (script_tx only)

Attach validators to the transaction witness set. Required when spending from script addresses, minting with Plutus, or performing script-based staking/governance.

```json
{ "type": "attach_spending_validator", "script_cbor_hex": "46450101002499", "script_type": "plutus_v3" }
{ "type": "attach_certificate_validator", "script_cbor_hex": "...", "script_type": "plutus_v3" }
{ "type": "attach_reward_validator", "script_cbor_hex": "...", "script_type": "plutus_v3" }
{ "type": "attach_proposing_validator", "script_cbor_hex": "...", "script_type": "plutus_v3" }
{ "type": "attach_voting_validator", "script_cbor_hex": "...", "script_type": "plutus_v3" }
```

`script_type` values: `"plutus_v1"`, `"plutus_v2"`, `"plutus_v3"`.

### Native Scripts

#### `attach_native_script`

Attach a native script to the witness set (regular `tx` mode).

```json
{
  "type": "attach_native_script",
  "script_json": "{\"type\":\"all\",\"scripts\":[{\"type\":\"sig\",\"keyHash\":\"ab12...\"}]}"
}
```

---

## UTXO Sourcing Modes

There are three ways to supply UTXOs and protocol parameters to QuickTx.

### 1. Inline Mode

The caller fetches UTXOs and protocol params from any source (Blockfrost, Koios, Ogmios, a database, etc.) and passes them directly in the JSON spec.

```
App fetches UTXOs + protocol params
    ↓
Builds JSON spec with inline "utxos" and "protocol_params"
    ↓
Calls ccl_quicktx_build
    ↓
Gets unsigned tx CBOR
```

**Example:**
```json
{
  "operations": [{ "type": "pay_to_address", "address": "...", "amounts": [...] }],
  "from": "addr_test1...",
  "utxos": [{ "tx_hash": "...", "output_index": 0, "address": "...", "amount": [...] }],
  "protocol_params": { "min_fee_a": 44, "min_fee_b": 155381, "..." : "..." },
  "signer_count": 1
}
```

Best for: full control over UTXO selection, custom backends, offline workflows, caching.

### 2. Provider Mode

Instead of inline data, set the `provider` field. The native library fetches UTXOs and protocol params via HTTP internally.

```
App builds JSON spec with "provider" config (no "utxos"/"protocol_params")
    ↓
Calls ccl_quicktx_build
    ↓
Library fetches UTXOs + params from provider via HTTP
    ↓
Builds transaction
    ↓
Returns unsigned tx CBOR
```

**Provider config:**
```json
{
  "provider": {
    "name": "yaci",
    "url": "http://localhost:8080/api/v1",
    "api_key": "optional-api-key",
    "enable_cost_evaluation": true
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Provider name (currently only `"yaci"`) |
| `url` | string | Yes | Base URL of the API |
| `api_key` | string | No | API key for authentication |
| `enable_cost_evaluation` | boolean | No | Enable Plutus script cost evaluation (default: true) |

The provider fetches UTXOs via `GET {url}/addresses/{address}/utxos` and protocol params via `GET {url}/epochs/parameters`. When `enable_cost_evaluation` is true and `script_tx` is used, it evaluates Plutus costs via `POST {url}/utils/txs/evaluate`.

Best for: simple setups, quick prototyping, when a compatible provider is available.

### 3. Wrapper-Level Provider

Some wrappers (Python, JS) support a wrapper-level provider config. The wrapper handles HTTP calls (not the native library), then passes data inline.

```
App configures provider on wrapper
    ↓
Calls build via wrapper API
    ↓
Wrapper fetches UTXOs + params via HTTP
    ↓
Constructs inline spec with fetched data
    ↓
Calls ccl_quicktx_build
    ↓
Returns unsigned tx CBOR
```

Best for: wrapper users who want provider convenience with wrapper-native HTTP (better error handling, auth headers, retries).

### Comparison

| | Inline | Provider | Wrapper Provider |
|---|--------|----------|-----------------|
| **Who fetches UTXOs?** | Your app | Native library | Wrapper library |
| **HTTP calls from native lib?** | No | Yes | No |
| **Provider dependency** | None | Yaci-compatible API | Wrapper-specific |
| **Flexibility** | Maximum | Limited to supported providers | Medium |
| **Simplicity** | More setup | Simplest | Simple |
| **Offline capable?** | Yes | No | No |

---

## Compose Mode

Compose mode builds a single transaction from multiple sub-transactions, potentially from different senders. Set the `transactions` array instead of top-level `operations`.

```json
{
  "transactions": [
    {
      "from": "addr_test1_sender1...",
      "operations": [
        { "type": "pay_to_address", "address": "addr_test1_receiver1...",
          "amounts": [{ "unit": "lovelace", "quantity": "5000000" }] }
      ]
    },
    {
      "from": "addr_test1_sender2...",
      "operations": [
        { "type": "pay_to_address", "address": "addr_test1_receiver2...",
          "amounts": [{ "unit": "lovelace", "quantity": "3000000" }] }
      ]
    }
  ],
  "fee_payer": "addr_test1_sender1...",
  "utxos": [
    { "tx_hash": "aaa...", "output_index": 0, "address": "addr_test1_sender1...",
      "amount": [{ "unit": "lovelace", "quantity": "100000000" }] },
    { "tx_hash": "bbb...", "output_index": 0, "address": "addr_test1_sender2...",
      "amount": [{ "unit": "lovelace", "quantity": "100000000" }] }
  ],
  "protocol_params": { "..." : "..." }
}
```

Each sub-transaction item supports:

| Field | Type | Required |
|-------|------|----------|
| `from` | string | Yes (except `script_tx`) |
| `change_address` | string | No |
| `operations` | array | Yes |
| `tx_type` | string | No (default `"tx"`) |
| `change_datum_cbor_hex` | string | No |
| `change_datum_hash` | string | No |

You can mix `tx` and `script_tx` items in the same compose transaction. The `signer_count` defaults to the number of sub-transactions if not set.

---

## Script_tx Mode

Set `"tx_type": "script_tx"` to build Plutus script transactions. Key differences from regular `tx`:

- `from` is optional (use `fee_payer` to specify who pays fees)
- Staking, DRep, voting, and governance operations require a `redeemer_cbor_hex` field
- `register_stake_address` is **not available** — use regular `tx` mode for that
- `mint_assets` is **not available** — use `mint_plutus_assets` instead
- Additional operations: `read_from`, `mint_plutus_assets`, `attach_*_validator`

### Example: Collect from Script and Spend

```json
{
  "tx_type": "script_tx",
  "operations": [
    {
      "type": "collect_from",
      "collect_utxos": [
        { "tx_hash": "aaa...", "output_index": 0, "address": "addr_test1wz...",
          "amount": [{ "unit": "lovelace", "quantity": "10000000" }] }
      ],
      "redeemer_cbor_hex": "d87980",
      "datum_cbor_hex": "d87980"
    },
    {
      "type": "attach_spending_validator",
      "script_cbor_hex": "46450101002499",
      "script_type": "plutus_v3"
    },
    {
      "type": "pay_to_address",
      "address": "addr_test1...",
      "amounts": [{ "unit": "lovelace", "quantity": "5000000" }]
    }
  ],
  "from": "addr_test1...",
  "utxos": [
    { "tx_hash": "bbb...", "output_index": 0, "address": "addr_test1...",
      "amount": [{ "unit": "lovelace", "quantity": "100000000" }] }
  ],
  "protocol_params": { "..." : "..." },
  "signer_count": 1
}
```

---

## Examples

### 1. Simple ADA Payment (Inline UTXOs)

```json
{
  "operations": [
    {
      "type": "pay_to_address",
      "address": "addr_test1qz...",
      "amounts": [{ "unit": "lovelace", "quantity": "5000000" }]
    }
  ],
  "from": "addr_test1qp...",
  "utxos": [
    {
      "tx_hash": "aaaa...64hex",
      "output_index": 0,
      "address": "addr_test1qp...",
      "amount": [{ "unit": "lovelace", "quantity": "100000000" }]
    }
  ],
  "protocol_params": { "min_fee_a": 44, "min_fee_b": 155381, "..." : "..." },
  "signer_count": 1
}
```

### 2. Multi-Asset Payment

```json
{
  "operations": [
    {
      "type": "pay_to_address",
      "address": "addr_test1qz...",
      "amounts": [
        { "unit": "lovelace", "quantity": "2000000" },
        { "unit": "aabb...policyId...assetNameHex", "quantity": "100" }
      ]
    }
  ],
  "from": "addr_test1qp...",
  "utxos": [
    {
      "tx_hash": "aaaa...64hex",
      "output_index": 0,
      "address": "addr_test1qp...",
      "amount": [
        { "unit": "lovelace", "quantity": "100000000" },
        { "unit": "aabb...policyId...assetNameHex", "quantity": "500" }
      ]
    }
  ],
  "protocol_params": { "..." : "..." },
  "signer_count": 1
}
```

### 3. Payment with Metadata

```json
{
  "operations": [
    {
      "type": "pay_to_address",
      "address": "addr_test1qz...",
      "amounts": [{ "unit": "lovelace", "quantity": "2000000" }]
    },
    {
      "type": "attach_metadata",
      "label": 674,
      "metadata": { "msg": ["Hello from CCL Bridge"] }
    }
  ],
  "from": "addr_test1qp...",
  "utxos": [{ "..." : "..." }],
  "protocol_params": { "..." : "..." },
  "signer_count": 1
}
```

### 4. Staking Delegation

```json
{
  "operations": [
    {
      "type": "register_stake_address",
      "address": "stake_test1ur..."
    },
    {
      "type": "delegate_to",
      "address": "stake_test1ur...",
      "pool_id": "pool1abc..."
    }
  ],
  "from": "addr_test1qp...",
  "utxos": [{ "..." : "..." }],
  "protocol_params": { "..." : "..." },
  "signer_count": 1
}
```

### 5. Governance — Register DRep and Create Vote

**Register DRep:**
```json
{
  "operations": [
    {
      "type": "register_drep",
      "credential_hash": "ab12...56hex",
      "anchor_url": "https://example.com/drep.json",
      "anchor_data_hash": "cd34...64hex"
    }
  ],
  "from": "addr_test1qp...",
  "utxos": [{ "..." : "..." }],
  "protocol_params": { "..." : "..." },
  "signer_count": 1
}
```

**Create Vote:**
```json
{
  "operations": [
    {
      "type": "create_vote",
      "voter_type": "drep_key_hash",
      "voter_hash": "ab12...56hex",
      "gov_action_tx_hash": "aaaa...64hex",
      "gov_action_index": 0,
      "vote": "yes",
      "anchor_url": "https://example.com/rationale.json",
      "anchor_data_hash": "ef56...64hex"
    }
  ],
  "from": "addr_test1qp...",
  "utxos": [{ "..." : "..." }],
  "protocol_params": { "..." : "..." },
  "signer_count": 1
}
```

**Create Info Action Proposal:**
```json
{
  "operations": [
    {
      "type": "create_proposal",
      "gov_action_type": "info_action",
      "return_address": "stake_test1ur...",
      "anchor_url": "https://example.com/proposal.json",
      "anchor_data_hash": "ab12...64hex"
    }
  ],
  "from": "addr_test1qp...",
  "utxos": [{ "..." : "..." }],
  "protocol_params": { "..." : "..." },
  "signer_count": 1
}
```

### 6. ScriptTx — Collect from Script with Plutus Validator

```json
{
  "tx_type": "script_tx",
  "operations": [
    {
      "type": "collect_from",
      "collect_utxos": [
        {
          "tx_hash": "aaa...64hex",
          "output_index": 0,
          "address": "addr_test1wz...",
          "amount": [{ "unit": "lovelace", "quantity": "10000000" }]
        }
      ],
      "redeemer_cbor_hex": "d87980",
      "datum_cbor_hex": "d87980"
    },
    {
      "type": "attach_spending_validator",
      "script_cbor_hex": "46450101002499",
      "script_type": "plutus_v3"
    },
    {
      "type": "pay_to_address",
      "address": "addr_test1qz...",
      "amounts": [{ "unit": "lovelace", "quantity": "5000000" }]
    }
  ],
  "from": "addr_test1qp...",
  "utxos": [
    {
      "tx_hash": "bbb...64hex",
      "output_index": 0,
      "address": "addr_test1qp...",
      "amount": [{ "unit": "lovelace", "quantity": "100000000" }]
    }
  ],
  "protocol_params": { "..." : "..." },
  "signer_count": 1
}
```

### 7. Compose Mode — Two Senders

```json
{
  "transactions": [
    {
      "from": "addr_test1_sender1...",
      "operations": [
        {
          "type": "pay_to_address",
          "address": "addr_test1_receiver1...",
          "amounts": [{ "unit": "lovelace", "quantity": "5000000" }]
        }
      ]
    },
    {
      "from": "addr_test1_sender2...",
      "operations": [
        {
          "type": "pay_to_address",
          "address": "addr_test1_receiver2...",
          "amounts": [{ "unit": "lovelace", "quantity": "3000000" }]
        }
      ]
    }
  ],
  "fee_payer": "addr_test1_sender1...",
  "utxos": [
    {
      "tx_hash": "aaa...", "output_index": 0,
      "address": "addr_test1_sender1...",
      "amount": [{ "unit": "lovelace", "quantity": "100000000" }]
    },
    {
      "tx_hash": "bbb...", "output_index": 0,
      "address": "addr_test1_sender2...",
      "amount": [{ "unit": "lovelace", "quantity": "100000000" }]
    }
  ],
  "protocol_params": { "..." : "..." }
}
```

### 8. Provider Mode — Using Yaci DevKit

```json
{
  "operations": [
    {
      "type": "pay_to_address",
      "address": "addr_test1qz...",
      "amounts": [{ "unit": "lovelace", "quantity": "2000000" }]
    }
  ],
  "from": "addr_test1qp...",
  "provider": {
    "name": "yaci",
    "url": "http://localhost:10000/local-cluster/api"
  }
}
```

No `utxos` or `protocol_params` needed — the library fetches them from the provider.

---

## Signing the Transaction

`ccl_quicktx_build` returns an **unsigned** transaction. Sign it with `ccl_account_sign_tx`, which takes the mnemonic, network ID, account/address indices, and the unsigned CBOR hex.

### Python

```python
from ccl import CclLib

lib = CclLib()

# Build
result = lib.quicktx.new_tx() \
    .pay_to_address(receiver_addr, Amount.ada(5)) \
    .from_address(sender_addr) \
    .with_utxos(utxos) \
    .with_protocol_params(pp) \
    .build()

# Sign
signed_tx = lib.account.sign_tx(
    mnemonic, result["tx_cbor"],
    CclLib.TESTNET, 0, 0)
```

### JavaScript (Bun)

```javascript
import { CclBridge, TESTNET } from '@bloxbean/ccl';

const bridge = new CclBridge();

// Build
const result = bridge.quicktx
  .newTx()
  .payToAddress(receiverAddr, Amount.ada(5))
  .from(senderAddr)
  .withUtxos(utxos)
  .withProtocolParams(pp)
  .build();

// Sign
const signedTx = bridge.account.signTx(
  mnemonic, TESTNET, 0, 0, result.tx_cbor);
```

### Go

```go
bridge, _ := ccl.New()
defer bridge.Close()

// Build
result, _ := bridge.QuickTx.NewTx().
    PayToAddress(receiverAddr, ccl.Amount{Unit: "lovelace", Quantity: "5000000"}).
    From(senderAddr).
    WithUtxos(utxos).
    WithProtocolParams(pp).
    Build()

// Sign
signedTx, _ := bridge.Account.SignTx(
    mnemonic, ccl.Testnet, 0, 0, result.TxCbor)
```

### Rust

```rust
let bridge = ccl::Bridge::new().unwrap();

// Build
let result = bridge.quicktx().new_tx()
    .pay_to_address(&receiver, &[Amount::ada(5.0)], None, None)
    .from(&sender)
    .with_utxos(utxos)
    .with_protocol_params(pp)
    .build()
    .unwrap();

// Sign
let signed_tx = bridge.account()
    .sign_tx(&mnemonic, ccl::network::TESTNET, 0, 0, &result.tx_cbor)
    .unwrap();
```
