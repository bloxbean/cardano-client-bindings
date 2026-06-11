import pytest

from ccl._ffi import CclLib, CclError

# Minimal protocol parameters (CCL ProtocolParams model).
PROTOCOL_PARAMS = {
    "min_fee_a": 44, "min_fee_b": 155381, "max_tx_size": 16384,
    "key_deposit": "2000000", "pool_deposit": "500000000",
    "coins_per_utxo_size": "4310", "max_val_size": "5000",
    "max_tx_ex_mem": "10000000", "max_tx_ex_steps": "10000000000",
    "price_mem": 0.0577, "price_step": 0.0000721, "collateral_percent": 150,
    "max_collateral_inputs": 3,
}

FAKE_TX_HASH = "a" * 64


def _utxos(address, lovelace=100_000_000):
    return [{
        "tx_hash": FAKE_TX_HASH,
        "output_index": 0,
        "address": address,
        "amount": [{"unit": "lovelace", "quantity": str(lovelace)}],
    }]


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


def _assert_built(result):
    assert isinstance(result, dict)
    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0


def test_simple_payment(ccl):
    sender = ccl.account.create(CclLib.TESTNET)
    receiver = ccl.account.create(CclLib.TESTNET)
    yaml_str = _payment_yaml(sender["base_address"], receiver["base_address"], "5000000")
    _assert_built(ccl.quicktx.build(yaml_str, _utxos(sender["base_address"]), PROTOCOL_PARAMS))


def test_multiple_payments(ccl):
    sender = ccl.account.create(CclLib.TESTNET)
    r1 = ccl.account.create(CclLib.TESTNET)
    r2 = ccl.account.create(CclLib.TESTNET)
    yaml_str = f"""
version: 1.0
transaction:
  - tx:
      from: {sender['base_address']}
      intents:
        - type: payment
          address: {r1['base_address']}
          amounts:
            - unit: lovelace
              quantity: "5000000"
        - type: payment
          address: {r2['base_address']}
          amounts:
            - unit: lovelace
              quantity: "3000000"
"""
    _assert_built(ccl.quicktx.build(yaml_str, _utxos(sender["base_address"]), PROTOCOL_PARAMS))


def test_variable_substitution(ccl):
    sender = ccl.account.create(CclLib.TESTNET)
    receiver = ccl.account.create(CclLib.TESTNET)
    yaml_str = f"""
version: 1.0
variables:
  to: {receiver['base_address']}
  amount: "4000000"
transaction:
  - tx:
      from: {sender['base_address']}
      intents:
        - type: payment
          address: ${{to}}
          amounts:
            - unit: lovelace
              quantity: ${{amount}}
"""
    _assert_built(ccl.quicktx.build(yaml_str, _utxos(sender["base_address"]), PROTOCOL_PARAMS))


def test_insufficient_funds(ccl):
    sender = ccl.account.create(CclLib.TESTNET)
    receiver = ccl.account.create(CclLib.TESTNET)
    yaml_str = _payment_yaml(sender["base_address"], receiver["base_address"], "200000000")
    with pytest.raises(CclError):
        ccl.quicktx.build(yaml_str, _utxos(sender["base_address"], 1_000_000), PROTOCOL_PARAMS)
