from ccl._ffi import CclLib


def test_address_info(ccl):
    # Create an account to get a valid address
    created = ccl.account_create(CclLib.MAINNET)
    info = ccl.address_info(created['base_address'])

    assert info['type'] == 'Base'
    assert info['network_id'] == 1
    assert 'payment_credential_hash' in info


def test_address_to_and_from_bytes(ccl):
    created = ccl.account_create(CclLib.MAINNET)
    addr = created['base_address']

    hex_bytes = ccl.address_to_bytes(addr)
    assert len(hex_bytes) > 0

    restored = ccl.address_from_bytes(hex_bytes)
    assert restored == addr


def test_address_validate(ccl):
    created = ccl.account_create(CclLib.MAINNET)
    assert ccl.address_validate(created['base_address']) is True
    assert ccl.address_validate("invalid_address") is False
