class Crypto:
    """Crypto namespace for CCL operations."""

    def __init__(self, bridge):
        self._b = bridge

    def blake2b_256(self, data_hex):
        """Compute Blake2b-256 hash. Returns hex string."""
        rc = self._b._lib.ccl_crypto_blake2b_256(self._b._thread, self._b._encode(data_hex))
        return self._b._check(rc)

    def blake2b_224(self, data_hex):
        """Compute Blake2b-224 hash. Returns hex string."""
        rc = self._b._lib.ccl_crypto_blake2b_224(self._b._thread, self._b._encode(data_hex))
        return self._b._check(rc)

    def generate_mnemonic(self, word_count=24):
        """Generate a new mnemonic phrase."""
        rc = self._b._lib.ccl_crypto_generate_mnemonic(self._b._thread, word_count)
        return self._b._check(rc)

    def validate_mnemonic(self, mnemonic):
        """Validate a mnemonic phrase. Returns True if valid."""
        rc = self._b._lib.ccl_crypto_validate_mnemonic(self._b._thread, self._b._encode(mnemonic))
        from ccl._ffi import CclLib
        return rc == CclLib.CCL_SUCCESS

    def sign(self, message_hex, sk_hex):
        """Sign message with secret key. Returns signature hex."""
        rc = self._b._lib.ccl_crypto_sign(
            self._b._thread, self._b._encode(message_hex), self._b._encode(sk_hex))
        return self._b._check(rc)

    def verify(self, signature_hex, message_hex, pk_hex):
        """Verify signature. Returns True if valid."""
        rc = self._b._lib.ccl_crypto_verify(
            self._b._thread, self._b._encode(signature_hex),
            self._b._encode(message_hex), self._b._encode(pk_hex))
        from ccl._ffi import CclLib
        return rc == CclLib.CCL_SUCCESS

    def derive_key(self, mnemonic, account_index=0, address_index=0, role="payment"):
        """Stateless CIP-1852 key derivation — the explicit "raw key material" utility.

        ``role`` is one of ``"payment"``, ``"change"``, ``"stake"``, ``"drep"``,
        ``"committee_cold"``, ``"committee_hot"``. Returns a dict with ``path``,
        ``private_key`` (hex 64-byte extended key; its first 64 hex chars are the raw
        Ed25519 key accepted by :meth:`sign`), ``public_key``, and ``public_key_hash``
        (for the committee roles this is the certificate credential). Key derivation is
        network-independent, so no network argument. Prefer managed accounts
        (``lib.accounts``) for signing — this exists for interop that genuinely needs
        key bytes.
        """
        import json
        rc = self._b._lib.ccl_crypto_derive_key(
            self._b._thread, self._b._encode(mnemonic),
            account_index, address_index, self._b._encode(role))
        return json.loads(self._b._check(rc))
