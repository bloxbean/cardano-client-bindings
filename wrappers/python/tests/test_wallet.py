from ccl._ffi import CclLib


def test_wallet_create(ccl):
    wallet = ccl.wallet.create(CclLib.MAINNET)
    assert 'mnemonic' in wallet
    assert 'stake_address' in wallet
    assert 'addresses' in wallet
    words = wallet['mnemonic'].split()
    assert len(words) == 24


def test_wallet_from_mnemonic(ccl):
    wallet = ccl.wallet.create(CclLib.MAINNET)
    mnemonic = wallet['mnemonic']

    restored = ccl.wallet.from_mnemonic(mnemonic, CclLib.MAINNET)
    assert restored['stake_address'] == wallet['stake_address']


def test_wallet_get_address(ccl):
    wallet = ccl.wallet.create(CclLib.MAINNET)
    mnemonic = wallet['mnemonic']

    addr0 = ccl.wallet.get_address(mnemonic, CclLib.MAINNET, 0)
    assert addr0.startswith('addr1')

    addr1 = ccl.wallet.get_address(mnemonic, CclLib.MAINNET, 1)
    assert addr1.startswith('addr1')
    assert addr0 != addr1


def test_wallet_create_testnet(ccl):
    wallet = ccl.wallet.create(CclLib.TESTNET)
    assert wallet['stake_address'].startswith('stake_test1')
