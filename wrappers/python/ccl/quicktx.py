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


class ProviderConfig:
    """Configuration for Java-side provider (lazy UTXO fetching via HTTP)."""

    def __init__(self, name, url, api_key=None, enable_cost_evaluation=None):
        self.name = name
        self.url = url
        self.api_key = api_key
        self.enable_cost_evaluation = enable_cost_evaluation

    def to_dict(self):
        d = {"name": self.name, "url": self.url}
        if self.api_key:
            d["api_key"] = self.api_key
        if self.enable_cost_evaluation is not None:
            d["enable_cost_evaluation"] = self.enable_cost_evaluation
        return d


def _build_provider_dict(provider_config):
    """Convert a provider_config (dict or ProviderConfig) to a spec dict."""
    if isinstance(provider_config, ProviderConfig):
        return provider_config.to_dict()
    # Assume it's already a dict; copy and add enable_cost_evaluation if present
    d = {"name": provider_config["name"], "url": provider_config["url"]}
    if provider_config.get("api_key"):
        d["api_key"] = provider_config["api_key"]
    if provider_config.get("enable_cost_evaluation") is not None:
        d["enable_cost_evaluation"] = provider_config["enable_cost_evaluation"]
    return d


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

    def pay_to_address(self, address, *amounts, script_ref_cbor_hex=None, script_ref_type=None):
        """Add a payment to an address.

        Args:
            address: Bech32 destination address
            *amounts: One or more Amount dicts (from Amount.ada(), Amount.lovelace(), etc.)
            script_ref_cbor_hex: Optional reference script CBOR hex to attach to output
            script_ref_type: Script type for ref script ('plutus_v1', 'plutus_v2', 'plutus_v3')
        """
        amount_list = list(amounts)
        op = {
            "type": "pay_to_address",
            "address": address,
            "amounts": amount_list,
        }
        if script_ref_cbor_hex:
            op["script_ref_cbor_hex"] = script_ref_cbor_hex
        if script_ref_type:
            op["script_ref_type"] = script_ref_type
        self._operations.append(op)
        return self

    def pay_to_contract(self, address, amounts, datum_cbor_hex=None, datum_hash=None,
                        script_ref_cbor_hex=None, script_ref_type=None):
        """Add a payment to a contract address with datum.

        Args:
            address: Contract address
            amounts: List of Amount dicts
            datum_cbor_hex: Inline datum as CBOR hex
            datum_hash: Datum hash hex
            script_ref_cbor_hex: Optional reference script CBOR hex
            script_ref_type: Script type for ref script
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
        if script_ref_cbor_hex:
            op["script_ref_cbor_hex"] = script_ref_cbor_hex
        if script_ref_type:
            op["script_ref_type"] = script_ref_type
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

    def unregister_drep(self, credential_hash, credential_type='key', refund_address=None, refund_amount=None):
        """Unregister a DRep."""
        op = {"type": "unregister_drep", "credential_hash": credential_hash, "credential_type": credential_type}
        if refund_address:
            op["refund_address"] = refund_address
        if refund_amount is not None:
            op["refund_amount"] = str(refund_amount)
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
        """Create a governance proposal.

        Supported gov_action_types: info_action, treasury_withdrawals, no_confidence,
        update_committee, new_constitution, hard_fork_initiation, parameter_change.
        """
        op = {
            "type": "create_proposal", "gov_action_type": gov_action_type,
            "return_address": return_address, "anchor_url": anchor_url, "anchor_data_hash": anchor_data_hash,
        }
        if "withdrawals" in kwargs:
            op["withdrawals"] = kwargs["withdrawals"]
        # Previous governance action reference
        if "gov_action_tx_hash" in kwargs:
            op["gov_action_tx_hash"] = kwargs["gov_action_tx_hash"]
        if "gov_action_index" in kwargs:
            op["gov_action_index"] = kwargs["gov_action_index"]
        # update_committee fields
        if "members_to_remove" in kwargs:
            op["members_to_remove"] = kwargs["members_to_remove"]
        if "new_members" in kwargs:
            op["new_members"] = kwargs["new_members"]
        if "quorum_numerator" in kwargs:
            op["quorum_numerator"] = str(kwargs["quorum_numerator"])
        if "quorum_denominator" in kwargs:
            op["quorum_denominator"] = str(kwargs["quorum_denominator"])
        # new_constitution fields
        if "constitution_anchor_url" in kwargs:
            op["constitution_anchor_url"] = kwargs["constitution_anchor_url"]
        if "constitution_anchor_data_hash" in kwargs:
            op["constitution_anchor_data_hash"] = kwargs["constitution_anchor_data_hash"]
        if "constitution_script_hash" in kwargs:
            op["constitution_script_hash"] = kwargs["constitution_script_hash"]
        # hard_fork_initiation fields
        if "protocol_version_major" in kwargs:
            op["protocol_version_major"] = kwargs["protocol_version_major"]
        if "protocol_version_minor" in kwargs:
            op["protocol_version_minor"] = kwargs["protocol_version_minor"]
        # parameter_change fields
        if "policy_hash" in kwargs:
            op["policy_hash"] = kwargs["policy_hash"]
        self._operations.append(op)
        return self

    # Pool operations

    def register_pool(self, operator, vrf_key_hash, pledge, cost, margin_numerator, margin_denominator,
                      reward_address, pool_owners, relays=None, pool_metadata_url=None, pool_metadata_hash=None):
        """Register a staking pool."""
        op = {
            "type": "register_pool", "operator": operator, "vrf_key_hash": vrf_key_hash,
            "pledge": str(pledge), "cost": str(cost),
            "margin_numerator": str(margin_numerator), "margin_denominator": str(margin_denominator),
            "reward_address": reward_address, "pool_owners": pool_owners,
        }
        if relays:
            op["relays"] = relays
        if pool_metadata_url:
            op["pool_metadata_url"] = pool_metadata_url
        if pool_metadata_hash:
            op["pool_metadata_hash"] = pool_metadata_hash
        self._operations.append(op)
        return self

    def update_pool(self, operator, vrf_key_hash, pledge, cost, margin_numerator, margin_denominator,
                    reward_address, pool_owners, relays=None, pool_metadata_url=None, pool_metadata_hash=None):
        """Update a staking pool."""
        op = {
            "type": "update_pool", "operator": operator, "vrf_key_hash": vrf_key_hash,
            "pledge": str(pledge), "cost": str(cost),
            "margin_numerator": str(margin_numerator), "margin_denominator": str(margin_denominator),
            "reward_address": reward_address, "pool_owners": pool_owners,
        }
        if relays:
            op["relays"] = relays
        if pool_metadata_url:
            op["pool_metadata_url"] = pool_metadata_url
        if pool_metadata_hash:
            op["pool_metadata_hash"] = pool_metadata_hash
        self._operations.append(op)
        return self

    def retire_pool(self, pool_id, epoch):
        """Retire a staking pool."""
        self._operations.append({"type": "retire_pool", "pool_id": pool_id, "epoch": epoch})
        return self

    # Treasury donation

    def donate_to_treasury(self, treasury_value, donation_amount):
        """Donate ADA to the treasury."""
        self._operations.append({
            "type": "donate_to_treasury",
            "treasury_value": str(treasury_value),
            "donation_amount": str(donation_amount),
        })
        return self

    # Native script attachment

    def attach_native_script(self, script_json):
        """Attach a native script to the transaction witness set."""
        self._operations.append({
            "type": "attach_native_script",
            "script_json": script_json if isinstance(script_json, str) else json.dumps(script_json),
        })
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
            provider_config: Optional ProviderConfig or dict with 'name', 'url',
                and optionally 'api_key' and 'enable_cost_evaluation' for
                Java-side lazy UTXO fetching via HTTP.

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
                "provider": _build_provider_dict(provider_config),
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

    def pay_to_address(self, address, *amounts, script_ref_cbor_hex=None, script_ref_type=None):
        op = {
            "type": "pay_to_address",
            "address": address,
            "amounts": list(amounts),
        }
        if script_ref_cbor_hex:
            op["script_ref_cbor_hex"] = script_ref_cbor_hex
        if script_ref_type:
            op["script_ref_type"] = script_ref_type
        self._operations.append(op)
        return self

    def pay_to_contract(self, address, amounts, datum_cbor_hex=None, datum_hash=None,
                        script_ref_cbor_hex=None, script_ref_type=None):
        op = {
            "type": "pay_to_contract",
            "address": address,
            "amounts": amounts if isinstance(amounts, list) else [amounts],
        }
        if datum_cbor_hex:
            op["datum_cbor_hex"] = datum_cbor_hex
        if datum_hash:
            op["datum_hash"] = datum_hash
        if script_ref_cbor_hex:
            op["script_ref_cbor_hex"] = script_ref_cbor_hex
        if script_ref_type:
            op["script_ref_type"] = script_ref_type
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

    def unregister_drep(self, credential_hash, credential_type='key', refund_address=None, refund_amount=None):
        op = {"type": "unregister_drep", "credential_hash": credential_hash, "credential_type": credential_type}
        if refund_address:
            op["refund_address"] = refund_address
        if refund_amount is not None:
            op["refund_amount"] = str(refund_amount)
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
        if "gov_action_tx_hash" in kwargs:
            op["gov_action_tx_hash"] = kwargs["gov_action_tx_hash"]
        if "gov_action_index" in kwargs:
            op["gov_action_index"] = kwargs["gov_action_index"]
        if "members_to_remove" in kwargs:
            op["members_to_remove"] = kwargs["members_to_remove"]
        if "new_members" in kwargs:
            op["new_members"] = kwargs["new_members"]
        if "quorum_numerator" in kwargs:
            op["quorum_numerator"] = str(kwargs["quorum_numerator"])
        if "quorum_denominator" in kwargs:
            op["quorum_denominator"] = str(kwargs["quorum_denominator"])
        if "constitution_anchor_url" in kwargs:
            op["constitution_anchor_url"] = kwargs["constitution_anchor_url"]
        if "constitution_anchor_data_hash" in kwargs:
            op["constitution_anchor_data_hash"] = kwargs["constitution_anchor_data_hash"]
        if "constitution_script_hash" in kwargs:
            op["constitution_script_hash"] = kwargs["constitution_script_hash"]
        if "protocol_version_major" in kwargs:
            op["protocol_version_major"] = kwargs["protocol_version_major"]
        if "protocol_version_minor" in kwargs:
            op["protocol_version_minor"] = kwargs["protocol_version_minor"]
        if "policy_hash" in kwargs:
            op["policy_hash"] = kwargs["policy_hash"]
        self._operations.append(op)
        return self

    # Pool operations

    def register_pool(self, operator, vrf_key_hash, pledge, cost, margin_numerator, margin_denominator,
                      reward_address, pool_owners, relays=None, pool_metadata_url=None, pool_metadata_hash=None):
        op = {
            "type": "register_pool", "operator": operator, "vrf_key_hash": vrf_key_hash,
            "pledge": str(pledge), "cost": str(cost),
            "margin_numerator": str(margin_numerator), "margin_denominator": str(margin_denominator),
            "reward_address": reward_address, "pool_owners": pool_owners,
        }
        if relays:
            op["relays"] = relays
        if pool_metadata_url:
            op["pool_metadata_url"] = pool_metadata_url
        if pool_metadata_hash:
            op["pool_metadata_hash"] = pool_metadata_hash
        self._operations.append(op)
        return self

    def update_pool(self, operator, vrf_key_hash, pledge, cost, margin_numerator, margin_denominator,
                    reward_address, pool_owners, relays=None, pool_metadata_url=None, pool_metadata_hash=None):
        op = {
            "type": "update_pool", "operator": operator, "vrf_key_hash": vrf_key_hash,
            "pledge": str(pledge), "cost": str(cost),
            "margin_numerator": str(margin_numerator), "margin_denominator": str(margin_denominator),
            "reward_address": reward_address, "pool_owners": pool_owners,
        }
        if relays:
            op["relays"] = relays
        if pool_metadata_url:
            op["pool_metadata_url"] = pool_metadata_url
        if pool_metadata_hash:
            op["pool_metadata_hash"] = pool_metadata_hash
        self._operations.append(op)
        return self

    def retire_pool(self, pool_id, epoch):
        self._operations.append({"type": "retire_pool", "pool_id": pool_id, "epoch": epoch})
        return self

    # Treasury donation

    def donate_to_treasury(self, treasury_value, donation_amount):
        self._operations.append({
            "type": "donate_to_treasury",
            "treasury_value": str(treasury_value),
            "donation_amount": str(donation_amount),
        })
        return self

    # Native script attachment

    def attach_native_script(self, script_json):
        self._operations.append({
            "type": "attach_native_script",
            "script_json": script_json if isinstance(script_json, str) else json.dumps(script_json),
        })
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


class ScriptTxBuilder:
    """Builder for script (Plutus) transaction specifications.

    Like TxBuilder but produces a spec with tx_type: "script_tx".
    Supports script-specific operations such as collect_from with redeemer,
    read_from reference inputs, mint_plutus_assets, and attach validators.
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
        self._change_datum_cbor_hex = None
        self._change_datum_hash = None

    # --- Common operations (same as TxBuilder) ---

    def pay_to_address(self, address, *amounts, script_ref_cbor_hex=None, script_ref_type=None):
        """Add a payment to an address."""
        op = {
            "type": "pay_to_address",
            "address": address,
            "amounts": list(amounts),
        }
        if script_ref_cbor_hex:
            op["script_ref_cbor_hex"] = script_ref_cbor_hex
        if script_ref_type:
            op["script_ref_type"] = script_ref_type
        self._operations.append(op)
        return self

    def pay_to_contract(self, address, amounts, datum_cbor_hex=None, datum_hash=None,
                        script_ref_cbor_hex=None, script_ref_type=None):
        """Add a payment to a contract address with datum."""
        op = {
            "type": "pay_to_contract",
            "address": address,
            "amounts": amounts if isinstance(amounts, list) else [amounts],
        }
        if datum_cbor_hex:
            op["datum_cbor_hex"] = datum_cbor_hex
        if datum_hash:
            op["datum_hash"] = datum_hash
        if script_ref_cbor_hex:
            op["script_ref_cbor_hex"] = script_ref_cbor_hex
        if script_ref_type:
            op["script_ref_type"] = script_ref_type
        self._operations.append(op)
        return self

    def attach_metadata(self, label, metadata):
        """Attach metadata to the transaction."""
        self._operations.append({
            "type": "attach_metadata",
            "label": label,
            "metadata": metadata,
        })
        return self

    def collect_from(self, utxos):
        """Specify explicit UTXOs as transaction inputs (without redeemer)."""
        self._operations.append({
            "type": "collect_from",
            "collect_utxos": utxos,
        })
        return self

    # --- Script-specific operations ---

    def collect_from_script(self, utxos, redeemer_cbor_hex, datum_cbor_hex=None):
        """Collect UTXOs from a script address with redeemer and optional datum.

        Args:
            utxos: List of UTXO dicts with tx_hash, output_index, address, amount
            redeemer_cbor_hex: Redeemer CBOR hex string
            datum_cbor_hex: Optional datum CBOR hex string
        """
        op = {
            "type": "collect_from",
            "collect_utxos": utxos,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if datum_cbor_hex:
            op["datum_cbor_hex"] = datum_cbor_hex
        self._operations.append(op)
        return self

    def read_from(self, reference_inputs):
        """Add reference inputs to the transaction.

        Args:
            reference_inputs: List of dicts with 'tx_hash' and 'output_index'
        """
        self._operations.append({
            "type": "read_from",
            "reference_inputs": reference_inputs,
        })
        return self

    def mint_plutus_assets(self, script_cbor_hex, script_type, assets, redeemer_cbor_hex,
                           receiver=None, output_datum_cbor_hex=None):
        """Mint assets using a Plutus script.

        Args:
            script_cbor_hex: Plutus script CBOR hex
            script_type: Script type ('plutus_v1', 'plutus_v2', 'plutus_v3')
            assets: List of {"name": "...", "quantity": "..."} dicts
            redeemer_cbor_hex: Redeemer CBOR hex string
            receiver: Optional receiver address for minted assets
            output_datum_cbor_hex: Optional output datum CBOR hex
        """
        op = {
            "type": "mint_plutus_assets",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
            "assets": assets,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if receiver:
            op["receiver"] = receiver
        if output_datum_cbor_hex:
            op["output_datum_cbor_hex"] = output_datum_cbor_hex
        self._operations.append(op)
        return self

    def attach_spending_validator(self, script_cbor_hex, script_type):
        """Attach a spending validator script."""
        self._operations.append({
            "type": "attach_spending_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        })
        return self

    def attach_certificate_validator(self, script_cbor_hex, script_type):
        """Attach a certificate validator script."""
        self._operations.append({
            "type": "attach_certificate_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        })
        return self

    def attach_reward_validator(self, script_cbor_hex, script_type):
        """Attach a reward validator script."""
        self._operations.append({
            "type": "attach_reward_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        })
        return self

    def attach_proposing_validator(self, script_cbor_hex, script_type):
        """Attach a proposing validator script."""
        self._operations.append({
            "type": "attach_proposing_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        })
        return self

    def attach_voting_validator(self, script_cbor_hex, script_type):
        """Attach a voting validator script."""
        self._operations.append({
            "type": "attach_voting_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        })
        return self

    # --- Staking (with redeemer) ---

    def deregister_stake_address(self, address, redeemer_cbor_hex, refund_address=None):
        """Deregister a stake address with redeemer."""
        op = {
            "type": "deregister_stake_address",
            "address": address,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if refund_address:
            op["refund_address"] = refund_address
        self._operations.append(op)
        return self

    def delegate_to(self, address, pool_id, redeemer_cbor_hex):
        """Delegate stake to a pool with redeemer."""
        self._operations.append({
            "type": "delegate_to",
            "address": address,
            "pool_id": pool_id,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        })
        return self

    def withdraw(self, reward_address, amount, redeemer_cbor_hex, receiver=None):
        """Withdraw staking rewards with redeemer."""
        op = {
            "type": "withdraw",
            "reward_address": reward_address,
            "amount": str(amount),
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if receiver:
            op["receiver"] = receiver
        self._operations.append(op)
        return self

    # --- DRep (with redeemer) ---

    def register_drep(self, credential_hash, credential_type, redeemer_cbor_hex,
                      anchor_url=None, anchor_data_hash=None):
        """Register a DRep with redeemer."""
        op = {
            "type": "register_drep",
            "credential_hash": credential_hash,
            "credential_type": credential_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if anchor_url:
            op["anchor_url"] = anchor_url
        if anchor_data_hash:
            op["anchor_data_hash"] = anchor_data_hash
        self._operations.append(op)
        return self

    def unregister_drep(self, credential_hash, credential_type, redeemer_cbor_hex,
                        refund_address=None, refund_amount=None):
        """Unregister a DRep with redeemer."""
        op = {
            "type": "unregister_drep",
            "credential_hash": credential_hash,
            "credential_type": credential_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if refund_address:
            op["refund_address"] = refund_address
        if refund_amount is not None:
            op["refund_amount"] = str(refund_amount)
        self._operations.append(op)
        return self

    def update_drep(self, credential_hash, credential_type, redeemer_cbor_hex,
                    anchor_url=None, anchor_data_hash=None):
        """Update DRep metadata with redeemer."""
        op = {
            "type": "update_drep",
            "credential_hash": credential_hash,
            "credential_type": credential_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if anchor_url:
            op["anchor_url"] = anchor_url
        if anchor_data_hash:
            op["anchor_data_hash"] = anchor_data_hash
        self._operations.append(op)
        return self

    # --- Voting (with redeemer) ---

    def delegate_voting_power_to(self, address, drep_type, drep_hash, redeemer_cbor_hex):
        """Delegate voting power to a DRep with redeemer."""
        op = {
            "type": "delegate_voting_power_to",
            "address": address,
            "drep_type": drep_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if drep_hash:
            op["drep_hash"] = drep_hash
        self._operations.append(op)
        return self

    def create_vote(self, voter_type, voter_hash, gov_action_tx_hash, gov_action_index, vote,
                    redeemer_cbor_hex, anchor_url=None, anchor_data_hash=None):
        """Cast a governance vote with redeemer."""
        op = {
            "type": "create_vote",
            "voter_type": voter_type,
            "voter_hash": voter_hash,
            "gov_action_tx_hash": gov_action_tx_hash,
            "gov_action_index": gov_action_index,
            "vote": vote,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if anchor_url:
            op["anchor_url"] = anchor_url
        if anchor_data_hash:
            op["anchor_data_hash"] = anchor_data_hash
        self._operations.append(op)
        return self

    # --- Governance (with redeemer) ---

    def create_proposal(self, gov_action_type, return_address, anchor_url, anchor_data_hash,
                        redeemer_cbor_hex, **kwargs):
        """Create a governance proposal with redeemer."""
        op = {
            "type": "create_proposal",
            "gov_action_type": gov_action_type,
            "return_address": return_address,
            "anchor_url": anchor_url,
            "anchor_data_hash": anchor_data_hash,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if "withdrawals" in kwargs:
            op["withdrawals"] = kwargs["withdrawals"]
        if "gov_action_tx_hash" in kwargs:
            op["gov_action_tx_hash"] = kwargs["gov_action_tx_hash"]
        if "gov_action_index" in kwargs:
            op["gov_action_index"] = kwargs["gov_action_index"]
        if "members_to_remove" in kwargs:
            op["members_to_remove"] = kwargs["members_to_remove"]
        if "new_members" in kwargs:
            op["new_members"] = kwargs["new_members"]
        if "quorum_numerator" in kwargs:
            op["quorum_numerator"] = str(kwargs["quorum_numerator"])
        if "quorum_denominator" in kwargs:
            op["quorum_denominator"] = str(kwargs["quorum_denominator"])
        if "constitution_anchor_url" in kwargs:
            op["constitution_anchor_url"] = kwargs["constitution_anchor_url"]
        if "constitution_anchor_data_hash" in kwargs:
            op["constitution_anchor_data_hash"] = kwargs["constitution_anchor_data_hash"]
        if "constitution_script_hash" in kwargs:
            op["constitution_script_hash"] = kwargs["constitution_script_hash"]
        if "protocol_version_major" in kwargs:
            op["protocol_version_major"] = kwargs["protocol_version_major"]
        if "protocol_version_minor" in kwargs:
            op["protocol_version_minor"] = kwargs["protocol_version_minor"]
        if "policy_hash" in kwargs:
            op["policy_hash"] = kwargs["policy_hash"]
        self._operations.append(op)
        return self

    # Treasury donation

    def donate_to_treasury(self, treasury_value, donation_amount, redeemer_cbor_hex):
        """Donate ADA to the treasury with redeemer."""
        self._operations.append({
            "type": "donate_to_treasury",
            "treasury_value": str(treasury_value),
            "donation_amount": str(donation_amount),
            "redeemer_cbor_hex": redeemer_cbor_hex,
        })
        return self

    # --- Builder configuration ---

    def from_address(self, address):
        """Set the sender address."""
        self._from = address
        return self

    def change_address(self, address):
        """Set the change address (defaults to sender)."""
        self._change_address = address
        return self

    def change_datum(self, datum_cbor_hex):
        """Set inline datum for the change output."""
        self._change_datum_cbor_hex = datum_cbor_hex
        return self

    def change_datum_hash(self, hash):
        """Set datum hash for the change output."""
        self._change_datum_hash = hash
        return self

    def fee_payer(self, address):
        """Set the fee payer address."""
        self._fee_payer = address
        return self

    def with_utxos(self, utxos):
        """Provide UTXOs for coin selection."""
        self._utxos = utxos
        return self

    def with_protocol_params(self, params):
        """Provide protocol parameters."""
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
        """Build the script transaction. Returns dict with tx_cbor, tx_hash, fee.

        Args:
            provider: Optional Provider instance for auto-fetching UTXOs
                and protocol params (wrapper-side).
            provider_config: Optional ProviderConfig or dict with 'name', 'url',
                and optionally 'api_key' and 'enable_cost_evaluation' for
                Java-side lazy UTXO fetching via HTTP.

        Raises:
            ValueError: If both provider and provider_config are specified.
        """
        if provider and provider_config:
            raise ValueError("Cannot specify both 'provider' and 'provider_config'")

        utxos = self._utxos
        protocol_params = self._protocol_params

        if provider_config:
            spec = {
                "tx_type": "script_tx",
                "operations": self._operations,
                "from": self._from,
                "provider": _build_provider_dict(provider_config),
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
                "tx_type": "script_tx",
                "operations": self._operations,
                "from": self._from,
                "utxos": utxos,
                "protocol_params": protocol_params,
                "signer_count": self._signer_count,
            }
        else:
            spec = {
                "tx_type": "script_tx",
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
        if self._change_datum_cbor_hex:
            spec["change_datum_cbor_hex"] = self._change_datum_cbor_hex
        if self._change_datum_hash:
            spec["change_datum_hash"] = self._change_datum_hash

        spec_json = json.dumps(spec)
        rc = self._bridge._lib.ccl_quicktx_build(
            self._bridge._thread, self._bridge._encode(spec_json))
        return json.loads(self._bridge._check(rc))

    def build_with_provider(self, provider):
        """Build with a Provider that auto-fetches UTXOs and protocol params.

        Args:
            provider: Provider instance (e.g. YaciDevKitProvider)
        """
        if self._utxos is None and self._from:
            self._utxos = provider.get_utxos(self._from)
        if self._protocol_params is None:
            self._protocol_params = provider.get_protocol_params()
        return self.build()


class ScriptTx:
    """Lightweight operation collector for a script transaction in a compose group.

    Like Tx but with tx_type: "script_tx" in _to_spec(). Supports script-specific
    operations such as collect_from with redeemer, read_from, mint_plutus_assets,
    and attach validators.
    """

    def __init__(self):
        self._operations = []
        self._from = None
        self._change_address = None
        self._change_datum_cbor_hex = None
        self._change_datum_hash = None

    # --- Common operations ---

    def pay_to_address(self, address, *amounts, script_ref_cbor_hex=None, script_ref_type=None):
        op = {
            "type": "pay_to_address",
            "address": address,
            "amounts": list(amounts),
        }
        if script_ref_cbor_hex:
            op["script_ref_cbor_hex"] = script_ref_cbor_hex
        if script_ref_type:
            op["script_ref_type"] = script_ref_type
        self._operations.append(op)
        return self

    def pay_to_contract(self, address, amounts, datum_cbor_hex=None, datum_hash=None,
                        script_ref_cbor_hex=None, script_ref_type=None):
        op = {
            "type": "pay_to_contract",
            "address": address,
            "amounts": amounts if isinstance(amounts, list) else [amounts],
        }
        if datum_cbor_hex:
            op["datum_cbor_hex"] = datum_cbor_hex
        if datum_hash:
            op["datum_hash"] = datum_hash
        if script_ref_cbor_hex:
            op["script_ref_cbor_hex"] = script_ref_cbor_hex
        if script_ref_type:
            op["script_ref_type"] = script_ref_type
        self._operations.append(op)
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

    # --- Script-specific operations ---

    def collect_from_script(self, utxos, redeemer_cbor_hex, datum_cbor_hex=None):
        """Collect UTXOs from a script address with redeemer and optional datum."""
        op = {
            "type": "collect_from",
            "collect_utxos": utxos,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if datum_cbor_hex:
            op["datum_cbor_hex"] = datum_cbor_hex
        self._operations.append(op)
        return self

    def read_from(self, reference_inputs):
        """Add reference inputs to the transaction."""
        self._operations.append({
            "type": "read_from",
            "reference_inputs": reference_inputs,
        })
        return self

    def mint_plutus_assets(self, script_cbor_hex, script_type, assets, redeemer_cbor_hex,
                           receiver=None, output_datum_cbor_hex=None):
        """Mint assets using a Plutus script."""
        op = {
            "type": "mint_plutus_assets",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
            "assets": assets,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if receiver:
            op["receiver"] = receiver
        if output_datum_cbor_hex:
            op["output_datum_cbor_hex"] = output_datum_cbor_hex
        self._operations.append(op)
        return self

    def attach_spending_validator(self, script_cbor_hex, script_type):
        self._operations.append({
            "type": "attach_spending_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        })
        return self

    def attach_certificate_validator(self, script_cbor_hex, script_type):
        self._operations.append({
            "type": "attach_certificate_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        })
        return self

    def attach_reward_validator(self, script_cbor_hex, script_type):
        self._operations.append({
            "type": "attach_reward_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        })
        return self

    def attach_proposing_validator(self, script_cbor_hex, script_type):
        self._operations.append({
            "type": "attach_proposing_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        })
        return self

    def attach_voting_validator(self, script_cbor_hex, script_type):
        self._operations.append({
            "type": "attach_voting_validator",
            "script_cbor_hex": script_cbor_hex,
            "script_type": script_type,
        })
        return self

    # --- Staking (with redeemer) ---

    def deregister_stake_address(self, address, redeemer_cbor_hex, refund_address=None):
        op = {
            "type": "deregister_stake_address",
            "address": address,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if refund_address:
            op["refund_address"] = refund_address
        self._operations.append(op)
        return self

    def delegate_to(self, address, pool_id, redeemer_cbor_hex):
        self._operations.append({
            "type": "delegate_to",
            "address": address,
            "pool_id": pool_id,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        })
        return self

    def withdraw(self, reward_address, amount, redeemer_cbor_hex, receiver=None):
        op = {
            "type": "withdraw",
            "reward_address": reward_address,
            "amount": str(amount),
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if receiver:
            op["receiver"] = receiver
        self._operations.append(op)
        return self

    # --- DRep (with redeemer) ---

    def register_drep(self, credential_hash, credential_type, redeemer_cbor_hex,
                      anchor_url=None, anchor_data_hash=None):
        op = {
            "type": "register_drep",
            "credential_hash": credential_hash,
            "credential_type": credential_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if anchor_url:
            op["anchor_url"] = anchor_url
        if anchor_data_hash:
            op["anchor_data_hash"] = anchor_data_hash
        self._operations.append(op)
        return self

    def unregister_drep(self, credential_hash, credential_type, redeemer_cbor_hex,
                        refund_address=None, refund_amount=None):
        op = {
            "type": "unregister_drep",
            "credential_hash": credential_hash,
            "credential_type": credential_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if refund_address:
            op["refund_address"] = refund_address
        if refund_amount is not None:
            op["refund_amount"] = str(refund_amount)
        self._operations.append(op)
        return self

    def update_drep(self, credential_hash, credential_type, redeemer_cbor_hex,
                    anchor_url=None, anchor_data_hash=None):
        op = {
            "type": "update_drep",
            "credential_hash": credential_hash,
            "credential_type": credential_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if anchor_url:
            op["anchor_url"] = anchor_url
        if anchor_data_hash:
            op["anchor_data_hash"] = anchor_data_hash
        self._operations.append(op)
        return self

    # --- Voting (with redeemer) ---

    def delegate_voting_power_to(self, address, drep_type, drep_hash, redeemer_cbor_hex):
        op = {
            "type": "delegate_voting_power_to",
            "address": address,
            "drep_type": drep_type,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if drep_hash:
            op["drep_hash"] = drep_hash
        self._operations.append(op)
        return self

    def create_vote(self, voter_type, voter_hash, gov_action_tx_hash, gov_action_index, vote,
                    redeemer_cbor_hex, anchor_url=None, anchor_data_hash=None):
        op = {
            "type": "create_vote",
            "voter_type": voter_type,
            "voter_hash": voter_hash,
            "gov_action_tx_hash": gov_action_tx_hash,
            "gov_action_index": gov_action_index,
            "vote": vote,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if anchor_url:
            op["anchor_url"] = anchor_url
        if anchor_data_hash:
            op["anchor_data_hash"] = anchor_data_hash
        self._operations.append(op)
        return self

    # --- Governance (with redeemer) ---

    def create_proposal(self, gov_action_type, return_address, anchor_url, anchor_data_hash,
                        redeemer_cbor_hex, **kwargs):
        op = {
            "type": "create_proposal",
            "gov_action_type": gov_action_type,
            "return_address": return_address,
            "anchor_url": anchor_url,
            "anchor_data_hash": anchor_data_hash,
            "redeemer_cbor_hex": redeemer_cbor_hex,
        }
        if "withdrawals" in kwargs:
            op["withdrawals"] = kwargs["withdrawals"]
        if "gov_action_tx_hash" in kwargs:
            op["gov_action_tx_hash"] = kwargs["gov_action_tx_hash"]
        if "gov_action_index" in kwargs:
            op["gov_action_index"] = kwargs["gov_action_index"]
        if "members_to_remove" in kwargs:
            op["members_to_remove"] = kwargs["members_to_remove"]
        if "new_members" in kwargs:
            op["new_members"] = kwargs["new_members"]
        if "quorum_numerator" in kwargs:
            op["quorum_numerator"] = str(kwargs["quorum_numerator"])
        if "quorum_denominator" in kwargs:
            op["quorum_denominator"] = str(kwargs["quorum_denominator"])
        if "constitution_anchor_url" in kwargs:
            op["constitution_anchor_url"] = kwargs["constitution_anchor_url"]
        if "constitution_anchor_data_hash" in kwargs:
            op["constitution_anchor_data_hash"] = kwargs["constitution_anchor_data_hash"]
        if "constitution_script_hash" in kwargs:
            op["constitution_script_hash"] = kwargs["constitution_script_hash"]
        if "protocol_version_major" in kwargs:
            op["protocol_version_major"] = kwargs["protocol_version_major"]
        if "protocol_version_minor" in kwargs:
            op["protocol_version_minor"] = kwargs["protocol_version_minor"]
        if "policy_hash" in kwargs:
            op["policy_hash"] = kwargs["policy_hash"]
        self._operations.append(op)
        return self

    # Treasury donation

    def donate_to_treasury(self, treasury_value, donation_amount, redeemer_cbor_hex):
        self._operations.append({
            "type": "donate_to_treasury",
            "treasury_value": str(treasury_value),
            "donation_amount": str(donation_amount),
            "redeemer_cbor_hex": redeemer_cbor_hex,
        })
        return self

    # --- Address configuration ---

    def from_address(self, address):
        self._from = address
        return self

    def change_address(self, address):
        self._change_address = address
        return self

    def change_datum(self, datum_cbor_hex):
        """Set inline datum for the change output."""
        self._change_datum_cbor_hex = datum_cbor_hex
        return self

    def change_datum_hash(self, hash):
        """Set datum hash for the change output."""
        self._change_datum_hash = hash
        return self

    def _to_spec(self):
        spec = {
            "tx_type": "script_tx",
            "from": self._from,
            "operations": self._operations,
        }
        if self._change_address:
            spec["change_address"] = self._change_address
        if self._change_datum_cbor_hex:
            spec["change_datum_cbor_hex"] = self._change_datum_cbor_hex
        if self._change_datum_hash:
            spec["change_datum_hash"] = self._change_datum_hash
        return spec


class ComposeTxBuilder:
    """Builder for composing multiple Tx/ScriptTx objects into a single transaction."""

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
            provider_config: Optional ProviderConfig or dict with 'name', 'url',
                and optionally 'api_key' and 'enable_cost_evaluation' for
                Java-side lazy UTXO fetching via HTTP.

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
                "provider": _build_provider_dict(provider_config),
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

    def new_script_tx(self):
        """Create a new ScriptTxBuilder for Plutus script transactions."""
        return ScriptTxBuilder(self._bridge)

    def script_tx(self):
        """Create a new ScriptTx for use with compose()."""
        return ScriptTx()

    def compose(self, *txs):
        """Compose multiple Tx/ScriptTx objects into a single transaction.

        Args:
            *txs: Tx or ScriptTx objects to compose
        """
        return ComposeTxBuilder(self._bridge, txs)
