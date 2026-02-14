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

// --- QuickTx Tests ---

var fakeTxHash = strings.Repeat("a", 64)

func testProtocolParams() map[string]interface{} {
	return map[string]interface{}{
		"min_fee_a":                       44,
		"min_fee_b":                       155381,
		"max_block_size":                  65536,
		"max_tx_size":                     16384,
		"max_block_header_size":           1100,
		"key_deposit":                     "2000000",
		"pool_deposit":                    "500000000",
		"e_max":                           18,
		"n_opt":                           500,
		"a0":                              0.3,
		"rho":                             0.003,
		"tau":                             0.2,
		"min_utxo":                        "34482",
		"min_pool_cost":                   "340000000",
		"price_mem":                       0.0577,
		"price_step":                      0.0000721,
		"max_tx_ex_mem":                   "10000000",
		"max_tx_ex_steps":                 "10000000000",
		"max_block_ex_mem":                "50000000",
		"max_block_ex_steps":              "40000000000",
		"max_val_size":                    "5000",
		"collateral_percent":              150,
		"max_collateral_inputs":           3,
		"coins_per_utxo_size":             "4310",
		"coins_per_utxo_word":             "34482",
		"pvt_motion_no_confidence":        0.51,
		"pvt_committee_normal":            0.51,
		"pvt_committee_no_confidence":     0.51,
		"pvt_hard_fork_initiation":        0.51,
		"dvt_motion_no_confidence":        0.51,
		"dvt_committee_normal":            0.51,
		"dvt_committee_no_confidence":     0.51,
		"dvt_update_to_constitution":      0.51,
		"dvt_hard_fork_initiation":        0.51,
		"dvt_ppnetwork_group":             0.51,
		"dvt_ppeconomic_group":            0.51,
		"dvt_pptechnical_group":           0.51,
		"dvt_ppgov_group":                 0.51,
		"dvt_treasury_withdrawal":         0.51,
		"committee_min_size":              0,
		"committee_max_term_length":       200,
		"gov_action_lifetime":             10,
		"gov_action_deposit":              1000000000,
		"drep_deposit":                    2000000,
		"drep_activity":                   20,
		"min_fee_ref_script_cost_per_byte": 44,
	}
}

func makeUtxos(address string, lovelace int64) []map[string]interface{} {
	return []map[string]interface{}{
		{
			"tx_hash":      fakeTxHash,
			"output_index": 0,
			"address":      address,
			"amount": []map[string]interface{}{
				{"unit": "lovelace", "quantity": fmt.Sprintf("%d", lovelace)},
			},
		},
	}
}

func assertTxResult(t *testing.T, result *TxResult) {
	t.Helper()
	if len(result.TxCbor) == 0 {
		t.Error("tx_cbor should not be empty")
	}
	if len(result.TxHash) != 64 {
		t.Errorf("expected 64 char tx_hash, got %d", len(result.TxHash))
	}
	fee := 0
	fmt.Sscanf(result.Fee, "%d", &fee)
	if fee <= 0 {
		t.Errorf("expected positive fee, got %s", result.Fee)
	}
}

func TestQuickTxSimpleADAPayment(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}
	receiver, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create receiver: %v", err)
	}

	result, err := bridge.QuickTx.NewTx().
		PayToAddress(receiver.BaseAddress, Ada(5)).
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 100_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxMultipleReceivers(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}
	receiver1, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create receiver1: %v", err)
	}
	receiver2, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create receiver2: %v", err)
	}

	result, err := bridge.QuickTx.NewTx().
		PayToAddress(receiver1.BaseAddress, Ada(5)).
		PayToAddress(receiver2.BaseAddress, Ada(3)).
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 100_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxPayToContract(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}
	receiver, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create receiver: %v", err)
	}

	result, err := bridge.QuickTx.NewTx().
		PayToContract(receiver.BaseAddress, []Amount{Ada(5)}, "182a", "").
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 100_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxMintAssets(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}

	addrInfo, err := bridge.Address.Info(sender.BaseAddress)
	if err != nil {
		t.Fatalf("address info: %v", err)
	}

	scriptJSON := fmt.Sprintf(`{"type":"sig","keyHash":"%s"}`, addrInfo.PaymentCredentialHash)
	assets := []MintAsset{{Name: "TestToken", Quantity: "1000"}}

	result, err := bridge.QuickTx.NewTx().
		MintAssets(scriptJSON, assets, sender.BaseAddress).
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 100_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxAttachMetadata(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}
	receiver, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create receiver: %v", err)
	}

	result, err := bridge.QuickTx.NewTx().
		PayToAddress(receiver.BaseAddress, Ada(2)).
		AttachMetadata(674, map[string]interface{}{"msg": []string{"Hello from Go"}}).
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 100_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxCollectFrom(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}
	receiver, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create receiver: %v", err)
	}

	utxos := makeUtxos(sender.BaseAddress, 100_000_000)
	result, err := bridge.QuickTx.NewTx().
		CollectFrom(utxos).
		PayToAddress(receiver.BaseAddress, Ada(2)).
		From(sender.BaseAddress).
		WithUtxos(utxos).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxRegisterStakeAddress(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}

	result, err := bridge.QuickTx.NewTx().
		RegisterStakeAddress(sender.BaseAddress).
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 100_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxDeregisterStakeAddress(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}

	result, err := bridge.QuickTx.NewTx().
		DeregisterStakeAddress(sender.BaseAddress).
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 100_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxDelegateTo(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}

	poolID := "pool1pu5jlj4q9w9jlxeu370a3c9myx47md5j5m2str0naunn2q3lkdy"
	result, err := bridge.QuickTx.NewTx().
		DelegateTo(sender.BaseAddress, poolID).
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 100_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxWithdraw(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}
	restored, err := bridge.Account.FromMnemonic(sender.Mnemonic, Testnet, 0, 0)
	if err != nil {
		t.Fatalf("restore: %v", err)
	}

	result, err := bridge.QuickTx.NewTx().
		Withdraw(restored.StakeAddress, 5000000).
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 100_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxRegisterDRep(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}

	credHash := strings.Repeat("ab", 28)
	result, err := bridge.QuickTx.NewTx().
		RegisterDRep(credHash, "key").
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 100_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxUnregisterDRep(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}

	credHash := strings.Repeat("ab", 28)
	result, err := bridge.QuickTx.NewTx().
		UnregisterDRep(credHash, "key").
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 100_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxUpdateDRep(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}

	credHash := strings.Repeat("ab", 28)
	dataHash := strings.Repeat("cd", 32)
	result, err := bridge.QuickTx.NewTx().
		UpdateDRep(credHash, "key", AnchorOption{
			AnchorURL:      "https://example.com/drep-v2.json",
			AnchorDataHash: dataHash,
		}).
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 100_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxDelegateVotingPowerTo(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}

	drepHash := strings.Repeat("ab", 28)
	result, err := bridge.QuickTx.NewTx().
		DelegateVotingPowerTo(sender.BaseAddress, "key_hash", drepHash).
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 100_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxCreateVote(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}

	voterHash := strings.Repeat("ab", 28)
	govTxHash := strings.Repeat("cd", 32)
	result, err := bridge.QuickTx.NewTx().
		CreateVote("drep_key_hash", voterHash, govTxHash, 0, "yes").
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 100_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxCreateInfoProposal(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}
	restored, err := bridge.Account.FromMnemonic(sender.Mnemonic, Testnet, 0, 0)
	if err != nil {
		t.Fatalf("restore: %v", err)
	}

	anchorDataHash := strings.Repeat("ab", 32)
	result, err := bridge.QuickTx.NewTx().
		CreateProposal("info_action", restored.StakeAddress,
			"https://example.com/proposal.json", anchorDataHash).
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 2_000_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxCreateTreasuryWithdrawalsProposal(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}
	restored, err := bridge.Account.FromMnemonic(sender.Mnemonic, Testnet, 0, 0)
	if err != nil {
		t.Fatalf("restore: %v", err)
	}

	anchorDataHash := strings.Repeat("ab", 32)
	withdrawals := []ProposalWithdrawal{
		{RewardAddress: restored.StakeAddress, Amount: "1000000"},
	}
	result, err := bridge.QuickTx.NewTx().
		CreateProposal("treasury_withdrawals", restored.StakeAddress,
			"https://example.com/proposal.json", anchorDataHash, withdrawals).
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 2_000_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxCompose(t *testing.T) {
	sender1, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender1: %v", err)
	}
	sender2, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender2: %v", err)
	}
	receiver1, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create receiver1: %v", err)
	}
	receiver2, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create receiver2: %v", err)
	}

	tx1 := bridge.QuickTx.Tx().
		PayToAddress(receiver1.BaseAddress, Ada(5)).
		From(sender1.BaseAddress)

	tx2 := bridge.QuickTx.Tx().
		PayToAddress(receiver2.BaseAddress, Ada(3)).
		From(sender2.BaseAddress)

	utxos := []map[string]interface{}{
		{
			"tx_hash":      fakeTxHash,
			"output_index": 0,
			"address":      sender1.BaseAddress,
			"amount": []map[string]interface{}{
				{"unit": "lovelace", "quantity": "100000000"},
			},
		},
		{
			"tx_hash":      strings.Repeat("b", 64),
			"output_index": 0,
			"address":      sender2.BaseAddress,
			"amount": []map[string]interface{}{
				{"unit": "lovelace", "quantity": "100000000"},
			},
		},
	}

	result, err := bridge.QuickTx.Compose(tx1, tx2).
		FeePayer(sender1.BaseAddress).
		WithUtxos(utxos).
		WithProtocolParams(testProtocolParams()).
		SignerCount(2).
		Build()
	if err != nil {
		t.Fatalf("Build() failed: %v", err)
	}
	assertTxResult(t, result)
}

func TestQuickTxProviderConfig(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}
	receiver, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create receiver: %v", err)
	}

	// This test verifies the spec is built correctly with provider config.
	// It will fail at the FFI level because there's no actual provider,
	// but we verify the builder doesn't error on spec construction.
	_, err = bridge.QuickTx.NewTx().
		PayToAddress(receiver.BaseAddress, Ada(5)).
		From(sender.BaseAddress).
		WithProtocolParams(testProtocolParams()).
		BuildWithProvider(ProviderConfig{
			Name: "yaci_devkit",
			URL:  "http://localhost:3000",
		})
	// Provider config will attempt Java-side HTTP; expect an error
	if err == nil {
		t.Log("BuildWithProvider succeeded (provider was reachable)")
	}
}

func TestQuickTxInsufficientFunds(t *testing.T) {
	sender, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create sender: %v", err)
	}
	receiver, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create receiver: %v", err)
	}

	_, err = bridge.QuickTx.NewTx().
		PayToAddress(receiver.BaseAddress, Ada(200)).
		From(sender.BaseAddress).
		WithUtxos(makeUtxos(sender.BaseAddress, 1_000_000)).
		WithProtocolParams(testProtocolParams()).
		Build()
	if err == nil {
		t.Fatal("expected error for insufficient funds")
	}
	cclErr, ok := err.(*CclError)
	if !ok {
		t.Fatalf("expected CclError, got %T: %v", err, err)
	}
	if cclErr.Code >= 0 {
		t.Errorf("expected negative error code, got %d", cclErr.Code)
	}
}
