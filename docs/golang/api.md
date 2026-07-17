# Go API Reference

Everything lives in package `ccl`:

```go
import "github.com/bloxbean/cardano-client-bindings/wrappers/go/ccl"
```

## Bridge

```go
func New() (*Bridge, error)
func (b *Bridge) Close() error
func (b *Bridge) Version() (string, error)

var ErrBridgeClosed = errors.New("ccl: bridge is closed")
```

`New()` loads the native library (downloading it on first use — see [troubleshooting](troubleshooting.md#how-the-native-library-is-found)), creates a GraalVM isolate on a dedicated pinned OS thread, and verifies the library version matches the wrapper. The API groups are exported fields:

```go
bridge.Account  // *AccountApi
bridge.Address  // *AddressApi
bridge.Crypto   // *CryptoApi
bridge.Tx       // *TxApi
bridge.Plutus   // *PlutusApi
bridge.Script   // *ScriptApi
bridge.Gov      // *GovApi
bridge.Wallet   // *WalletApi
bridge.QuickTx  // *QuickTxApi
```

**Lifecycle.** `Close()` tears down the isolate and is idempotent. Any call after `Close` returns `ErrBridgeClosed` (test with `errors.Is`) — it never hangs or panics.

**Concurrency.** A `*Bridge` may be shared across goroutines; calls are serialized onto the bridge's single isolate thread. Create multiple bridges for parallelism.

## Networks

```go
type Network int

const (
	Mainnet Network = 0
	Testnet Network = 1
	Preprod Network = 2
	Preview Network = 3
)

func (n Network) String() string  // "mainnet", "testnet", ...
func (n Network) Valid() bool
```

Every method that derives keys (`Account`, `Wallet`, `Gov`) requires a `Network` value. An out-of-range value returns a plain descriptive error before any native call.

> **Gotcha:** these constants are CCL enum ordinals, **not** Cardano's on-chain network id — the two are inverted for mainnet/testnet (`Mainnet = 0`, but a mainnet address's on-chain `network_id` is `1`). `AddressInfo.NetworkID` is the genuine on-chain value; never feed it back into an API that takes a `Network`.

## Errors

```go
type CclError struct {
	Code    int
	Message string
}
func (e *CclError) Error() string  // "CCL Error <code>: <message>"
```

Native failures surface as `*CclError` — match with `errors.As`. Error codes:

| Constant | Code | Meaning |
|---|---|---|
| `ErrGeneral` | -1 | Unspecified failure |
| `ErrInvalidArgument` | -2 | Bad argument |
| `ErrSerialization` | -3 | (De)serialization failure |
| `ErrCrypto` | -4 | Cryptographic failure |
| `ErrInvalidNetwork` | -5 | Bad network value |
| `ErrInvalidMnemonic` | -6 | Bad mnemonic |
| `ErrInvalidAddress` | -7 | Bad address |
| `ErrInsufficientFunds` | -8 | UTXOs can't cover outputs + fee |
| `ErrInvalidTransaction` | -9 | Bad transaction |
| `ErrTxBuild` | -10 | TxPlan build failure (most common `QuickTx.Build` error — usually a malformed plan) |

Predicate methods (`Address.Validate`, `Crypto.ValidateMnemonic`, `Crypto.Verify`) return `bool` and never error.

## bridge.Account

```go
func (a *AccountApi) Create(network Network) (*AccountInfo, error)
func (a *AccountApi) FromMnemonic(mnemonic string, network Network, accountIndex, addressIndex int) (*AccountInfo, error)
func (a *AccountApi) GetPublicKey(mnemonic string, network Network, accountIndex, addressIndex int) (string, error)
func (a *AccountApi) GetPrivateKey(mnemonic string, network Network, accountIndex, addressIndex int) (string, error)
func (a *AccountApi) GetDRepID(mnemonic string, network Network, accountIndex int) (string, error)
func (a *AccountApi) SignTx(mnemonic string, network Network, accountIndex, addressIndex int, txCborHex string) (string, error)
func (a *AccountApi) SignTxWithKeys(mnemonic string, network Network, accountIndex, addressIndex int, txCborHex string, keys ...string) (string, error)
```

```go
type AccountInfo struct {
	Mnemonic          string `json:"mnemonic"`
	BaseAddress       string `json:"base_address"`
	EnterpriseAddress string `json:"enterprise_address"`
	StakeAddress      string `json:"stake_address"`
	ChangeAddress     string `json:"change_address"`
}
```

- `Create` generates a fresh 24-word mnemonic; treat `AccountInfo.Mnemonic` as a secret.
- `GetPrivateKey` returns the 64-byte **extended** key as 128 hex chars. For raw Ed25519 signing (`Crypto.Sign`) use the first 64 hex chars.
- `SignTx` witnesses with the payment key only. When a transaction carries certificates that need other witnesses, use `SignTxWithKeys` with roles in order — valid roles: `"payment"`, `"stake"`, `"drep"`, `"committee_cold"`, `"committee_hot"`:

```go
// A stake registration needs the payment key (fee) and the stake key (certificate):
signed, err := bridge.Account.SignTxWithKeys(mnemonic, ccl.Testnet, 0, 0, result.TxCbor, "payment", "stake")
```

Without the extra witness the node rejects the transaction with `MissingVKeyWitnessesUTXOW`.

## bridge.Address

```go
func (a *AddressApi) Info(bech32 string) (*AddressInfo, error)
func (a *AddressApi) Validate(bech32 string) bool
func (a *AddressApi) ToBytes(bech32 string) (string, error)     // hex
func (a *AddressApi) FromBytes(hexBytes string) (string, error) // bech32
```

```go
type AddressInfo struct {
	Type                     string `json:"type"`        // "Base", "Enterprise", "Pointer", "Reward"
	NetworkID                int    `json:"network_id"`  // on-chain id: 0=testnet, 1=mainnet
	PaymentCredentialHash    string `json:"payment_credential_hash,omitempty"`
	DelegationCredentialHash string `json:"delegation_credential_hash,omitempty"`
	IsPubkeyPayment          bool   `json:"is_pubkey_payment"`
	IsScriptPayment          bool   `json:"is_script_payment"`
}
```

## bridge.Crypto

```go
func (c *CryptoApi) Blake2b256(dataHex string) (string, error)
func (c *CryptoApi) Blake2b224(dataHex string) (string, error)
func (c *CryptoApi) GenerateMnemonic(wordCount int) (string, error)   // 12 or 24
func (c *CryptoApi) ValidateMnemonic(mnemonic string) bool
func (c *CryptoApi) Sign(messageHex, skHex string) (string, error)    // Ed25519; 32-byte key (64 hex chars)
func (c *CryptoApi) Verify(signatureHex, messageHex, pkHex string) bool
```

Hash inputs are hex in → hex out:

```go
digest, _ := bridge.Crypto.Blake2b256("48656c6c6f") // "Hello"
priv, _ := bridge.Account.GetPrivateKey(mnemonic, ccl.Testnet, 0, 0)
sig, _ := bridge.Crypto.Sign(msgHex, priv[:64])     // first 32 bytes of the extended key
```

## bridge.Tx

```go
func (t *TxApi) Hash(txCborHex string) (string, error)
func (t *TxApi) SignWithSecretKey(txCborHex, skCborHex string) (string, error)
func (t *TxApi) ToJson(txCborHex string) (string, error)
func (t *TxApi) FromJson(txJson string) (string, error)     // returns CBOR hex
func (t *TxApi) Deserialize(txCborHex string) (string, error)
```

`ToJson`/`Deserialize` return a JSON string with a `body` field (inputs/outputs/fee). `SignWithSecretKey` expects a CBOR-encoded secret key, not raw key hex — for mnemonic-based accounts prefer `Account.SignTx`.

## bridge.Plutus

```go
func (p *PlutusApi) DataHash(datumCborHex string) (string, error)   // 64 hex chars
func (p *PlutusApi) DataToJson(cborHex string) (string, error)
func (p *PlutusApi) DataFromJson(jsonStr string) (string, error)    // returns CBOR hex
```

```go
hash, _ := bridge.Plutus.DataHash("182a")  // hash of PlutusData int 42
```

## bridge.Script

```go
func (s *ScriptApi) NativeFromJson(jsonStr string) (string, error)              // JSON: {policy_id, script_hash, cbor_hex}
func (s *ScriptApi) Hash(scriptCborHex string, scriptType int) (string, error)  // 56 hex chars
```

`scriptType`: `0` native, `1` PlutusV1, `2` PlutusV2, `3` PlutusV3.

```go
scriptJSON := fmt.Sprintf(`{"type":"sig","keyHash":"%s"}`, info.PaymentCredentialHash)
result, _ := bridge.Script.NativeFromJson(scriptJSON)
// unmarshal result → policy_id, script_hash, cbor_hex
```

## bridge.Gov

```go
func (g *GovApi) DrepKeyFromMnemonic(mnemonic string, network Network, accountIndex int) (*GovKeyInfo, error)
func (g *GovApi) CommitteeColdKeyFromMnemonic(mnemonic string, network Network, accountIndex int) (*GovKeyInfo, error)
func (g *GovApi) CommitteeHotKeyFromMnemonic(mnemonic string, network Network, accountIndex int) (*GovKeyInfo, error)
```

```go
type GovKeyInfo struct {
	DrepID                    string `json:"drep_id,omitempty"` // drep1... (DRep keys)
	ID                        string `json:"id,omitempty"`      // cc_cold1... / cc_hot1... (committee keys)
	VerificationKey           string `json:"verification_key"`
	VerificationKeyHash       string `json:"verification_key_hash"`
	Bech32VerificationKey     string `json:"bech32_verification_key"`
	Bech32VerificationKeyHash string `json:"bech32_verification_key_hash"`
}
```

## bridge.Wallet

HD wallet: one mnemonic, many sequential addresses.

```go
func (w *WalletApi) Create(network Network) (*WalletInfo, error)
func (w *WalletApi) FromMnemonic(mnemonic string, network Network) (*WalletInfo, error)
func (w *WalletApi) GetAddress(mnemonic string, network Network, index int) (string, error)
```

```go
type WalletInfo struct {
	Mnemonic     string   `json:"mnemonic"`
	StakeAddress string   `json:"stake_address"`
	Addresses    []string `json:"addresses"`
}
```

## bridge.QuickTx

```go
func (q *QuickTxApi) Build(yaml string, utxos interface{}, protocolParams interface{}, execUnits ...interface{}) (*TxResult, error)
func (q *QuickTxApi) BuildWith(yaml string, provider ChainDataProvider, sender string, evaluator ...TransactionEvaluator) (*TxResult, error)
```

```go
type TxResult struct {
	TxCbor string `yaml:"tx_cbor"`
	TxHash string `yaml:"tx_hash"`
	Fee    string `yaml:"fee"`
}
```

- **`Build`** is fully offline: you describe the transaction as [TxPlan YAML](../quicktx.md) and supply the chain data yourself. UTXO selection, fee calculation, and change handling happen inside the native library. It never submits — sign the returned `TxCbor` and submit with any HTTP client.
- `utxos` is a slice of CCL `Utxo` objects (typically `[]map[string]interface{}`): `{tx_hash, output_index, address, amount: [{unit, quantity}]}`. `unit` is `"lovelace"` or `policyId + assetNameHex`. Quantities are best passed as **strings**.
- `protocolParams` is the CCL `ProtocolParams` model (typically `map[string]interface{}`); unknown fields are ignored.
- `execUnits` — for Plutus transactions, pass one value: a slice of `{mem, steps}` maps, one per redeemer in transaction order. When omitted, the native library computes them **offline** with the embedded Scalus evaluator.
- **`BuildWith`** fetches UTXOs and protocol parameters from a [provider](providers.md), then builds. With an evaluator it runs two passes: draft build → remote evaluation → rebuild with the returned units.

```go
result, err := bridge.QuickTx.Build(yaml, utxos, params)

plutusResult, err := bridge.QuickTx.Build(yaml, utxos, params,
	[]map[string]interface{}{{"mem": 2000000, "steps": 500000000}})
```
