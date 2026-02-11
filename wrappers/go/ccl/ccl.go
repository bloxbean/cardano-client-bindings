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

// Bridge wraps the CCL native library.
type Bridge struct {
	isolate *C.graal_isolate_t
	thread  *C.graal_isolatethread_t
}

// New creates a new Bridge instance with a GraalVM isolate.
func New() (*Bridge, error) {
	b := &Bridge{}
	rc := C.graal_create_isolate(nil, &b.isolate, &b.thread)
	if rc != 0 {
		return nil, fmt.Errorf("failed to create GraalVM isolate: %d", rc)
	}
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

// AccountCreate creates a new random account.
func (b *Bridge) AccountCreate(networkID int) (*AccountInfo, error) {
	rc := C.ccl_account_create(b.thread, C.int(networkID))
	result, err := b.check(rc)
	if err != nil {
		return nil, err
	}
	var info AccountInfo
	if err := json.Unmarshal([]byte(result), &info); err != nil {
		return nil, err
	}
	return &info, nil
}

// AccountFromMnemonic restores an account from a mnemonic.
func (b *Bridge) AccountFromMnemonic(mnemonic string, networkID, accountIndex, addressIndex int) (*AccountInfo, error) {
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_account_from_mnemonic(b.thread, C.int(networkID), cs, C.int(accountIndex), C.int(addressIndex))
	result, err := b.check(rc)
	if err != nil {
		return nil, err
	}
	var info AccountInfo
	if err := json.Unmarshal([]byte(result), &info); err != nil {
		return nil, err
	}
	return &info, nil
}

// AccountGetPublicKey returns the public key hex for the given mnemonic.
func (b *Bridge) AccountGetPublicKey(mnemonic string, networkID, accountIndex, addressIndex int) (string, error) {
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_account_get_public_key(b.thread, cs, C.int(networkID), C.int(accountIndex), C.int(addressIndex))
	return b.check(rc)
}

// AccountGetPrivateKey returns the private key hex for the given mnemonic.
func (b *Bridge) AccountGetPrivateKey(mnemonic string, networkID, accountIndex, addressIndex int) (string, error) {
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_account_get_private_key(b.thread, cs, C.int(networkID), C.int(accountIndex), C.int(addressIndex))
	return b.check(rc)
}

// AccountGetDRepID returns the DRep ID for the given mnemonic.
func (b *Bridge) AccountGetDRepID(mnemonic string, networkID, accountIndex int) (string, error) {
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_account_get_drep_id(b.thread, cs, C.int(networkID), C.int(accountIndex))
	return b.check(rc)
}

// AddressInfo parses a bech32 address and returns its components.
func (b *Bridge) AddressInfoParse(bech32 string) (*AddressInfo, error) {
	cs := cstr(bech32)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_address_info(b.thread, cs)
	result, err := b.check(rc)
	if err != nil {
		return nil, err
	}
	var info AddressInfo
	if err := json.Unmarshal([]byte(result), &info); err != nil {
		return nil, err
	}
	return &info, nil
}

// AddressValidate validates a bech32 address.
func (b *Bridge) AddressValidate(bech32 string) bool {
	cs := cstr(bech32)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_address_validate(b.thread, cs)
	return rc == Success
}

// CryptoBlake2b256 computes Blake2b-256 hash. Returns hex string.
func (b *Bridge) CryptoBlake2b256(dataHex string) (string, error) {
	cs := cstr(dataHex)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_crypto_blake2b_256(b.thread, cs)
	return b.check(rc)
}

// CryptoGenerateMnemonic generates a new mnemonic phrase.
func (b *Bridge) CryptoGenerateMnemonic(wordCount int) (string, error) {
	rc := C.ccl_crypto_generate_mnemonic(b.thread, C.int(wordCount))
	return b.check(rc)
}

// CryptoValidateMnemonic validates a mnemonic phrase.
func (b *Bridge) CryptoValidateMnemonic(mnemonic string) bool {
	cs := cstr(mnemonic)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_crypto_validate_mnemonic(b.thread, cs)
	return rc == Success
}

// WalletCreate creates a new HD wallet.
func (b *Bridge) WalletCreate(networkID int) (*WalletInfo, error) {
	rc := C.ccl_wallet_create(b.thread, C.int(networkID))
	result, err := b.check(rc)
	if err != nil {
		return nil, err
	}
	var info WalletInfo
	if err := json.Unmarshal([]byte(result), &info); err != nil {
		return nil, err
	}
	return &info, nil
}

// TxHash returns the transaction hash (blake2b-256 of body).
func (b *Bridge) TxHash(txCborHex string) (string, error) {
	cs := cstr(txCborHex)
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_tx_hash(b.thread, cs)
	return b.check(rc)
}
