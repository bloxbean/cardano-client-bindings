package ccl

import (
	"strings"
	"testing"
)

// A testnet wallet's stake address carries the stake_test1 prefix.
func TestWalletCreateTestnet(t *testing.T) {
	wallet, err := bridge.Wallet.Create(Testnet)
	if err != nil {
		t.Fatalf("Wallet.Create(Testnet) failed: %v", err)
	}
	if !strings.HasPrefix(wallet.StakeAddress, "stake_test1") {
		t.Errorf("expected stake_test1 prefix, got %s", wallet.StakeAddress)
	}
}
