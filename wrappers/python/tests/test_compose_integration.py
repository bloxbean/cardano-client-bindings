"""Integration tests for QuickTx compose (multi-Tx) with Yaci DevKit.

Requires:
- Yaci DevKit running on port 10000
- Native library built: ./gradlew :core:nativeCompile

Run with:
    PYTHONPATH=wrappers/python CCL_LIB_PATH=core/build/native/nativeCompile \
        pytest wrappers/python/tests/test_compose_integration.py -v
"""
import time
import pytest
from ccl._ffi import CclLib, CclError
from ccl.quicktx import Amount
from tests.devkit_helper import DevKitHelper


@pytest.fixture(scope="module")
def devkit():
    """Provide a DevKit helper, skip if DevKit is not running."""
    helper = DevKitHelper()
    if not helper.is_available():
        pytest.skip("Yaci DevKit is not running on port 10000")
    helper.reset()
    time.sleep(3)  # wait for devnet reset
    return helper


@pytest.fixture(scope="module")
def ccl_lib():
    """Create a shared CclLib instance."""
    lib = CclLib()
    yield lib
    lib.close()


def fund_account(ccl_lib, devkit, ada=150):
    """Create and fund a new account."""
    account = ccl_lib.account.create(CclLib.TESTNET)
    devkit.topup(account["base_address"], ada)
    devkit.wait_for_block(2)
    return account


def get_lovelace(devkit, address):
    """Sum all lovelace at an address."""
    utxos = devkit.get_utxos(address)
    return sum(
        int(a["quantity"])
        for u in utxos
        for a in u["amount"]
        if a["unit"] == "lovelace"
    )


def test_compose_two_senders(ccl_lib, devkit):
    """Compose two Txs from different senders, sign with both, submit, verify."""
    sender1 = fund_account(ccl_lib, devkit)
    sender2 = fund_account(ccl_lib, devkit)
    receiver1 = ccl_lib.account.create(CclLib.TESTNET)
    receiver2 = ccl_lib.account.create(CclLib.TESTNET)

    tx1 = ccl_lib.quicktx.tx() \
        .pay_to_address(receiver1["base_address"], Amount.ada(5)) \
        .from_address(sender1["base_address"])

    tx2 = ccl_lib.quicktx.tx() \
        .pay_to_address(receiver2["base_address"], Amount.ada(3)) \
        .from_address(sender2["base_address"])

    # Gather UTXOs for both senders
    utxos1 = devkit.get_utxos(sender1["base_address"])
    utxos2 = devkit.get_utxos(sender2["base_address"])
    all_utxos = utxos1 + utxos2
    pp = devkit.get_protocol_params()

    result = ccl_lib.quicktx.compose(tx1, tx2) \
        .fee_payer(sender1["base_address"]) \
        .with_utxos(all_utxos) \
        .with_protocol_params(pp) \
        .signer_count(2) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0

    # Sign with both senders
    signed = ccl_lib.account.sign_tx(
        sender1["mnemonic"], result["tx_cbor"],
        CclLib.TESTNET, 0, 0)
    signed = ccl_lib.account.sign_tx(
        sender2["mnemonic"], signed,
        CclLib.TESTNET, 0, 0)

    # Submit
    devkit.submit_tx(signed)
    devkit.wait_for_block(3)

    # Verify both receivers got their ADA
    assert get_lovelace(devkit, receiver1["base_address"]) == 5_000_000
    assert get_lovelace(devkit, receiver2["base_address"]) == 3_000_000


def test_compose_with_metadata(ccl_lib, devkit):
    """Compose two Txs where one has metadata attached."""
    sender1 = fund_account(ccl_lib, devkit)
    sender2 = fund_account(ccl_lib, devkit)
    receiver1 = ccl_lib.account.create(CclLib.TESTNET)
    receiver2 = ccl_lib.account.create(CclLib.TESTNET)

    tx1 = ccl_lib.quicktx.tx() \
        .pay_to_address(receiver1["base_address"], Amount.ada(5)) \
        .attach_metadata(674, {"msg": ["Compose integration test"]}) \
        .from_address(sender1["base_address"])

    tx2 = ccl_lib.quicktx.tx() \
        .pay_to_address(receiver2["base_address"], Amount.ada(3)) \
        .from_address(sender2["base_address"])

    utxos1 = devkit.get_utxos(sender1["base_address"])
    utxos2 = devkit.get_utxos(sender2["base_address"])
    pp = devkit.get_protocol_params()

    result = ccl_lib.quicktx.compose(tx1, tx2) \
        .fee_payer(sender1["base_address"]) \
        .with_utxos(utxos1 + utxos2) \
        .with_protocol_params(pp) \
        .signer_count(2) \
        .build()

    signed = ccl_lib.account.sign_tx(
        sender1["mnemonic"], result["tx_cbor"],
        CclLib.TESTNET, 0, 0)
    signed = ccl_lib.account.sign_tx(
        sender2["mnemonic"], signed,
        CclLib.TESTNET, 0, 0)

    devkit.submit_tx(signed)
    devkit.wait_for_block(3)

    # Verify tx on-chain
    tx_info = devkit.get_tx(result["tx_hash"])
    assert tx_info is not None
    assert get_lovelace(devkit, receiver1["base_address"]) == 5_000_000
    assert get_lovelace(devkit, receiver2["base_address"]) == 3_000_000


def test_compose_with_provider(ccl_lib, devkit):
    """Compose using provider for auto-fetching UTXOs and protocol params."""
    sender1 = fund_account(ccl_lib, devkit)
    sender2 = fund_account(ccl_lib, devkit)
    receiver1 = ccl_lib.account.create(CclLib.TESTNET)
    receiver2 = ccl_lib.account.create(CclLib.TESTNET)

    tx1 = ccl_lib.quicktx.tx() \
        .pay_to_address(receiver1["base_address"], Amount.ada(5)) \
        .from_address(sender1["base_address"])

    tx2 = ccl_lib.quicktx.tx() \
        .pay_to_address(receiver2["base_address"], Amount.ada(3)) \
        .from_address(sender2["base_address"])

    result = ccl_lib.quicktx.compose(tx1, tx2) \
        .fee_payer(sender1["base_address"]) \
        .signer_count(2) \
        .build(provider=devkit)

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64

    signed = ccl_lib.account.sign_tx(
        sender1["mnemonic"], result["tx_cbor"],
        CclLib.TESTNET, 0, 0)
    signed = ccl_lib.account.sign_tx(
        sender2["mnemonic"], signed,
        CclLib.TESTNET, 0, 0)

    devkit.submit_tx(signed)
    devkit.wait_for_block(3)

    assert get_lovelace(devkit, receiver1["base_address"]) == 5_000_000
    assert get_lovelace(devkit, receiver2["base_address"]) == 3_000_000
