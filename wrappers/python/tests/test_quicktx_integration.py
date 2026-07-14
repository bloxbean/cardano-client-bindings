"""Integration tests for QuickTx (TxPlan YAML) with Yaci DevKit.

Requires:
- Yaci DevKit running on port 10000
- Native library built: ./gradlew :core:nativeCompile

Run with:
    PYTHONPATH=wrappers/python CCL_LIB_PATH=core/build/native/nativeCompile \
        pytest wrappers/python/tests/test_quicktx_integration.py -v
"""
import re
import time
from pathlib import Path

import pytest

from ccl._ffi import CclLib, CclError
from ccl.network import Network
from tests.devkit_helper import DevKitHelper

# The fixed test account the quicktx-intents fixtures are derived from (account 0/0).
INTENT_MNEMONIC = "test walk nut penalty hip pave soap entry language right filter choice"
INTENT_SENDER = "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp"
FIXTURES = Path(__file__).parent / "../../../test-fixtures/quicktx-intents"

# The address the mint fixtures pay the minted asset to (account.enterprise_address).
MINT_RECEIVER = "addr_test1vz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzerspjrlsz"

# The deterministic always-succeeds-V2 script address + datum hash used by the Plutus spend fixtures,
# and the placeholder tx hash baked into script_collect_from.yaml (repointed at the real locked UTXO).
SCRIPT_ADDR = "addr_test1wpunlryvl7aqsxe22erzlsseej87v5kk5vutvtrmzdy8dect48z0w"
SCRIPT_DATUM_HASH = "9e1199a988ba72ffd6e9c269cadb3b53b5f360ff99f112d9b2ee30c4d74ad88b"
SCRIPT_TX_HASH = "b" * 64
EXEC_UNITS = [{"mem": 2000000, "steps": 500000000}]

# The gov_action_tx_hash baked into voting.yaml; the voting test repoints it at the real proposal it
# submits.
GOV_ACTION_PLACEHOLDER = "12745f09b138d4d0a11a560b4591ebb830cf12336347606d2edbbf1893d395c6"

# The pool id baked into stake_delegation.yaml, and the pool id keyed to the account's stake key in
# pool_registration.yaml. The delegation test registers that pool and repoints the placeholder at it.
POOL_PLACEHOLDER = "pool1pu5jlj4q9w9jlxeu370a3c9myx47md5j5m2str0naunn2q3lkdy"
ACCOUNT_POOL_ID = "pool1xtrj35uxrctye2egew8sqezgzwwg796ql7uw02572gedcpgmwck"


def _read_fixture(rel):
    return (FIXTURES / rel).read_text()


def _devnet_pp(devkit):
    """Fetch the devnet protocol params and fill in the Conway deposits DevKit returns as null (the
    node validates the actual values on submit)."""
    pp = devkit.get_protocol_params()
    pp["drep_deposit"] = "500000000"
    pp["gov_action_deposit"] = "1000000000"
    pp["pool_deposit"] = "500000000"
    return pp


def _reset_and_fund(devkit):
    """Reset the devnet, fund the fixed account with 6000 ADA, and return the devnet protocol params."""
    devkit.reset()
    devkit.wait_for_block(3)
    devkit.topup(INTENT_SENDER, 6000)
    devkit.wait_for_block(3)
    return _devnet_pp(devkit)


def _sign_submit(ccl_lib, devkit, yaml_str, utxos, pp, keys, exec_units=None):
    """Build the YAML with the given UTXOs + params, sign with the key roles, and submit.

    The devnet's /tx/submit returns 200/202 only after the node has validated and accepted the tx (a
    rejected tx raises RuntimeError with the ledger error) — that acceptance is the proof.
    """
    result = ccl_lib.quicktx.build(yaml_str, utxos, pp, exec_units=exec_units)
    signed = ccl_lib.account.sign_tx_with_keys(
        INTENT_MNEMONIC, result["tx_cbor"], list(keys), Network.TESTNET, 0, 0)
    tx_hash = devkit.submit_tx(signed)
    assert tx_hash
    return tx_hash


def _build_sign_submit(ccl_lib, devkit, fixture, keys, exec_units=None):
    """Reset the devnet, fund the fixed account, build the fixture with its real UTXOs, sign with the
    given key roles, submit, and verify the node accepted it."""
    pp = _reset_and_fund(devkit)
    utxos = devkit.get_utxos(INTENT_SENDER)
    return _sign_submit(ccl_lib, devkit, _read_fixture(fixture), utxos, pp, keys, exec_units)


def _setup_then_submit(ccl_lib, devkit, setup_fixture, setup_keys, fixture, keys):
    """Reset+fund the devnet, submit a prerequisite fixture (e.g. registering a stake address or
    DRep), then submit the target fixture in the next block. Used for intents whose certificate
    depends on prior on-chain state."""
    pp = _reset_and_fund(devkit)
    u = devkit.get_utxos(INTENT_SENDER)
    _sign_submit(ccl_lib, devkit, _read_fixture(setup_fixture), u, pp, setup_keys)
    devkit.wait_for_block(3)
    u2 = devkit.get_utxos(INTENT_SENDER)
    _sign_submit(ccl_lib, devkit, _read_fixture(fixture), u2, pp, keys)


def _assert_minted_asset_at(devkit, address):
    """Confirm a mint actually landed on-chain: the receiver holds a non-lovelace asset. ("Submit
    accepted" alone doesn't prove the intended effect; this does.)"""
    devkit.wait_for_block(3)
    for u in devkit.get_utxos(address):
        for a in u.get("amount", []):
            unit = a.get("unit")
            if unit and unit != "lovelace":
                return
    raise AssertionError(f"expected a minted asset at {address}, found none")


def _assert_utxo_consumed(devkit, address, tx_hash):
    """Confirm the given UTXO is no longer present at an address (it was spent)."""
    devkit.wait_for_block(3)
    for u in devkit.get_utxos(address):
        if u.get("tx_hash") == tx_hash:
            raise AssertionError(f"UTXO {tx_hash} at {address} was not consumed")


@pytest.fixture(scope="module")
def devkit():
    helper = DevKitHelper()
    if not helper.is_available():
        pytest.skip("Yaci DevKit is not running on port 10000")
    helper.reset()
    time.sleep(3)
    return helper


@pytest.fixture(scope="module")
def ccl_lib():
    lib = CclLib()
    yield lib
    lib.close()


@pytest.fixture
def funded_sender(ccl_lib, devkit):
    account = ccl_lib.account.create(Network.TESTNET)
    devkit.topup(account["base_address"], 150)
    devkit.wait_for_block(2)
    return account


def _payment_yaml(from_addr, to_addr, quantity):
    return f"""
version: 1.0
transaction:
  - tx:
      from: {from_addr}
      intents:
        - type: payment
          address: {to_addr}
          amounts:
            - unit: lovelace
              quantity: "{quantity}"
"""


def test_simple_ada_transfer(ccl_lib, devkit, funded_sender):
    """Build a 5 ADA payment from TxPlan YAML, sign, submit, and verify on-chain."""
    receiver = ccl_lib.account.create(Network.TESTNET)

    utxos = devkit.get_utxos(funded_sender["base_address"])
    pp = devkit.get_protocol_params()

    yaml_str = _payment_yaml(funded_sender["base_address"], receiver["base_address"], "5000000")
    result = ccl_lib.quicktx.build(yaml_str, utxos, pp)
    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0

    signed_tx = ccl_lib.account.sign_tx(
        funded_sender["mnemonic"], result["tx_cbor"], Network.TESTNET, 0, 0)
    tx_hash = devkit.submit_tx(signed_tx)
    assert tx_hash

    devkit.wait_for_block(3)
    receiver_utxos = devkit.get_utxos(receiver["base_address"])
    total = sum(int(a["quantity"]) for u in receiver_utxos
                for a in u["amount"] if a["unit"] == "lovelace")
    assert total == 5_000_000


def test_multiple_receivers(ccl_lib, devkit, funded_sender):
    r1 = ccl_lib.account.create(Network.TESTNET)
    r2 = ccl_lib.account.create(Network.TESTNET)

    utxos = devkit.get_utxos(funded_sender["base_address"])
    pp = devkit.get_protocol_params()

    yaml_str = f"""
version: 1.0
transaction:
  - tx:
      from: {funded_sender['base_address']}
      intents:
        - type: payment
          address: {r1['base_address']}
          amounts:
            - unit: lovelace
              quantity: "3000000"
        - type: payment
          address: {r2['base_address']}
          amounts:
            - unit: lovelace
              quantity: "2000000"
"""
    result = ccl_lib.quicktx.build(yaml_str, utxos, pp)
    signed_tx = ccl_lib.account.sign_tx(
        funded_sender["mnemonic"], result["tx_cbor"], Network.TESTNET, 0, 0)
    assert devkit.submit_tx(signed_tx)

    devkit.wait_for_block(3)
    r1_utxos = devkit.get_utxos(r1["base_address"])
    total = sum(int(a["quantity"]) for u in r1_utxos
                for a in u["amount"] if a["unit"] == "lovelace")
    assert total == 3_000_000


def test_insufficient_funds(ccl_lib, devkit):
    sender = ccl_lib.account.create(Network.TESTNET)
    devkit.topup(sender["base_address"], 2)
    devkit.wait_for_block(2)
    receiver = ccl_lib.account.create(Network.TESTNET)

    utxos = devkit.get_utxos(sender["base_address"])
    pp = devkit.get_protocol_params()

    yaml_str = _payment_yaml(sender["base_address"], receiver["base_address"], "100000000")
    with pytest.raises(CclError):
        ccl_lib.quicktx.build(yaml_str, utxos, pp)


def test_build_with_yaci_provider(ccl_lib, devkit, funded_sender):
    """The shipped YaciProvider fetches the devnet's real chain data and feeds build()."""
    from ccl.providers import YaciProvider

    receiver = ccl_lib.account.create(Network.TESTNET)
    provider = YaciProvider()  # defaults to the local DevKit cluster
    yaml_str = _payment_yaml(funded_sender["base_address"], receiver["base_address"], "5000000")

    result = ccl_lib.quicktx.build_with(yaml_str, provider, funded_sender["base_address"])

    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0


def test_donation_treasury(ccl_lib, devkit):
    """Submit a treasury donation.

    Conway validates the tx's declared current_treasury_value against the node's live ledger treasury
    *exactly* (ConwayTreasuryValueMismatch otherwise), so the donation.yaml fixture's hardcoded
    current_treasury_value: 0 no longer works on Yaci DevKit 0.12 (non-zero, epoch-varying treasury).

    We deliberately do NOT read the treasury from an endpoint and declare it — and not for lack of
    trying. The obvious "clean" design was to read yaci-store's /network endpoint
    (http://localhost:8080/api/v1/network -> supply.treasury) and submit that exact value. It does not
    work reliably: yaci-store computes the treasury off-chain and its value drifts from the node's
    ledger — in CI it returned 21,599,698,134,578 while the node held 43,186,776,312,112 (an epoch of
    indexing lag), so the fetched value was rejected. The node is the sole authority on its own
    treasury, and the only channel that reports its exact current value is the rejection itself.

    So: submit, read "expected: Coin N" out of the ConwayTreasuryValueMismatch, rebuild with N, and
    resubmit. Retrying also absorbs an epoch boundary landing between attempts. The offline donation
    build is covered separately by the intents build tests.
    """
    devkit.reset()
    devkit.wait_for_block(3)
    devkit.topup(INTENT_SENDER, 6000)
    devkit.wait_for_block(3)

    utxos = devkit.get_utxos(INTENT_SENDER)
    pp = devkit.get_protocol_params()
    base_yaml = (FIXTURES / "donation.yaml").read_text()

    treasury = "0"
    last_err = None
    for _ in range(5):
        yaml_str = base_yaml.replace("current_treasury_value: 0", f"current_treasury_value: {treasury}")
        result = ccl_lib.quicktx.build(yaml_str, utxos, pp)
        signed = ccl_lib.account.sign_tx(INTENT_MNEMONIC, result["tx_cbor"], Network.TESTNET, 0, 0)
        try:
            tx_hash = devkit.submit_tx(signed)
            assert tx_hash
            return  # accepted
        except RuntimeError as e:
            last_err = str(e)
            m = re.search(r"expected:\s*Coin\s*(\d+)", last_err)
            if not m:
                raise
            treasury = m.group(1)
    raise AssertionError(f"donation submit failed after retries: {last_err}")


# --- Metadata / minting suite (mirrors intents_integration_test.go) ---

def test_metadata(ccl_lib, devkit):
    """Submit a transaction carrying auxiliary metadata. Mirrors TestIntegrationMetadata."""
    _build_sign_submit(ccl_lib, devkit, "metadata.yaml", ["payment"])


def test_native_mint(ccl_lib, devkit):
    """Mint a native asset under an empty ScriptAll policy (no signature needed beyond the fee
    payer), then confirm the asset landed at the receiver. Mirrors TestIntegrationNativeMint."""
    _build_sign_submit(ccl_lib, devkit, "minting.yaml", ["payment"])
    _assert_minted_asset_at(devkit, MINT_RECEIVER)


def test_plutus_mint(ccl_lib, devkit):
    """Mint under a Plutus V2 policy (supplying execution units), then confirm the asset landed at
    the receiver. Mirrors TestIntegrationPlutusMint."""
    _build_sign_submit(ccl_lib, devkit, "plutus/script_minting.yaml", ["payment"],
                       exec_units=EXEC_UNITS)
    _assert_minted_asset_at(devkit, MINT_RECEIVER)


def test_plutus_spend(ccl_lib, devkit):
    """Lock a UTXO at the script address (with the datum hash), then spend it. The spend fixture
    references a placeholder UTXO; we repoint it at the real on-chain locked UTXO. Mirrors
    TestIntegrationPlutusSpend.
    """
    pp = _reset_and_fund(devkit)

    # Step 1: lock 10 ADA at the script address with the datum hash.
    utxos = devkit.get_utxos(INTENT_SENDER)
    _sign_submit(ccl_lib, devkit, _read_fixture("plutus/plutus_lock.yaml"), utxos, pp, ["payment"])
    devkit.wait_for_block(3)

    # Step 2: find the locked UTXO at the script address.
    script_utxos = devkit.get_utxos(SCRIPT_ADDR)
    assert script_utxos, "no locked UTXO at script address"
    locked = script_utxos[0]
    lock_hash = locked["tx_hash"]
    lock_idx = int(locked.get("output_index", 0))

    # Step 3: repoint the spend fixture's utxo_ref at the real locked UTXO.
    spend_yaml = _read_fixture("plutus/script_collect_from.yaml").replace(SCRIPT_TX_HASH, lock_hash)
    if lock_idx != 0:
        spend_yaml = spend_yaml.replace("output_index: 0", f"output_index: {lock_idx}", 1)

    # Step 4: spend it — supply the locked UTXO (with its datum hash) + a fee/collateral UTXO.
    spend_utxos = [{
        "tx_hash": lock_hash,
        "output_index": lock_idx,
        "address": SCRIPT_ADDR,
        "amount": [{"unit": "lovelace", "quantity": "10000000"}],
        "data_hash": SCRIPT_DATUM_HASH,
    }]
    spend_utxos.extend(devkit.get_utxos(INTENT_SENDER))

    _sign_submit(ccl_lib, devkit, spend_yaml, spend_utxos, pp, ["payment"], exec_units=EXEC_UNITS)

    # Confirm the spend actually consumed the locked script UTXO.
    _assert_utxo_consumed(devkit, SCRIPT_ADDR, lock_hash)


# --- Governance / staking suite (mirrors intents_integration_test.go) ---

def test_stake_registration(ccl_lib, devkit):
    """Register a stake address (witnessed by payment + stake keys). Mirrors
    TestIntegrationStakeRegistration."""
    _build_sign_submit(ccl_lib, devkit, "stake_registration.yaml", ["payment", "stake"])


def test_drep_registration(ccl_lib, devkit):
    """Register a DRep (witnessed by payment + drep keys). Mirrors
    TestIntegrationDRepRegistration."""
    _build_sign_submit(ccl_lib, devkit, "drep_registration.yaml", ["payment", "drep"])


def test_drep_key_required(ccl_lib, devkit):
    """Negative test: a DRep registration certificate must be witnessed by the DRep key, so signing
    with the payment key alone must be rejected by the node (MissingVKeyWitnessesUTXOW). This proves
    the extra witness sign_tx_with_keys adds is genuinely required — not cosmetic — and complements
    the positive test_drep_registration (payment+drep) above. Mirrors TestIntegrationDRepKeyRequired.
    """
    pp = _reset_and_fund(devkit)
    utxos = devkit.get_utxos(INTENT_SENDER)
    built = ccl_lib.quicktx.build(_read_fixture("drep_registration.yaml"), utxos, pp)

    # Sign with the payment key ONLY (sign_tx), omitting the DRep-key witness.
    signed_payment_only = ccl_lib.account.sign_tx(
        INTENT_MNEMONIC, built["tx_cbor"], Network.TESTNET, 0, 0)
    with pytest.raises(RuntimeError):
        devkit.submit_tx(signed_payment_only)


def test_drep_update(ccl_lib, devkit):
    """Register a DRep, then update it. Mirrors TestIntegrationDRepUpdate."""
    _setup_then_submit(ccl_lib, devkit,
                       "drep_registration.yaml", ["payment", "drep"],
                       "drep_update.yaml", ["payment", "drep"])


def test_drep_deregistration(ccl_lib, devkit):
    """Register a DRep, then deregister it. Mirrors TestIntegrationDRepDeregistration."""
    _setup_then_submit(ccl_lib, devkit,
                       "drep_registration.yaml", ["payment", "drep"],
                       "drep_deregistration.yaml", ["payment", "drep"])


def test_voting_delegation(ccl_lib, devkit):
    """Delegate voting power (requires the stake address to be registered; vote target is abstain).
    Mirrors TestIntegrationVotingDelegation."""
    _setup_then_submit(ccl_lib, devkit,
                       "stake_registration.yaml", ["payment", "stake"],
                       "voting_delegation.yaml", ["payment", "stake"])


def test_pool_registration(ccl_lib, devkit):
    """Register a stake pool keyed to the account's stake key (operator, owner, reward account), so
    signing with the stake key witnesses it. The reward account must be a registered stake address,
    so register it first. Mirrors TestIntegrationPoolRegistration.
    """
    _setup_then_submit(ccl_lib, devkit,
                       "stake_registration.yaml", ["payment", "stake"],
                       "pool_registration.yaml", ["payment", "stake"])


def test_stake_delegation(ccl_lib, devkit):
    """Register the stake address, register a pool keyed to the account, then delegate to that pool.
    (DevKit exposes no pool-list endpoint, so we delegate to a pool we create rather than discover.)
    Mirrors TestIntegrationStakeDelegation.
    """
    pp = _reset_and_fund(devkit)

    u = devkit.get_utxos(INTENT_SENDER)
    _sign_submit(ccl_lib, devkit, _read_fixture("stake_registration.yaml"), u, pp,
                 ["payment", "stake"])
    devkit.wait_for_block(3)

    u2 = devkit.get_utxos(INTENT_SENDER)
    _sign_submit(ccl_lib, devkit, _read_fixture("pool_registration.yaml"), u2, pp,
                 ["payment", "stake"])
    devkit.wait_for_block(3)

    u3 = devkit.get_utxos(INTENT_SENDER)
    deleg_yaml = _read_fixture("stake_delegation.yaml").replace(POOL_PLACEHOLDER, ACCOUNT_POOL_ID)
    _sign_submit(ccl_lib, devkit, deleg_yaml, u3, pp, ["payment", "stake"])


def test_stake_withdrawal(ccl_lib, devkit):
    """Conway requires a stake address to be vote-delegated to a DRep before it can withdraw, so the
    sequence is: register stake -> delegate voting power -> withdraw the (zero) reward balance.
    Mirrors TestIntegrationStakeWithdrawal.
    """
    pp = _reset_and_fund(devkit)

    u = devkit.get_utxos(INTENT_SENDER)
    _sign_submit(ccl_lib, devkit, _read_fixture("stake_registration.yaml"), u, pp,
                 ["payment", "stake"])
    devkit.wait_for_block(3)

    u2 = devkit.get_utxos(INTENT_SENDER)
    _sign_submit(ccl_lib, devkit, _read_fixture("voting_delegation.yaml"), u2, pp,
                 ["payment", "stake"])
    devkit.wait_for_block(3)

    u3 = devkit.get_utxos(INTENT_SENDER)
    _sign_submit(ccl_lib, devkit, _read_fixture("stake_withdrawal.yaml"), u3, pp,
                 ["payment", "stake"])


def test_info_proposal(ccl_lib, devkit):
    """A Conway proposal's deposit-return account must be a registered stake address, so register it
    first, then submit the proposal in the next block. Mirrors TestIntegrationInfoProposal.
    """
    pp = _reset_and_fund(devkit)

    utxos = devkit.get_utxos(INTENT_SENDER)
    _sign_submit(ccl_lib, devkit, _read_fixture("stake_registration.yaml"), utxos, pp,
                 ["payment", "stake"])
    devkit.wait_for_block(3)

    utxos2 = devkit.get_utxos(INTENT_SENDER)
    _sign_submit(ccl_lib, devkit, _read_fixture("governance_proposal.yaml"), utxos2, pp, ["payment"])


def test_voting(ccl_lib, devkit):
    """A vote needs a registered DRep (the voter), a registered stake address (the proposal's return
    account), a live gov action to vote on, and the vote referencing it. Mirrors
    TestIntegrationVoting.
    """
    pp = _reset_and_fund(devkit)

    u = devkit.get_utxos(INTENT_SENDER)
    _sign_submit(ccl_lib, devkit, _read_fixture("drep_registration.yaml"), u, pp,
                 ["payment", "drep"])
    devkit.wait_for_block(3)

    u2 = devkit.get_utxos(INTENT_SENDER)
    _sign_submit(ccl_lib, devkit, _read_fixture("stake_registration.yaml"), u2, pp,
                 ["payment", "stake"])
    devkit.wait_for_block(3)

    # Submit an info proposal. Its tx hash (from the build result, not the garbled submit response)
    # is the gov action id we vote on.
    u3 = devkit.get_utxos(INTENT_SENDER)
    proposal = ccl_lib.quicktx.build(_read_fixture("governance_proposal.yaml"), u3, pp)
    action_tx_hash = proposal["tx_hash"]
    signed_proposal = ccl_lib.account.sign_tx_with_keys(
        INTENT_MNEMONIC, proposal["tx_cbor"], ["payment"], Network.TESTNET, 0, 0)
    assert devkit.submit_tx(signed_proposal)
    devkit.wait_for_block(3)

    # Vote on the proposal we just submitted.
    u4 = devkit.get_utxos(INTENT_SENDER)
    vote_yaml = _read_fixture("voting.yaml").replace(GOV_ACTION_PLACEHOLDER, action_tx_hash)
    _sign_submit(ccl_lib, devkit, vote_yaml, u4, pp, ["payment", "drep"])
