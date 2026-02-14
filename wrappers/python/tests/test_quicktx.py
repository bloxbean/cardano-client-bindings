import json
from ccl._ffi import CclLib, CclError
from ccl.quicktx import Amount

# Protocol params matching CCL's test resource (Blockfrost/Koios format)
PROTOCOL_PARAMS = {
    "min_fee_a": 44,
    "min_fee_b": 155381,
    "max_block_size": 65536,
    "max_tx_size": 16384,
    "max_block_header_size": 1100,
    "key_deposit": "2000000",
    "pool_deposit": "500000000",
    "e_max": 18,
    "n_opt": 500,
    "a0": 0.3,
    "rho": 0.003,
    "tau": 0.2,
    "min_utxo": "34482",
    "min_pool_cost": "340000000",
    "price_mem": 0.0577,
    "price_step": 0.0000721,
    "max_tx_ex_mem": "10000000",
    "max_tx_ex_steps": "10000000000",
    "max_block_ex_mem": "50000000",
    "max_block_ex_steps": "40000000000",
    "max_val_size": "5000",
    "collateral_percent": 150,
    "max_collateral_inputs": 3,
    "coins_per_utxo_size": "4310",
    "coins_per_utxo_word": "34482",
    "pvt_motion_no_confidence": 0.51,
    "pvt_committee_normal": 0.51,
    "pvt_committee_no_confidence": 0.51,
    "pvt_hard_fork_initiation": 0.51,
    "dvt_motion_no_confidence": 0.51,
    "dvt_committee_normal": 0.51,
    "dvt_committee_no_confidence": 0.51,
    "dvt_update_to_constitution": 0.51,
    "dvt_hard_fork_initiation": 0.51,
    "dvt_ppnetwork_group": 0.51,
    "dvt_ppeconomic_group": 0.51,
    "dvt_pptechnical_group": 0.51,
    "dvt_ppgov_group": 0.51,
    "dvt_treasury_withdrawal": 0.51,
    "committee_min_size": 0,
    "committee_max_term_length": 200,
    "gov_action_lifetime": 10,
    "gov_action_deposit": 1000000000,
    "drep_deposit": 2000000,
    "drep_activity": 20,
    "min_fee_ref_script_cost_per_byte": 44,
}

FAKE_TX_HASH = "a" * 64


def _make_utxos(address, lovelace=100_000_000):
    """Create a simple UTXO list for testing."""
    return [{
        "tx_hash": FAKE_TX_HASH,
        "output_index": 0,
        "address": address,
        "amount": [{"unit": "lovelace", "quantity": str(lovelace)}],
    }]


def test_simple_ada_payment(ccl):
    """Build a simple ADA payment transaction."""
    sender = ccl.account.create(CclLib.TESTNET)
    receiver = ccl.account.create(CclLib.TESTNET)

    result = ccl.quicktx.new_tx() \
        .pay_to_address(receiver["base_address"], Amount.ada(5)) \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert "tx_cbor" in result
    assert len(result["tx_cbor"]) > 0
    assert "tx_hash" in result
    assert len(result["tx_hash"]) == 64
    assert "fee" in result
    assert int(result["fee"]) > 0


def test_multiple_payments(ccl):
    """Build a transaction with multiple payment outputs."""
    sender = ccl.account.create(CclLib.TESTNET)
    receiver1 = ccl.account.create(CclLib.TESTNET)
    receiver2 = ccl.account.create(CclLib.TESTNET)

    result = ccl.quicktx.new_tx() \
        .pay_to_address(receiver1["base_address"], Amount.ada(5)) \
        .pay_to_address(receiver2["base_address"], Amount.ada(3)) \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0


def test_with_metadata(ccl):
    """Build a transaction with CIP-20 metadata."""
    sender = ccl.account.create(CclLib.TESTNET)
    receiver = ccl.account.create(CclLib.TESTNET)

    result = ccl.quicktx.new_tx() \
        .pay_to_address(receiver["base_address"], Amount.ada(2)) \
        .attach_metadata(674, {"msg": ["Hello from Python"]}) \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert int(result["fee"]) > 0


def test_with_validity_interval(ccl):
    """Build a transaction with validity interval."""
    sender = ccl.account.create(CclLib.TESTNET)
    receiver = ccl.account.create(CclLib.TESTNET)

    result = ccl.quicktx.new_tx() \
        .pay_to_address(receiver["base_address"], Amount.ada(2)) \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .valid_from(1000) \
        .valid_to(50000) \
        .build()

    assert len(result["tx_cbor"]) > 0


def test_insufficient_funds(ccl):
    """Should raise error when UTXOs don't have enough funds."""
    sender = ccl.account.create(CclLib.TESTNET)
    receiver = ccl.account.create(CclLib.TESTNET)

    try:
        ccl.quicktx.new_tx() \
            .pay_to_address(receiver["base_address"], Amount.ada(200)) \
            .from_address(sender["base_address"]) \
            .with_utxos(_make_utxos(sender["base_address"], lovelace=1_000_000)) \
            .with_protocol_params(PROTOCOL_PARAMS) \
            .build()
        assert False, "Should have raised CclError"
    except CclError:
        pass  # expected


def test_multi_asset_payment(ccl):
    """Build a transaction with native asset payment."""
    sender = ccl.account.create(CclLib.TESTNET)
    receiver = ccl.account.create(CclLib.TESTNET)

    policy_id = "a" * 56
    asset_name_hex = "546f6b656e"  # "Token"
    unit = policy_id + asset_name_hex

    utxos = [{
        "tx_hash": FAKE_TX_HASH,
        "output_index": 0,
        "address": sender["base_address"],
        "amount": [
            {"unit": "lovelace", "quantity": "100000000"},
            {"unit": unit, "quantity": "500"},
        ],
    }]

    result = ccl.quicktx.new_tx() \
        .pay_to_address(
            receiver["base_address"],
            Amount.lovelace(2_000_000),
            Amount.asset(unit, 100),
        ) \
        .from_address(sender["base_address"]) \
        .with_utxos(utxos) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_hash"]) == 64


def test_compose_two_senders(ccl):
    """Compose two Tx objects from different senders into one transaction."""
    sender1 = ccl.account.create(CclLib.TESTNET)
    sender2 = ccl.account.create(CclLib.TESTNET)
    receiver1 = ccl.account.create(CclLib.TESTNET)
    receiver2 = ccl.account.create(CclLib.TESTNET)

    tx1 = ccl.quicktx.tx() \
        .pay_to_address(receiver1["base_address"], Amount.ada(5)) \
        .from_address(sender1["base_address"])

    tx2 = ccl.quicktx.tx() \
        .pay_to_address(receiver2["base_address"], Amount.ada(3)) \
        .from_address(sender2["base_address"])

    utxos = [
        {
            "tx_hash": FAKE_TX_HASH,
            "output_index": 0,
            "address": sender1["base_address"],
            "amount": [{"unit": "lovelace", "quantity": "100000000"}],
        },
        {
            "tx_hash": "b" * 64,
            "output_index": 0,
            "address": sender2["base_address"],
            "amount": [{"unit": "lovelace", "quantity": "100000000"}],
        },
    ]

    result = ccl.quicktx.compose(tx1, tx2) \
        .fee_payer(sender1["base_address"]) \
        .with_utxos(utxos) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .signer_count(2) \
        .build()

    assert "tx_cbor" in result
    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0


def test_compose_missing_fee_payer(ccl):
    """Compose should fail when fee_payer is not set."""
    sender1 = ccl.account.create(CclLib.TESTNET)
    sender2 = ccl.account.create(CclLib.TESTNET)
    receiver1 = ccl.account.create(CclLib.TESTNET)
    receiver2 = ccl.account.create(CclLib.TESTNET)

    tx1 = ccl.quicktx.tx() \
        .pay_to_address(receiver1["base_address"], Amount.ada(5)) \
        .from_address(sender1["base_address"])

    tx2 = ccl.quicktx.tx() \
        .pay_to_address(receiver2["base_address"], Amount.ada(3)) \
        .from_address(sender2["base_address"])

    try:
        ccl.quicktx.compose(tx1, tx2) \
            .with_utxos(_make_utxos(sender1["base_address"])) \
            .with_protocol_params(PROTOCOL_PARAMS) \
            .build()
        assert False, "Should have raised CclError"
    except CclError:
        pass  # expected


# --- Staking ---

def test_register_stake_address(ccl):
    """Build a register stake address transaction."""
    sender = ccl.account.create(CclLib.TESTNET)

    result = ccl.quicktx.new_tx() \
        .register_stake_address(sender["base_address"]) \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64


def test_deregister_stake_address(ccl):
    """Build a deregister stake address transaction."""
    sender = ccl.account.create(CclLib.TESTNET)

    result = ccl.quicktx.new_tx() \
        .deregister_stake_address(sender["base_address"]) \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64


def test_delegate_to(ccl):
    """Build a delegate to pool transaction."""
    sender = ccl.account.create(CclLib.TESTNET)
    pool_id = "pool1pu5jlj4q9w9jlxeu370a3c9myx47md5j5m2str0naunn2q3lkdy"

    result = ccl.quicktx.new_tx() \
        .delegate_to(sender["base_address"], pool_id) \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64


def test_withdraw(ccl):
    """Build a withdraw rewards transaction."""
    sender = ccl.account.create(CclLib.TESTNET)
    info = ccl.account.from_mnemonic(sender["mnemonic"], CclLib.TESTNET)

    result = ccl.quicktx.new_tx() \
        .withdraw(info["stake_address"], "5000000") \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64


# --- DRep ---

def test_register_drep(ccl):
    """Build a register DRep transaction."""
    sender = ccl.account.create(CclLib.TESTNET)
    credential_hash = "ab" * 28

    result = ccl.quicktx.new_tx() \
        .register_drep(credential_hash, "key") \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64


def test_register_drep_with_anchor(ccl):
    """Build a register DRep with anchor transaction."""
    sender = ccl.account.create(CclLib.TESTNET)
    credential_hash = "ab" * 28
    data_hash = "cd" * 32

    result = ccl.quicktx.new_tx() \
        .register_drep(credential_hash, "key",
                       anchor_url="https://example.com/drep.json",
                       anchor_data_hash=data_hash) \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64


def test_unregister_drep(ccl):
    """Build an unregister DRep transaction."""
    sender = ccl.account.create(CclLib.TESTNET)
    credential_hash = "ab" * 28

    result = ccl.quicktx.new_tx() \
        .unregister_drep(credential_hash, "key") \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64


def test_update_drep(ccl):
    """Build an update DRep transaction."""
    sender = ccl.account.create(CclLib.TESTNET)
    credential_hash = "ab" * 28
    data_hash = "cd" * 32

    result = ccl.quicktx.new_tx() \
        .update_drep(credential_hash, "key",
                     anchor_url="https://example.com/drep-v2.json",
                     anchor_data_hash=data_hash) \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64


# --- Voting ---

def test_delegate_voting_power_to_key_hash(ccl):
    """Build a delegate voting power to key hash transaction."""
    sender = ccl.account.create(CclLib.TESTNET)
    drep_hash = "ab" * 28

    result = ccl.quicktx.new_tx() \
        .delegate_voting_power_to(sender["base_address"], "key_hash", drep_hash) \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64


def test_delegate_voting_power_to_abstain(ccl):
    """Build a delegate voting power to abstain transaction."""
    sender = ccl.account.create(CclLib.TESTNET)

    result = ccl.quicktx.new_tx() \
        .delegate_voting_power_to(sender["base_address"], "abstain") \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64


def test_create_vote(ccl):
    """Build a create vote transaction."""
    sender = ccl.account.create(CclLib.TESTNET)
    voter_hash = "ab" * 28
    gov_tx_hash = "cd" * 32

    result = ccl.quicktx.new_tx() \
        .create_vote("drep_key_hash", voter_hash, gov_tx_hash, 0, "yes") \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64


def test_create_vote_with_anchor(ccl):
    """Build a create vote with anchor transaction."""
    sender = ccl.account.create(CclLib.TESTNET)
    voter_hash = "ab" * 28
    gov_tx_hash = "cd" * 32
    anchor_data_hash = "ef" * 32

    result = ccl.quicktx.new_tx() \
        .create_vote("drep_key_hash", voter_hash, gov_tx_hash, 0, "no",
                     anchor_url="https://example.com/rationale.json",
                     anchor_data_hash=anchor_data_hash) \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"])) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64


# --- Governance proposals ---

def test_create_info_action_proposal(ccl):
    """Build an info action governance proposal."""
    sender = ccl.account.create(CclLib.TESTNET)
    info = ccl.account.from_mnemonic(sender["mnemonic"], CclLib.TESTNET)
    anchor_data_hash = "ab" * 32

    result = ccl.quicktx.new_tx() \
        .create_proposal("info_action", info["stake_address"],
                         "https://example.com/proposal.json", anchor_data_hash) \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"], lovelace=2_000_000_000)) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64


def test_create_treasury_withdrawals_proposal(ccl):
    """Build a treasury withdrawals governance proposal."""
    sender = ccl.account.create(CclLib.TESTNET)
    info = ccl.account.from_mnemonic(sender["mnemonic"], CclLib.TESTNET)
    anchor_data_hash = "ab" * 32

    result = ccl.quicktx.new_tx() \
        .create_proposal("treasury_withdrawals", info["stake_address"],
                         "https://example.com/proposal.json", anchor_data_hash,
                         withdrawals=[{"reward_address": info["stake_address"], "amount": "1000000"}]) \
        .from_address(sender["base_address"]) \
        .with_utxos(_make_utxos(sender["base_address"], lovelace=2_000_000_000)) \
        .with_protocol_params(PROTOCOL_PARAMS) \
        .build()

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64


def test_amount_helpers():
    """Test Amount helper methods."""
    assert Amount.ada(5) == {"unit": "lovelace", "quantity": "5000000"}
    assert Amount.lovelace(2000000) == {"unit": "lovelace", "quantity": "2000000"}
    assert Amount.asset("abc123", 100) == {"unit": "abc123", "quantity": "100"}
