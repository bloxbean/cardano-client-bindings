package ccl

import "testing"

// --- Negative / Error Tests ---

func TestPlutusDataHashInvalidCbor(t *testing.T) {
	_, err := bridge.Plutus.DataHash("zzzz")
	assertCclError(t, "Plutus.DataHash(invalid cbor)", err)
}

func TestPlutusDataHashEmpty(t *testing.T) {
	_, err := bridge.Plutus.DataHash("")
	assertCclError(t, "Plutus.DataHash(empty)", err)
}
