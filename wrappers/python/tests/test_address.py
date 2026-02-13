from ccl._ffi import CclLib


def test_address_info(ccl):
    # Create an account to get a valid address
    created = ccl.account.create(CclLib.MAINNET)
    info = ccl.address.info(created['base_address'])

    assert info['type'] == 'Base'
    assert info['network_id'] == 1
    assert 'payment_credential_hash' in info


def test_address_to_and_from_bytes(ccl):
    created = ccl.account.create(CclLib.MAINNET)
    addr = created['base_address']

    hex_bytes = ccl.address.to_bytes(addr)
    assert len(hex_bytes) > 0

    restored = ccl.address.from_bytes(hex_bytes)
    assert restored == addr


def test_address_validate(ccl):
    created = ccl.account.create(CclLib.MAINNET)
    assert ccl.address.validate(created['base_address']) is True
    assert ccl.address.validate("invalid_address") is False
