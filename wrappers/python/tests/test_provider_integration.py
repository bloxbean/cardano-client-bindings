"""Integration tests for Provider pattern with Yaci DevKit.

Requires:
- Yaci DevKit running on port 10000
- Native library built: ./gradlew :core:nativeCompile

Run with:
    PYTHONPATH=wrappers/python CCL_LIB_PATH=core/build/native/nativeCompile \
        pytest wrappers/python/tests/test_provider_integration.py -v
"""
import time
import pytest
from ccl._ffi import CclLib, CclError
from ccl.quicktx import Amount
from ccl.provider import YaciDevKitProvider


@pytest.fixture(scope="module")
def provider():
    """Provide a YaciDevKitProvider, skip if DevKit is not running."""
    p = YaciDevKitProvider()
    if not p.is_available():
        pytest.skip("Yaci DevKit is not running on port 10000")
    p.reset()
    time.sleep(3)  # wait for devnet reset
    return p


@pytest.fixture(scope="module")
def ccl_lib():
    """Create a shared CclLib instance."""
    lib = CclLib()
    yield lib
    lib.close()


@pytest.fixture
def funded_sender(ccl_lib, provider):
    """Create and fund a sender account."""
    account = ccl_lib.account.create(CclLib.TESTNET)
    provider.topup(account["base_address"], 150)
    provider.wait_for_block(2)
    return account


def test_build_with_provider(ccl_lib, provider, funded_sender):
    """Auto-fetch UTXOs + PP via provider, build, sign, submit, verify."""
    receiver = ccl_lib.account.create(CclLib.TESTNET)

    # Build with provider - no manual withUtxos/withProtocolParams needed
    result = ccl_lib.quicktx.new_tx() \
        .pay_to_address(receiver["base_address"], Amount.ada(5)) \
        .from_address(funded_sender["base_address"]) \
        .build(provider=provider)

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0

    # Sign
    signed_tx = ccl_lib.account.sign_tx(
        funded_sender["mnemonic"], result["tx_cbor"],
        CclLib.TESTNET, 0, 0)

    # Submit via provider
    tx_hash = provider.submit_tx(signed_tx)
    assert tx_hash is not None

    # Verify
    provider.wait_for_block(3)
    receiver_utxos = provider.get_utxos(receiver["base_address"])
    total_lovelace = sum(
        int(a["quantity"])
        for u in receiver_utxos
        for a in u["amount"]
        if a["unit"] == "lovelace"
    )
    assert total_lovelace == 5_000_000


def test_provider_with_manual_utxo_override(ccl_lib, provider, funded_sender):
    """withUtxos() should override provider auto-fetch."""
    receiver = ccl_lib.account.create(CclLib.TESTNET)

    # Manually fetch UTXOs
    utxos = provider.get_utxos(funded_sender["base_address"])

    # Build with provider but override UTXOs manually
    result = ccl_lib.quicktx.new_tx() \
        .pay_to_address(receiver["base_address"], Amount.ada(3)) \
        .from_address(funded_sender["base_address"]) \
        .with_utxos(utxos) \
        .build(provider=provider)

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64

    signed_tx = ccl_lib.account.sign_tx(
        funded_sender["mnemonic"], result["tx_cbor"],
        CclLib.TESTNET, 0, 0)

    tx_hash = provider.submit_tx(signed_tx)
    assert tx_hash is not None


def test_multiple_receivers_with_provider(ccl_lib, provider, funded_sender):
    """Send to two receivers using provider."""
    r1 = ccl_lib.account.create(CclLib.TESTNET)
    r2 = ccl_lib.account.create(CclLib.TESTNET)

    result = ccl_lib.quicktx.new_tx() \
        .pay_to_address(r1["base_address"], Amount.ada(3)) \
        .pay_to_address(r2["base_address"], Amount.ada(2)) \
        .from_address(funded_sender["base_address"]) \
        .build(provider=provider)

    signed_tx = ccl_lib.account.sign_tx(
        funded_sender["mnemonic"], result["tx_cbor"],
        CclLib.TESTNET, 0, 0)

    provider.submit_tx(signed_tx)
    provider.wait_for_block(3)

    r1_utxos = provider.get_utxos(r1["base_address"])
    r2_utxos = provider.get_utxos(r2["base_address"])

    r1_lovelace = sum(int(a["quantity"]) for u in r1_utxos for a in u["amount"] if a["unit"] == "lovelace")
    r2_lovelace = sum(int(a["quantity"]) for u in r2_utxos for a in u["amount"] if a["unit"] == "lovelace")

    assert r1_lovelace == 3_000_000
    assert r2_lovelace == 2_000_000


def test_metadata_with_provider(ccl_lib, provider, funded_sender):
    """Send ADA with metadata using provider."""
    receiver = ccl_lib.account.create(CclLib.TESTNET)

    result = ccl_lib.quicktx.new_tx() \
        .pay_to_address(receiver["base_address"], Amount.ada(2)) \
        .attach_metadata(674, {"msg": ["Hello from Provider"]}) \
        .from_address(funded_sender["base_address"]) \
        .build(provider=provider)

    signed_tx = ccl_lib.account.sign_tx(
        funded_sender["mnemonic"], result["tx_cbor"],
        CclLib.TESTNET, 0, 0)

    tx_hash = provider.submit_tx(signed_tx)
    assert tx_hash is not None

    provider.wait_for_block(3)
    receiver_utxos = provider.get_utxos(receiver["base_address"])
    total = sum(
        int(a["quantity"])
        for u in receiver_utxos
        for a in u["amount"]
        if a["unit"] == "lovelace"
    )
    assert total == 2_000_000
