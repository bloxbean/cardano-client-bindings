"""Integration tests for QuickTx with Yaci DevKit.

Requires:
- Yaci DevKit running on port 10000
- Native library built: ./gradlew :core:nativeCompile

Run with:
    PYTHONPATH=wrappers/python CCL_LIB_PATH=core/build/native/nativeCompile \
        pytest wrappers/python/tests/test_quicktx_integration.py -v
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


@pytest.fixture
def funded_sender(ccl_lib, devkit):
    """Create and fund a sender account."""
    account = ccl_lib.account.create(CclLib.TESTNET)
    devkit.topup(account["base_address"], 150)
    devkit.wait_for_block(2)
    return account


def test_simple_ada_transfer(ccl_lib, devkit, funded_sender):
    """Send 5 ADA to a new address and verify on-chain."""
    receiver = ccl_lib.account.create(CclLib.TESTNET)

    # Fetch UTXOs and protocol params from DevKit
    utxos = devkit.get_utxos(funded_sender["base_address"])
    pp = devkit.get_protocol_params()

    # Build transaction
    result = ccl_lib.quicktx.new_tx() \
        .pay_to_address(receiver["base_address"], Amount.ada(5)) \
        .from_address(funded_sender["base_address"]) \
        .with_utxos(utxos) \
        .with_protocol_params(pp) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0

    # Sign
    signed_tx = ccl_lib.account.sign_tx(
        funded_sender["mnemonic"], result["tx_cbor"],
        CclLib.TESTNET, 0, 0)

    # Submit
    tx_hash = devkit.submit_tx(signed_tx)
    assert tx_hash is not None

    # Wait and verify
    devkit.wait_for_block(3)
    receiver_utxos = devkit.get_utxos(receiver["base_address"])
    total_lovelace = sum(
        int(a["quantity"])
        for u in receiver_utxos
        for a in u["amount"]
        if a["unit"] == "lovelace"
    )
    assert total_lovelace == 5_000_000


def test_multiple_receivers(ccl_lib, devkit, funded_sender):
    """Send to two receivers in one transaction."""
    r1 = ccl_lib.account.create(CclLib.TESTNET)
    r2 = ccl_lib.account.create(CclLib.TESTNET)

    utxos = devkit.get_utxos(funded_sender["base_address"])
    pp = devkit.get_protocol_params()

    result = ccl_lib.quicktx.new_tx() \
        .pay_to_address(r1["base_address"], Amount.ada(3)) \
        .pay_to_address(r2["base_address"], Amount.ada(2)) \
        .from_address(funded_sender["base_address"]) \
        .with_utxos(utxos) \
        .with_protocol_params(pp) \
        .build()

    signed_tx = ccl_lib.account.sign_tx(
        funded_sender["mnemonic"], result["tx_cbor"],
        CclLib.TESTNET, 0, 0)

    devkit.submit_tx(signed_tx)
    devkit.wait_for_block(3)

    r1_utxos = devkit.get_utxos(r1["base_address"])
    r2_utxos = devkit.get_utxos(r2["base_address"])

    r1_lovelace = sum(int(a["quantity"]) for u in r1_utxos for a in u["amount"] if a["unit"] == "lovelace")
    r2_lovelace = sum(int(a["quantity"]) for u in r2_utxos for a in u["amount"] if a["unit"] == "lovelace")

    assert r1_lovelace == 3_000_000
    assert r2_lovelace == 2_000_000


def test_metadata_transfer(ccl_lib, devkit, funded_sender):
    """Send ADA with CIP-20 metadata and verify."""
    receiver = ccl_lib.account.create(CclLib.TESTNET)

    utxos = devkit.get_utxos(funded_sender["base_address"])
    pp = devkit.get_protocol_params()

    result = ccl_lib.quicktx.new_tx() \
        .pay_to_address(receiver["base_address"], Amount.ada(2)) \
        .attach_metadata(674, {"msg": ["Hello from CCL Bridge"]}) \
        .from_address(funded_sender["base_address"]) \
        .with_utxos(utxos) \
        .with_protocol_params(pp) \
        .build()

    signed_tx = ccl_lib.account.sign_tx(
        funded_sender["mnemonic"], result["tx_cbor"],
        CclLib.TESTNET, 0, 0)

    tx_hash = devkit.submit_tx(signed_tx)
    devkit.wait_for_block(3)

    # Verify tx exists on-chain
    tx_info = devkit.get_tx(result["tx_hash"])
    assert tx_info is not None


def test_insufficient_funds_error(ccl_lib, devkit):
    """Should fail when UTXOs don't have enough funds."""
    sender = ccl_lib.account.create(CclLib.TESTNET)
    receiver = ccl_lib.account.create(CclLib.TESTNET)

    # Fund with only 2 ADA
    devkit.topup(sender["base_address"], 2)
    devkit.wait_for_block(2)

    utxos = devkit.get_utxos(sender["base_address"])
    pp = devkit.get_protocol_params()

    with pytest.raises(CclError):
        ccl_lib.quicktx.new_tx() \
            .pay_to_address(receiver["base_address"], Amount.ada(100)) \
            .from_address(sender["base_address"]) \
            .with_utxos(utxos) \
            .with_protocol_params(pp) \
            .build()


def test_round_trip_sign_submit(ccl_lib, devkit, funded_sender):
    """Full round trip: build -> sign -> submit -> confirm -> check balance."""
    receiver = ccl_lib.account.create(CclLib.TESTNET)

    utxos = devkit.get_utxos(funded_sender["base_address"])
    pp = devkit.get_protocol_params()

    # Build
    result = ccl_lib.quicktx.new_tx() \
        .pay_to_address(receiver["base_address"], Amount.ada(10)) \
        .from_address(funded_sender["base_address"]) \
        .with_utxos(utxos) \
        .with_protocol_params(pp) \
        .build()

    # Sign
    signed_tx = ccl_lib.account.sign_tx(
        funded_sender["mnemonic"], result["tx_cbor"],
        CclLib.TESTNET, 0, 0)

    # Submit
    tx_hash = devkit.submit_tx(signed_tx)
    devkit.wait_for_block(3)

    # Confirm on-chain
    tx_info = devkit.get_tx(result["tx_hash"])
    assert tx_info is not None

    # Check receiver balance
    receiver_utxos = devkit.get_utxos(receiver["base_address"])
    total = sum(
        int(a["quantity"])
        for u in receiver_utxos
        for a in u["amount"]
        if a["unit"] == "lovelace"
    )
    assert total == 10_000_000


# --- Provider Config (server-side lazy UTXO fetching) tests ---

DEVKIT_PROVIDER_URL = "http://localhost:10000/local-cluster/api"


def test_provider_config_simple_transfer(ccl_lib, devkit, funded_sender):
    """Build with provider_config — Java fetches UTXOs lazily via HTTP."""
    receiver = ccl_lib.account.create(CclLib.TESTNET)

    result = ccl_lib.quicktx.new_tx() \
        .pay_to_address(receiver["base_address"], Amount.ada(5)) \
        .from_address(funded_sender["base_address"]) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0

    # Sign and submit
    signed_tx = ccl_lib.account.sign_tx(
        funded_sender["mnemonic"], result["tx_cbor"],
        CclLib.TESTNET, 0, 0)
    tx_hash = devkit.submit_tx(signed_tx)
    assert tx_hash is not None

    devkit.wait_for_block(3)
    receiver_utxos = devkit.get_utxos(receiver["base_address"])
    total = sum(
        int(a["quantity"])
        for u in receiver_utxos
        for a in u["amount"]
        if a["unit"] == "lovelace"
    )
    assert total == 5_000_000


def test_provider_config_multiple_receivers(ccl_lib, devkit, funded_sender):
    """Build with provider_config and multiple payment outputs."""
    r1 = ccl_lib.account.create(CclLib.TESTNET)
    r2 = ccl_lib.account.create(CclLib.TESTNET)

    result = ccl_lib.quicktx.new_tx() \
        .pay_to_address(r1["base_address"], Amount.ada(3)) \
        .pay_to_address(r2["base_address"], Amount.ada(2)) \
        .from_address(funded_sender["base_address"]) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    signed_tx = ccl_lib.account.sign_tx(
        funded_sender["mnemonic"], result["tx_cbor"],
        CclLib.TESTNET, 0, 0)
    devkit.submit_tx(signed_tx)
    devkit.wait_for_block(3)

    r1_utxos = devkit.get_utxos(r1["base_address"])
    r2_utxos = devkit.get_utxos(r2["base_address"])

    r1_lovelace = sum(int(a["quantity"]) for u in r1_utxos for a in u["amount"] if a["unit"] == "lovelace")
    r2_lovelace = sum(int(a["quantity"]) for u in r2_utxos for a in u["amount"] if a["unit"] == "lovelace")

    assert r1_lovelace == 3_000_000
    assert r2_lovelace == 2_000_000


def test_provider_config_with_metadata(ccl_lib, devkit, funded_sender):
    """Build with provider_config and metadata attachment."""
    receiver = ccl_lib.account.create(CclLib.TESTNET)

    result = ccl_lib.quicktx.new_tx() \
        .pay_to_address(receiver["base_address"], Amount.ada(2)) \
        .attach_metadata(674, {"msg": ["Hello from providerConfig"]}) \
        .from_address(funded_sender["base_address"]) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    signed_tx = ccl_lib.account.sign_tx(
        funded_sender["mnemonic"], result["tx_cbor"],
        CclLib.TESTNET, 0, 0)
    tx_hash = devkit.submit_tx(signed_tx)
    assert tx_hash is not None

    devkit.wait_for_block(3)
    tx_info = devkit.get_tx(result["tx_hash"])
    assert tx_info is not None
