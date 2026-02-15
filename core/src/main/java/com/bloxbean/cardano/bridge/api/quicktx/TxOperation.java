package com.bloxbean.cardano.bridge.api.quicktx;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TxOperation {

    @JsonProperty("type")
    private String type;

    // pay_to_address / pay_to_contract
    @JsonProperty("address")
    private String address;

    @JsonProperty("amounts")
    private List<Amount> amounts;

    // pay_to_contract - inline datum CBOR hex
    @JsonProperty("datum_cbor_hex")
    private String datumCborHex;

    // pay_to_contract - datum hash hex
    @JsonProperty("datum_hash")
    private String datumHash;

    // mint_assets
    @JsonProperty("script_json")
    private String scriptJson;

    @JsonProperty("assets")
    private List<MintAsset> assets;

    @JsonProperty("receiver")
    private String receiver;

    // attach_metadata
    @JsonProperty("label")
    private Integer label;

    @JsonProperty("metadata")
    private Object metadata;

    // collect_from
    @JsonProperty("collect_utxos")
    private List<Utxo> collectUtxos;

    // Staking
    @JsonProperty("pool_id")
    private String poolId;

    @JsonProperty("reward_address")
    private String rewardAddress;

    @JsonProperty("amount")
    private String amount;

    @JsonProperty("refund_address")
    private String refundAddress;

    // DRep
    @JsonProperty("credential_hash")
    private String credentialHash;

    @JsonProperty("credential_type")
    private String credentialType;

    @JsonProperty("anchor_url")
    private String anchorUrl;

    @JsonProperty("anchor_data_hash")
    private String anchorDataHash;

    // Voting
    @JsonProperty("drep_type")
    private String drepType;

    @JsonProperty("drep_hash")
    private String drepHash;

    @JsonProperty("voter_type")
    private String voterType;

    @JsonProperty("voter_hash")
    private String voterHash;

    @JsonProperty("gov_action_tx_hash")
    private String govActionTxHash;

    @JsonProperty("gov_action_index")
    private Integer govActionIndex;

    @JsonProperty("vote")
    private String vote;

    // Governance proposals
    @JsonProperty("gov_action_type")
    private String govActionType;

    @JsonProperty("return_address")
    private String returnAddress;

    @JsonProperty("withdrawals")
    private List<Map<String, String>> withdrawals;

    // ScriptTx fields
    @JsonProperty("redeemer_cbor_hex")
    private String redeemerCborHex;

    @JsonProperty("script_cbor_hex")
    private String scriptCborHex;

    @JsonProperty("script_type")
    private String scriptType;

    @JsonProperty("reference_inputs")
    private List<ReferenceInput> referenceInputs;

    @JsonProperty("output_datum_cbor_hex")
    private String outputDatumCborHex;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public List<Amount> getAmounts() { return amounts; }
    public void setAmounts(List<Amount> amounts) { this.amounts = amounts; }

    public String getDatumCborHex() { return datumCborHex; }
    public void setDatumCborHex(String datumCborHex) { this.datumCborHex = datumCborHex; }

    public String getDatumHash() { return datumHash; }
    public void setDatumHash(String datumHash) { this.datumHash = datumHash; }

    public String getScriptJson() { return scriptJson; }
    public void setScriptJson(String scriptJson) { this.scriptJson = scriptJson; }

    public List<MintAsset> getAssets() { return assets; }
    public void setAssets(List<MintAsset> assets) { this.assets = assets; }

    public String getReceiver() { return receiver; }
    public void setReceiver(String receiver) { this.receiver = receiver; }

    public Integer getLabel() { return label; }
    public void setLabel(Integer label) { this.label = label; }

    public Object getMetadata() { return metadata; }
    public void setMetadata(Object metadata) { this.metadata = metadata; }

    public List<Utxo> getCollectUtxos() { return collectUtxos; }
    public void setCollectUtxos(List<Utxo> collectUtxos) { this.collectUtxos = collectUtxos; }

    public String getPoolId() { return poolId; }
    public void setPoolId(String poolId) { this.poolId = poolId; }

    public String getRewardAddress() { return rewardAddress; }
    public void setRewardAddress(String rewardAddress) { this.rewardAddress = rewardAddress; }

    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }

    public String getRefundAddress() { return refundAddress; }
    public void setRefundAddress(String refundAddress) { this.refundAddress = refundAddress; }

    public String getCredentialHash() { return credentialHash; }
    public void setCredentialHash(String credentialHash) { this.credentialHash = credentialHash; }

    public String getCredentialType() { return credentialType; }
    public void setCredentialType(String credentialType) { this.credentialType = credentialType; }

    public String getAnchorUrl() { return anchorUrl; }
    public void setAnchorUrl(String anchorUrl) { this.anchorUrl = anchorUrl; }

    public String getAnchorDataHash() { return anchorDataHash; }
    public void setAnchorDataHash(String anchorDataHash) { this.anchorDataHash = anchorDataHash; }

    public String getDrepType() { return drepType; }
    public void setDrepType(String drepType) { this.drepType = drepType; }

    public String getDrepHash() { return drepHash; }
    public void setDrepHash(String drepHash) { this.drepHash = drepHash; }

    public String getVoterType() { return voterType; }
    public void setVoterType(String voterType) { this.voterType = voterType; }

    public String getVoterHash() { return voterHash; }
    public void setVoterHash(String voterHash) { this.voterHash = voterHash; }

    public String getGovActionTxHash() { return govActionTxHash; }
    public void setGovActionTxHash(String govActionTxHash) { this.govActionTxHash = govActionTxHash; }

    public Integer getGovActionIndex() { return govActionIndex; }
    public void setGovActionIndex(Integer govActionIndex) { this.govActionIndex = govActionIndex; }

    public String getVote() { return vote; }
    public void setVote(String vote) { this.vote = vote; }

    public String getGovActionType() { return govActionType; }
    public void setGovActionType(String govActionType) { this.govActionType = govActionType; }

    public String getReturnAddress() { return returnAddress; }
    public void setReturnAddress(String returnAddress) { this.returnAddress = returnAddress; }

    public List<Map<String, String>> getWithdrawals() { return withdrawals; }
    public void setWithdrawals(List<Map<String, String>> withdrawals) { this.withdrawals = withdrawals; }

    public String getRedeemerCborHex() { return redeemerCborHex; }
    public void setRedeemerCborHex(String redeemerCborHex) { this.redeemerCborHex = redeemerCborHex; }

    public String getScriptCborHex() { return scriptCborHex; }
    public void setScriptCborHex(String scriptCborHex) { this.scriptCborHex = scriptCborHex; }

    public String getScriptType() { return scriptType; }
    public void setScriptType(String scriptType) { this.scriptType = scriptType; }

    public List<ReferenceInput> getReferenceInputs() { return referenceInputs; }
    public void setReferenceInputs(List<ReferenceInput> referenceInputs) { this.referenceInputs = referenceInputs; }

    public String getOutputDatumCborHex() { return outputDatumCborHex; }
    public void setOutputDatumCborHex(String outputDatumCborHex) { this.outputDatumCborHex = outputDatumCborHex; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReferenceInput {
        @JsonProperty("tx_hash")
        private String txHash;

        @JsonProperty("output_index")
        private int outputIndex;

        public String getTxHash() { return txHash; }
        public void setTxHash(String txHash) { this.txHash = txHash; }

        public int getOutputIndex() { return outputIndex; }
        public void setOutputIndex(int outputIndex) { this.outputIndex = outputIndex; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MintAsset {
        @JsonProperty("name")
        private String name;

        @JsonProperty("quantity")
        private String quantity;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getQuantity() { return quantity; }
        public void setQuantity(String quantity) { this.quantity = quantity; }
    }
}
