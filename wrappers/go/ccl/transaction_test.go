package ccl

import "testing"

// --- Negative / Error Tests ---

// Well-formed hex, but not a valid transaction CBOR.
func TestTxHashMalformedCbor(t *testing.T) {
	_, err := bridge.Tx.Hash("deadbeef")
	assertCclError(t, "Tx.Hash(malformed cbor)", err)
}

// Not even valid hex.
func TestTxHashInvalidHex(t *testing.T) {
	_, err := bridge.Tx.Hash("not_hex!")
	assertCclError(t, "Tx.Hash(invalid hex)", err)
}

func TestTxDeserializeMalformed(t *testing.T) {
	_, err := bridge.Tx.Deserialize("deadbeef")
	assertCclError(t, "Tx.Deserialize(malformed)", err)
}
