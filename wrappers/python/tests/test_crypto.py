from ccl.network import Network


def test_crypto_blake2b_256(ccl):
    hash_result = ccl.crypto.blake2b_256("48656c6c6f")
    assert len(hash_result) == 64  # 32 bytes = 64 hex chars


def test_crypto_blake2b_224(ccl):
    hash_result = ccl.crypto.blake2b_224("48656c6c6f")
    assert len(hash_result) == 56  # 28 bytes = 56 hex chars


def test_crypto_generate_and_validate_mnemonic(ccl):
    mnemonic = ccl.crypto.generate_mnemonic(24)
    words = mnemonic.split()
    assert len(words) == 24
    assert ccl.crypto.validate_mnemonic(mnemonic) is True


def test_crypto_generate_12_word_mnemonic(ccl):
    mnemonic = ccl.crypto.generate_mnemonic(12)
    words = mnemonic.split()
    assert len(words) == 12


def test_crypto_invalid_mnemonic(ccl):
    assert ccl.crypto.validate_mnemonic("not a valid mnemonic") is False


def test_crypto_sign(ccl):
    # derive_key returns a 64-byte extended BIP32-Ed25519 key (128 hex chars);
    # ccl_crypto_sign expects the standard 32-byte Ed25519 key (64 hex chars)
    mnemonic = ccl.crypto.generate_mnemonic(24)
    private_key_extended = ccl.crypto.derive_key(mnemonic)['private_key']
    private_key = private_key_extended[:64]  # first 32 bytes

    message_hex = "68656c6c6f"
    signature = ccl.crypto.sign(message_hex, private_key)
    assert len(signature) == 128  # 64 bytes = 128 hex chars


def test_crypto_verify_rejects_wrong_signature(ccl):
    mnemonic = ccl.crypto.generate_mnemonic(24)
    public_key = ccl.crypto.derive_key(mnemonic)['public_key']

    # A fake signature should fail verification
    fake_sig = "00" * 64
    assert ccl.crypto.verify(fake_sig, "68656c6c6f", public_key) is False


def test_version(ccl):
    version = ccl.version()
    assert version == "0.1.0"


# --- Negative / Error Tests ---

def test_crypto_blake2b_256_invalid_hex(ccl):
    from ccl._ffi import CclError
    try:
        ccl.crypto.blake2b_256("not_valid_hex!")
        assert False, "Should have raised CclError"
    except CclError:
        pass  # expected


def test_crypto_sign_invalid_key(ccl):
    from ccl._ffi import CclError
    try:
        ccl.crypto.sign("68656c6c6f", "zz" * 32)
        assert False, "Should have raised CclError"
    except CclError:
        pass  # expected
