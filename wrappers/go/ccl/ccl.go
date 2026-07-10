package ccl

import (
	"encoding/json"
	"fmt"
	"os"
	"runtime"
	"strings"
	"sync"

	goyaml "gopkg.in/yaml.v3"
)

// Network IDs
const (
	Mainnet = 0
	Testnet = 1
	Preprod = 2
	Preview = 3
)

// Error codes
const (
	Success               = 0
	ErrGeneral            = -1
	ErrInvalidArgument    = -2
	ErrSerialization      = -3
	ErrCrypto             = -4
	ErrInvalidNetwork     = -5
	ErrInvalidMnemonic    = -6
	ErrInvalidAddress     = -7
	ErrInsufficientFunds  = -8
	ErrInvalidTransaction = -9
)

// CclError represents an error from the CCL native library.
type CclError struct {
	Code    int
	Message string
}

func (e *CclError) Error() string {
	return fmt.Sprintf("CCL Error %d: %s", e.Code, e.Message)
}

// AccountInfo contains account creation/restoration result.
type AccountInfo struct {
	Mnemonic          string `json:"mnemonic"`
	BaseAddress       string `json:"base_address"`
	EnterpriseAddress string `json:"enterprise_address"`
	StakeAddress      string `json:"stake_address"`
	ChangeAddress     string `json:"change_address"`
}

// AddressInfo contains address parsing result.
type AddressInfo struct {
	Type                     string `json:"type"`
	NetworkID                int    `json:"network_id"`
	PaymentCredentialHash    string `json:"payment_credential_hash,omitempty"`
	DelegationCredentialHash string `json:"delegation_credential_hash,omitempty"`
	IsPubkeyPayment          bool   `json:"is_pubkey_payment"`
	IsScriptPayment          bool   `json:"is_script_payment"`
}

// WalletInfo contains wallet creation result.
type WalletInfo struct {
	Mnemonic     string   `json:"mnemonic"`
	StakeAddress string   `json:"stake_address"`
	Addresses    []string `json:"addresses"`
}

// GovKeyInfo contains governance key derivation result.
type GovKeyInfo struct {
	DrepID                    string `json:"drep_id,omitempty"`
	ID                        string `json:"id,omitempty"`
	VerificationKey           string `json:"verification_key"`
	VerificationKeyHash       string `json:"verification_key_hash"`
	Bech32VerificationKey     string `json:"bech32_verification_key"`
	Bech32VerificationKeyHash string `json:"bech32_verification_key_hash"`
}

// Bridge wraps the CCL native library.
//
// All FFI calls are funneled to a single, dedicated OS thread (see loop) for the
// lifetime of the Bridge. A GraalVM IsolateThread is bound to the OS thread that
// created it, but the Go runtime can migrate a goroutine across OS threads between
// (or within) FFI calls. Calling the isolate from a different OS thread than the one
// that created it makes GraalVM read the wrong thread's stack — which on Linux
// x86_64 crashes with a "yellow zone" StackOverflowError. Pinning every call to one
// locked OS thread keeps the isolate thread and the executing thread in sync.
type Bridge struct {
	isolate uintptr
	thread  uintptr

	mu    sync.Mutex
	calls chan func()

	Account *AccountApi
	Address *AddressApi
	Crypto  *CryptoApi
	Tx      *TxApi
	Plutus  *PlutusApi
	Script  *ScriptApi
	Gov     *GovApi
	Wallet  *WalletApi
	QuickTx *QuickTxApi
}

// New creates a new Bridge instance with a GraalVM isolate. The native library is located and loaded
// on first use (see resolveLibPath) — via CCL_LIB_PATH, a per-version cache, or a one-time download.
func New() (*Bridge, error) {
	if err := ensureLoaded(); err != nil {
		return nil, err
	}

	b := &Bridge{calls: make(chan func())}

	ready := make(chan error, 1)
	go b.loop(ready)
	if err := <-ready; err != nil {
		return nil, err
	}

	if err := b.checkVersion(); err != nil {
		b.Close()
		return nil, err
	}

	b.Account = &AccountApi{bridge: b}
	b.Address = &AddressApi{bridge: b}
	b.Crypto = &CryptoApi{bridge: b}
	b.Tx = &TxApi{bridge: b}
	b.Plutus = &PlutusApi{bridge: b}
	b.Script = &ScriptApi{bridge: b}
	b.Gov = &GovApi{bridge: b}
	b.Wallet = &WalletApi{bridge: b}
	b.QuickTx = &QuickTxApi{bridge: b}

	return b, nil
}

// loop owns the isolate's OS thread: it locks the thread, creates the isolate on it,
// then executes every queued FFI closure on that same thread until Close.
func (b *Bridge) loop(ready chan<- error) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	if rc := graalCreateIsolate(0, &b.isolate, &b.thread); rc != 0 {
		ready <- fmt.Errorf("failed to create GraalVM isolate: %d", int(rc))
		return
	}
	ready <- nil

	for fn := range b.calls {
		fn()
	}
}

// run executes fn on the isolate's dedicated OS thread and blocks until it finishes.
func (b *Bridge) run(fn func()) {
	b.mu.Lock()
	defer b.mu.Unlock()

	done := make(chan struct{})
	b.calls <- func() {
		fn()
		close(done)
	}
	<-done
}

// invoke runs a result-returning FFI call on the isolate thread and reads the
// per-thread result/error there, where the Java thread-local state lives.
func (b *Bridge) invoke(call func() int32) (string, error) {
	var s string
	var err error
	b.run(func() {
		if rc := call(); rc != Success {
			err = &CclError{Code: int(rc), Message: b.getError()}
		} else {
			s = b.getResult()
		}
	})
	return s, err
}

// invokeRC runs an FFI call on the isolate thread and returns its raw status code,
// for calls (e.g. validate/verify) where the caller interprets the code directly.
func (b *Bridge) invokeRC(call func() int32) int32 {
	var rc int32
	b.run(func() { rc = call() })
	return rc
}

// Close tears down the GraalVM isolate and stops its OS thread.
func (b *Bridge) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.calls == nil {
		return nil
	}
	done := make(chan struct{})
	b.calls <- func() {
		if b.thread != 0 {
			graalTearDownIsolate(b.thread)
			b.thread = 0
		}
		close(done)
	}
	<-done
	close(b.calls)
	b.calls = nil
	return nil
}

func (b *Bridge) getResult() string {
	ptr := cclGetResult(b.thread)
	if ptr == nil {
		return ""
	}
	result := goString(ptr)
	cclFreeString(b.thread, ptr)
	return result
}

func (b *Bridge) getError() string {
	ptr := cclGetLastError(b.thread)
	if ptr == nil {
		return ""
	}
	result := goString(ptr)
	cclFreeString(b.thread, ptr)
	return result
}

// Version returns the library version string.
func (b *Bridge) Version() (string, error) {
	return b.invoke(func() int32 { return cclVersion(b.thread) })
}

// expectedLibVersion is the native libccl version this wrapper expects, kept in lockstep with the
// module release. baseVersion strips any pre-release / build suffix ("0.1.0-preview1" -> "0.1.0").
const expectedLibVersion = "0.1.0"

func baseVersion(v string) string {
	if i := strings.IndexAny(v, "-+"); i >= 0 {
		v = v[:i]
	}
	return strings.TrimSpace(v)
}

// checkVersion fails fast on a native-lib / wrapper version skew rather than surfacing it later as a
// confusing missing-symbol or wrong-result error. Bypass with CCL_SKIP_VERSION_CHECK.
func (b *Bridge) checkVersion() error {
	if os.Getenv("CCL_SKIP_VERSION_CHECK") != "" {
		return nil
	}
	libVer, err := b.Version()
	if err != nil {
		return err
	}
	if baseVersion(libVer) != baseVersion(expectedLibVersion) {
		return fmt.Errorf("libccl version %q is incompatible with the cardano-client-lib Go wrapper "+
			"(expects %q); the native library and wrapper must be the same version — update the module "+
			"or set CCL_LIB_PATH/CCL_LIB_VERSION to a matching libccl (set CCL_SKIP_VERSION_CHECK=1 to bypass)",
			libVer, expectedLibVersion)
	}
	return nil
}

// --- AccountApi ---

type AccountApi struct {
	bridge *Bridge
}

func (a *AccountApi) Create(networkID int) (*AccountInfo, error) {
	result, err := a.bridge.invoke(func() int32 { return cclAccountCreate(a.bridge.thread, int32(networkID)) })
	if err != nil {
		return nil, err
	}
	var info AccountInfo
	if err := json.Unmarshal([]byte(result), &info); err != nil {
		return nil, err
	}
	return &info, nil
}

func (a *AccountApi) FromMnemonic(mnemonic string, networkID, accountIndex, addressIndex int) (*AccountInfo, error) {
	result, err := a.bridge.invoke(func() int32 {
		return cclAccountFromMnemonic(a.bridge.thread, int32(networkID), mnemonic, int32(accountIndex), int32(addressIndex))
	})
	if err != nil {
		return nil, err
	}
	var info AccountInfo
	if err := json.Unmarshal([]byte(result), &info); err != nil {
		return nil, err
	}
	return &info, nil
}

func (a *AccountApi) GetPublicKey(mnemonic string, networkID, accountIndex, addressIndex int) (string, error) {
	return a.bridge.invoke(func() int32 {
		return cclAccountGetPublicKey(a.bridge.thread, mnemonic, int32(networkID), int32(accountIndex), int32(addressIndex))
	})
}

func (a *AccountApi) GetPrivateKey(mnemonic string, networkID, accountIndex, addressIndex int) (string, error) {
	return a.bridge.invoke(func() int32 {
		return cclAccountGetPrivKey(a.bridge.thread, mnemonic, int32(networkID), int32(accountIndex), int32(addressIndex))
	})
}

func (a *AccountApi) GetDRepID(mnemonic string, networkID, accountIndex int) (string, error) {
	return a.bridge.invoke(func() int32 {
		return cclAccountGetDRepID(a.bridge.thread, mnemonic, int32(networkID), int32(accountIndex))
	})
}

func (a *AccountApi) SignTx(mnemonic string, networkID, accountIndex, addressIndex int, txCborHex string) (string, error) {
	return a.bridge.invoke(func() int32 {
		return cclAccountSignTx(a.bridge.thread, mnemonic, int32(networkID), int32(accountIndex), int32(addressIndex), txCborHex)
	})
}

// SignTxWithKeys signs a transaction with one or more of the account's keys, selected by role
// (any of: payment, stake, drep, committee_cold, committee_hot, applied in order). Use this for
// transactions whose certificates also need the stake or DRep key — stake registration/delegation/
// withdrawal and DRep/vote operations — which the payment key alone cannot witness.
func (a *AccountApi) SignTxWithKeys(mnemonic string, networkID, accountIndex, addressIndex int, txCborHex string, keys ...string) (string, error) {
	return a.bridge.invoke(func() int32 {
		return cclAccountSignTxMulti(a.bridge.thread, mnemonic, int32(networkID), int32(accountIndex), int32(addressIndex), txCborHex, strings.Join(keys, ","))
	})
}

// --- AddressApi ---

type AddressApi struct {
	bridge *Bridge
}

func (a *AddressApi) Info(bech32 string) (*AddressInfo, error) {
	result, err := a.bridge.invoke(func() int32 { return cclAddressInfo(a.bridge.thread, bech32) })
	if err != nil {
		return nil, err
	}
	var info AddressInfo
	if err := json.Unmarshal([]byte(result), &info); err != nil {
		return nil, err
	}
	return &info, nil
}

func (a *AddressApi) Validate(bech32 string) bool {
	return a.bridge.invokeRC(func() int32 { return cclAddressValidate(a.bridge.thread, bech32) }) == Success
}

func (a *AddressApi) ToBytes(bech32 string) (string, error) {
	return a.bridge.invoke(func() int32 { return cclAddressToBytes(a.bridge.thread, bech32) })
}

func (a *AddressApi) FromBytes(hexBytes string) (string, error) {
	return a.bridge.invoke(func() int32 { return cclAddressFromBytes(a.bridge.thread, hexBytes) })
}

// --- CryptoApi ---

type CryptoApi struct {
	bridge *Bridge
}

func (c *CryptoApi) Blake2b256(dataHex string) (string, error) {
	return c.bridge.invoke(func() int32 { return cclCryptoBlake2b256(c.bridge.thread, dataHex) })
}

func (c *CryptoApi) Blake2b224(dataHex string) (string, error) {
	return c.bridge.invoke(func() int32 { return cclCryptoBlake2b224(c.bridge.thread, dataHex) })
}

func (c *CryptoApi) GenerateMnemonic(wordCount int) (string, error) {
	return c.bridge.invoke(func() int32 { return cclCryptoGenerateMnemon(c.bridge.thread, int32(wordCount)) })
}

func (c *CryptoApi) ValidateMnemonic(mnemonic string) bool {
	return c.bridge.invokeRC(func() int32 { return cclCryptoValidateMnemon(c.bridge.thread, mnemonic) }) == Success
}

func (c *CryptoApi) Sign(messageHex, skHex string) (string, error) {
	return c.bridge.invoke(func() int32 { return cclCryptoSign(c.bridge.thread, messageHex, skHex) })
}

func (c *CryptoApi) Verify(signatureHex, messageHex, pkHex string) bool {
	return c.bridge.invokeRC(func() int32 { return cclCryptoVerify(c.bridge.thread, signatureHex, messageHex, pkHex) }) == Success
}

// --- TxApi ---

type TxApi struct {
	bridge *Bridge
}

func (t *TxApi) Hash(txCborHex string) (string, error) {
	return t.bridge.invoke(func() int32 { return cclTxHash(t.bridge.thread, txCborHex) })
}

func (t *TxApi) SignWithSecretKey(txCborHex, skCborHex string) (string, error) {
	return t.bridge.invoke(func() int32 { return cclTxSignSecretKey(t.bridge.thread, txCborHex, skCborHex) })
}

func (t *TxApi) ToJson(txCborHex string) (string, error) {
	return t.bridge.invoke(func() int32 { return cclTxToJSON(t.bridge.thread, txCborHex) })
}

func (t *TxApi) FromJson(txJson string) (string, error) {
	return t.bridge.invoke(func() int32 { return cclTxFromJSON(t.bridge.thread, txJson) })
}

func (t *TxApi) Deserialize(txCborHex string) (string, error) {
	return t.bridge.invoke(func() int32 { return cclTxDeserialize(t.bridge.thread, txCborHex) })
}

// --- PlutusApi ---

type PlutusApi struct {
	bridge *Bridge
}

func (p *PlutusApi) DataHash(datumCborHex string) (string, error) {
	return p.bridge.invoke(func() int32 { return cclPlutusDataHash(p.bridge.thread, datumCborHex) })
}

func (p *PlutusApi) DataToJson(cborHex string) (string, error) {
	return p.bridge.invoke(func() int32 { return cclPlutusDataToJSON(p.bridge.thread, cborHex) })
}

func (p *PlutusApi) DataFromJson(jsonStr string) (string, error) {
	return p.bridge.invoke(func() int32 { return cclPlutusDataFromJSON(p.bridge.thread, jsonStr) })
}

// --- ScriptApi ---

type ScriptApi struct {
	bridge *Bridge
}

func (s *ScriptApi) NativeFromJson(jsonStr string) (string, error) {
	return s.bridge.invoke(func() int32 { return cclScriptNativeFromJSON(s.bridge.thread, jsonStr) })
}

func (s *ScriptApi) Hash(scriptCborHex string, scriptType int) (string, error) {
	return s.bridge.invoke(func() int32 { return cclScriptHash(s.bridge.thread, scriptCborHex, int32(scriptType)) })
}

// --- GovApi ---

type GovApi struct {
	bridge *Bridge
}

func (g *GovApi) DrepKeyFromMnemonic(mnemonic string, networkID, accountIndex int) (*GovKeyInfo, error) {
	result, err := g.bridge.invoke(func() int32 {
		return cclGovDRepKey(g.bridge.thread, mnemonic, int32(networkID), int32(accountIndex))
	})
	if err != nil {
		return nil, err
	}
	var info GovKeyInfo
	if err := json.Unmarshal([]byte(result), &info); err != nil {
		return nil, err
	}
	return &info, nil
}

func (g *GovApi) CommitteeColdKeyFromMnemonic(mnemonic string, networkID, accountIndex int) (*GovKeyInfo, error) {
	result, err := g.bridge.invoke(func() int32 {
		return cclGovCommitteeColdKey(g.bridge.thread, mnemonic, int32(networkID), int32(accountIndex))
	})
	if err != nil {
		return nil, err
	}
	var info GovKeyInfo
	if err := json.Unmarshal([]byte(result), &info); err != nil {
		return nil, err
	}
	return &info, nil
}

func (g *GovApi) CommitteeHotKeyFromMnemonic(mnemonic string, networkID, accountIndex int) (*GovKeyInfo, error) {
	result, err := g.bridge.invoke(func() int32 {
		return cclGovCommitteeHotKey(g.bridge.thread, mnemonic, int32(networkID), int32(accountIndex))
	})
	if err != nil {
		return nil, err
	}
	var info GovKeyInfo
	if err := json.Unmarshal([]byte(result), &info); err != nil {
		return nil, err
	}
	return &info, nil
}

// --- WalletApi ---

type WalletApi struct {
	bridge *Bridge
}

func (w *WalletApi) Create(networkID int) (*WalletInfo, error) {
	result, err := w.bridge.invoke(func() int32 { return cclWalletCreate(w.bridge.thread, int32(networkID)) })
	if err != nil {
		return nil, err
	}
	var info WalletInfo
	if err := json.Unmarshal([]byte(result), &info); err != nil {
		return nil, err
	}
	return &info, nil
}

func (w *WalletApi) FromMnemonic(mnemonic string, networkID int) (*WalletInfo, error) {
	result, err := w.bridge.invoke(func() int32 { return cclWalletFromMnemonic(w.bridge.thread, mnemonic, int32(networkID)) })
	if err != nil {
		return nil, err
	}
	var info WalletInfo
	if err := json.Unmarshal([]byte(result), &info); err != nil {
		return nil, err
	}
	return &info, nil
}

func (w *WalletApi) GetAddress(mnemonic string, networkID, index int) (string, error) {
	return w.bridge.invoke(func() int32 { return cclWalletGetAddress(w.bridge.thread, mnemonic, int32(networkID), int32(index)) })
}

// --- QuickTx API ---

// TxResult is the result of building a transaction: the unsigned CBOR, its hash, and the fee.
type TxResult struct {
	TxCbor string `yaml:"tx_cbor"`
	TxHash string `yaml:"tx_hash"`
	Fee    string `yaml:"fee"`
}

// QuickTxApi builds unsigned transactions from a CCL TxPlan (YAML), fully offline.
type QuickTxApi struct {
	bridge *Bridge
}

// Build builds an unsigned transaction from a TxPlan YAML document using the caller-supplied
// UTXOs and protocol parameters. utxos and protocolParams are marshalled to JSON (the standard
// CCL Utxo / ProtocolParams models). The transaction is built offline and never submitted —
// sign the returned TxCbor and submit it yourself.
//
// For Plutus script transactions, pass the redeemers' execution units as the optional execUnits
// argument: a slice of {mem, steps} (one per redeemer, in transaction order). Omit them to have the
// bridge compute them offline with Scalus.
func (q *QuickTxApi) Build(yaml string, utxos interface{}, protocolParams interface{}, execUnits ...interface{}) (*TxResult, error) {
	utxosJSON, err := json.Marshal(utxos)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal utxos: %w", err)
	}
	ppJSON, err := json.Marshal(protocolParams)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal protocol params: %w", err)
	}

	// An empty string means "no execution units" (the native side treats blank as absent).
	execStr := ""
	if len(execUnits) > 0 && execUnits[0] != nil {
		execJSON, err := json.Marshal(execUnits[0])
		if err != nil {
			return nil, fmt.Errorf("failed to marshal exec units: %w", err)
		}
		execStr = string(execJSON)
	}

	result, err := q.bridge.invoke(func() int32 {
		return cclQuicktxBuild(q.bridge.thread, yaml, string(utxosJSON), string(ppJSON), execStr)
	})
	if err != nil {
		return nil, err
	}

	// The build result is a YAML document.
	var txResult TxResult
	if err := goyaml.Unmarshal([]byte(result), &txResult); err != nil {
		return nil, fmt.Errorf("failed to parse tx result: %w", err)
	}
	return &txResult, nil
}
