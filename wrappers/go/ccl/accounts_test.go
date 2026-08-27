package ccl

// Managed-account object tests (ADR-0016 slice 6): lifecycle, typed-role signing parity with the
// mnemonic-per-call path, one-shot recovery-phrase export, secret hygiene, and executor-thread
// concurrency. Fully offline.

import (
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
)

func unsignedStakeReg(t *testing.T, info *AccountPublicInfo) string {
	t.Helper()
	yaml := fmt.Sprintf(`
version: 1.0
transaction:
  - tx:
      from: %s
      intents:
        - type: stake_registration
          stake_address: %s
`, info.BaseAddress, info.StakeAddress)
	utxos := makeUtxos(info.BaseAddress, 2_000_000_000)
	result, err := bridge.QuickTx.Build(yaml, utxos, testProtocolParams(), 1)
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	return result.TxCbor
}

func handleErrCode(t *testing.T, err error) int {
	t.Helper()
	var cclErr *CclError
	if !errors.As(err, &cclErr) {
		t.Fatalf("expected *CclError, got %T: %v", err, err)
	}
	return cclErr.Code
}

func TestManagedAccountInfoMatchesLegacy(t *testing.T) {
	acct, err := bridge.Accounts.FromMnemonic(intentMnemonic, Testnet, 0, 0)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer acct.Close()

	info, err := acct.Info()
	if err != nil {
		t.Fatalf("info: %v", err)
	}
	legacy, err := bridge.Account.FromMnemonic(intentMnemonic, Testnet, 0, 0)
	if err != nil {
		t.Fatalf("legacy: %v", err)
	}
	if info.BaseAddress != legacy.BaseAddress || info.StakeAddress != legacy.StakeAddress {
		t.Fatalf("managed info diverges from legacy derivation")
	}
	if info.Network != int(Testnet) || info.AccountIndex != 0 || info.AddressIndex != 0 {
		t.Fatalf("unexpected derivation metadata: %+v", info)
	}
}

func TestManagedAccountSignParity(t *testing.T) {
	acct, err := bridge.Accounts.FromMnemonic(intentMnemonic, Testnet, 0, 0)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer acct.Close()
	info, _ := acct.Info()
	unsigned := unsignedStakeReg(t, info)

	managed, err := acct.SignTx(unsigned, RolePayment)
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	legacy, err := bridge.Account.SignTx(intentMnemonic, Testnet, 0, 0, unsigned)
	if err != nil {
		t.Fatalf("legacy sign: %v", err)
	}
	if managed != legacy {
		t.Fatal("payment signature diverges from mnemonic-per-call path")
	}

	managed2, err := acct.SignTx(unsigned, RolePayment|RoleStake)
	if err != nil {
		t.Fatalf("sign 2: %v", err)
	}
	legacy2, err := bridge.Account.SignTxWithKeys(intentMnemonic, Testnet, 0, 0, unsigned, "payment", "stake")
	if err != nil {
		t.Fatalf("legacy sign 2: %v", err)
	}
	if managed2 != legacy2 {
		t.Fatal("payment+stake signature diverges from mnemonic-per-call path")
	}

	// Mask order is irrelevant — canonical application order fixes the output.
	reordered, _ := acct.SignTx(unsigned, RoleStake|RolePayment)
	if reordered != managed2 {
		t.Fatal("mask order changed the signed output")
	}
}

func TestManagedAccountEmptyMaskRejected(t *testing.T) {
	acct, _ := bridge.Accounts.FromMnemonic(intentMnemonic, Testnet, 0, 0)
	defer acct.Close()
	info, _ := acct.Info()
	if _, err := acct.SignTx(unsignedStakeReg(t, info), 0); err == nil {
		t.Fatal("empty role mask must be rejected")
	}
}

func TestManagedAccountLifecycle(t *testing.T) {
	acct, _ := bridge.Accounts.FromMnemonic(intentMnemonic, Testnet, 0, 0)
	if err := acct.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	if err := acct.Close(); err != nil { // idempotent
		t.Fatalf("double close: %v", err)
	}
	_, err := acct.Info()
	if err == nil {
		t.Fatal("use after close must fail")
	}
	if code := handleErrCode(t, err); code != ErrInvalidHandle {
		t.Fatalf("expected ErrInvalidHandle (-11), got %d", code)
	}
}

func TestManagedAccountCreateExportOnceRestore(t *testing.T) {
	acct, err := bridge.Accounts.Create(Testnet)
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	defer acct.Close()
	info, _ := acct.Info()

	phrase, err := acct.ExportRecoveryPhrase()
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	if len(strings.Fields(phrase)) != 24 {
		t.Fatalf("expected 24 words, got %d", len(strings.Fields(phrase)))
	}

	restored, err := bridge.Accounts.FromMnemonic(phrase, Testnet, 0, 0)
	if err != nil {
		t.Fatalf("restore: %v", err)
	}
	defer restored.Close()
	rInfo, _ := restored.Info()
	if rInfo.BaseAddress != info.BaseAddress {
		t.Fatal("exported phrase does not restore the same account")
	}

	if _, err := acct.ExportRecoveryPhrase(); err == nil {
		t.Fatal("export must be one-shot")
	}
	if _, err := restored.ExportRecoveryPhrase(); err == nil {
		t.Fatal("imported accounts must not export")
	}
}

func TestManagedAccountStringNeverContainsSecrets(t *testing.T) {
	acct, _ := bridge.Accounts.Create(Testnet)
	defer acct.Close()
	phrase, _ := acct.ExportRecoveryPhrase()
	s := acct.String()
	if strings.Contains(s, "addr") || strings.Contains(s, strings.Fields(phrase)[0]) {
		t.Fatalf("secret or address leaked through String(): %s", s)
	}
	acct.Close()
	if acct.String() != "<ccl.Account closed>" {
		t.Fatalf("unexpected closed representation: %s", acct.String())
	}
}

// Concurrent Account use from many goroutines must serialize safely onto the Bridge's dedicated
// isolate thread (ADR-0010) — correct results, no data race (run with -race in CI).
func TestManagedAccountConcurrentUseSerializes(t *testing.T) {
	acct, _ := bridge.Accounts.FromMnemonic(intentMnemonic, Testnet, 0, 0)
	defer acct.Close()
	want, _ := acct.Info()

	var wg sync.WaitGroup
	errs := make(chan error, 16)
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			info, err := acct.Info()
			if err != nil {
				errs <- err
				return
			}
			if info.BaseAddress != want.BaseAddress {
				errs <- fmt.Errorf("diverging info under concurrency")
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
}
