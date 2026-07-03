# CCL Bridge — Go

Go bindings for [Cardano Client Lib](https://github.com/bloxbean/cardano-client-lib)
via the CCL Bridge native library, using `cgo`.

> Part of the [CCL Bridge](../../README.md) project. See the
> [top-level README](../../README.md) for the full API reference and
> [`docs/quicktx.md`](../../docs/quicktx.md) for transaction building.

## Requirements

- Go 1.21+ with `cgo` enabled (a C toolchain on `PATH`).
- The native library `libccl.{dylib,so,dll}` for your platform.

> **Threading:** all FFI calls run on a single dedicated OS thread that the `Bridge`
> pins for its lifetime, so a `Bridge` is safe to share across goroutines and is immune
> to Go's goroutine/OS-thread migration (which otherwise crashes the GraalVM isolate on
> Linux x86_64). Calls are serialized; create multiple `Bridge` instances if you need
> concurrent isolate work.

## Getting the native library

The `cgo` directives in `ccl/ccl.go` already point the compiler/linker at
`core/build/native/nativeCompile` (relative to the package), so you only need to build
or download the library there. From the repo root:

```bash
./gradlew :core:nativeCompile   # build from source (needs Oracle GraalVM 25.0.3)
# or:
make download-lib               # download a pre-built binary
```

At **runtime** the OS loader also needs to find the library, via `DYLD_LIBRARY_PATH`
(macOS) / `LD_LIBRARY_PATH` (Linux).

## Running the examples

From `wrappers/go`:

```bash
LIB_DIR=../../core/build/native/nativeCompile

DYLD_LIBRARY_PATH=$LIB_DIR LD_LIBRARY_PATH=$LIB_DIR \
  go run ./examples/account
```

The [`examples/`](examples/) directory contains:

| Program | What it shows |
|---------|---------------|
| [`account`](examples/account/main.go) | Create an account, restore from mnemonic, derive keys and a DRep ID |
| [`primitives`](examples/primitives/main.go) | Mnemonics, Blake2b hashing, Ed25519 signing, address parsing/validation |
| [`transaction`](examples/transaction/main.go) | Build an unsigned payment **offline** (QuickTx) and sign it — no node/DevKit needed |

## Quick start

```go
package main

import (
	"fmt"
	"log"

	"github.com/bloxbean/ccl-bridge/wrappers/go/ccl"
)

func main() {
	bridge, err := ccl.New() // loads libccl, starts a GraalVM isolate
	if err != nil {
		log.Fatal(err)
	}
	defer bridge.Close() // tears down the isolate

	account, err := bridge.Account.Create(ccl.Testnet)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println(account.BaseAddress) // addr_test1...
	fmt.Println(account.Mnemonic)    // 24-word phrase
}
```

## API namespaces

A `*Bridge` exposes these namespaces (all offline operations):
`bridge.Account`, `bridge.Address`, `bridge.Crypto`, `bridge.Tx`, `bridge.Plutus`,
`bridge.Script`, `bridge.Gov`, `bridge.Wallet`, `bridge.QuickTx`.

Network IDs: `ccl.Mainnet` (0), `ccl.Testnet` (1), `ccl.Preprod` (2), `ccl.Preview` (3).
Errors are returned as a `*ccl.CclError`.

Transactions are built from a [TxPlan](https://github.com/bloxbean/cardano-client-lib)
**YAML** document via `bridge.QuickTx.Build(yaml, utxos, protocolParams)`, fully offline —
you supply the UTXOs and protocol parameters. See
[`examples/transaction`](examples/transaction/main.go).

## Chain-data providers (optional)

`Build` is offline — you supply the UTXOs and protocol parameters. The optional providers fetch those
for you over HTTP (stdlib `net/http`), so the native library stays offline and provider-free:

```go
provider, _ := ccl.NewBlockfrostProvider(projectID, "preprod") // or ccl.NewYaciProvider("")
result, err := bridge.QuickTx.BuildWith(yaml, provider, senderAddress)
```

Plug in any backend (Koios, Ogmios, …) by implementing the `ccl.ChainDataProvider` interface
(`Utxos(address)`, `ProtocolParams()`). UTXO *selection* is handled inside the bridge — a provider
only returns all UTXOs at the address.

## Transaction evaluators (optional)

A Plutus build needs each redeemer's execution units. The bridge computes them **offline** with
Scalus when you supply none — so a script build just works, no evaluation step:

```go
result, err := bridge.QuickTx.BuildWith(yaml, provider, senderAddress) // Scalus computes the units
```

To use a **remote** evaluator instead (e.g. an authoritative fallback), pass a
`TransactionEvaluator`; `BuildWith` runs a two-pass (draft → evaluate → rebuild). libccl never makes
HTTP calls ([ADR-0013](../../docs/adr/0013-transaction-evaluators.md)), so remote evaluation lives
here in the wrapper:

```go
evaluator, _ := ccl.NewBlockfrostEvaluator(projectID, "preprod")
result, err := bridge.QuickTx.BuildWith(yaml, provider, senderAddress, evaluator)
```

Plug in any evaluator (Ogmios, …) by implementing the `ccl.TransactionEvaluator` interface
(`Evaluate`). To supply units you computed yourself, call `Build` directly. See
`examples/evaluator`.
