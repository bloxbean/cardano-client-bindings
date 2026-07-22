from ccl.network import Network


def test_address_info(ccl):
    # Create an account to get a valid address
    created = ccl.account.create(Network.MAINNET)
    info = ccl.address.info(created['base_address'])

    assert info['type'] == 'Base'
    assert info['network_id'] == 1
    assert 'payment_credential_hash' in info


def test_address_to_and_from_bytes(ccl):
    created = ccl.account.create(Network.MAINNET)
    addr = created['base_address']

    hex_bytes = ccl.address.to_bytes(addr)
    assert len(hex_bytes) > 0

    restored = ccl.address.from_bytes(hex_bytes)
    assert restored == addr


def test_address_validate(ccl):
    created = ccl.account.create(Network.MAINNET)
    assert ccl.address.validate(created['base_address']) is True
    assert ccl.address.validate("invalid_address") is False


# --- Negative / Error Tests ---

def test_address_info_invalid(ccl):
    from ccl._ffi import CclError
    try:
        ccl.address.info("not_a_valid_address")
        assert False, "Should have raised CclError"
    except CclError:
        pass  # expected


def test_address_from_bytes_invalid(ccl):
    from ccl._ffi import CclError
    try:
        ccl.address.from_bytes("zzzz")
        assert False, "Should have raised CclError"
    except CclError:
        pass  # expected
