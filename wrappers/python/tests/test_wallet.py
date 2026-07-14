from ccl.network import Network


def test_wallet_create(ccl):
    wallet = ccl.wallet.create(Network.MAINNET)
    assert 'mnemonic' in wallet
    assert 'stake_address' in wallet
    assert 'addresses' in wallet
    words = wallet['mnemonic'].split()
    assert len(words) == 24


def test_wallet_from_mnemonic(ccl):
    wallet = ccl.wallet.create(Network.MAINNET)
    mnemonic = wallet['mnemonic']

    restored = ccl.wallet.from_mnemonic(mnemonic, Network.MAINNET)
    assert restored['stake_address'] == wallet['stake_address']


def test_wallet_get_address(ccl):
    wallet = ccl.wallet.create(Network.MAINNET)
    mnemonic = wallet['mnemonic']

    addr0 = ccl.wallet.get_address(mnemonic, Network.MAINNET, 0)
    assert addr0.startswith('addr1')

    addr1 = ccl.wallet.get_address(mnemonic, Network.MAINNET, 1)
    assert addr1.startswith('addr1')
    assert addr0 != addr1


def test_wallet_create_testnet(ccl):
    wallet = ccl.wallet.create(Network.TESTNET)
    assert wallet['stake_address'].startswith('stake_test1')
