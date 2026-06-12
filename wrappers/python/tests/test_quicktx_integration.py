"""Integration tests for QuickTx (TxPlan YAML) with Yaci DevKit.

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
from tests.devkit_helper import DevKitHelper


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
    account = ccl_lib.account.create(CclLib.TESTNET)
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
    receiver = ccl_lib.account.create(CclLib.TESTNET)

    utxos = devkit.get_utxos(funded_sender["base_address"])
    pp = devkit.get_protocol_params()

    yaml_str = _payment_yaml(funded_sender["base_address"], receiver["base_address"], "5000000")
    result = ccl_lib.quicktx.build(yaml_str, utxos, pp)
    assert len(result["tx_cbor"]) > 0
    assert len(result["tx_hash"]) == 64
    assert int(result["fee"]) > 0

    signed_tx = ccl_lib.account.sign_tx(
        funded_sender["mnemonic"], result["tx_cbor"], CclLib.TESTNET, 0, 0)
    tx_hash = devkit.submit_tx(signed_tx)
    assert tx_hash

    devkit.wait_for_block(3)
    receiver_utxos = devkit.get_utxos(receiver["base_address"])
    total = sum(int(a["quantity"]) for u in receiver_utxos
                for a in u["amount"] if a["unit"] == "lovelace")
    assert total == 5_000_000


def test_multiple_receivers(ccl_lib, devkit, funded_sender):
    r1 = ccl_lib.account.create(CclLib.TESTNET)
    r2 = ccl_lib.account.create(CclLib.TESTNET)

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
        funded_sender["mnemonic"], result["tx_cbor"], CclLib.TESTNET, 0, 0)
    assert devkit.submit_tx(signed_tx)

    devkit.wait_for_block(3)
    r1_utxos = devkit.get_utxos(r1["base_address"])
    total = sum(int(a["quantity"]) for u in r1_utxos
                for a in u["amount"] if a["unit"] == "lovelace")
    assert total == 3_000_000


def test_insufficient_funds(ccl_lib, devkit):
    sender = ccl_lib.account.create(CclLib.TESTNET)
    devkit.topup(sender["base_address"], 2)
    devkit.wait_for_block(2)
    receiver = ccl_lib.account.create(CclLib.TESTNET)

    utxos = devkit.get_utxos(sender["base_address"])
    pp = devkit.get_protocol_params()

    yaml_str = _payment_yaml(sender["base_address"], receiver["base_address"], "100000000")
    with pytest.raises(CclError):
        ccl_lib.quicktx.build(yaml_str, utxos, pp)
