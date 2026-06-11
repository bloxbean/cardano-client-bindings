# CCL Bridge — JavaScript (Bun)

JavaScript bindings for [Cardano Client Lib](https://github.com/bloxbean/cardano-client-lib)
via the CCL Bridge native library, using Bun's built-in FFI.

> Part of the [CCL Bridge](../../README.md) project. See the
> [top-level README](../../README.md) for the full API reference and
> [`docs/quicktx.md`](../../docs/quicktx.md) for transaction building.

## Requirements

- [Bun](https://bun.sh/) 1.0+.
- The native library `libccl.{dylib,so,dll}` for your platform.

> **Node.js is not supported.** Node's FFI libraries (ffi-napi, koffi) crash against the
> GraalVM native library due to stack-boundary detection. Use Bun, whose built-in FFI
> works correctly. See the project [`TODO.md`](../../TODO.md) Non-Goals.

## Getting the native library

The bindings load the library at runtime via the `CCL_LIB_PATH` environment variable.
Build or download it first. From the repo root:

```bash
./gradlew :core:nativeCompile   # build from source (needs Oracle GraalVM 25.0.3)
# or:
make download-lib               # download a pre-built binary
```

At **runtime** the OS loader also needs it via `DYLD_LIBRARY_PATH` (macOS) /
`LD_LIBRARY_PATH` (Linux).

## Running the examples

From `wrappers/js`:

```bash
LIB_DIR=../../core/build/native/nativeCompile

CCL_LIB_PATH=$LIB_DIR DYLD_LIBRARY_PATH=$LIB_DIR LD_LIBRARY_PATH=$LIB_DIR \
  bun examples/account.js
```

The [`examples/`](examples/) directory contains:

| File | What it shows |
|------|---------------|
| [`account.js`](examples/account.js) | Create an account, restore from mnemonic, derive keys and a DRep ID |
| [`primitives.js`](examples/primitives.js) | Mnemonics, Blake2b hashing, Ed25519 signing, address parsing/validation |
| [`transaction.js`](examples/transaction.js) | Build an unsigned payment **offline** (QuickTx) and sign it — no node/DevKit needed |

## Quick start

```javascript
import { CclBridge, TESTNET } from './src/index.js';

const bridge = new CclBridge();      // loads libccl, starts a GraalVM isolate
try {
  const account = bridge.account.create(TESTNET);
  console.log(account.base_address); // addr_test1...
  console.log(account.mnemonic);     // 24-word phrase
} finally {
  bridge.close();                    // tears down the isolate
}
```

## API namespaces

A `CclBridge` instance exposes these namespaces (all offline operations):
`bridge.account`, `bridge.address`, `bridge.crypto`, `bridge.tx`, `bridge.plutus`,
`bridge.script`, `bridge.gov`, `bridge.wallet`, `bridge.quicktx`.

Network IDs are exported constants: `MAINNET` (0), `TESTNET` (1), `PREPROD` (2),
`PREVIEW` (3). Errors throw `CclError`.

Transactions are defined as a [TxPlan](https://github.com/bloxbean/cardano-client-lib)
**YAML** document and built fully offline — you supply the UTXOs and protocol parameters:

```js
const result = bridge.quicktx.build(yaml, utxos, protocolParams); // { tx_cbor, tx_hash, fee }
```

See [`examples/transaction.js`](examples/transaction.js).
