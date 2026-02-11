from ccl._ffi import CclLib


def test_crypto_blake2b_256(ccl):
    # "Hello" in hex
    hash_result = ccl.crypto_blake2b_256("48656c6c6f")
    assert len(hash_result) == 64  # 32 bytes = 64 hex chars


def test_crypto_blake2b_224(ccl):
    hash_result = ccl.crypto_blake2b_224("48656c6c6f")
    assert len(hash_result) == 56  # 28 bytes = 56 hex chars


def test_crypto_generate_and_validate_mnemonic(ccl):
    mnemonic = ccl.crypto_generate_mnemonic(24)
    words = mnemonic.split()
    assert len(words) == 24
    assert ccl.crypto_validate_mnemonic(mnemonic) is True


def test_crypto_invalid_mnemonic(ccl):
    assert ccl.crypto_validate_mnemonic("not a valid mnemonic") is False


def test_version(ccl):
    version = ccl.version()
    assert version == "0.1.0"
