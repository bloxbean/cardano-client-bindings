from ccl._ffi import CclLib


def test_account_create_mainnet(ccl):
    result = ccl.account_create(CclLib.MAINNET)
    assert 'mnemonic' in result
    assert 'base_address' in result
    assert result['base_address'].startswith('addr1')
    words = result['mnemonic'].split()
    assert len(words) == 24


def test_account_create_testnet(ccl):
    result = ccl.account_create(CclLib.TESTNET)
    assert result['base_address'].startswith('addr_test1')


def test_account_from_mnemonic(ccl):
    # Create an account first to get a valid mnemonic
    created = ccl.account_create(CclLib.MAINNET)
    mnemonic = created['mnemonic']

    # Restore from mnemonic should produce same addresses
    restored = ccl.account_from_mnemonic(mnemonic, CclLib.MAINNET)
    assert restored['base_address'] == created['base_address']
    assert restored['enterprise_address'] == created['enterprise_address']
    assert restored['stake_address'] == created['stake_address']


def test_account_different_indices(ccl):
    created = ccl.account_create(CclLib.MAINNET)
    mnemonic = created['mnemonic']

    addr0 = ccl.account_from_mnemonic(mnemonic, CclLib.MAINNET, 0, 0)
    addr1 = ccl.account_from_mnemonic(mnemonic, CclLib.MAINNET, 0, 1)
    assert addr0['base_address'] != addr1['base_address']


def test_account_get_keys(ccl):
    created = ccl.account_create(CclLib.MAINNET)
    mnemonic = created['mnemonic']

    private_key = ccl.account_get_private_key(mnemonic, CclLib.MAINNET)
    assert len(private_key) > 0

    public_key = ccl.account_get_public_key(mnemonic, CclLib.MAINNET)
    assert len(public_key) == 64  # 32 bytes = 64 hex chars


def test_account_drep_id(ccl):
    created = ccl.account_create(CclLib.MAINNET)
    mnemonic = created['mnemonic']

    drep_id = ccl.account_get_drep_id(mnemonic, CclLib.MAINNET)
    assert drep_id.startswith('drep1')
