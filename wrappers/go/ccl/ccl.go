package ccl

/*
#cgo CFLAGS: -I${SRCDIR}/../../core/build/native/nativeCompile
#cgo LDFLAGS: -L${SRCDIR}/../../core/build/native/nativeCompile -lccl

#include "libccl.h"
#include <stdlib.h>
*/
import "C"
import (
	"encoding/json"
	"fmt"
	"unsafe"
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
	Success             = 0
	ErrGeneral          = -1
	ErrInvalidArgument  = -2
	ErrSerialization    = -3
	ErrCrypto           = -4
	ErrInvalidNetwork   = -5
	ErrInvalidMnemonic  = -6
	ErrInvalidAddress   = -7
	ErrInsufficientFunds = -8
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
	DrepID                   string `json:"drep_id,omitempty"`
	ID                       string `json:"id,omitempty"`
	VerificationKey          string `json:"verification_key"`
	VerificationKeyHash      string `json:"verification_key_hash"`
	Bech32VerificationKey    string `json:"bech32_verification_key"`
	Bech32VerificationKeyHash string `json:"bech32_verification_key_hash"`
}

// Bridge wraps the CCL native library.
type Bridge struct {
	isolate *C.graal_isolate_t
	thread  *C.graal_isolatethread_t

	Account *AccountApi
	Address *AddressApi
	Crypto  *CryptoApi
	Tx      *TxApi
	Plutus  *PlutusApi
	Script  *ScriptApi
	Gov     *GovApi
	Wallet  *WalletApi
}

// New creates a new Bridge instance with a GraalVM isolate.
func New() (*Bridge, error) {
	b := &Bridge{}
	rc := C.graal_create_isolate(nil, &b.isolate, &b.thread)
	if rc != 0 {
		return nil, fmt.Errorf("failed to create GraalVM isolate: %d", rc)
	}

	b.Account = &AccountApi{bridge: b}
	b.Address = &AddressApi{bridge: b}
	b.Crypto = &CryptoApi{bridge: b}
	b.Tx = &TxApi{bridge: b}
	b.Plutus = &PlutusApi{bridge: b}
	b.Script = &ScriptApi{bridge: b}
	b.Gov = &GovApi{bridge: b}
	b.Wallet = &WalletApi{bridge: b}

	return b, nil
}

// Close tears down the GraalVM isolate.
func (b *Bridge) Close() error {
	if b.thread != nil {
		C.graal_tear_down_isolate(b.thread)
		b.thread = nil
	}
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

func (b *Bridge) check(rc C.int) (string, error) {
	if rc != Success {
		return "", &CclError{Code: int(rc), Message: b.getError()}
	}
	return b.getResult(), nil
}

func cstr(s string) *C.char {
	return C.CString(s)
}

// Version returns the library version string.
func (b *Bridge) Version() (string, error) {
	rc := C.ccl_version(b.thread)
	return b.check(rc)
}

// --- AccountApi ---

type AccountApi struct {
	bridge *Bridge
}

func (a *AccountApi) Create(networkID int) (*AccountInfo, error) {
	rc := C.ccl_account_create(a.bridge.thread, C.int(networkID))
	result, err := a.bridge.check(rc)
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

	rc := C.ccl_account_from_mnemonic(a.bridge.thread, C.int(networkID), cs, C.int(accountIndex), C.int(addressIndex))
	result, err := a.bridge.check(rc)
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

	rc := C.ccl_account_get_public_key(a.bridge.thread, cs, C.int(networkID), C.int(accountIndex), C.int(addressIndex))
	return a.bridge.check(rc)
}

func (a *AccountApi) GetPrivateKey(mnemonic string, networkID, accountIndex, addressIndex int) (string, error) {
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_account_get_private_key(a.bridge.thread, cs, C.int(networkID), C.int(accountIndex), C.int(addressIndex))
	return a.bridge.check(rc)
}

func (a *AccountApi) GetDRepID(mnemonic string, networkID, accountIndex int) (string, error) {
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_account_get_drep_id(a.bridge.thread, cs, C.int(networkID), C.int(accountIndex))
	return a.bridge.check(rc)
}

func (a *AccountApi) SignTx(mnemonic string, networkID, accountIndex, addressIndex int, txCborHex string) (string, error) {
	csMnemonic := cstr(mnemonic)
	defer C.free(unsafe.Pointer(csMnemonic))
	csTx := cstr(txCborHex)
	defer C.free(unsafe.Pointer(csTx))

	rc := C.ccl_account_sign_tx(a.bridge.thread, csMnemonic, C.int(networkID), C.int(accountIndex), C.int(addressIndex), csTx)
	return a.bridge.check(rc)
}

// --- AddressApi ---

type AddressApi struct {
	bridge *Bridge
}

func (a *AddressApi) Info(bech32 string) (*AddressInfo, error) {
	cs := cstr(bech32)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_address_info(a.bridge.thread, cs)
	result, err := a.bridge.check(rc)
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

	rc := C.ccl_address_validate(a.bridge.thread, cs)
	return rc == Success
}

func (a *AddressApi) ToBytes(bech32 string) (string, error) {
	cs := cstr(bech32)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_address_to_bytes(a.bridge.thread, cs)
	return a.bridge.check(rc)
}

func (a *AddressApi) FromBytes(hexBytes string) (string, error) {
	cs := cstr(hexBytes)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_address_from_bytes(a.bridge.thread, cs)
	return a.bridge.check(rc)
}

// --- CryptoApi ---

type CryptoApi struct {
	bridge *Bridge
}

func (c *CryptoApi) Blake2b256(dataHex string) (string, error) {
	cs := cstr(dataHex)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_crypto_blake2b_256(c.bridge.thread, cs)
	return c.bridge.check(rc)
}

func (c *CryptoApi) Blake2b224(dataHex string) (string, error) {
	cs := cstr(dataHex)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_crypto_blake2b_224(c.bridge.thread, cs)
	return c.bridge.check(rc)
}

func (c *CryptoApi) GenerateMnemonic(wordCount int) (string, error) {
	rc := C.ccl_crypto_generate_mnemonic(c.bridge.thread, C.int(wordCount))
	return c.bridge.check(rc)
}

func (c *CryptoApi) ValidateMnemonic(mnemonic string) bool {
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_crypto_validate_mnemonic(c.bridge.thread, cs)
	return rc == Success
}

func (c *CryptoApi) Sign(messageHex, skHex string) (string, error) {
	csMsg := cstr(messageHex)
	defer C.free(unsafe.Pointer(csMsg))
	csSk := cstr(skHex)
	defer C.free(unsafe.Pointer(csSk))

	rc := C.ccl_crypto_sign(c.bridge.thread, csMsg, csSk)
	return c.bridge.check(rc)
}

func (c *CryptoApi) Verify(signatureHex, messageHex, pkHex string) bool {
	csSig := cstr(signatureHex)
	defer C.free(unsafe.Pointer(csSig))
	csMsg := cstr(messageHex)
	defer C.free(unsafe.Pointer(csMsg))
	csPk := cstr(pkHex)
	defer C.free(unsafe.Pointer(csPk))

	rc := C.ccl_crypto_verify(c.bridge.thread, csSig, csMsg, csPk)
	return rc == Success
}

// --- TxApi ---

type TxApi struct {
	bridge *Bridge
}

func (t *TxApi) Hash(txCborHex string) (string, error) {
	cs := cstr(txCborHex)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_tx_hash(t.bridge.thread, cs)
	return t.bridge.check(rc)
}

func (t *TxApi) SignWithSecretKey(txCborHex, skCborHex string) (string, error) {
	csTx := cstr(txCborHex)
	defer C.free(unsafe.Pointer(csTx))
	csSk := cstr(skCborHex)
	defer C.free(unsafe.Pointer(csSk))

	rc := C.ccl_tx_sign_with_secret_key(t.bridge.thread, csTx, csSk)
	return t.bridge.check(rc)
}

func (t *TxApi) ToJson(txCborHex string) (string, error) {
	cs := cstr(txCborHex)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_tx_to_json(t.bridge.thread, cs)
	return t.bridge.check(rc)
}

func (t *TxApi) FromJson(txJson string) (string, error) {
	cs := cstr(txJson)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_tx_from_json(t.bridge.thread, cs)
	return t.bridge.check(rc)
}

func (t *TxApi) Deserialize(txCborHex string) (string, error) {
	cs := cstr(txCborHex)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_tx_deserialize(t.bridge.thread, cs)
	return t.bridge.check(rc)
}

// --- PlutusApi ---

type PlutusApi struct {
	bridge *Bridge
}

func (p *PlutusApi) DataHash(datumCborHex string) (string, error) {
	cs := cstr(datumCborHex)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_plutus_data_hash(p.bridge.thread, cs)
	return p.bridge.check(rc)
}

func (p *PlutusApi) DataToJson(cborHex string) (string, error) {
	cs := cstr(cborHex)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_plutus_data_to_json(p.bridge.thread, cs)
	return p.bridge.check(rc)
}

func (p *PlutusApi) DataFromJson(jsonStr string) (string, error) {
	cs := cstr(jsonStr)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_plutus_data_from_json(p.bridge.thread, cs)
	return p.bridge.check(rc)
}

// --- ScriptApi ---

type ScriptApi struct {
	bridge *Bridge
}

func (s *ScriptApi) NativeFromJson(jsonStr string) (string, error) {
	cs := cstr(jsonStr)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_script_native_from_json(s.bridge.thread, cs)
	return s.bridge.check(rc)
}

func (s *ScriptApi) Hash(scriptCborHex string, scriptType int) (string, error) {
	cs := cstr(scriptCborHex)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_script_hash(s.bridge.thread, cs, C.int(scriptType))
	return s.bridge.check(rc)
}

// --- GovApi ---

type GovApi struct {
	bridge *Bridge
}

func (g *GovApi) DrepKeyFromMnemonic(mnemonic string, networkID, accountIndex int) (*GovKeyInfo, error) {
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_gov_drep_key_from_mnemonic(g.bridge.thread, cs, C.int(networkID), C.int(accountIndex))
	result, err := g.bridge.check(rc)
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

	rc := C.ccl_gov_committee_cold_key_from_mnemonic(g.bridge.thread, cs, C.int(networkID), C.int(accountIndex))
	result, err := g.bridge.check(rc)
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

	rc := C.ccl_gov_committee_hot_key_from_mnemonic(g.bridge.thread, cs, C.int(networkID), C.int(accountIndex))
	result, err := g.bridge.check(rc)
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
	rc := C.ccl_wallet_create(w.bridge.thread, C.int(networkID))
	result, err := w.bridge.check(rc)
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

	rc := C.ccl_wallet_from_mnemonic(w.bridge.thread, cs, C.int(networkID))
	result, err := w.bridge.check(rc)
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

	rc := C.ccl_wallet_get_address(w.bridge.thread, cs, C.int(networkID), C.int(index))
	return w.bridge.check(rc)
}
