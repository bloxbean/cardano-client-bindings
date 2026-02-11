package ccl

import (
	"strings"
	"testing"
)

func TestNew(t *testing.T) {
	b, err := New()
	if err != nil {
		t.Fatalf("New() failed: %v", err)
	}
	defer b.Close()
}

func TestVersion(t *testing.T) {
	b, err := New()
	if err != nil {
		t.Fatalf("New() failed: %v", err)
	}
	defer b.Close()

	version, err := b.Version()
	if err != nil {
		t.Fatalf("Version() failed: %v", err)
	}
	if version != "0.1.0" {
		t.Errorf("expected version 0.1.0, got %s", version)
	}
}

func TestAccountCreate(t *testing.T) {
	b, err := New()
	if err != nil {
		t.Fatalf("New() failed: %v", err)
	}
	defer b.Close()

	info, err := b.AccountCreate(Mainnet)
	if err != nil {
		t.Fatalf("AccountCreate() failed: %v", err)
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
	b, err := New()
	if err != nil {
		t.Fatalf("New() failed: %v", err)
	}
	defer b.Close()

	created, err := b.AccountCreate(Mainnet)
	if err != nil {
		t.Fatalf("AccountCreate() failed: %v", err)
	}

	restored, err := b.AccountFromMnemonic(created.Mnemonic, Mainnet, 0, 0)
	if err != nil {
		t.Fatalf("AccountFromMnemonic() failed: %v", err)
	}

	if restored.BaseAddress != created.BaseAddress {
		t.Errorf("addresses don't match: %s != %s", restored.BaseAddress, created.BaseAddress)
	}
}

func TestCryptoBlake2b256(t *testing.T) {
	b, err := New()
	if err != nil {
		t.Fatalf("New() failed: %v", err)
	}
	defer b.Close()

	hash, err := b.CryptoBlake2b256("48656c6c6f")
	if err != nil {
		t.Fatalf("CryptoBlake2b256() failed: %v", err)
	}

	if len(hash) != 64 {
		t.Errorf("expected 64 hex chars, got %d", len(hash))
	}
}

func TestCryptoMnemonic(t *testing.T) {
	b, err := New()
	if err != nil {
		t.Fatalf("New() failed: %v", err)
	}
	defer b.Close()

	mnemonic, err := b.CryptoGenerateMnemonic(24)
	if err != nil {
		t.Fatalf("CryptoGenerateMnemonic() failed: %v", err)
	}

	words := strings.Fields(mnemonic)
	if len(words) != 24 {
		t.Errorf("expected 24 words, got %d", len(words))
	}

	if !b.CryptoValidateMnemonic(mnemonic) {
		t.Error("generated mnemonic should be valid")
	}

	if b.CryptoValidateMnemonic("invalid mnemonic") {
		t.Error("invalid mnemonic should fail validation")
	}
}

func TestAddressValidate(t *testing.T) {
	b, err := New()
	if err != nil {
		t.Fatalf("New() failed: %v", err)
	}
	defer b.Close()

	created, err := b.AccountCreate(Mainnet)
	if err != nil {
		t.Fatalf("AccountCreate() failed: %v", err)
	}

	if !b.AddressValidate(created.BaseAddress) {
		t.Error("valid address should pass validation")
	}

	if b.AddressValidate("invalid_address") {
		t.Error("invalid address should fail validation")
	}
}
