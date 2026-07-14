from ccl.network import Network


def test_gov_drep_key(ccl):
    created = ccl.account.create(Network.MAINNET)
    mnemonic = created['mnemonic']

    result = ccl.gov.drep_key_from_mnemonic(mnemonic, Network.MAINNET)
    assert 'drep_id' in result
    assert 'verification_key' in result
    assert 'verification_key_hash' in result
    assert result['drep_id'].startswith('drep1')


def test_gov_committee_cold_key(ccl):
    created = ccl.account.create(Network.MAINNET)
    mnemonic = created['mnemonic']

    result = ccl.gov.committee_cold_key_from_mnemonic(mnemonic, Network.MAINNET)
    assert 'id' in result
    assert 'verification_key' in result
    assert 'verification_key_hash' in result
    assert result['id'].startswith('cc_cold1')


def test_gov_committee_hot_key(ccl):
    created = ccl.account.create(Network.MAINNET)
    mnemonic = created['mnemonic']

    result = ccl.gov.committee_hot_key_from_mnemonic(mnemonic, Network.MAINNET)
    assert 'id' in result
    assert 'verification_key' in result
    assert 'verification_key_hash' in result
    assert result['id'].startswith('cc_hot1')
