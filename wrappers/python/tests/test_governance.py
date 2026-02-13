from ccl._ffi import CclLib


def test_gov_drep_key(ccl):
    created = ccl.account.create(CclLib.MAINNET)
    mnemonic = created['mnemonic']

    result = ccl.gov.drep_key_from_mnemonic(mnemonic, CclLib.MAINNET)
    assert 'drep_id' in result
    assert 'verification_key' in result
    assert 'verification_key_hash' in result
    assert result['drep_id'].startswith('drep1')


def test_gov_committee_cold_key(ccl):
    created = ccl.account.create(CclLib.MAINNET)
    mnemonic = created['mnemonic']

    result = ccl.gov.committee_cold_key_from_mnemonic(mnemonic, CclLib.MAINNET)
    assert 'id' in result
    assert 'verification_key' in result
    assert 'verification_key_hash' in result
    assert result['id'].startswith('cc_cold1')


def test_gov_committee_hot_key(ccl):
    created = ccl.account.create(CclLib.MAINNET)
    mnemonic = created['mnemonic']

    result = ccl.gov.committee_hot_key_from_mnemonic(mnemonic, CclLib.MAINNET)
    assert 'id' in result
    assert 'verification_key' in result
    assert 'verification_key_hash' in result
    assert result['id'].startswith('cc_hot1')
