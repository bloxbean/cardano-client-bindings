from ccl._ffi import CclLib


class Crypto:
    """Pythonic wrapper for CCL Crypto operations."""

    def __init__(self, lib: CclLib):
        self._lib = lib

    def blake2b_256(self, data_hex):
        """Compute Blake2b-256 hash. Returns hex string."""
        return self._lib.crypto_blake2b_256(data_hex)

    def blake2b_224(self, data_hex):
        """Compute Blake2b-224 hash. Returns hex string."""
        return self._lib.crypto_blake2b_224(data_hex)

    def generate_mnemonic(self, word_count=24):
        """Generate a new mnemonic phrase."""
        return self._lib.crypto_generate_mnemonic(word_count)

    def validate_mnemonic(self, mnemonic):
        """Validate a mnemonic phrase. Returns True if valid."""
        return self._lib.crypto_validate_mnemonic(mnemonic)

    def sign(self, message_hex, sk_hex):
        """Sign message with secret key. Returns signature hex."""
        return self._lib.crypto_sign(message_hex, sk_hex)

    def verify(self, signature_hex, message_hex, pk_hex):
        """Verify signature. Returns True if valid."""
        return self._lib.crypto_verify(signature_hex, message_hex, pk_hex)
