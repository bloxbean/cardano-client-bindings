from ccl._ffi import CclLib


def test_account_create_mainnet(ccl):
    result = ccl.account.create(CclLib.MAINNET)
    assert 'mnemonic' in result
    assert 'base_address' in result
    assert result['base_address'].startswith('addr1')
    words = result['mnemonic'].split()
    assert len(words) == 24


def test_account_create_testnet(ccl):
    result = ccl.account.create(CclLib.TESTNET)
    assert result['base_address'].startswith('addr_test1')


def test_account_from_mnemonic(ccl):
    # Create an account first to get a valid mnemonic
    created = ccl.account.create(CclLib.MAINNET)
    mnemonic = created['mnemonic']

    # Restore from mnemonic should produce same addresses
    restored = ccl.account.from_mnemonic(mnemonic, CclLib.MAINNET)
    assert restored['base_address'] == created['base_address']
    assert restored['enterprise_address'] == created['enterprise_address']
    assert restored['stake_address'] == created['stake_address']


def test_account_different_indices(ccl):
    created = ccl.account.create(CclLib.MAINNET)
    mnemonic = created['mnemonic']

    addr0 = ccl.account.from_mnemonic(mnemonic, CclLib.MAINNET, 0, 0)
    addr1 = ccl.account.from_mnemonic(mnemonic, CclLib.MAINNET, 0, 1)
    assert addr0['base_address'] != addr1['base_address']


def test_account_get_keys(ccl):
    created = ccl.account.create(CclLib.MAINNET)
    mnemonic = created['mnemonic']

    private_key = ccl.account.get_private_key(mnemonic, CclLib.MAINNET)
    assert len(private_key) > 0

    public_key = ccl.account.get_public_key(mnemonic, CclLib.MAINNET)
    assert len(public_key) == 64  # 32 bytes = 64 hex chars


def test_account_drep_id(ccl):
    created = ccl.account.create(CclLib.MAINNET)
    mnemonic = created['mnemonic']

    drep_id = ccl.account.get_drep_id(mnemonic, CclLib.MAINNET)
    assert drep_id.startswith('drep1')


# --- Negative / Error Tests ---

def test_account_from_invalid_mnemonic(ccl):
    from ccl._ffi import CclError
    try:
        ccl.account.from_mnemonic("invalid words that are not a valid mnemonic phrase at all", CclLib.MAINNET)
        assert False, "Should have raised CclError"
    except CclError:
        pass  # expected


def test_account_from_empty_mnemonic(ccl):
    from ccl._ffi import CclError
    try:
        ccl.account.from_mnemonic("", CclLib.MAINNET)
        assert False, "Should have raised CclError"
    except CclError:
        pass  # expected


def test_account_sign_tx_invalid_cbor(ccl):
    from ccl._ffi import CclError
    created = ccl.account.create(CclLib.TESTNET)
    try:
        ccl.account.sign_tx(created['mnemonic'], "deadbeef", CclLib.TESTNET)
        assert False, "Should have raised CclError"
    except CclError:
        pass  # expected
