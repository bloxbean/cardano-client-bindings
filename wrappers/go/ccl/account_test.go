package ccl

import (
	"errors"
	"strings"
	"testing"
)

// assertCclError fails unless err is a non-nil *CclError. Used by the offline negative/error tests
// ported from the Python wrapper (which assert a CclError is raised).
func assertCclError(t *testing.T, op string, err error) {
	t.Helper()
	if err == nil {
		t.Fatalf("%s: expected an error, got nil", op)
	}
	var ce *CclError
	if !errors.As(err, &ce) {
		t.Fatalf("%s: expected *CclError, got %T: %v", op, err, err)
	}
}

// A testnet account's base address is bech32 with the addr_test1 prefix (network id 0 on the wire).
func TestAccountCreateTestnet(t *testing.T) {
	info, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("Account.Create(Testnet) failed: %v", err)
	}
	if !strings.HasPrefix(info.BaseAddress, "addr_test1") {
		t.Errorf("expected addr_test1 prefix, got %s", info.BaseAddress)
	}
}

// Restoring from a mnemonic must reproduce every derived address, not just the base one.
func TestAccountFromMnemonicRestoresAllAddresses(t *testing.T) {
	created, err := bridge.Account.Create(Mainnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	restored, err := bridge.Account.FromMnemonic(created.Mnemonic, Mainnet, 0, 0)
	if err != nil {
		t.Fatalf("Account.FromMnemonic() failed: %v", err)
	}

	if restored.BaseAddress != created.BaseAddress {
		t.Errorf("base address mismatch: %s != %s", restored.BaseAddress, created.BaseAddress)
	}
	if restored.EnterpriseAddress != created.EnterpriseAddress {
		t.Errorf("enterprise address mismatch: %s != %s", restored.EnterpriseAddress, created.EnterpriseAddress)
	}
	if restored.StakeAddress != created.StakeAddress {
		t.Errorf("stake address mismatch: %s != %s", restored.StakeAddress, created.StakeAddress)
	}
}

// Different address indices derive different base addresses from the same mnemonic.
func TestAccountFromMnemonicDifferentIndices(t *testing.T) {
	created, err := bridge.Account.Create(Mainnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}

	addr0, err := bridge.Account.FromMnemonic(created.Mnemonic, Mainnet, 0, 0)
	if err != nil {
		t.Fatalf("FromMnemonic(0,0) failed: %v", err)
	}
	addr1, err := bridge.Account.FromMnemonic(created.Mnemonic, Mainnet, 0, 1)
	if err != nil {
		t.Fatalf("FromMnemonic(0,1) failed: %v", err)
	}
	if addr0.BaseAddress == addr1.BaseAddress {
		t.Errorf("addresses at different indices should differ, both %s", addr0.BaseAddress)
	}
}

// --- Negative / Error Tests ---

func TestAccountFromInvalidMnemonic(t *testing.T) {
	_, err := bridge.Account.FromMnemonic("invalid words that are not a valid mnemonic phrase at all", Mainnet, 0, 0)
	assertCclError(t, "FromMnemonic(invalid)", err)
}

func TestAccountFromEmptyMnemonic(t *testing.T) {
	_, err := bridge.Account.FromMnemonic("", Mainnet, 0, 0)
	assertCclError(t, "FromMnemonic(empty)", err)
}

func TestAccountSignTxInvalidCbor(t *testing.T) {
	created, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("Account.Create() failed: %v", err)
	}
	_, err = bridge.Account.SignTx(created.Mnemonic, Testnet, 0, 0, "deadbeef")
	assertCclError(t, "SignTx(invalid cbor)", err)
}
