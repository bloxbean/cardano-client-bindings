"""Managed-account object tests (ADR-0016 slice 4): lifecycle, typed-role signing parity with the
mnemonic-per-call path, one-shot recovery-phrase export, and secret hygiene. Fully offline."""
import pytest

from ccl import CclInvalidHandleError, Network, SigningRole
from ccl._ffi import CclError

TEST_MNEMONIC = "test walk nut penalty hip pave soap entry language right filter choice"

PROTOCOL_PARAMS = {
    "min_fee_a": 44, "min_fee_b": 155381, "max_tx_size": 16384,
    "key_deposit": "2000000", "pool_deposit": "500000000",
    "coins_per_utxo_size": "4310", "max_val_size": "5000",
    "max_tx_ex_mem": "10000000", "max_tx_ex_steps": "10000000000",
    "price_mem": 0.0577, "price_step": 0.0000721, "collateral_percent": 150,
    "max_collateral_inputs": 3,
}


def _unsigned_stake_reg(ccl, sender_info):
    yaml = f"""
version: 1.0
transaction:
  - tx:
      from: {sender_info["base_address"]}
      intents:
        - type: stake_registration
          stake_address: {sender_info["stake_address"]}
"""
    utxos = [{"tx_hash": "a" * 64, "output_index": 0, "address": sender_info["base_address"],
              "amount": [{"unit": "lovelace", "quantity": "2000000000"}]}]
    return ccl.quicktx.build(yaml, utxos, PROTOCOL_PARAMS, additional_signers=1)["tx_cbor"]


def test_open_info_matches_legacy_derivation(ccl):
    legacy = ccl.account.from_mnemonic(TEST_MNEMONIC, Network.TESTNET)
    with ccl.accounts.from_mnemonic(TEST_MNEMONIC, Network.TESTNET) as acct:
        info = acct.info
        assert info["base_address"] == legacy["base_address"]
        assert info["enterprise_address"] == legacy["enterprise_address"]
        assert info["stake_address"] == legacy["stake_address"]
        assert info["network"] == int(Network.TESTNET)
        assert info["account_index"] == 0 and info["address_index"] == 0
        assert "mnemonic" not in info


def test_sign_parity_with_mnemonic_per_call_path(ccl):
    with ccl.accounts.from_mnemonic(TEST_MNEMONIC, Network.TESTNET) as acct:
        unsigned = _unsigned_stake_reg(ccl, acct.info)

        assert acct.sign_tx(unsigned) == ccl.account.sign_tx(
            TEST_MNEMONIC, unsigned, Network.TESTNET)
        assert acct.sign_tx(unsigned, SigningRole.PAYMENT | SigningRole.STAKE) == \
            ccl.account.sign_tx_with_keys(
                TEST_MNEMONIC, unsigned, ["payment", "stake"], Network.TESTNET)
        # Mask order is irrelevant — canonical application order fixes the output.
        assert acct.sign_tx(unsigned, SigningRole.STAKE | SigningRole.PAYMENT) == \
            acct.sign_tx(unsigned, SigningRole.PAYMENT | SigningRole.STAKE)


def test_empty_role_mask_rejected(ccl):
    with ccl.accounts.from_mnemonic(TEST_MNEMONIC, Network.TESTNET) as acct:
        unsigned = _unsigned_stake_reg(ccl, acct.info)
        with pytest.raises(CclError):
            acct.sign_tx(unsigned, 0)


def test_lifecycle_close_idempotent_use_after_close_typed(ccl):
    acct = ccl.accounts.from_mnemonic(TEST_MNEMONIC, Network.TESTNET)
    acct.close()
    acct.close()  # idempotent
    with pytest.raises(CclInvalidHandleError):
        _ = acct.info
    with pytest.raises(CclInvalidHandleError):
        acct.sign_tx("84a400", SigningRole.PAYMENT)


def test_create_export_once_and_restore(ccl):
    with ccl.accounts.create(Network.TESTNET) as acct:
        base = acct.info["base_address"]
        phrase = acct.export_recovery_phrase()
        assert len(phrase.split()) == 24
        with ccl.accounts.from_mnemonic(phrase, Network.TESTNET) as restored:
            assert restored.info["base_address"] == base
        with pytest.raises(CclError):
            acct.export_recovery_phrase()  # one-shot


def test_export_on_imported_account_fails(ccl):
    with ccl.accounts.from_mnemonic(TEST_MNEMONIC, Network.TESTNET) as acct:
        with pytest.raises(CclError):
            acct.export_recovery_phrase()


def test_repr_never_contains_secrets(ccl):
    with ccl.accounts.create(Network.TESTNET) as acct:
        assert "addr" not in repr(acct)  # not even public data, just the handle
        phrase = acct.export_recovery_phrase()
        assert phrase.split()[0] not in repr(acct)
    assert repr(acct) == "<ccl.Account closed>"


def test_two_handles_same_leaf_independent_lifecycle(ccl):
    a = ccl.accounts.from_mnemonic(TEST_MNEMONIC, Network.TESTNET)
    b = ccl.accounts.from_mnemonic(TEST_MNEMONIC, Network.TESTNET)
    try:
        assert a.info["base_address"] == b.info["base_address"]
        a.close()
        with pytest.raises(CclInvalidHandleError):
            _ = a.info
        assert b.info["base_address"]  # sibling unaffected
    finally:
        b.close()
