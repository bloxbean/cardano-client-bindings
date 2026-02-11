from ccl._ffi import CclLib


class Transaction:
    """Pythonic wrapper for CCL Transaction operations."""

    def __init__(self, lib: CclLib):
        self._lib = lib

    def sign_with_secret_key(self, tx_cbor_hex, sk_cbor_hex):
        """Sign transaction with a secret key. Returns signed tx CBOR hex."""
        return self._lib.tx_sign_with_secret_key(tx_cbor_hex, sk_cbor_hex)

    def hash(self, tx_cbor_hex):
        """Get transaction hash (blake2b-256 of body). Returns hex string."""
        return self._lib.tx_hash(tx_cbor_hex)

    def to_json(self, tx_cbor_hex):
        """Convert transaction CBOR to JSON representation."""
        return self._lib.tx_to_json(tx_cbor_hex)
