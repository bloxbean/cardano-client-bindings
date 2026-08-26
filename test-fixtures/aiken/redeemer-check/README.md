# redeemer-check — Aiken validator for positive/negative Plutus integration tests

A deliberately minimal mint validator: **passes iff the redeemer is the integer 42**. It gives the
four wrappers' integration suites one script that can both accept (happy path) and genuinely
reject (phase-2 validation failure) — something the previous always-succeeds CBOR blob could not
express.

Consumed by the fixtures:

- `test-fixtures/quicktx-intents/plutus/aiken_mint_pass.yaml` — redeemer 42, node must accept
- `test-fixtures/quicktx-intents/plutus/aiken_mint_fail.yaml` — redeemer 0, node must reject

## Regenerating the compiled artifact

Requires [Aiken](https://aiken-lang.org) (built with v1.1.21, Plutus V3):

```bash
cd test-fixtures/aiken/redeemer-check
aiken build          # → plutus.json
```

From `plutus.json`, the fixtures need:

- `validators[0].hash` → the fixtures' `policyId`
- `validators[0].compiledCode`, wrapped in **one more** CBOR bytestring, → the fixtures' `cbor_hex`
  (CCL's TxPlan `validator.cbor_hex` uses the double-wrapped form, like a cardano-cli script file):

```bash
python3 - <<'EOF'
import json
code = bytes.fromhex(json.load(open("plutus.json"))["validators"][0]["compiledCode"])
hdr = bytes([0x58, len(code)]) if len(code) < 256 else bytes([0x59]) + len(code).to_bytes(2, "big")
print((hdr + code).hex())
EOF
```

`plutus.json` is committed so the fixtures' provenance is auditable without the Aiken toolchain;
tests never invoke Aiken.
