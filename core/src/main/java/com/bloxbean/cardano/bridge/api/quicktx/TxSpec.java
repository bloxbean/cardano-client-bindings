package com.bloxbean.cardano.bridge.api.quicktx;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TxSpec {

    @JsonProperty("operations")
    private List<TxOperation> operations;

    @JsonProperty("from")
    private String from;

    @JsonProperty("change_address")
    private String changeAddress;

    @JsonProperty("fee_payer")
    private String feePayer;

    @JsonProperty("utxos")
    private List<Utxo> utxos;

    @JsonProperty("protocol_params")
    private ProtocolParams protocolParams;

    @JsonProperty("validity")
    private Validity validity;

    @JsonProperty("merge_outputs")
    private Boolean mergeOutputs;

    @JsonProperty("signer_count")
    private Integer signerCount;

    @JsonProperty("transactions")
    private List<TxItemSpec> transactions;

    @JsonProperty("provider")
    private ProviderConfig provider;

    @JsonProperty("tx_type")
    private String txType;

    @JsonProperty("change_datum_cbor_hex")
    private String changeDatumCborHex;

    @JsonProperty("change_datum_hash")
    private String changeDatumHash;

    public List<TxOperation> getOperations() { return operations; }
    public void setOperations(List<TxOperation> operations) { this.operations = operations; }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getChangeAddress() { return changeAddress; }
    public void setChangeAddress(String changeAddress) { this.changeAddress = changeAddress; }

    public String getFeePayer() { return feePayer; }
    public void setFeePayer(String feePayer) { this.feePayer = feePayer; }

    public List<Utxo> getUtxos() { return utxos; }
    public void setUtxos(List<Utxo> utxos) { this.utxos = utxos; }

    public ProtocolParams getProtocolParams() { return protocolParams; }
    public void setProtocolParams(ProtocolParams protocolParams) { this.protocolParams = protocolParams; }

    public Validity getValidity() { return validity; }
    public void setValidity(Validity validity) { this.validity = validity; }

    public Boolean getMergeOutputs() { return mergeOutputs; }
    public void setMergeOutputs(Boolean mergeOutputs) { this.mergeOutputs = mergeOutputs; }

    public Integer getSignerCount() { return signerCount; }
    public void setSignerCount(Integer signerCount) { this.signerCount = signerCount; }

    public List<TxItemSpec> getTransactions() { return transactions; }
    public void setTransactions(List<TxItemSpec> transactions) { this.transactions = transactions; }

    public ProviderConfig getProvider() { return provider; }
    public void setProvider(ProviderConfig provider) { this.provider = provider; }

    public String getTxType() { return txType; }
    public void setTxType(String txType) { this.txType = txType; }

    public String getChangeDatumCborHex() { return changeDatumCborHex; }
    public void setChangeDatumCborHex(String changeDatumCborHex) { this.changeDatumCborHex = changeDatumCborHex; }

    public String getChangeDatumHash() { return changeDatumHash; }
    public void setChangeDatumHash(String changeDatumHash) { this.changeDatumHash = changeDatumHash; }

    public void validate() {
        if (transactions != null && !transactions.isEmpty()) {
            // Compose mode
            if (feePayer == null || feePayer.isEmpty()) {
                throw new IllegalArgumentException("'fee_payer' is required when composing multiple transactions");
            }
            for (int i = 0; i < transactions.size(); i++) {
                transactions.get(i).validate(i);
            }
        } else {
            // Single mode
            if (operations == null || operations.isEmpty()) {
                throw new IllegalArgumentException("At least one operation is required");
            }
            if (!"script_tx".equals(txType) && (from == null || from.isEmpty())) {
                throw new IllegalArgumentException("'from' address is required");
            }
        }

        // Common validations — provider mode supplies utxos and protocol_params via HTTP
        if (provider != null) {
            provider.validate();
        } else {
            if (utxos == null || utxos.isEmpty()) {
                throw new IllegalArgumentException("'utxos' are required");
            }
            if (protocolParams == null) {
                throw new IllegalArgumentException("'protocol_params' are required");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Validity {
        @JsonProperty("valid_from")
        private Long validFrom;

        @JsonProperty("valid_to")
        private Long validTo;

        public Long getValidFrom() { return validFrom; }
        public void setValidFrom(Long validFrom) { this.validFrom = validFrom; }

        public Long getValidTo() { return validTo; }
        public void setValidTo(Long validTo) { this.validTo = validTo; }
    }
}
