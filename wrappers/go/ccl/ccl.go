package ccl

/*
#cgo CFLAGS: -I${SRCDIR}/../../../core/build/native/nativeCompile
#cgo LDFLAGS: -L${SRCDIR}/../../../core/build/native/nativeCompile -lccl

#include "libccl.h"
#include <stdlib.h>
*/
import "C"
import (
	"encoding/json"
	"fmt"
	"runtime"
	"sync"
	"unsafe"

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
// (or within) cgo calls. Calling the isolate from a different OS thread than the one
// that created it makes GraalVM read the wrong thread's stack — which on Linux
// x86_64 crashes with a "yellow zone" StackOverflowError. Pinning every call to one
// locked OS thread keeps the isolate thread and the executing thread in sync.
type Bridge struct {
	isolate *C.graal_isolate_t
	thread  *C.graal_isolatethread_t

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

// New creates a new Bridge instance with a GraalVM isolate.
func New() (*Bridge, error) {
	b := &Bridge{calls: make(chan func())}

	ready := make(chan error, 1)
	go b.loop(ready)
	if err := <-ready; err != nil {
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

	if rc := C.graal_create_isolate(nil, &b.isolate, &b.thread); rc != 0 {
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
func (b *Bridge) invoke(call func() C.int) (string, error) {
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
func (b *Bridge) invokeRC(call func() C.int) C.int {
	var rc C.int
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
		if b.thread != nil {
			C.graal_tear_down_isolate(b.thread)
			b.thread = nil
		}
		close(done)
	}
	<-done
	close(b.calls)
	b.calls = nil
	return nil
}

func (b *Bridge) getResult() string {
	ptr := C.ccl_get_result(b.thread)
	if ptr == nil {
		return ""
	}
	result := C.GoString((*C.char)(unsafe.Pointer(ptr)))
	C.ccl_free_string(b.thread, ptr)
	return result
}

func (b *Bridge) getError() string {
	ptr := C.ccl_get_last_error(b.thread)
	if ptr == nil {
		return ""
	}
	result := C.GoString((*C.char)(unsafe.Pointer(ptr)))
	C.ccl_free_string(b.thread, ptr)
	return result
}

func cstr(s string) *C.char {
	return C.CString(s)
}

// Version returns the library version string.
func (b *Bridge) Version() (string, error) {
	return b.invoke(func() C.int { return C.ccl_version(b.thread) })
}

// --- AccountApi ---

type AccountApi struct {
	bridge *Bridge
}

func (a *AccountApi) Create(networkID int) (*AccountInfo, error) {
	result, err := a.bridge.invoke(func() C.int { return C.ccl_account_create(a.bridge.thread, C.int(networkID)) })
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
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	result, err := a.bridge.invoke(func() C.int {
		return C.ccl_account_from_mnemonic(a.bridge.thread, C.int(networkID), cs, C.int(accountIndex), C.int(addressIndex))
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
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	return a.bridge.invoke(func() C.int {
		return C.ccl_account_get_public_key(a.bridge.thread, cs, C.int(networkID), C.int(accountIndex), C.int(addressIndex))
	})
}

func (a *AccountApi) GetPrivateKey(mnemonic string, networkID, accountIndex, addressIndex int) (string, error) {
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	return a.bridge.invoke(func() C.int {
		return C.ccl_account_get_private_key(a.bridge.thread, cs, C.int(networkID), C.int(accountIndex), C.int(addressIndex))
	})
}

func (a *AccountApi) GetDRepID(mnemonic string, networkID, accountIndex int) (string, error) {
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	return a.bridge.invoke(func() C.int {
		return C.ccl_account_get_drep_id(a.bridge.thread, cs, C.int(networkID), C.int(accountIndex))
	})
}

func (a *AccountApi) SignTx(mnemonic string, networkID, accountIndex, addressIndex int, txCborHex string) (string, error) {
	csMnemonic := cstr(mnemonic)
	defer C.free(unsafe.Pointer(csMnemonic))
	csTx := cstr(txCborHex)
	defer C.free(unsafe.Pointer(csTx))

	return a.bridge.invoke(func() C.int {
		return C.ccl_account_sign_tx(a.bridge.thread, csMnemonic, C.int(networkID), C.int(accountIndex), C.int(addressIndex), csTx)
	})
}

// --- AddressApi ---

type AddressApi struct {
	bridge *Bridge
}

func (a *AddressApi) Info(bech32 string) (*AddressInfo, error) {
	cs := cstr(bech32)
	defer C.free(unsafe.Pointer(cs))

	result, err := a.bridge.invoke(func() C.int { return C.ccl_address_info(a.bridge.thread, cs) })
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
	cs := cstr(bech32)
	defer C.free(unsafe.Pointer(cs))

	return a.bridge.invokeRC(func() C.int { return C.ccl_address_validate(a.bridge.thread, cs) }) == Success
}

func (a *AddressApi) ToBytes(bech32 string) (string, error) {
	cs := cstr(bech32)
	defer C.free(unsafe.Pointer(cs))

	return a.bridge.invoke(func() C.int { return C.ccl_address_to_bytes(a.bridge.thread, cs) })
}

func (a *AddressApi) FromBytes(hexBytes string) (string, error) {
	cs := cstr(hexBytes)
	defer C.free(unsafe.Pointer(cs))

	return a.bridge.invoke(func() C.int { return C.ccl_address_from_bytes(a.bridge.thread, cs) })
}

// --- CryptoApi ---

type CryptoApi struct {
	bridge *Bridge
}

func (c *CryptoApi) Blake2b256(dataHex string) (string, error) {
	cs := cstr(dataHex)
	defer C.free(unsafe.Pointer(cs))

	return c.bridge.invoke(func() C.int { return C.ccl_crypto_blake2b_256(c.bridge.thread, cs) })
}

func (c *CryptoApi) Blake2b224(dataHex string) (string, error) {
	cs := cstr(dataHex)
	defer C.free(unsafe.Pointer(cs))

	return c.bridge.invoke(func() C.int { return C.ccl_crypto_blake2b_224(c.bridge.thread, cs) })
}

func (c *CryptoApi) GenerateMnemonic(wordCount int) (string, error) {
	return c.bridge.invoke(func() C.int { return C.ccl_crypto_generate_mnemonic(c.bridge.thread, C.int(wordCount)) })
}

func (c *CryptoApi) ValidateMnemonic(mnemonic string) bool {
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	return c.bridge.invokeRC(func() C.int { return C.ccl_crypto_validate_mnemonic(c.bridge.thread, cs) }) == Success
}

func (c *CryptoApi) Sign(messageHex, skHex string) (string, error) {
	csMsg := cstr(messageHex)
	defer C.free(unsafe.Pointer(csMsg))
	csSk := cstr(skHex)
	defer C.free(unsafe.Pointer(csSk))

	return c.bridge.invoke(func() C.int { return C.ccl_crypto_sign(c.bridge.thread, csMsg, csSk) })
}

func (c *CryptoApi) Verify(signatureHex, messageHex, pkHex string) bool {
	csSig := cstr(signatureHex)
	defer C.free(unsafe.Pointer(csSig))
	csMsg := cstr(messageHex)
	defer C.free(unsafe.Pointer(csMsg))
	csPk := cstr(pkHex)
	defer C.free(unsafe.Pointer(csPk))

	return c.bridge.invokeRC(func() C.int { return C.ccl_crypto_verify(c.bridge.thread, csSig, csMsg, csPk) }) == Success
}

// --- TxApi ---

type TxApi struct {
	bridge *Bridge
}

func (t *TxApi) Hash(txCborHex string) (string, error) {
	cs := cstr(txCborHex)
	defer C.free(unsafe.Pointer(cs))

	return t.bridge.invoke(func() C.int { return C.ccl_tx_hash(t.bridge.thread, cs) })
}

func (t *TxApi) SignWithSecretKey(txCborHex, skCborHex string) (string, error) {
	csTx := cstr(txCborHex)
	defer C.free(unsafe.Pointer(csTx))
	csSk := cstr(skCborHex)
	defer C.free(unsafe.Pointer(csSk))

	return t.bridge.invoke(func() C.int { return C.ccl_tx_sign_with_secret_key(t.bridge.thread, csTx, csSk) })
}

func (t *TxApi) ToJson(txCborHex string) (string, error) {
	cs := cstr(txCborHex)
	defer C.free(unsafe.Pointer(cs))

	return t.bridge.invoke(func() C.int { return C.ccl_tx_to_json(t.bridge.thread, cs) })
}

func (t *TxApi) FromJson(txJson string) (string, error) {
	cs := cstr(txJson)
	defer C.free(unsafe.Pointer(cs))

	return t.bridge.invoke(func() C.int { return C.ccl_tx_from_json(t.bridge.thread, cs) })
}

func (t *TxApi) Deserialize(txCborHex string) (string, error) {
	cs := cstr(txCborHex)
	defer C.free(unsafe.Pointer(cs))

	return t.bridge.invoke(func() C.int { return C.ccl_tx_deserialize(t.bridge.thread, cs) })
}

// --- PlutusApi ---

type PlutusApi struct {
	bridge *Bridge
}

func (p *PlutusApi) DataHash(datumCborHex string) (string, error) {
	cs := cstr(datumCborHex)
	defer C.free(unsafe.Pointer(cs))

	return p.bridge.invoke(func() C.int { return C.ccl_plutus_data_hash(p.bridge.thread, cs) })
}

func (p *PlutusApi) DataToJson(cborHex string) (string, error) {
	cs := cstr(cborHex)
	defer C.free(unsafe.Pointer(cs))

	return p.bridge.invoke(func() C.int { return C.ccl_plutus_data_to_json(p.bridge.thread, cs) })
}

func (p *PlutusApi) DataFromJson(jsonStr string) (string, error) {
	cs := cstr(jsonStr)
	defer C.free(unsafe.Pointer(cs))

	return p.bridge.invoke(func() C.int { return C.ccl_plutus_data_from_json(p.bridge.thread, cs) })
}

// --- ScriptApi ---

type ScriptApi struct {
	bridge *Bridge
}

func (s *ScriptApi) NativeFromJson(jsonStr string) (string, error) {
	cs := cstr(jsonStr)
	defer C.free(unsafe.Pointer(cs))

	return s.bridge.invoke(func() C.int { return C.ccl_script_native_from_json(s.bridge.thread, cs) })
}

func (s *ScriptApi) Hash(scriptCborHex string, scriptType int) (string, error) {
	cs := cstr(scriptCborHex)
	defer C.free(unsafe.Pointer(cs))

	return s.bridge.invoke(func() C.int { return C.ccl_script_hash(s.bridge.thread, cs, C.int(scriptType)) })
}

// --- GovApi ---

type GovApi struct {
	bridge *Bridge
}

func (g *GovApi) DrepKeyFromMnemonic(mnemonic string, networkID, accountIndex int) (*GovKeyInfo, error) {
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	result, err := g.bridge.invoke(func() C.int {
		return C.ccl_gov_drep_key_from_mnemonic(g.bridge.thread, cs, C.int(networkID), C.int(accountIndex))
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
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	result, err := g.bridge.invoke(func() C.int {
		return C.ccl_gov_committee_cold_key_from_mnemonic(g.bridge.thread, cs, C.int(networkID), C.int(accountIndex))
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
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	result, err := g.bridge.invoke(func() C.int {
		return C.ccl_gov_committee_hot_key_from_mnemonic(g.bridge.thread, cs, C.int(networkID), C.int(accountIndex))
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
	result, err := w.bridge.invoke(func() C.int { return C.ccl_wallet_create(w.bridge.thread, C.int(networkID)) })
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
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	result, err := w.bridge.invoke(func() C.int { return C.ccl_wallet_from_mnemonic(w.bridge.thread, cs, C.int(networkID)) })
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
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	return w.bridge.invoke(func() C.int { return C.ccl_wallet_get_address(w.bridge.thread, cs, C.int(networkID), C.int(index)) })
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
// argument: a slice of {mem, steps} (one per redeemer, in transaction order). Compute them with any
// evaluator (Ogmios, Blockfrost, Aiken, Scalus); the bridge does not run the script.
func (q *QuickTxApi) Build(yaml string, utxos interface{}, protocolParams interface{}, execUnits ...interface{}) (*TxResult, error) {
	utxosJSON, err := json.Marshal(utxos)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal utxos: %w", err)
	}
	ppJSON, err := json.Marshal(protocolParams)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal protocol params: %w", err)
	}

	yamlCs := cstr(yaml)
	defer C.free(unsafe.Pointer(yamlCs))
	utxosCs := cstr(string(utxosJSON))
	defer C.free(unsafe.Pointer(utxosCs))
	ppCs := cstr(string(ppJSON))
	defer C.free(unsafe.Pointer(ppCs))

	// nil *C.char marshals to a NULL pointer (no execution units).
	var execCs *C.char
	if len(execUnits) > 0 && execUnits[0] != nil {
		execJSON, err := json.Marshal(execUnits[0])
		if err != nil {
			return nil, fmt.Errorf("failed to marshal exec units: %w", err)
		}
		execCs = cstr(string(execJSON))
		defer C.free(unsafe.Pointer(execCs))
	}

	result, err := q.bridge.invoke(func() C.int {
		return C.ccl_quicktx_build(q.bridge.thread, yamlCs, utxosCs, ppCs, execCs)
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
