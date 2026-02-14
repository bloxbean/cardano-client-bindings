import json


class Amount:
    """Helper to build amount objects for transaction operations."""

    @staticmethod
    def lovelace(quantity):
        """Create a lovelace amount."""
        return {"unit": "lovelace", "quantity": str(int(quantity))}

    @staticmethod
    def ada(ada_amount):
        """Create a lovelace amount from ADA (1 ADA = 1,000,000 lovelace)."""
        return {"unit": "lovelace", "quantity": str(int(ada_amount * 1_000_000))}

    @staticmethod
    def asset(unit, quantity):
        """Create a native asset amount. Unit = policyId + hex asset name."""
        return {"unit": unit, "quantity": str(int(quantity))}


class TxBuilder:
    """Builder for QuickTx transaction specifications.

    Builds a JSON spec that is sent to ccl_quicktx_build for automatic
    coin selection, fee calculation, and change balancing.
    """

    def __init__(self, bridge):
        self._bridge = bridge
        self._operations = []
        self._from = None
        self._change_address = None
        self._fee_payer = None
        self._utxos = None
        self._protocol_params = None
        self._validity = {}
        self._merge_outputs = None
        self._signer_count = 1

    def pay_to_address(self, address, *amounts):
        """Add a payment to an address.

        Args:
            address: Bech32 destination address
            *amounts: One or more Amount dicts (from Amount.ada(), Amount.lovelace(), etc.)
        """
        amount_list = list(amounts)
        self._operations.append({
            "type": "pay_to_address",
            "address": address,
            "amounts": amount_list,
        })
        return self

    def pay_to_contract(self, address, amounts, datum_cbor_hex=None, datum_hash=None):
        """Add a payment to a contract address with datum.

        Args:
            address: Contract address
            amounts: List of Amount dicts
            datum_cbor_hex: Inline datum as CBOR hex
            datum_hash: Datum hash hex
        """
        op = {
            "type": "pay_to_contract",
            "address": address,
            "amounts": amounts if isinstance(amounts, list) else [amounts],
        }
        if datum_cbor_hex:
            op["datum_cbor_hex"] = datum_cbor_hex
        if datum_hash:
            op["datum_hash"] = datum_hash
        self._operations.append(op)
        return self

    def mint_assets(self, script_json, assets, receiver):
        """Mint native assets.

        Args:
            script_json: Native script JSON string
            assets: List of {"name": "...", "quantity": "..."} dicts
            receiver: Address to receive minted assets
        """
        self._operations.append({
            "type": "mint_assets",
            "script_json": script_json if isinstance(script_json, str) else json.dumps(script_json),
            "assets": assets,
            "receiver": receiver,
        })
        return self

    def attach_metadata(self, label, metadata):
        """Attach metadata to the transaction.

        Args:
            label: Integer metadata label (e.g. 674 for CIP-20)
            metadata: Metadata value (string, number, list, or dict)
        """
        self._operations.append({
            "type": "attach_metadata",
            "label": label,
            "metadata": metadata,
        })
        return self

    def collect_from(self, utxos):
        """Specify explicit UTXOs as transaction inputs (bypasses coin selection).

        Args:
            utxos: List of UTXO dicts with tx_hash, output_index, address, amount
        """
        self._operations.append({
            "type": "collect_from",
            "collect_utxos": utxos,
        })
        return self

    # Staking

    def register_stake_address(self, address):
        """Register a stake address."""
        self._operations.append({"type": "register_stake_address", "address": address})
        return self

    def deregister_stake_address(self, address, refund_address=None):
        """Deregister a stake address."""
        op = {"type": "deregister_stake_address", "address": address}
        if refund_address:
            op["refund_address"] = refund_address
        self._operations.append(op)
        return self

    def delegate_to(self, address, pool_id):
        """Delegate stake to a pool."""
        self._operations.append({"type": "delegate_to", "address": address, "pool_id": pool_id})
        return self

    def withdraw(self, reward_address, amount, receiver=None):
        """Withdraw staking rewards."""
        op = {"type": "withdraw", "reward_address": reward_address, "amount": str(amount)}
        if receiver:
            op["receiver"] = receiver
        self._operations.append(op)
        return self

    # DRep

    def register_drep(self, credential_hash, credential_type='key', anchor_url=None, anchor_data_hash=None):
        """Register a DRep."""
        op = {"type": "register_drep", "credential_hash": credential_hash, "credential_type": credential_type}
        if anchor_url:
            op["anchor_url"] = anchor_url
        if anchor_data_hash:
            op["anchor_data_hash"] = anchor_data_hash
        self._operations.append(op)
        return self

    def unregister_drep(self, credential_hash, credential_type='key', refund_address=None):
        """Unregister a DRep."""
        op = {"type": "unregister_drep", "credential_hash": credential_hash, "credential_type": credential_type}
        if refund_address:
            op["refund_address"] = refund_address
        self._operations.append(op)
        return self

    def update_drep(self, credential_hash, credential_type='key', anchor_url=None, anchor_data_hash=None):
        """Update DRep metadata."""
        op = {"type": "update_drep", "credential_hash": credential_hash, "credential_type": credential_type}
        if anchor_url:
            op["anchor_url"] = anchor_url
        if anchor_data_hash:
            op["anchor_data_hash"] = anchor_data_hash
        self._operations.append(op)
        return self

    # Voting

    def delegate_voting_power_to(self, address, drep_type, drep_hash=None):
        """Delegate voting power to a DRep."""
        op = {"type": "delegate_voting_power_to", "address": address, "drep_type": drep_type}
        if drep_hash:
            op["drep_hash"] = drep_hash
        self._operations.append(op)
        return self

    def create_vote(self, voter_type, voter_hash, gov_action_tx_hash, gov_action_index, vote,
                    anchor_url=None, anchor_data_hash=None):
        """Cast a governance vote."""
        op = {
            "type": "create_vote", "voter_type": voter_type, "voter_hash": voter_hash,
            "gov_action_tx_hash": gov_action_tx_hash, "gov_action_index": gov_action_index, "vote": vote,
        }
        if anchor_url:
            op["anchor_url"] = anchor_url
        if anchor_data_hash:
            op["anchor_data_hash"] = anchor_data_hash
        self._operations.append(op)
        return self

    # Governance

    def create_proposal(self, gov_action_type, return_address, anchor_url, anchor_data_hash, **kwargs):
        """Create a governance proposal."""
        op = {
            "type": "create_proposal", "gov_action_type": gov_action_type,
            "return_address": return_address, "anchor_url": anchor_url, "anchor_data_hash": anchor_data_hash,
        }
        if "withdrawals" in kwargs:
            op["withdrawals"] = kwargs["withdrawals"]
        self._operations.append(op)
        return self

    def from_address(self, address):
        """Set the sender address."""
        self._from = address
        return self

    def change_address(self, address):
        """Set the change address (defaults to sender)."""
        self._change_address = address
        return self

    def fee_payer(self, address):
        """Set the fee payer address."""
        self._fee_payer = address
        return self

    def with_utxos(self, utxos):
        """Provide UTXOs for coin selection.

        Args:
            utxos: List of UTXO dicts (Blockfrost/Koios/DevKit format)
        """
        self._utxos = utxos
        return self

    def with_protocol_params(self, params):
        """Provide protocol parameters.

        Args:
            params: Protocol params dict (Blockfrost/Koios/DevKit format)
        """
        self._protocol_params = params
        return self

    def valid_from(self, slot):
        """Set transaction validity start slot."""
        self._validity["valid_from"] = slot
        return self

    def valid_to(self, slot):
        """Set transaction validity end slot (TTL)."""
        self._validity["valid_to"] = slot
        return self

    def merge_outputs(self, merge):
        """Whether to merge outputs to the same address (default: True)."""
        self._merge_outputs = merge
        return self

    def signer_count(self, count):
        """Set the number of signers for fee estimation (default: 1)."""
        self._signer_count = count
        return self

    def build(self, provider=None, provider_config=None):
        """Build the transaction. Returns dict with tx_cbor, tx_hash, fee.

        Args:
            provider: Optional Provider instance for auto-fetching UTXOs
                and protocol params (wrapper-side).
            provider_config: Optional dict with 'name', 'url', and optionally
                'api_key' for Java-side lazy UTXO fetching via HTTP.

        Raises:
            ValueError: If both provider and provider_config are specified.
        """
        if provider and provider_config:
            raise ValueError("Cannot specify both 'provider' and 'provider_config'")

        utxos = self._utxos
        protocol_params = self._protocol_params

        if provider_config:
            spec = {
                "operations": self._operations,
                "from": self._from,
                "provider": provider_config,
                "signer_count": self._signer_count,
            }
            if protocol_params is not None:
                spec["protocol_params"] = protocol_params
        elif provider:
            if utxos is None and self._from:
                utxos = provider.get_utxos(self._from)
            if protocol_params is None:
                protocol_params = provider.get_protocol_params()
            spec = {
                "operations": self._operations,
                "from": self._from,
                "utxos": utxos,
                "protocol_params": protocol_params,
                "signer_count": self._signer_count,
            }
        else:
            spec = {
                "operations": self._operations,
                "from": self._from,
                "utxos": utxos,
                "protocol_params": protocol_params,
                "signer_count": self._signer_count,
            }

        if self._change_address:
            spec["change_address"] = self._change_address
        if self._fee_payer:
            spec["fee_payer"] = self._fee_payer
        if self._validity:
            spec["validity"] = self._validity
        if self._merge_outputs is not None:
            spec["merge_outputs"] = self._merge_outputs

        spec_json = json.dumps(spec)
        rc = self._bridge._lib.ccl_quicktx_build(
            self._bridge._thread, self._bridge._encode(spec_json))
        return json.loads(self._bridge._check(rc))


class Tx:
    """Lightweight operation collector for one transaction in a compose group.

    Use QuickTx.tx() to create, then chain operations and set sender address.
    """

    def __init__(self):
        self._operations = []
        self._from = None
        self._change_address = None

    def pay_to_address(self, address, *amounts):
        self._operations.append({
            "type": "pay_to_address",
            "address": address,
            "amounts": list(amounts),
        })
        return self

    def pay_to_contract(self, address, amounts, datum_cbor_hex=None, datum_hash=None):
        op = {
            "type": "pay_to_contract",
            "address": address,
            "amounts": amounts if isinstance(amounts, list) else [amounts],
        }
        if datum_cbor_hex:
            op["datum_cbor_hex"] = datum_cbor_hex
        if datum_hash:
            op["datum_hash"] = datum_hash
        self._operations.append(op)
        return self

    def mint_assets(self, script_json, assets, receiver):
        self._operations.append({
            "type": "mint_assets",
            "script_json": script_json if isinstance(script_json, str) else json.dumps(script_json),
            "assets": assets,
            "receiver": receiver,
        })
        return self

    def attach_metadata(self, label, metadata):
        self._operations.append({
            "type": "attach_metadata",
            "label": label,
            "metadata": metadata,
        })
        return self

    def collect_from(self, utxos):
        self._operations.append({
            "type": "collect_from",
            "collect_utxos": utxos,
        })
        return self

    # Staking

    def register_stake_address(self, address):
        self._operations.append({"type": "register_stake_address", "address": address})
        return self

    def deregister_stake_address(self, address, refund_address=None):
        op = {"type": "deregister_stake_address", "address": address}
        if refund_address:
            op["refund_address"] = refund_address
        self._operations.append(op)
        return self

    def delegate_to(self, address, pool_id):
        self._operations.append({"type": "delegate_to", "address": address, "pool_id": pool_id})
        return self

    def withdraw(self, reward_address, amount, receiver=None):
        op = {"type": "withdraw", "reward_address": reward_address, "amount": str(amount)}
        if receiver:
            op["receiver"] = receiver
        self._operations.append(op)
        return self

    # DRep

    def register_drep(self, credential_hash, credential_type='key', anchor_url=None, anchor_data_hash=None):
        op = {"type": "register_drep", "credential_hash": credential_hash, "credential_type": credential_type}
        if anchor_url:
            op["anchor_url"] = anchor_url
        if anchor_data_hash:
            op["anchor_data_hash"] = anchor_data_hash
        self._operations.append(op)
        return self

    def unregister_drep(self, credential_hash, credential_type='key', refund_address=None):
        op = {"type": "unregister_drep", "credential_hash": credential_hash, "credential_type": credential_type}
        if refund_address:
            op["refund_address"] = refund_address
        self._operations.append(op)
        return self

    def update_drep(self, credential_hash, credential_type='key', anchor_url=None, anchor_data_hash=None):
        op = {"type": "update_drep", "credential_hash": credential_hash, "credential_type": credential_type}
        if anchor_url:
            op["anchor_url"] = anchor_url
        if anchor_data_hash:
            op["anchor_data_hash"] = anchor_data_hash
        self._operations.append(op)
        return self

    # Voting

    def delegate_voting_power_to(self, address, drep_type, drep_hash=None):
        op = {"type": "delegate_voting_power_to", "address": address, "drep_type": drep_type}
        if drep_hash:
            op["drep_hash"] = drep_hash
        self._operations.append(op)
        return self

    def create_vote(self, voter_type, voter_hash, gov_action_tx_hash, gov_action_index, vote,
                    anchor_url=None, anchor_data_hash=None):
        op = {
            "type": "create_vote", "voter_type": voter_type, "voter_hash": voter_hash,
            "gov_action_tx_hash": gov_action_tx_hash, "gov_action_index": gov_action_index, "vote": vote,
        }
        if anchor_url:
            op["anchor_url"] = anchor_url
        if anchor_data_hash:
            op["anchor_data_hash"] = anchor_data_hash
        self._operations.append(op)
        return self

    # Governance

    def create_proposal(self, gov_action_type, return_address, anchor_url, anchor_data_hash, **kwargs):
        op = {
            "type": "create_proposal", "gov_action_type": gov_action_type,
            "return_address": return_address, "anchor_url": anchor_url, "anchor_data_hash": anchor_data_hash,
        }
        if "withdrawals" in kwargs:
            op["withdrawals"] = kwargs["withdrawals"]
        self._operations.append(op)
        return self

    def from_address(self, address):
        self._from = address
        return self

    def change_address(self, address):
        self._change_address = address
        return self

    def _to_spec(self):
        spec = {
            "from": self._from,
            "operations": self._operations,
        }
        if self._change_address:
            spec["change_address"] = self._change_address
        return spec


class ComposeTxBuilder:
    """Builder for composing multiple Tx objects into a single transaction."""

    def __init__(self, bridge, txs):
        self._bridge = bridge
        self._txs = list(txs)
        self._fee_payer = None
        self._utxos = None
        self._protocol_params = None
        self._validity = {}
        self._merge_outputs = None
        self._signer_count = None

    def fee_payer(self, address):
        self._fee_payer = address
        return self

    def with_utxos(self, utxos):
        self._utxos = utxos
        return self

    def with_protocol_params(self, params):
        self._protocol_params = params
        return self

    def valid_from(self, slot):
        self._validity["valid_from"] = slot
        return self

    def valid_to(self, slot):
        self._validity["valid_to"] = slot
        return self

    def merge_outputs(self, merge):
        self._merge_outputs = merge
        return self

    def signer_count(self, count):
        self._signer_count = count
        return self

    def build(self, provider=None, provider_config=None):
        """Build the composed transaction.

        Args:
            provider: Optional Provider instance for auto-fetching UTXOs
                and protocol params (wrapper-side).
            provider_config: Optional dict with 'name', 'url', and optionally
                'api_key' for Java-side lazy UTXO fetching via HTTP.

        Raises:
            ValueError: If both provider and provider_config are specified.
        """
        if provider and provider_config:
            raise ValueError("Cannot specify both 'provider' and 'provider_config'")

        utxos = self._utxos
        protocol_params = self._protocol_params

        if provider_config:
            spec = {
                "transactions": [tx._to_spec() for tx in self._txs],
                "fee_payer": self._fee_payer,
                "provider": provider_config,
            }
            if protocol_params is not None:
                spec["protocol_params"] = protocol_params
        elif provider:
            if utxos is None:
                addresses = set()
                for tx in self._txs:
                    if tx._from:
                        addresses.add(tx._from)
                all_utxos = []
                for addr in addresses:
                    all_utxos.extend(provider.get_utxos(addr))
                utxos = all_utxos
            if protocol_params is None:
                protocol_params = provider.get_protocol_params()
            spec = {
                "transactions": [tx._to_spec() for tx in self._txs],
                "fee_payer": self._fee_payer,
                "utxos": utxos,
                "protocol_params": protocol_params,
            }
        else:
            spec = {
                "transactions": [tx._to_spec() for tx in self._txs],
                "fee_payer": self._fee_payer,
                "utxos": utxos,
                "protocol_params": protocol_params,
            }

        if self._signer_count is not None:
            spec["signer_count"] = self._signer_count
        if self._validity:
            spec["validity"] = self._validity
        if self._merge_outputs is not None:
            spec["merge_outputs"] = self._merge_outputs

        spec_json = json.dumps(spec)
        rc = self._bridge._lib.ccl_quicktx_build(
            self._bridge._thread, self._bridge._encode(spec_json))
        return json.loads(self._bridge._check(rc))


class QuickTx:
    """QuickTx namespace for CCL bridge."""

    def __init__(self, bridge):
        self._bridge = bridge

    def new_tx(self):
        """Create a new TxBuilder."""
        return TxBuilder(self._bridge)

    def tx(self):
        """Create a new Tx for use with compose()."""
        return Tx()

    def compose(self, *txs):
        """Compose multiple Tx objects into a single transaction.

        Args:
            *txs: Tx objects to compose
        """
        return ComposeTxBuilder(self._bridge, txs)
