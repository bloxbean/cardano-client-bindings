package ccl

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"testing"
)

// A known valid transaction CBOR hex (built from Java tests)
const sampleTxCbor = "84a300d901028182582073198b7ad003862b9798106b88fbccfca464b1a38afb34958275c4a7d7d8d002010181825839009493315cd92eb5d8c4304e67b7e16ae36d61d34502694657811a2c8e32c728d3861e164cab28cb8f006448139c8f1740ffb8e7aa9e5232dc1a001e8480021a00029810a0f5f6"

var bridge *Bridge

func TestMain(m *testing.M) {
	var err error
	bridge, err = New()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to create bridge: %v\n", err)
		os.Exit(1)
	}
	code := m.Run()
	bridge.Close()
	os.Exit(code)
}

func TestVersion(t *testing.T) {
	version, err := bridge.Version()
	if err != nil {
		t.Fatalf("Version() failed: %v", err)
	}
	if version != "0.1.0" {
		t.Errorf("expected version 0.1.0, got %s", version)
	}
}

func TestAccountCreate(t *testing.T) {
	info, err := bridge.Account.Create(Mainnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	if !strings.HasPrefix(info.BaseAddress, "addr1") {
		t.Errorf("expected mainnet address prefix, got %s", info.BaseAddress)
	}

	words := strings.Fields(info.Mnemonic)
	if len(words) != 24 {
		t.Errorf("expected 24 word mnemonic, got %d", len(words))
	}
}

func TestAccountFromMnemonic(t *testing.T) {
	created, err := bridge.Account.Create(Mainnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	restored, err := bridge.Account.FromMnemonic(created.Mnemonic, Mainnet, 0, 0)
	if err != nil {
		t.Fatalf("Account.FromMnemonic() failed: %v", err)
	}

	if restored.BaseAddress != created.BaseAddress {
		t.Errorf("addresses don't match: %s != %s", restored.BaseAddress, created.BaseAddress)
	}
}

func TestAccountGetKeys(t *testing.T) {
	created, err := bridge.Account.Create(Mainnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	privKey, err := bridge.Account.GetPrivateKey(created.Mnemonic, Mainnet, 0, 0)
	if err != nil {
		t.Fatalf("Account.GetPrivateKey() failed: %v", err)
	}
	if len(privKey) != 128 {
		t.Errorf("expected 128 hex chars (64 bytes extended), got %d", len(privKey))
	}

	pubKey, err := bridge.Account.GetPublicKey(created.Mnemonic, Mainnet, 0, 0)
	if err != nil {
		t.Fatalf("Account.GetPublicKey() failed: %v", err)
	}
	if len(pubKey) != 64 {
		t.Errorf("expected 64 hex chars public key, got %d", len(pubKey))
	}
}

func TestAccountGetDRepID(t *testing.T) {
	created, err := bridge.Account.Create(Mainnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	drepID, err := bridge.Account.GetDRepID(created.Mnemonic, Mainnet, 0)
	if err != nil {
		t.Fatalf("Account.GetDRepID() failed: %v", err)
	}
	if !strings.HasPrefix(drepID, "drep1") {
		t.Errorf("expected drep1 prefix, got %s", drepID)
	}
}

func TestAccountSignTx(t *testing.T) {
	created, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	signed, err := bridge.Account.SignTx(created.Mnemonic, Testnet, 0, 0, sampleTxCbor)
	if err != nil {
		t.Fatalf("Account.SignTx() failed: %v", err)
	}
	if len(signed) <= len(sampleTxCbor) {
		t.Error("signed tx should be larger than unsigned")
	}
}

func TestAddressToFromBytes(t *testing.T) {
	created, err := bridge.Account.Create(Mainnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	hexBytes, err := bridge.Address.ToBytes(created.BaseAddress)
	if err != nil {
		t.Fatalf("Address.ToBytes() failed: %v", err)
	}
	if len(hexBytes) == 0 {
		t.Error("hex bytes should not be empty")
	}

	restored, err := bridge.Address.FromBytes(hexBytes)
	if err != nil {
		t.Fatalf("Address.FromBytes() failed: %v", err)
	}
	if restored != created.BaseAddress {
		t.Errorf("round-trip failed: %s != %s", restored, created.BaseAddress)
	}
}

func TestAddressValidate(t *testing.T) {
	created, err := bridge.Account.Create(Mainnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	if !bridge.Address.Validate(created.BaseAddress) {
		t.Error("valid address should pass validation")
	}

	if bridge.Address.Validate("invalid_address") {
		t.Error("invalid address should fail validation")
	}
}

func TestAddressInfo(t *testing.T) {
	created, err := bridge.Account.Create(Mainnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	info, err := bridge.Address.Info(created.BaseAddress)
	if err != nil {
		t.Fatalf("Address.Info() failed: %v", err)
	}
	if info.Type != "Base" {
		t.Errorf("expected type Base, got %s", info.Type)
	}
	if info.NetworkID != 1 {
		t.Errorf("expected network_id 1, got %d", info.NetworkID)
	}
}

func TestCryptoBlake2b256(t *testing.T) {
	hash, err := bridge.Crypto.Blake2b256("48656c6c6f")
	if err != nil {
		t.Fatalf("Crypto.Blake2b256() failed: %v", err)
	}
	if len(hash) != 64 {
		t.Errorf("expected 64 hex chars, got %d", len(hash))
	}
}

func TestCryptoBlake2b224(t *testing.T) {
	hash, err := bridge.Crypto.Blake2b224("48656c6c6f")
	if err != nil {
		t.Fatalf("Crypto.Blake2b224() failed: %v", err)
	}
	if len(hash) != 56 {
		t.Errorf("expected 56 hex chars, got %d", len(hash))
	}
}

func TestCryptoMnemonic(t *testing.T) {
	mnemonic, err := bridge.Crypto.GenerateMnemonic(24)
	if err != nil {
		t.Fatalf("Crypto.GenerateMnemonic() failed: %v", err)
	}

	words := strings.Fields(mnemonic)
	if len(words) != 24 {
		t.Errorf("expected 24 words, got %d", len(words))
	}

	if !bridge.Crypto.ValidateMnemonic(mnemonic) {
		t.Error("generated mnemonic should be valid")
	}

	if bridge.Crypto.ValidateMnemonic("invalid mnemonic") {
		t.Error("invalid mnemonic should fail validation")
	}
}

func TestCryptoSign(t *testing.T) {
	created, err := bridge.Account.Create(Mainnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	privKey, err := bridge.Account.GetPrivateKey(created.Mnemonic, Mainnet, 0, 0)
	if err != nil {
		t.Fatalf("Account.GetPrivateKey() failed: %v", err)
	}

	// Use first 32 bytes (64 hex chars) for standard Ed25519 sign
	privKey32 := privKey[:64]

	messageHex := "68656c6c6f"
	sig, err := bridge.Crypto.Sign(messageHex, privKey32)
	if err != nil {
		t.Fatalf("Crypto.Sign() failed: %v", err)
	}
	if len(sig) != 128 {
		t.Errorf("expected 128 hex chars signature, got %d", len(sig))
	}
}

func TestTxHash(t *testing.T) {
	hash, err := bridge.Tx.Hash(sampleTxCbor)
	if err != nil {
		t.Fatalf("Tx.Hash() failed: %v", err)
	}
	if len(hash) != 64 {
		t.Errorf("expected 64 hex chars, got %d", len(hash))
	}
	if hash != "7af07f974db1d004305d29670d04faeef0e9670e8cf95e4b54a06f668eed8de4" {
		t.Errorf("unexpected tx hash: %s", hash)
	}
}

func TestTxToJson(t *testing.T) {
	jsonStr, err := bridge.Tx.ToJson(sampleTxCbor)
	if err != nil {
		t.Fatalf("Tx.ToJson() failed: %v", err)
	}

	var parsed map[string]interface{}
	if err := json.Unmarshal([]byte(jsonStr), &parsed); err != nil {
		t.Fatalf("Tx.ToJson returned invalid JSON: %v", err)
	}
	if _, ok := parsed["body"]; !ok {
		t.Error("expected 'body' key in JSON")
	}
}

func TestTxDeserialize(t *testing.T) {
	jsonStr, err := bridge.Tx.Deserialize(sampleTxCbor)
	if err != nil {
		t.Fatalf("Tx.Deserialize() failed: %v", err)
	}

	var parsed map[string]interface{}
	if err := json.Unmarshal([]byte(jsonStr), &parsed); err != nil {
		t.Fatalf("Tx.Deserialize returned invalid JSON: %v", err)
	}
	if _, ok := parsed["body"]; !ok {
		t.Error("expected 'body' key in deserialized JSON")
	}
}

func TestPlutusDataHash(t *testing.T) {
	hash, err := bridge.Plutus.DataHash("182a")
	if err != nil {
		t.Fatalf("Plutus.DataHash() failed: %v", err)
	}
	if len(hash) != 64 {
		t.Errorf("expected 64 hex chars, got %d", len(hash))
	}
	if hash != "9e1199a988ba72ffd6e9c269cadb3b53b5f360ff99f112d9b2ee30c4d74ad88b" {
		t.Errorf("unexpected datum hash: %s", hash)
	}
}

func TestScriptNativeFromJson(t *testing.T) {
	created, err := bridge.Account.Create(Mainnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	addrInfo, err := bridge.Address.Info(created.BaseAddress)
	if err != nil {
		t.Fatalf("Address.Info() failed: %v", err)
	}

	scriptJSON := fmt.Sprintf(`{"type":"sig","keyHash":"%s"}`, addrInfo.PaymentCredentialHash)
	result, err := bridge.Script.NativeFromJson(scriptJSON)
	if err != nil {
		t.Fatalf("Script.NativeFromJson() failed: %v", err)
	}

	var parsed map[string]interface{}
	if err := json.Unmarshal([]byte(result), &parsed); err != nil {
		t.Fatalf("Script.NativeFromJson returned invalid JSON: %v", err)
	}
	if _, ok := parsed["policy_id"]; !ok {
		t.Error("expected 'policy_id' key in result")
	}
	if _, ok := parsed["script_hash"]; !ok {
		t.Error("expected 'script_hash' key in result")
	}
	if _, ok := parsed["cbor_hex"]; !ok {
		t.Error("expected 'cbor_hex' key in result")
	}
}

func TestScriptHash(t *testing.T) {
	created, err := bridge.Account.Create(Mainnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	addrInfo, err := bridge.Address.Info(created.BaseAddress)
	if err != nil {
		t.Fatalf("Address.Info() failed: %v", err)
	}

	scriptJSON := fmt.Sprintf(`{"type":"sig","keyHash":"%s"}`, addrInfo.PaymentCredentialHash)
	result, err := bridge.Script.NativeFromJson(scriptJSON)
	if err != nil {
		t.Fatalf("Script.NativeFromJson() failed: %v", err)
	}

	var parsed map[string]interface{}
	json.Unmarshal([]byte(result), &parsed)
	cborHex := parsed["cbor_hex"].(string)

	hash, err := bridge.Script.Hash(cborHex, 0)
	if err != nil {
		t.Fatalf("Script.Hash() failed: %v", err)
	}
	if len(hash) != 56 {
		t.Errorf("expected 56 hex chars, got %d", len(hash))
	}
}

func TestGovDrepKey(t *testing.T) {
	created, err := bridge.Account.Create(Mainnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	info, err := bridge.Gov.DrepKeyFromMnemonic(created.Mnemonic, Mainnet, 0)
	if err != nil {
		t.Fatalf("Gov.DrepKeyFromMnemonic() failed: %v", err)
	}
	if len(info.VerificationKey) == 0 {
		t.Error("verification key should not be empty")
	}
	if !strings.HasPrefix(info.DrepID, "drep1") {
		t.Errorf("expected drep1 prefix, got %s", info.DrepID)
	}
}

func TestGovCommitteeColdKey(t *testing.T) {
	created, err := bridge.Account.Create(Mainnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	info, err := bridge.Gov.CommitteeColdKeyFromMnemonic(created.Mnemonic, Mainnet, 0)
	if err != nil {
		t.Fatalf("Gov.CommitteeColdKeyFromMnemonic() failed: %v", err)
	}
	if len(info.VerificationKey) == 0 {
		t.Error("verification key should not be empty")
	}
	if !strings.HasPrefix(info.ID, "cc_cold1") {
		t.Errorf("expected cc_cold1 prefix, got %s", info.ID)
	}
}

func TestGovCommitteeHotKey(t *testing.T) {
	created, err := bridge.Account.Create(Mainnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	info, err := bridge.Gov.CommitteeHotKeyFromMnemonic(created.Mnemonic, Mainnet, 0)
	if err != nil {
		t.Fatalf("Gov.CommitteeHotKeyFromMnemonic() failed: %v", err)
	}
	if len(info.VerificationKey) == 0 {
		t.Error("verification key should not be empty")
	}
	if !strings.HasPrefix(info.ID, "cc_hot1") {
		t.Errorf("expected cc_hot1 prefix, got %s", info.ID)
	}
}

func TestWalletCreate(t *testing.T) {
	wallet, err := bridge.Wallet.Create(Mainnet)
	if err != nil {
		t.Fatalf("Wallet.Create() failed: %v", err)
	}

	words := strings.Fields(wallet.Mnemonic)
	if len(words) != 24 {
		t.Errorf("expected 24 word mnemonic, got %d", len(words))
	}
}

func TestWalletFromMnemonic(t *testing.T) {
	wallet, err := bridge.Wallet.Create(Mainnet)
	if err != nil {
		t.Fatalf("Wallet.Create() failed: %v", err)
	}

	restored, err := bridge.Wallet.FromMnemonic(wallet.Mnemonic, Mainnet)
	if err != nil {
		t.Fatalf("Wallet.FromMnemonic() failed: %v", err)
	}

	if restored.StakeAddress != wallet.StakeAddress {
		t.Errorf("stake addresses don't match: %s != %s", restored.StakeAddress, wallet.StakeAddress)
	}
}

func TestWalletGetAddress(t *testing.T) {
	wallet, err := bridge.Wallet.Create(Mainnet)
	if err != nil {
		t.Fatalf("Wallet.Create() failed: %v", err)
	}

	addr0, err := bridge.Wallet.GetAddress(wallet.Mnemonic, Mainnet, 0)
	if err != nil {
		t.Fatalf("Wallet.GetAddress(0) failed: %v", err)
	}
	if !strings.HasPrefix(addr0, "addr1") {
		t.Errorf("expected addr1 prefix, got %s", addr0)
	}

	addr1, err := bridge.Wallet.GetAddress(wallet.Mnemonic, Mainnet, 1)
	if err != nil {
		t.Fatalf("Wallet.GetAddress(1) failed: %v", err)
	}
	if addr0 == addr1 {
		t.Error("addresses at different indices should differ")
	}
}
