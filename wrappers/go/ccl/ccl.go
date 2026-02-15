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
	"math"
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
	QuickTx *QuickTxApi
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
	b.QuickTx = &QuickTxApi{bridge: b}

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

// --- QuickTx API ---

// TxResult is the result from building a transaction.
type TxResult struct {
	TxCbor string `json:"tx_cbor"`
	TxHash string `json:"tx_hash"`
	Fee    string `json:"fee"`
}

// Amount represents a token amount in a transaction.
type Amount struct {
	Unit     string `json:"unit"`
	Quantity string `json:"quantity"`
}

// Lovelace creates a lovelace Amount.
func Lovelace(quantity int64) Amount {
	return Amount{Unit: "lovelace", Quantity: fmt.Sprintf("%d", quantity)}
}

// Ada creates a lovelace Amount from ADA (1 ADA = 1,000,000 lovelace).
func Ada(ada float64) Amount {
	return Amount{Unit: "lovelace", Quantity: fmt.Sprintf("%d", int64(math.Floor(ada*1_000_000)))}
}

// Asset creates a native asset Amount.
func Asset(unit string, quantity int64) Amount {
	return Amount{Unit: unit, Quantity: fmt.Sprintf("%d", quantity)}
}

// MintAsset represents an asset to mint.
type MintAsset struct {
	Name     string `json:"name"`
	Quantity string `json:"quantity"`
}

// AnchorOption holds optional anchor fields.
type AnchorOption struct {
	AnchorURL      string
	AnchorDataHash string
}

// ProviderConfig configures Java-side lazy provider fetching.
type ProviderConfig struct {
	Name                 string `json:"name"`
	URL                  string `json:"url"`
	APIKey               string `json:"api_key,omitempty"`
	EnableCostEvaluation *bool  `json:"enable_cost_evaluation,omitempty"`
}

// ReferenceInput represents a reference input (read-only) for script transactions.
type ReferenceInput struct {
	TxHash      string `json:"tx_hash"`
	OutputIndex int    `json:"output_index"`
}

// Composable is implemented by Tx and ScriptTx for use with Compose().
type Composable interface {
	ToSpec() map[string]interface{}
}

// ProposalWithdrawal represents a treasury withdrawal in a proposal.
type ProposalWithdrawal struct {
	RewardAddress string `json:"reward_address"`
	Amount        string `json:"amount"`
}

// QuickTxApi provides transaction building via QuickTx.
type QuickTxApi struct {
	bridge *Bridge
}

// NewTx creates a new TxBuilder for building a single transaction.
func (q *QuickTxApi) NewTx() *TxBuilder {
	return &TxBuilder{bridge: q.bridge, signerCount: 1}
}

// Tx creates a new Tx for use with Compose().
func (q *QuickTxApi) Tx() *Tx {
	return &Tx{}
}

// Compose creates a ComposeTxBuilder from multiple Composable objects (Tx or ScriptTx).
func (q *QuickTxApi) Compose(txs ...Composable) *ComposeTxBuilder {
	return &ComposeTxBuilder{bridge: q.bridge, txs: txs}
}

// NewScriptTx creates a new ScriptTxBuilder for building a single script transaction.
func (q *QuickTxApi) NewScriptTx() *ScriptTxBuilder {
	return &ScriptTxBuilder{bridge: q.bridge, signerCount: 1}
}

// ScriptTx creates a new ScriptTx for use with Compose().
func (q *QuickTxApi) ScriptTx() *ScriptTx {
	return &ScriptTx{}
}

// --- TxBuilder ---

// TxBuilder builds a single transaction spec.
type TxBuilder struct {
	bridge         *Bridge
	operations     []map[string]interface{}
	from           string
	changeAddress  string
	feePayer       string
	utxos          interface{}
	protocolParams interface{}
	validity       map[string]interface{}
	mergeOutputs   *bool
	signerCount    int
}

func (tb *TxBuilder) PayToAddress(address string, amounts ...Amount) *TxBuilder {
	amountList := make([]Amount, len(amounts))
	copy(amountList, amounts)
	tb.operations = append(tb.operations, map[string]interface{}{
		"type":    "pay_to_address",
		"address": address,
		"amounts": amountList,
	})
	return tb
}

func (tb *TxBuilder) PayToContract(address string, amounts []Amount, datumCborHex, datumHash string) *TxBuilder {
	op := map[string]interface{}{
		"type":    "pay_to_contract",
		"address": address,
		"amounts": amounts,
	}
	if datumCborHex != "" {
		op["datum_cbor_hex"] = datumCborHex
	}
	if datumHash != "" {
		op["datum_hash"] = datumHash
	}
	tb.operations = append(tb.operations, op)
	return tb
}

func (tb *TxBuilder) MintAssets(scriptJSON string, assets []MintAsset, receiver string) *TxBuilder {
	tb.operations = append(tb.operations, map[string]interface{}{
		"type":        "mint_assets",
		"script_json": scriptJSON,
		"assets":      assets,
		"receiver":    receiver,
	})
	return tb
}

func (tb *TxBuilder) AttachMetadata(label int, metadata interface{}) *TxBuilder {
	tb.operations = append(tb.operations, map[string]interface{}{
		"type":     "attach_metadata",
		"label":    label,
		"metadata": metadata,
	})
	return tb
}

func (tb *TxBuilder) CollectFrom(utxos []map[string]interface{}) *TxBuilder {
	tb.operations = append(tb.operations, map[string]interface{}{
		"type":          "collect_from",
		"collect_utxos": utxos,
	})
	return tb
}

// Staking

func (tb *TxBuilder) RegisterStakeAddress(address string) *TxBuilder {
	tb.operations = append(tb.operations, map[string]interface{}{
		"type": "register_stake_address", "address": address,
	})
	return tb
}

func (tb *TxBuilder) DeregisterStakeAddress(address string, refundAddress ...string) *TxBuilder {
	op := map[string]interface{}{"type": "deregister_stake_address", "address": address}
	if len(refundAddress) > 0 && refundAddress[0] != "" {
		op["refund_address"] = refundAddress[0]
	}
	tb.operations = append(tb.operations, op)
	return tb
}

func (tb *TxBuilder) DelegateTo(address, poolID string) *TxBuilder {
	tb.operations = append(tb.operations, map[string]interface{}{
		"type": "delegate_to", "address": address, "pool_id": poolID,
	})
	return tb
}

func (tb *TxBuilder) Withdraw(rewardAddress string, amount int64, receiver ...string) *TxBuilder {
	op := map[string]interface{}{
		"type": "withdraw", "reward_address": rewardAddress, "amount": fmt.Sprintf("%d", amount),
	}
	if len(receiver) > 0 && receiver[0] != "" {
		op["receiver"] = receiver[0]
	}
	tb.operations = append(tb.operations, op)
	return tb
}

// DRep

func (tb *TxBuilder) RegisterDRep(credHash, credType string, anchor ...AnchorOption) *TxBuilder {
	op := map[string]interface{}{
		"type": "register_drep", "credential_hash": credHash, "credential_type": credType,
	}
	if len(anchor) > 0 {
		if anchor[0].AnchorURL != "" {
			op["anchor_url"] = anchor[0].AnchorURL
		}
		if anchor[0].AnchorDataHash != "" {
			op["anchor_data_hash"] = anchor[0].AnchorDataHash
		}
	}
	tb.operations = append(tb.operations, op)
	return tb
}

func (tb *TxBuilder) UnregisterDRep(credHash, credType string, refundAddress ...string) *TxBuilder {
	op := map[string]interface{}{
		"type": "unregister_drep", "credential_hash": credHash, "credential_type": credType,
	}
	if len(refundAddress) > 0 && refundAddress[0] != "" {
		op["refund_address"] = refundAddress[0]
	}
	tb.operations = append(tb.operations, op)
	return tb
}

func (tb *TxBuilder) UpdateDRep(credHash, credType string, anchor ...AnchorOption) *TxBuilder {
	op := map[string]interface{}{
		"type": "update_drep", "credential_hash": credHash, "credential_type": credType,
	}
	if len(anchor) > 0 {
		if anchor[0].AnchorURL != "" {
			op["anchor_url"] = anchor[0].AnchorURL
		}
		if anchor[0].AnchorDataHash != "" {
			op["anchor_data_hash"] = anchor[0].AnchorDataHash
		}
	}
	tb.operations = append(tb.operations, op)
	return tb
}

// Voting

func (tb *TxBuilder) DelegateVotingPowerTo(address, drepType string, drepHash ...string) *TxBuilder {
	op := map[string]interface{}{
		"type": "delegate_voting_power_to", "address": address, "drep_type": drepType,
	}
	if len(drepHash) > 0 && drepHash[0] != "" {
		op["drep_hash"] = drepHash[0]
	}
	tb.operations = append(tb.operations, op)
	return tb
}

func (tb *TxBuilder) CreateVote(voterType, voterHash, govActionTxHash string, govActionIndex int, vote string, anchor ...AnchorOption) *TxBuilder {
	op := map[string]interface{}{
		"type": "create_vote", "voter_type": voterType, "voter_hash": voterHash,
		"gov_action_tx_hash": govActionTxHash, "gov_action_index": govActionIndex, "vote": vote,
	}
	if len(anchor) > 0 {
		if anchor[0].AnchorURL != "" {
			op["anchor_url"] = anchor[0].AnchorURL
		}
		if anchor[0].AnchorDataHash != "" {
			op["anchor_data_hash"] = anchor[0].AnchorDataHash
		}
	}
	tb.operations = append(tb.operations, op)
	return tb
}

// Governance

func (tb *TxBuilder) CreateProposal(govActionType, returnAddress, anchorURL, anchorDataHash string, withdrawals ...[]ProposalWithdrawal) *TxBuilder {
	op := map[string]interface{}{
		"type": "create_proposal", "gov_action_type": govActionType,
		"return_address": returnAddress, "anchor_url": anchorURL, "anchor_data_hash": anchorDataHash,
	}
	if len(withdrawals) > 0 && len(withdrawals[0]) > 0 {
		op["withdrawals"] = withdrawals[0]
	}
	tb.operations = append(tb.operations, op)
	return tb
}

// Config

func (tb *TxBuilder) From(address string) *TxBuilder {
	tb.from = address
	return tb
}

func (tb *TxBuilder) ChangeAddress(address string) *TxBuilder {
	tb.changeAddress = address
	return tb
}

func (tb *TxBuilder) FeePayer(address string) *TxBuilder {
	tb.feePayer = address
	return tb
}

func (tb *TxBuilder) WithUtxos(utxos interface{}) *TxBuilder {
	tb.utxos = utxos
	return tb
}

func (tb *TxBuilder) WithProtocolParams(params interface{}) *TxBuilder {
	tb.protocolParams = params
	return tb
}

func (tb *TxBuilder) ValidFrom(slot int64) *TxBuilder {
	if tb.validity == nil {
		tb.validity = make(map[string]interface{})
	}
	tb.validity["valid_from"] = slot
	return tb
}

func (tb *TxBuilder) ValidTo(slot int64) *TxBuilder {
	if tb.validity == nil {
		tb.validity = make(map[string]interface{})
	}
	tb.validity["valid_to"] = slot
	return tb
}

func (tb *TxBuilder) MergeOutputs(merge bool) *TxBuilder {
	tb.mergeOutputs = &merge
	return tb
}

func (tb *TxBuilder) SignerCount(count int) *TxBuilder {
	tb.signerCount = count
	return tb
}

func (tb *TxBuilder) buildSpec(providerConfig *ProviderConfig) map[string]interface{} {
	spec := map[string]interface{}{
		"operations":   tb.operations,
		"from":         tb.from,
		"signer_count": tb.signerCount,
	}
	if providerConfig != nil {
		spec["provider"] = providerConfig
	} else {
		spec["utxos"] = tb.utxos
	}
	if tb.protocolParams != nil {
		spec["protocol_params"] = tb.protocolParams
	}
	if tb.changeAddress != "" {
		spec["change_address"] = tb.changeAddress
	}
	if tb.feePayer != "" {
		spec["fee_payer"] = tb.feePayer
	}
	if len(tb.validity) > 0 {
		spec["validity"] = tb.validity
	}
	if tb.mergeOutputs != nil {
		spec["merge_outputs"] = *tb.mergeOutputs
	}
	return spec
}

// Build builds the transaction. Returns TxResult with tx_cbor, tx_hash, fee.
func (tb *TxBuilder) Build() (*TxResult, error) {
	return tb.doBuild(nil)
}

// BuildWithProvider builds with a Java-side provider config for lazy UTXO fetching.
func (tb *TxBuilder) BuildWithProvider(config ProviderConfig) (*TxResult, error) {
	return tb.doBuild(&config)
}

func (tb *TxBuilder) doBuild(providerConfig *ProviderConfig) (*TxResult, error) {
	spec := tb.buildSpec(providerConfig)
	specJSON, err := json.Marshal(spec)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal spec: %w", err)
	}

	cs := cstr(string(specJSON))
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_quicktx_build(tb.bridge.thread, cs)
	result, err := tb.bridge.check(rc)
	if err != nil {
		return nil, err
	}

	var txResult TxResult
	if err := json.Unmarshal([]byte(result), &txResult); err != nil {
		return nil, fmt.Errorf("failed to parse tx result: %w", err)
	}
	return &txResult, nil
}

// --- Tx (for Compose) ---

// Tx is a lightweight operation collector for use with Compose.
type Tx struct {
	operations    []map[string]interface{}
	from          string
	changeAddress string
}

func (tx *Tx) PayToAddress(address string, amounts ...Amount) *Tx {
	amountList := make([]Amount, len(amounts))
	copy(amountList, amounts)
	tx.operations = append(tx.operations, map[string]interface{}{
		"type":    "pay_to_address",
		"address": address,
		"amounts": amountList,
	})
	return tx
}

func (tx *Tx) PayToContract(address string, amounts []Amount, datumCborHex, datumHash string) *Tx {
	op := map[string]interface{}{
		"type":    "pay_to_contract",
		"address": address,
		"amounts": amounts,
	}
	if datumCborHex != "" {
		op["datum_cbor_hex"] = datumCborHex
	}
	if datumHash != "" {
		op["datum_hash"] = datumHash
	}
	tx.operations = append(tx.operations, op)
	return tx
}

func (tx *Tx) MintAssets(scriptJSON string, assets []MintAsset, receiver string) *Tx {
	tx.operations = append(tx.operations, map[string]interface{}{
		"type":        "mint_assets",
		"script_json": scriptJSON,
		"assets":      assets,
		"receiver":    receiver,
	})
	return tx
}

func (tx *Tx) AttachMetadata(label int, metadata interface{}) *Tx {
	tx.operations = append(tx.operations, map[string]interface{}{
		"type":     "attach_metadata",
		"label":    label,
		"metadata": metadata,
	})
	return tx
}

func (tx *Tx) CollectFrom(utxos []map[string]interface{}) *Tx {
	tx.operations = append(tx.operations, map[string]interface{}{
		"type":          "collect_from",
		"collect_utxos": utxos,
	})
	return tx
}

func (tx *Tx) RegisterStakeAddress(address string) *Tx {
	tx.operations = append(tx.operations, map[string]interface{}{
		"type": "register_stake_address", "address": address,
	})
	return tx
}

func (tx *Tx) DeregisterStakeAddress(address string, refundAddress ...string) *Tx {
	op := map[string]interface{}{"type": "deregister_stake_address", "address": address}
	if len(refundAddress) > 0 && refundAddress[0] != "" {
		op["refund_address"] = refundAddress[0]
	}
	tx.operations = append(tx.operations, op)
	return tx
}

func (tx *Tx) DelegateTo(address, poolID string) *Tx {
	tx.operations = append(tx.operations, map[string]interface{}{
		"type": "delegate_to", "address": address, "pool_id": poolID,
	})
	return tx
}

func (tx *Tx) Withdraw(rewardAddress string, amount int64, receiver ...string) *Tx {
	op := map[string]interface{}{
		"type": "withdraw", "reward_address": rewardAddress, "amount": fmt.Sprintf("%d", amount),
	}
	if len(receiver) > 0 && receiver[0] != "" {
		op["receiver"] = receiver[0]
	}
	tx.operations = append(tx.operations, op)
	return tx
}

func (tx *Tx) RegisterDRep(credHash, credType string, anchor ...AnchorOption) *Tx {
	op := map[string]interface{}{
		"type": "register_drep", "credential_hash": credHash, "credential_type": credType,
	}
	if len(anchor) > 0 {
		if anchor[0].AnchorURL != "" {
			op["anchor_url"] = anchor[0].AnchorURL
		}
		if anchor[0].AnchorDataHash != "" {
			op["anchor_data_hash"] = anchor[0].AnchorDataHash
		}
	}
	tx.operations = append(tx.operations, op)
	return tx
}

func (tx *Tx) UnregisterDRep(credHash, credType string, refundAddress ...string) *Tx {
	op := map[string]interface{}{
		"type": "unregister_drep", "credential_hash": credHash, "credential_type": credType,
	}
	if len(refundAddress) > 0 && refundAddress[0] != "" {
		op["refund_address"] = refundAddress[0]
	}
	tx.operations = append(tx.operations, op)
	return tx
}

func (tx *Tx) UpdateDRep(credHash, credType string, anchor ...AnchorOption) *Tx {
	op := map[string]interface{}{
		"type": "update_drep", "credential_hash": credHash, "credential_type": credType,
	}
	if len(anchor) > 0 {
		if anchor[0].AnchorURL != "" {
			op["anchor_url"] = anchor[0].AnchorURL
		}
		if anchor[0].AnchorDataHash != "" {
			op["anchor_data_hash"] = anchor[0].AnchorDataHash
		}
	}
	tx.operations = append(tx.operations, op)
	return tx
}

func (tx *Tx) DelegateVotingPowerTo(address, drepType string, drepHash ...string) *Tx {
	op := map[string]interface{}{
		"type": "delegate_voting_power_to", "address": address, "drep_type": drepType,
	}
	if len(drepHash) > 0 && drepHash[0] != "" {
		op["drep_hash"] = drepHash[0]
	}
	tx.operations = append(tx.operations, op)
	return tx
}

func (tx *Tx) CreateVote(voterType, voterHash, govActionTxHash string, govActionIndex int, vote string, anchor ...AnchorOption) *Tx {
	op := map[string]interface{}{
		"type": "create_vote", "voter_type": voterType, "voter_hash": voterHash,
		"gov_action_tx_hash": govActionTxHash, "gov_action_index": govActionIndex, "vote": vote,
	}
	if len(anchor) > 0 {
		if anchor[0].AnchorURL != "" {
			op["anchor_url"] = anchor[0].AnchorURL
		}
		if anchor[0].AnchorDataHash != "" {
			op["anchor_data_hash"] = anchor[0].AnchorDataHash
		}
	}
	tx.operations = append(tx.operations, op)
	return tx
}

func (tx *Tx) CreateProposal(govActionType, returnAddress, anchorURL, anchorDataHash string, withdrawals ...[]ProposalWithdrawal) *Tx {
	op := map[string]interface{}{
		"type": "create_proposal", "gov_action_type": govActionType,
		"return_address": returnAddress, "anchor_url": anchorURL, "anchor_data_hash": anchorDataHash,
	}
	if len(withdrawals) > 0 && len(withdrawals[0]) > 0 {
		op["withdrawals"] = withdrawals[0]
	}
	tx.operations = append(tx.operations, op)
	return tx
}

func (tx *Tx) From(address string) *Tx {
	tx.from = address
	return tx
}

func (tx *Tx) ChangeAddress(address string) *Tx {
	tx.changeAddress = address
	return tx
}

func (tx *Tx) ToSpec() map[string]interface{} {
	spec := map[string]interface{}{
		"from":       tx.from,
		"operations": tx.operations,
	}
	if tx.changeAddress != "" {
		spec["change_address"] = tx.changeAddress
	}
	return spec
}

// --- ComposeTxBuilder ---

// ComposeTxBuilder composes multiple Composable objects into a single transaction.
type ComposeTxBuilder struct {
	bridge         *Bridge
	txs            []Composable
	feePayer       string
	utxos          interface{}
	protocolParams interface{}
	validity       map[string]interface{}
	mergeOutputs   *bool
	signerCount    *int
}

func (cb *ComposeTxBuilder) FeePayer(address string) *ComposeTxBuilder {
	cb.feePayer = address
	return cb
}

func (cb *ComposeTxBuilder) WithUtxos(utxos interface{}) *ComposeTxBuilder {
	cb.utxos = utxos
	return cb
}

func (cb *ComposeTxBuilder) WithProtocolParams(params interface{}) *ComposeTxBuilder {
	cb.protocolParams = params
	return cb
}

func (cb *ComposeTxBuilder) ValidFrom(slot int64) *ComposeTxBuilder {
	if cb.validity == nil {
		cb.validity = make(map[string]interface{})
	}
	cb.validity["valid_from"] = slot
	return cb
}

func (cb *ComposeTxBuilder) ValidTo(slot int64) *ComposeTxBuilder {
	if cb.validity == nil {
		cb.validity = make(map[string]interface{})
	}
	cb.validity["valid_to"] = slot
	return cb
}

func (cb *ComposeTxBuilder) MergeOutputs(merge bool) *ComposeTxBuilder {
	cb.mergeOutputs = &merge
	return cb
}

func (cb *ComposeTxBuilder) SignerCount(count int) *ComposeTxBuilder {
	cb.signerCount = &count
	return cb
}

// Build builds the composed transaction.
func (cb *ComposeTxBuilder) Build() (*TxResult, error) {
	return cb.doBuild(nil)
}

// BuildWithProvider builds with a Java-side provider config.
func (cb *ComposeTxBuilder) BuildWithProvider(config ProviderConfig) (*TxResult, error) {
	return cb.doBuild(&config)
}

func (cb *ComposeTxBuilder) doBuild(providerConfig *ProviderConfig) (*TxResult, error) {
	txSpecs := make([]map[string]interface{}, len(cb.txs))
	for i, tx := range cb.txs {
		txSpecs[i] = tx.ToSpec()
	}

	spec := map[string]interface{}{
		"transactions": txSpecs,
		"fee_payer":    cb.feePayer,
	}

	if providerConfig != nil {
		spec["provider"] = providerConfig
	} else {
		spec["utxos"] = cb.utxos
	}
	if cb.protocolParams != nil {
		spec["protocol_params"] = cb.protocolParams
	}
	if cb.signerCount != nil {
		spec["signer_count"] = *cb.signerCount
	}
	if len(cb.validity) > 0 {
		spec["validity"] = cb.validity
	}
	if cb.mergeOutputs != nil {
		spec["merge_outputs"] = *cb.mergeOutputs
	}

	specJSON, err := json.Marshal(spec)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal compose spec: %w", err)
	}

	cs := cstr(string(specJSON))
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_quicktx_build(cb.bridge.thread, cs)
	result, err := cb.bridge.check(rc)
	if err != nil {
		return nil, err
	}

	var txResult TxResult
	if err := json.Unmarshal([]byte(result), &txResult); err != nil {
		return nil, fmt.Errorf("failed to parse tx result: %w", err)
	}
	return &txResult, nil
}

// --- ScriptTxBuilder ---

// ScriptTxBuilder builds a single script transaction spec.
type ScriptTxBuilder struct {
	bridge            *Bridge
	operations        []map[string]interface{}
	from              string
	changeAddress     string
	feePayer          string
	utxos             interface{}
	protocolParams    interface{}
	validity          map[string]interface{}
	mergeOutputs      *bool
	signerCount       int
	changeDatumCbor   string
	changeDatumHash   string
}

func (sb *ScriptTxBuilder) PayToAddress(address string, amounts ...Amount) *ScriptTxBuilder {
	amountList := make([]Amount, len(amounts))
	copy(amountList, amounts)
	sb.operations = append(sb.operations, map[string]interface{}{
		"type":    "pay_to_address",
		"address": address,
		"amounts": amountList,
	})
	return sb
}

func (sb *ScriptTxBuilder) PayToContract(address string, amounts []Amount, datumCborHex, datumHash string) *ScriptTxBuilder {
	op := map[string]interface{}{
		"type":    "pay_to_contract",
		"address": address,
		"amounts": amounts,
	}
	if datumCborHex != "" {
		op["datum_cbor_hex"] = datumCborHex
	}
	if datumHash != "" {
		op["datum_hash"] = datumHash
	}
	sb.operations = append(sb.operations, op)
	return sb
}

func (sb *ScriptTxBuilder) AttachMetadata(label int, metadata interface{}) *ScriptTxBuilder {
	sb.operations = append(sb.operations, map[string]interface{}{
		"type":     "attach_metadata",
		"label":    label,
		"metadata": metadata,
	})
	return sb
}

func (sb *ScriptTxBuilder) CollectFrom(utxos []map[string]interface{}) *ScriptTxBuilder {
	sb.operations = append(sb.operations, map[string]interface{}{
		"type":          "collect_from",
		"collect_utxos": utxos,
	})
	return sb
}

func (sb *ScriptTxBuilder) CollectFromScript(utxos []map[string]interface{}, redeemerCborHex, datumCborHex string) *ScriptTxBuilder {
	op := map[string]interface{}{
		"type":              "collect_from",
		"collect_utxos":     utxos,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if datumCborHex != "" {
		op["datum_cbor_hex"] = datumCborHex
	}
	sb.operations = append(sb.operations, op)
	return sb
}

func (sb *ScriptTxBuilder) ReadFrom(referenceInputs []ReferenceInput) *ScriptTxBuilder {
	sb.operations = append(sb.operations, map[string]interface{}{
		"type":             "read_from",
		"reference_inputs": referenceInputs,
	})
	return sb
}

func (sb *ScriptTxBuilder) MintPlutusAssets(scriptCborHex, scriptType string, assets []MintAsset, redeemerCborHex, receiver, outputDatumCborHex string) *ScriptTxBuilder {
	op := map[string]interface{}{
		"type":              "mint_plutus_assets",
		"script_cbor_hex":   scriptCborHex,
		"script_type":       scriptType,
		"assets":            assets,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if receiver != "" {
		op["receiver"] = receiver
	}
	if outputDatumCborHex != "" {
		op["output_datum_cbor_hex"] = outputDatumCborHex
	}
	sb.operations = append(sb.operations, op)
	return sb
}

func (sb *ScriptTxBuilder) AttachSpendingValidator(scriptCborHex, scriptType string) *ScriptTxBuilder {
	sb.operations = append(sb.operations, map[string]interface{}{
		"type": "attach_spending_validator", "script_cbor_hex": scriptCborHex, "script_type": scriptType,
	})
	return sb
}

func (sb *ScriptTxBuilder) AttachCertificateValidator(scriptCborHex, scriptType string) *ScriptTxBuilder {
	sb.operations = append(sb.operations, map[string]interface{}{
		"type": "attach_certificate_validator", "script_cbor_hex": scriptCborHex, "script_type": scriptType,
	})
	return sb
}

func (sb *ScriptTxBuilder) AttachRewardValidator(scriptCborHex, scriptType string) *ScriptTxBuilder {
	sb.operations = append(sb.operations, map[string]interface{}{
		"type": "attach_reward_validator", "script_cbor_hex": scriptCborHex, "script_type": scriptType,
	})
	return sb
}

func (sb *ScriptTxBuilder) AttachProposingValidator(scriptCborHex, scriptType string) *ScriptTxBuilder {
	sb.operations = append(sb.operations, map[string]interface{}{
		"type": "attach_proposing_validator", "script_cbor_hex": scriptCborHex, "script_type": scriptType,
	})
	return sb
}

func (sb *ScriptTxBuilder) AttachVotingValidator(scriptCborHex, scriptType string) *ScriptTxBuilder {
	sb.operations = append(sb.operations, map[string]interface{}{
		"type": "attach_voting_validator", "script_cbor_hex": scriptCborHex, "script_type": scriptType,
	})
	return sb
}

// Redeemer-enhanced staking/governance

func (sb *ScriptTxBuilder) DeregisterStakeAddress(address, redeemerCborHex, refundAddress string) *ScriptTxBuilder {
	op := map[string]interface{}{
		"type": "deregister_stake_address", "address": address, "redeemer_cbor_hex": redeemerCborHex,
	}
	if refundAddress != "" {
		op["refund_address"] = refundAddress
	}
	sb.operations = append(sb.operations, op)
	return sb
}

func (sb *ScriptTxBuilder) DelegateTo(address, poolID, redeemerCborHex string) *ScriptTxBuilder {
	sb.operations = append(sb.operations, map[string]interface{}{
		"type": "delegate_to", "address": address, "pool_id": poolID, "redeemer_cbor_hex": redeemerCborHex,
	})
	return sb
}

func (sb *ScriptTxBuilder) Withdraw(rewardAddress, amount, redeemerCborHex, receiver string) *ScriptTxBuilder {
	op := map[string]interface{}{
		"type": "withdraw", "reward_address": rewardAddress, "amount": amount, "redeemer_cbor_hex": redeemerCborHex,
	}
	if receiver != "" {
		op["receiver"] = receiver
	}
	sb.operations = append(sb.operations, op)
	return sb
}

func (sb *ScriptTxBuilder) RegisterDRep(credHash, credType, redeemerCborHex, anchorURL, anchorDataHash string) *ScriptTxBuilder {
	op := map[string]interface{}{
		"type": "register_drep", "credential_hash": credHash, "credential_type": credType,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if anchorURL != "" {
		op["anchor_url"] = anchorURL
	}
	if anchorDataHash != "" {
		op["anchor_data_hash"] = anchorDataHash
	}
	sb.operations = append(sb.operations, op)
	return sb
}

func (sb *ScriptTxBuilder) UnregisterDRep(credHash, credType, redeemerCborHex, refundAddress string) *ScriptTxBuilder {
	op := map[string]interface{}{
		"type": "unregister_drep", "credential_hash": credHash, "credential_type": credType,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if refundAddress != "" {
		op["refund_address"] = refundAddress
	}
	sb.operations = append(sb.operations, op)
	return sb
}

func (sb *ScriptTxBuilder) UpdateDRep(credHash, credType, redeemerCborHex, anchorURL, anchorDataHash string) *ScriptTxBuilder {
	op := map[string]interface{}{
		"type": "update_drep", "credential_hash": credHash, "credential_type": credType,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if anchorURL != "" {
		op["anchor_url"] = anchorURL
	}
	if anchorDataHash != "" {
		op["anchor_data_hash"] = anchorDataHash
	}
	sb.operations = append(sb.operations, op)
	return sb
}

func (sb *ScriptTxBuilder) DelegateVotingPowerTo(address, drepType, drepHash, redeemerCborHex string) *ScriptTxBuilder {
	op := map[string]interface{}{
		"type": "delegate_voting_power_to", "address": address, "drep_type": drepType,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if drepHash != "" {
		op["drep_hash"] = drepHash
	}
	sb.operations = append(sb.operations, op)
	return sb
}

func (sb *ScriptTxBuilder) CreateVote(voterType, voterHash, govActionTxHash string, govActionIndex int, vote, redeemerCborHex, anchorURL, anchorDataHash string) *ScriptTxBuilder {
	op := map[string]interface{}{
		"type": "create_vote", "voter_type": voterType, "voter_hash": voterHash,
		"gov_action_tx_hash": govActionTxHash, "gov_action_index": govActionIndex, "vote": vote,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if anchorURL != "" {
		op["anchor_url"] = anchorURL
	}
	if anchorDataHash != "" {
		op["anchor_data_hash"] = anchorDataHash
	}
	sb.operations = append(sb.operations, op)
	return sb
}

func (sb *ScriptTxBuilder) CreateProposal(govActionType, returnAddress, anchorURL, anchorDataHash, redeemerCborHex string, withdrawals []map[string]string) *ScriptTxBuilder {
	op := map[string]interface{}{
		"type": "create_proposal", "gov_action_type": govActionType,
		"return_address": returnAddress, "anchor_url": anchorURL, "anchor_data_hash": anchorDataHash,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if len(withdrawals) > 0 {
		op["withdrawals"] = withdrawals
	}
	sb.operations = append(sb.operations, op)
	return sb
}

// Config

func (sb *ScriptTxBuilder) From(address string) *ScriptTxBuilder {
	sb.from = address
	return sb
}

func (sb *ScriptTxBuilder) ChangeAddress(address string) *ScriptTxBuilder {
	sb.changeAddress = address
	return sb
}

func (sb *ScriptTxBuilder) ChangeDatum(datumCborHex string) *ScriptTxBuilder {
	sb.changeDatumCbor = datumCborHex
	return sb
}

func (sb *ScriptTxBuilder) ChangeDatumHash(hash string) *ScriptTxBuilder {
	sb.changeDatumHash = hash
	return sb
}

func (sb *ScriptTxBuilder) FeePayer(address string) *ScriptTxBuilder {
	sb.feePayer = address
	return sb
}

func (sb *ScriptTxBuilder) WithUtxos(utxos interface{}) *ScriptTxBuilder {
	sb.utxos = utxos
	return sb
}

func (sb *ScriptTxBuilder) WithProtocolParams(params interface{}) *ScriptTxBuilder {
	sb.protocolParams = params
	return sb
}

func (sb *ScriptTxBuilder) ValidFrom(slot int64) *ScriptTxBuilder {
	if sb.validity == nil {
		sb.validity = make(map[string]interface{})
	}
	sb.validity["valid_from"] = slot
	return sb
}

func (sb *ScriptTxBuilder) ValidTo(slot int64) *ScriptTxBuilder {
	if sb.validity == nil {
		sb.validity = make(map[string]interface{})
	}
	sb.validity["valid_to"] = slot
	return sb
}

func (sb *ScriptTxBuilder) MergeOutputs(merge bool) *ScriptTxBuilder {
	sb.mergeOutputs = &merge
	return sb
}

func (sb *ScriptTxBuilder) SignerCount(count int) *ScriptTxBuilder {
	sb.signerCount = count
	return sb
}

func (sb *ScriptTxBuilder) buildSpec(providerConfig *ProviderConfig) map[string]interface{} {
	spec := map[string]interface{}{
		"tx_type":      "script_tx",
		"operations":   sb.operations,
		"from":         sb.from,
		"signer_count": sb.signerCount,
	}
	if providerConfig != nil {
		spec["provider"] = providerConfig
	} else {
		spec["utxos"] = sb.utxos
	}
	if sb.protocolParams != nil {
		spec["protocol_params"] = sb.protocolParams
	}
	if sb.changeAddress != "" {
		spec["change_address"] = sb.changeAddress
	}
	if sb.feePayer != "" {
		spec["fee_payer"] = sb.feePayer
	}
	if len(sb.validity) > 0 {
		spec["validity"] = sb.validity
	}
	if sb.mergeOutputs != nil {
		spec["merge_outputs"] = *sb.mergeOutputs
	}
	if sb.changeDatumCbor != "" {
		spec["change_datum_cbor_hex"] = sb.changeDatumCbor
	}
	if sb.changeDatumHash != "" {
		spec["change_datum_hash"] = sb.changeDatumHash
	}
	return spec
}

// Build builds the script transaction. Returns TxResult with tx_cbor, tx_hash, fee.
func (sb *ScriptTxBuilder) Build() (*TxResult, error) {
	return sb.doBuild(nil)
}

// BuildWithProvider builds with a Java-side provider config for lazy UTXO fetching.
func (sb *ScriptTxBuilder) BuildWithProvider(config ProviderConfig) (*TxResult, error) {
	return sb.doBuild(&config)
}

func (sb *ScriptTxBuilder) doBuild(providerConfig *ProviderConfig) (*TxResult, error) {
	spec := sb.buildSpec(providerConfig)
	specJSON, err := json.Marshal(spec)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal spec: %w", err)
	}

	cs := cstr(string(specJSON))
	defer C.free(unsafe.Pointer(cs))

	rc := C.ccl_quicktx_build(sb.bridge.thread, cs)
	result, err := sb.bridge.check(rc)
	if err != nil {
		return nil, err
	}

	var txResult TxResult
	if err := json.Unmarshal([]byte(result), &txResult); err != nil {
		return nil, fmt.Errorf("failed to parse tx result: %w", err)
	}
	return &txResult, nil
}

// --- ScriptTx (for Compose) ---

// ScriptTx is a lightweight operation collector for script transactions in Compose.
type ScriptTx struct {
	operations      []map[string]interface{}
	from            string
	changeAddress   string
	changeDatumCbor string
	changeDatumHash string
}

func (st *ScriptTx) PayToAddress(address string, amounts ...Amount) *ScriptTx {
	amountList := make([]Amount, len(amounts))
	copy(amountList, amounts)
	st.operations = append(st.operations, map[string]interface{}{
		"type":    "pay_to_address",
		"address": address,
		"amounts": amountList,
	})
	return st
}

func (st *ScriptTx) PayToContract(address string, amounts []Amount, datumCborHex, datumHash string) *ScriptTx {
	op := map[string]interface{}{
		"type":    "pay_to_contract",
		"address": address,
		"amounts": amounts,
	}
	if datumCborHex != "" {
		op["datum_cbor_hex"] = datumCborHex
	}
	if datumHash != "" {
		op["datum_hash"] = datumHash
	}
	st.operations = append(st.operations, op)
	return st
}

func (st *ScriptTx) AttachMetadata(label int, metadata interface{}) *ScriptTx {
	st.operations = append(st.operations, map[string]interface{}{
		"type":     "attach_metadata",
		"label":    label,
		"metadata": metadata,
	})
	return st
}

func (st *ScriptTx) CollectFrom(utxos []map[string]interface{}) *ScriptTx {
	st.operations = append(st.operations, map[string]interface{}{
		"type":          "collect_from",
		"collect_utxos": utxos,
	})
	return st
}

func (st *ScriptTx) CollectFromScript(utxos []map[string]interface{}, redeemerCborHex, datumCborHex string) *ScriptTx {
	op := map[string]interface{}{
		"type":              "collect_from",
		"collect_utxos":     utxos,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if datumCborHex != "" {
		op["datum_cbor_hex"] = datumCborHex
	}
	st.operations = append(st.operations, op)
	return st
}

func (st *ScriptTx) ReadFrom(referenceInputs []ReferenceInput) *ScriptTx {
	st.operations = append(st.operations, map[string]interface{}{
		"type":             "read_from",
		"reference_inputs": referenceInputs,
	})
	return st
}

func (st *ScriptTx) MintPlutusAssets(scriptCborHex, scriptType string, assets []MintAsset, redeemerCborHex, receiver, outputDatumCborHex string) *ScriptTx {
	op := map[string]interface{}{
		"type":              "mint_plutus_assets",
		"script_cbor_hex":   scriptCborHex,
		"script_type":       scriptType,
		"assets":            assets,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if receiver != "" {
		op["receiver"] = receiver
	}
	if outputDatumCborHex != "" {
		op["output_datum_cbor_hex"] = outputDatumCborHex
	}
	st.operations = append(st.operations, op)
	return st
}

func (st *ScriptTx) AttachSpendingValidator(scriptCborHex, scriptType string) *ScriptTx {
	st.operations = append(st.operations, map[string]interface{}{
		"type": "attach_spending_validator", "script_cbor_hex": scriptCborHex, "script_type": scriptType,
	})
	return st
}

func (st *ScriptTx) AttachCertificateValidator(scriptCborHex, scriptType string) *ScriptTx {
	st.operations = append(st.operations, map[string]interface{}{
		"type": "attach_certificate_validator", "script_cbor_hex": scriptCborHex, "script_type": scriptType,
	})
	return st
}

func (st *ScriptTx) AttachRewardValidator(scriptCborHex, scriptType string) *ScriptTx {
	st.operations = append(st.operations, map[string]interface{}{
		"type": "attach_reward_validator", "script_cbor_hex": scriptCborHex, "script_type": scriptType,
	})
	return st
}

func (st *ScriptTx) AttachProposingValidator(scriptCborHex, scriptType string) *ScriptTx {
	st.operations = append(st.operations, map[string]interface{}{
		"type": "attach_proposing_validator", "script_cbor_hex": scriptCborHex, "script_type": scriptType,
	})
	return st
}

func (st *ScriptTx) AttachVotingValidator(scriptCborHex, scriptType string) *ScriptTx {
	st.operations = append(st.operations, map[string]interface{}{
		"type": "attach_voting_validator", "script_cbor_hex": scriptCborHex, "script_type": scriptType,
	})
	return st
}

// Redeemer-enhanced staking/governance

func (st *ScriptTx) DeregisterStakeAddress(address, redeemerCborHex, refundAddress string) *ScriptTx {
	op := map[string]interface{}{
		"type": "deregister_stake_address", "address": address, "redeemer_cbor_hex": redeemerCborHex,
	}
	if refundAddress != "" {
		op["refund_address"] = refundAddress
	}
	st.operations = append(st.operations, op)
	return st
}

func (st *ScriptTx) DelegateTo(address, poolID, redeemerCborHex string) *ScriptTx {
	st.operations = append(st.operations, map[string]interface{}{
		"type": "delegate_to", "address": address, "pool_id": poolID, "redeemer_cbor_hex": redeemerCborHex,
	})
	return st
}

func (st *ScriptTx) Withdraw(rewardAddress, amount, redeemerCborHex, receiver string) *ScriptTx {
	op := map[string]interface{}{
		"type": "withdraw", "reward_address": rewardAddress, "amount": amount, "redeemer_cbor_hex": redeemerCborHex,
	}
	if receiver != "" {
		op["receiver"] = receiver
	}
	st.operations = append(st.operations, op)
	return st
}

func (st *ScriptTx) RegisterDRep(credHash, credType, redeemerCborHex, anchorURL, anchorDataHash string) *ScriptTx {
	op := map[string]interface{}{
		"type": "register_drep", "credential_hash": credHash, "credential_type": credType,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if anchorURL != "" {
		op["anchor_url"] = anchorURL
	}
	if anchorDataHash != "" {
		op["anchor_data_hash"] = anchorDataHash
	}
	st.operations = append(st.operations, op)
	return st
}

func (st *ScriptTx) UnregisterDRep(credHash, credType, redeemerCborHex, refundAddress string) *ScriptTx {
	op := map[string]interface{}{
		"type": "unregister_drep", "credential_hash": credHash, "credential_type": credType,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if refundAddress != "" {
		op["refund_address"] = refundAddress
	}
	st.operations = append(st.operations, op)
	return st
}

func (st *ScriptTx) UpdateDRep(credHash, credType, redeemerCborHex, anchorURL, anchorDataHash string) *ScriptTx {
	op := map[string]interface{}{
		"type": "update_drep", "credential_hash": credHash, "credential_type": credType,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if anchorURL != "" {
		op["anchor_url"] = anchorURL
	}
	if anchorDataHash != "" {
		op["anchor_data_hash"] = anchorDataHash
	}
	st.operations = append(st.operations, op)
	return st
}

func (st *ScriptTx) DelegateVotingPowerTo(address, drepType, drepHash, redeemerCborHex string) *ScriptTx {
	op := map[string]interface{}{
		"type": "delegate_voting_power_to", "address": address, "drep_type": drepType,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if drepHash != "" {
		op["drep_hash"] = drepHash
	}
	st.operations = append(st.operations, op)
	return st
}

func (st *ScriptTx) CreateVote(voterType, voterHash, govActionTxHash string, govActionIndex int, vote, redeemerCborHex, anchorURL, anchorDataHash string) *ScriptTx {
	op := map[string]interface{}{
		"type": "create_vote", "voter_type": voterType, "voter_hash": voterHash,
		"gov_action_tx_hash": govActionTxHash, "gov_action_index": govActionIndex, "vote": vote,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if anchorURL != "" {
		op["anchor_url"] = anchorURL
	}
	if anchorDataHash != "" {
		op["anchor_data_hash"] = anchorDataHash
	}
	st.operations = append(st.operations, op)
	return st
}

func (st *ScriptTx) CreateProposal(govActionType, returnAddress, anchorURL, anchorDataHash, redeemerCborHex string, withdrawals []map[string]string) *ScriptTx {
	op := map[string]interface{}{
		"type": "create_proposal", "gov_action_type": govActionType,
		"return_address": returnAddress, "anchor_url": anchorURL, "anchor_data_hash": anchorDataHash,
		"redeemer_cbor_hex": redeemerCborHex,
	}
	if len(withdrawals) > 0 {
		op["withdrawals"] = withdrawals
	}
	st.operations = append(st.operations, op)
	return st
}

func (st *ScriptTx) From(address string) *ScriptTx {
	st.from = address
	return st
}

func (st *ScriptTx) ChangeAddress(address string) *ScriptTx {
	st.changeAddress = address
	return st
}

func (st *ScriptTx) ChangeDatum(datumCborHex string) *ScriptTx {
	st.changeDatumCbor = datumCborHex
	return st
}

func (st *ScriptTx) ChangeDatumHash(hash string) *ScriptTx {
	st.changeDatumHash = hash
	return st
}

func (st *ScriptTx) ToSpec() map[string]interface{} {
	spec := map[string]interface{}{
		"tx_type":    "script_tx",
		"from":       st.from,
		"operations": st.operations,
	}
	if st.changeAddress != "" {
		spec["change_address"] = st.changeAddress
	}
	if st.changeDatumCbor != "" {
		spec["change_datum_cbor_hex"] = st.changeDatumCbor
	}
	if st.changeDatumHash != "" {
		spec["change_datum_hash"] = st.changeDatumHash
	}
	return spec
}
