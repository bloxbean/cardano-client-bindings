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

    def build(self, txplan_yaml, utxos, protocol_params):
        """Build an unsigned transaction from a TxPlan YAML document.

        Args:
            txplan_yaml: the TxPlan YAML string defining the transaction(s).
            utxos: list of UTXO dicts (CCL ``Utxo`` model) available to the sender.
            protocol_params: protocol parameters dict (CCL ``ProtocolParams`` model).

        Returns:
            dict with ``tx_cbor``, ``tx_hash`` and ``fee`` (parsed from the YAML result).
        """
        utxos_json = json.dumps(utxos)
        pp_json = json.dumps(protocol_params)
        rc = self._bridge._lib.ccl_quicktx_build(
            self._bridge._thread,
            self._bridge._encode(txplan_yaml),
            self._bridge._encode(utxos_json),
            self._bridge._encode(pp_json),
        )
        return yaml.safe_load(self._bridge._check(rc))
