"""Integration tests for QuickTx (TxPlan YAML) with Yaci DevKit.

Requires:
- Yaci DevKit running on port 10000
- Native library built: ./gradlew :core:nativeCompile

Run with:
    PYTHONPATH=wrappers/python CCL_LIB_PATH=core/build/native/nativeCompile \
        pytest wrappers/python/tests/test_quicktx_integration.py -v
"""
import time
from pathlib import Path

import pytest

from ccl._ffi import CclLib, CclError
from tests.devkit_helper import DevKitHelper

# The fixed test account the quicktx-intents fixtures are derived from (account 0/0).
INTENT_MNEMONIC = "test walk nut penalty hip pave soap entry language right filter choice"
INTENT_SENDER = "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp"
FIXTURES = Path(__file__).parent / "../../../test-fixtures/quicktx-intents"


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


def test_donation_treasury(ccl_lib, devkit):
    """Treasury donation: Conway validates the tx's declared current_treasury_value against the
    node's live treasury. Read the current value from yaci-store's /network endpoint and declare
    exactly that. The treasury only moves at epoch boundaries, so retry (re-reading) if one lands
    between build and submit.
    """
    devkit.reset()
    devkit.wait_for_block(3)
    devkit.topup(INTENT_SENDER, 6000)
    devkit.wait_for_block(3)

    utxos = devkit.get_utxos(INTENT_SENDER)
    pp = devkit.get_protocol_params()
    base_yaml = (FIXTURES / "donation.yaml").read_text()

    last_err = None
    for _ in range(5):
        treasury = devkit.get_treasury()
        yaml_str = base_yaml.replace("current_treasury_value: 0", f"current_treasury_value: {treasury}")
        result = ccl_lib.quicktx.build(yaml_str, utxos, pp)
        signed = ccl_lib.account.sign_tx(INTENT_MNEMONIC, result["tx_cbor"], CclLib.TESTNET, 0, 0)
        try:
            tx_hash = devkit.submit_tx(signed)
            assert tx_hash
            return  # accepted
        except RuntimeError as e:
            last_err = str(e)
            if "TreasuryValueMismatch" not in last_err:
                raise
            devkit.wait_for_block(3)  # epoch may have advanced; re-read treasury and retry
    raise AssertionError(f"donation submit failed after retries: {last_err}")
