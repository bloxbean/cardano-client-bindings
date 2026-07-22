"""The Network enum, and the inversion that makes it a footgun.

`Network` values are CCL's *enum ordinals*. Cardano's *on-chain* network id is a different number,
and for mainnet/testnet the two are inverted:

    Network.MAINNET == 0  ->  address.info()["network_id"] == 1
    Network.TESTNET == 1  ->  address.info()["network_id"] == 0

This looks like a bug and is not one, so it gets pinned here: anyone who "fixes" the enum by
renumbering it to match the on-chain ids will derive keys on the wrong network, and these tests will
stop them. The parameter is named `network`, never `network_id`, for exactly this reason — while the
`network_id` *returned* by `address.info()` is the genuine on-chain id and must keep that name.
"""

import pytest

from ccl import Network


def test_members_are_ccl_ordinals():
    assert (Network.MAINNET, Network.TESTNET, Network.PREPROD, Network.PREVIEW) == (0, 1, 2, 3)


def test_mainnet_derives_an_address_whose_onchain_network_id_is_one(ccl):
    """Network.MAINNET is 0 — but the address it produces reports on-chain network_id 1."""
    account = ccl.account.create(Network.MAINNET)

    assert int(Network.MAINNET) == 0
    assert account["base_address"].startswith("addr1")
    assert ccl.address.info(account["base_address"])["network_id"] == 1


def test_testnet_derives_an_address_whose_onchain_network_id_is_zero(ccl):
    """Network.TESTNET is 1 — but the address it produces reports on-chain network_id 0."""
    account = ccl.account.create(Network.TESTNET)

    assert int(Network.TESTNET) == 1
    assert account["base_address"].startswith("addr_test1")
    assert ccl.address.info(account["base_address"])["network_id"] == 0


@pytest.mark.parametrize("network", [Network.PREPROD, Network.PREVIEW])
def test_preprod_and_preview_are_testnets_on_chain(ccl, network):
    account = ccl.account.create(network)

    assert ccl.address.info(account["base_address"])["network_id"] == 0


def test_plain_ints_still_work(ccl):
    """IntEnum keeps the native call wire-compatible: an int in 0-3 is still accepted."""
    from_enum = ccl.account.create(Network.TESTNET)
    from_int = ccl.account.from_mnemonic(from_enum["mnemonic"], 1)

    assert from_int["base_address"] == from_enum["base_address"]


@pytest.mark.parametrize("bad", [4, -1, 99])
def test_out_of_range_network_raises_valueerror(ccl, bad):
    """Caught at the boundary, not deep inside the native library."""
    with pytest.raises(ValueError, match="Network"):
        ccl.account.create(bad)


def test_network_is_required_and_never_defaults_to_mainnet(ccl):
    """No default. `lib.account.create()` used to silently mint a *mainnet* account."""
    with pytest.raises(TypeError):
        ccl.account.create()

    with pytest.raises(TypeError):
        ccl.wallet.create()

    mnemonic = ccl.crypto.generate_mnemonic(24)
    with pytest.raises(TypeError):
        ccl.account.get_private_key(mnemonic)

    with pytest.raises(TypeError):
        ccl.gov.drep_key_from_mnemonic(mnemonic)
