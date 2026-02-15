"""Integration tests for new QuickTx features with Yaci DevKit.

Tests reference scripts, governance action types, pool ops, treasury donation,
native script attachment, and unregisterDRep refundAmount.

Requires:
- Yaci DevKit running on port 10000
- Native library built: ./gradlew :core:nativeCompile

Run with:
    PYTHONPATH=wrappers/python CCL_LIB_PATH=core/build/native/nativeCompile \
        pytest wrappers/python/tests/test_new_features_integration.py -v
"""
import time
import json
import pytest
from ccl._ffi import CclLib, CclError
from ccl.quicktx import Amount
from tests.devkit_helper import DevKitHelper

ANCHOR_URL = "https://bit.ly/3zCH2HL"
ANCHOR_DATA_HASH = "cafef700c0039a2efb056a665b3a8bcd94f8670b88d659f7f3db68340f6f0937"
ALWAYS_TRUE_PLUTUS_V3 = "46450101002499"

DEVKIT_PROVIDER_URL = "http://localhost:10000/local-cluster/api"


@pytest.fixture(scope="module")
def devkit():
    """Provide a DevKit helper, skip if DevKit is not running."""
    helper = DevKitHelper()
    if not helper.is_available():
        pytest.skip("Yaci DevKit is not running on port 10000")
    helper.reset()
    time.sleep(3)
    return helper


@pytest.fixture(scope="module")
def ccl_lib():
    """Create a shared CclLib instance."""
    lib = CclLib()
    yield lib
    lib.close()


def fund_account(ccl_lib, devkit, ada=500):
    """Create and fund a new account."""
    account = ccl_lib.account.create(CclLib.TESTNET)
    devkit.topup(account["base_address"], ada)
    devkit.wait_for_block(2)
    return account


def build_sign_submit(ccl_lib, devkit, account, result):
    """Sign and submit a built transaction."""
    signed_tx = ccl_lib.account.sign_tx(
        account["mnemonic"], result["tx_cbor"],
        CclLib.TESTNET, 0, 0)
    tx_hash = devkit.submit_tx(signed_tx)
    assert tx_hash is not None
    return tx_hash


def register_stake(ccl_lib, devkit, account):
    """Register the stake address for an account. Returns the tx result."""
    result = ccl_lib.quicktx.new_tx() \
        .register_stake_address(account["stake_address"]) \
        .from_address(account["base_address"]) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})
    build_sign_submit(ccl_lib, devkit, account, result)
    devkit.wait_for_block(3)
    return result


# --- Full E2E tests (payment key signing only) ---


def test_pay_to_address_with_reference_script(ccl_lib, devkit):
    """Send ADA to address with a PlutusV3 reference script attached."""
    sender = fund_account(ccl_lib, devkit, 150)
    receiver = ccl_lib.account.create(CclLib.TESTNET)

    result = ccl_lib.quicktx.new_tx() \
        .pay_to_address(
            receiver["base_address"], Amount.ada(10),
            script_ref_cbor_hex=ALWAYS_TRUE_PLUTUS_V3,
            script_ref_type="plutus_v3") \
        .from_address(sender["base_address"]) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0

    build_sign_submit(ccl_lib, devkit, sender, result)
    devkit.wait_for_block(3)

    receiver_utxos = devkit.get_utxos(receiver["base_address"])
    total_lovelace = sum(
        int(a["quantity"])
        for u in receiver_utxos
        for a in u["amount"]
        if a["unit"] == "lovelace"
    )
    assert total_lovelace >= 10_000_000


def test_attach_native_script(ccl_lib, devkit):
    """Build a tx with attachNativeScript and submit."""
    sender = fund_account(ccl_lib, devkit, 150)
    receiver = ccl_lib.account.create(CclLib.TESTNET)

    # Get the payment key hash from the sender address
    addr_info = ccl_lib.address.info(sender["base_address"])
    key_hash = addr_info["payment_credential_hash"]

    native_script = {"type": "sig", "keyHash": key_hash}

    result = ccl_lib.quicktx.new_tx() \
        .pay_to_address(receiver["base_address"], Amount.ada(5)) \
        .attach_native_script(native_script) \
        .from_address(sender["base_address"]) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64

    build_sign_submit(ccl_lib, devkit, sender, result)
    devkit.wait_for_block(3)

    tx_info = devkit.get_tx(result["tx_hash"])
    assert tx_info is not None


def test_register_stake_address(ccl_lib, devkit):
    """Register sender's stake address and submit."""
    sender = fund_account(ccl_lib, devkit)

    result = ccl_lib.quicktx.new_tx() \
        .register_stake_address(sender["stake_address"]) \
        .from_address(sender["base_address"]) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    assert len(result["tx_cbor"]) > 0
    assert int(result["fee"]) > 0

    build_sign_submit(ccl_lib, devkit, sender, result)
    devkit.wait_for_block(3)

    tx_info = devkit.get_tx(result["tx_hash"])
    assert tx_info is not None


def test_delegate_voting_power_to_always_abstain(ccl_lib, devkit):
    """Register stake address, then delegate voting power to always_abstain."""
    sender = fund_account(ccl_lib, devkit)

    # Step 1: Register stake address
    register_stake(ccl_lib, devkit, sender)

    # Step 2: Delegate voting power to always_abstain
    result = ccl_lib.quicktx.new_tx() \
        .delegate_voting_power_to(sender["stake_address"], "abstain") \
        .from_address(sender["base_address"]) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    assert len(result["tx_cbor"]) > 0

    build_sign_submit(ccl_lib, devkit, sender, result)
    devkit.wait_for_block(3)

    tx_info = devkit.get_tx(result["tx_hash"])
    assert tx_info is not None


def test_create_proposal_info_action(ccl_lib, devkit):
    """Create an info_action governance proposal and submit."""
    sender = fund_account(ccl_lib, devkit)
    register_stake(ccl_lib, devkit, sender)

    result = ccl_lib.quicktx.new_tx() \
        .create_proposal(
            "info_action",
            sender["stake_address"],
            ANCHOR_URL,
            ANCHOR_DATA_HASH) \
        .from_address(sender["base_address"]) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    assert len(result["tx_cbor"]) > 0
    assert int(result["fee"]) > 0

    build_sign_submit(ccl_lib, devkit, sender, result)
    devkit.wait_for_block(3)

    tx_info = devkit.get_tx(result["tx_hash"])
    assert tx_info is not None


def test_create_proposal_no_confidence(ccl_lib, devkit):
    """Create a no_confidence governance proposal and submit."""
    sender = fund_account(ccl_lib, devkit)
    register_stake(ccl_lib, devkit, sender)

    result = ccl_lib.quicktx.new_tx() \
        .create_proposal(
            "no_confidence",
            sender["stake_address"],
            ANCHOR_URL,
            ANCHOR_DATA_HASH) \
        .from_address(sender["base_address"]) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    assert len(result["tx_cbor"]) > 0

    build_sign_submit(ccl_lib, devkit, sender, result)
    devkit.wait_for_block(3)

    tx_info = devkit.get_tx(result["tx_hash"])
    assert tx_info is not None


def test_create_proposal_new_constitution(ccl_lib, devkit):
    """Create a new_constitution governance proposal and submit."""
    sender = fund_account(ccl_lib, devkit)
    register_stake(ccl_lib, devkit, sender)

    result = ccl_lib.quicktx.new_tx() \
        .create_proposal(
            "new_constitution",
            sender["stake_address"],
            ANCHOR_URL,
            ANCHOR_DATA_HASH,
            constitution_anchor_url=ANCHOR_URL,
            constitution_anchor_data_hash=ANCHOR_DATA_HASH) \
        .from_address(sender["base_address"]) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    assert len(result["tx_cbor"]) > 0

    build_sign_submit(ccl_lib, devkit, sender, result)
    devkit.wait_for_block(3)

    tx_info = devkit.get_tx(result["tx_hash"])
    assert tx_info is not None


def test_create_proposal_update_committee(ccl_lib, devkit):
    """Create an update_committee governance proposal and submit."""
    sender = fund_account(ccl_lib, devkit)
    register_stake(ccl_lib, devkit, sender)

    # Use a deterministic hash for committee member
    member_hash = "a" * 56

    result = ccl_lib.quicktx.new_tx() \
        .create_proposal(
            "update_committee",
            sender["stake_address"],
            ANCHOR_URL,
            ANCHOR_DATA_HASH,
            new_members=[{"hash": member_hash, "type": "key", "epoch": 100}],
            quorum_numerator=2,
            quorum_denominator=3) \
        .from_address(sender["base_address"]) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    assert len(result["tx_cbor"]) > 0

    build_sign_submit(ccl_lib, devkit, sender, result)
    devkit.wait_for_block(3)

    tx_info = devkit.get_tx(result["tx_hash"])
    assert tx_info is not None


def test_create_proposal_hard_fork_initiation(ccl_lib, devkit):
    """Create a hard_fork_initiation governance proposal and submit."""
    sender = fund_account(ccl_lib, devkit)
    register_stake(ccl_lib, devkit, sender)

    result = ccl_lib.quicktx.new_tx() \
        .create_proposal(
            "hard_fork_initiation",
            sender["stake_address"],
            ANCHOR_URL,
            ANCHOR_DATA_HASH,
            protocol_version_major=10,
            protocol_version_minor=0) \
        .from_address(sender["base_address"]) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    assert len(result["tx_cbor"]) > 0

    build_sign_submit(ccl_lib, devkit, sender, result)
    devkit.wait_for_block(3)

    tx_info = devkit.get_tx(result["tx_hash"])
    assert tx_info is not None


# --- Build-only tests (need additional key signatures) ---


def test_register_drep_build_only(ccl_lib, devkit):
    """Build a registerDRep tx and verify structure (no submit — needs DRep key)."""
    sender = fund_account(ccl_lib, devkit)

    drep_key = ccl_lib.gov.drep_key_from_mnemonic(
        sender["mnemonic"], CclLib.TESTNET, 0)
    credential_hash = drep_key["verification_key_hash"]

    result = ccl_lib.quicktx.new_tx() \
        .register_drep(
            credential_hash, "key",
            anchor_url=ANCHOR_URL,
            anchor_data_hash=ANCHOR_DATA_HASH) \
        .from_address(sender["base_address"]) \
        .signer_count(2) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0


def test_unregister_drep_with_refund_build_only(ccl_lib, devkit):
    """Build an unregisterDRep tx with refundAmount (no submit — needs DRep key)."""
    sender = fund_account(ccl_lib, devkit)

    drep_key = ccl_lib.gov.drep_key_from_mnemonic(
        sender["mnemonic"], CclLib.TESTNET, 0)
    credential_hash = drep_key["verification_key_hash"]

    result = ccl_lib.quicktx.new_tx() \
        .unregister_drep(
            credential_hash, "key",
            refund_address=sender["base_address"],
            refund_amount=500_000_000) \
        .from_address(sender["base_address"]) \
        .signer_count(2) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0


def test_register_pool_build_only(ccl_lib, devkit):
    """Build a registerPool tx and verify structure (no submit — needs pool operator key)."""
    sender = fund_account(ccl_lib, devkit)

    # Deterministic hashes for pool operator and VRF
    operator_hash = "ab" * 14  # 28-byte hex
    vrf_key_hash = "cd" * 16  # 32-byte hex

    result = ccl_lib.quicktx.new_tx() \
        .register_pool(
            operator=operator_hash,
            vrf_key_hash=vrf_key_hash,
            pledge=500_000_000,
            cost=340_000_000,
            margin_numerator=1,
            margin_denominator=100,
            reward_address=sender["stake_address"],
            pool_owners=[operator_hash]) \
        .from_address(sender["base_address"]) \
        .signer_count(2) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0


def test_donate_to_treasury_build_only(ccl_lib, devkit):
    """Build a donateToTreasury tx and verify structure."""
    sender = fund_account(ccl_lib, devkit)

    result = ccl_lib.quicktx.new_tx() \
        .donate_to_treasury(
            treasury_value=0,
            donation_amount=5_000_000) \
        .from_address(sender["base_address"]) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0


def test_create_vote_build_only(ccl_lib, devkit):
    """Build a createVote tx (no submit — needs DRep key)."""
    sender = fund_account(ccl_lib, devkit)

    drep_key = ccl_lib.gov.drep_key_from_mnemonic(
        sender["mnemonic"], CclLib.TESTNET, 0)
    credential_hash = drep_key["verification_key_hash"]

    # Use a fake governance action tx hash
    fake_gov_tx_hash = "ab" * 32

    result = ccl_lib.quicktx.new_tx() \
        .create_vote(
            voter_type="drep_key_hash",
            voter_hash=credential_hash,
            gov_action_tx_hash=fake_gov_tx_hash,
            gov_action_index=0,
            vote="yes",
            anchor_url=ANCHOR_URL,
            anchor_data_hash=ANCHOR_DATA_HASH) \
        .from_address(sender["base_address"]) \
        .signer_count(2) \
        .build(provider_config={"name": "yaci", "url": DEVKIT_PROVIDER_URL})

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0
