"""Build and sign a payment transaction fully offline from a TxPlan (YAML).

The transaction is defined as a TxPlan YAML document; we supply the UTXOs and protocol
parameters ourselves (no node / no provider), build the unsigned transaction, then sign it
locally. Submitting it to a network is a separate, online step.

Run from the repo root:

    LIB_DIR=core/build/native/nativeCompile
    PYTHONPATH=wrappers/python CCL_LIB_PATH=$LIB_DIR \
    DYLD_LIBRARY_PATH=$LIB_DIR LD_LIBRARY_PATH=$LIB_DIR \
      python3 wrappers/python/examples/03_build_and_sign_tx.py
"""
from ccl._ffi import CclLib

# Minimal protocol parameters (CCL ProtocolParams model).
PROTOCOL_PARAMS = {
    "min_fee_a": 44, "min_fee_b": 155381, "max_tx_size": 16384,
    "key_deposit": "2000000", "pool_deposit": "500000000",
    "coins_per_utxo_size": "4310", "max_val_size": "5000",
    "max_tx_ex_mem": "10000000", "max_tx_ex_steps": "10000000000",
    "price_mem": 0.0577, "price_step": 0.0000721, "collateral_percent": 150,
    "max_collateral_inputs": 3,
}


def main():
    lib = CclLib()
    try:
        sender = lib.account.create(CclLib.TESTNET)
        receiver = lib.account.create(CclLib.TESTNET)

        # A static UTXO the sender controls (100 ADA), instead of querying a node.
        utxos = [{
            "tx_hash": "a" * 64,
            "output_index": 0,
            "address": sender["base_address"],
            "amount": [{"unit": "lovelace", "quantity": "100000000"}],
        }]

        # Define the transaction as a TxPlan YAML document: pay 5 ADA to the receiver.
        txplan_yaml = f"""
version: 1.0
transaction:
  - tx:
      from: {sender['base_address']}
      intents:
        - type: payment
          address: {receiver['base_address']}
          amounts:
            - unit: lovelace
              quantity: "5000000"
"""

        result = lib.quicktx.build(txplan_yaml, utxos, PROTOCOL_PARAMS)
        print("Built unsigned transaction from TxPlan YAML")
        print("  tx hash:", result["tx_hash"])
        print("  fee    :", result["fee"])
        print("  cbor   :", result["tx_cbor"][:80], "...")

        signed = lib.account.sign_tx(
            sender["mnemonic"], result["tx_cbor"], CclLib.TESTNET, 0, 0)
        print("Signed transaction cbor:", signed[:80], "...")
        print("\nNext step (not shown): submit `signed` to a Cardano node over HTTP.")
    finally:
        lib.close()


if __name__ == "__main__":
    main()
