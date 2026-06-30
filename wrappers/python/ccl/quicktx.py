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

    def build_with_provider(self, txplan_yaml, provider, sender, exec_units=None):
        """Convenience: fetch chain data from ``provider`` and build, in one call.

        Composes ``provider.utxos(sender)`` + ``provider.protocol_params()`` with :meth:`build`.
        The bridge stays offline — this only moves the optional HTTP fetch into wrapper code. See
        :mod:`ccl.providers` for available providers (Yaci DevKit, Blockfrost) or implement your own.

        Args:
            txplan_yaml: the TxPlan YAML string defining the transaction(s).
            provider: a :class:`ccl.providers.ChainDataProvider` (anything with ``utxos(address)``
                and ``protocol_params()``).
            sender: the address whose UTXOs fund the transaction.
            exec_units: optional Plutus execution units, as for :meth:`build`.

        Returns:
            dict with ``tx_cbor``, ``tx_hash`` and ``fee``.
        """
        utxos = provider.utxos(sender)
        protocol_params = provider.protocol_params()
        return self.build(txplan_yaml, utxos, protocol_params, exec_units)
