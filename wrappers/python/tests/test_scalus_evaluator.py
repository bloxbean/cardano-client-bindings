"""Native-image runtime check for the Scalus transaction evaluator.

Builds a real Plutus mint (always-succeeds validator) with **no caller-supplied execution units** —
so the core falls back to Scalus, which runs the UPLC evaluator to compute the units. This exercises
Scalus *inside the native library* (not just on the JVM), on whatever platform CI runs.
"""
import json
from pathlib import Path

FIXTURES = Path(__file__).parent / "../../../test-fixtures/plutus-mint-scalus"


def test_plutus_mint_falls_back_to_scalus(ccl):
    yaml = (FIXTURES / "mint.yaml").read_text()
    utxos = json.loads((FIXTURES / "utxos.json").read_text())
    # Params include cost models (Scalus needs them to run the UPLC machine).
    params = json.loads((FIXTURES / "protocol-params.json").read_text())

    # exec_units omitted → Scalus computes them offline by evaluating the validator in libccl.
    result = ccl.quicktx.build(yaml, utxos, params)

    assert result.get("tx_cbor"), "expected a built transaction"
    assert result.get("tx_hash")
    assert int(result["fee"]) > 0
