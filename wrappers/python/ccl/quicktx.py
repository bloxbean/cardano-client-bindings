import json

import yaml


class QuickTx:
    """Builds unsigned transactions from a CCL TxPlan (YAML), fully offline.

    The transaction is defined by a TxPlan YAML document; the caller supplies the chain data
    (UTXOs and protocol parameters). Nothing is fetched and nothing is submitted — the result
    is the unsigned transaction CBOR plus its hash and fee.
    """

    def __init__(self, bridge):
        self._bridge = bridge

    def build(self, txplan_yaml, utxos, protocol_params, exec_units=None):
        """Build an unsigned transaction from a TxPlan YAML document.

        Args:
            txplan_yaml: the TxPlan YAML string defining the transaction(s).
            utxos: list of UTXO dicts (CCL ``Utxo`` model) available to the sender.
            protocol_params: protocol parameters dict (CCL ``ProtocolParams`` model).
            exec_units: optional list of redeemer execution units (``[{"mem","steps"}]``), one per
                redeemer in transaction order, for Plutus script transactions. Compute these with any
                evaluator (Ogmios, Blockfrost, Aiken, Scalus); the bridge does not run the script.

        Returns:
            dict with ``tx_cbor``, ``tx_hash`` and ``fee`` (parsed from the YAML result).
        """
        utxos_json = json.dumps(utxos)
        pp_json = json.dumps(protocol_params)
        exec_units_json = json.dumps(exec_units) if exec_units is not None else None
        rc = self._bridge._lib.ccl_quicktx_build(
            self._bridge._thread,
            self._bridge._encode(txplan_yaml),
            self._bridge._encode(utxos_json),
            self._bridge._encode(pp_json),
            self._bridge._encode(exec_units_json),
        )
        return yaml.safe_load(self._bridge._check(rc))
