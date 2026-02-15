package com.bloxbean.cardano.bridge.api.quicktx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TxItemSpec {

    @JsonProperty("from")
    private String from;

    @JsonProperty("change_address")
    private String changeAddress;

    @JsonProperty("operations")
    private List<TxOperation> operations;

    @JsonProperty("tx_type")
    private String txType;

    @JsonProperty("change_datum_cbor_hex")
    private String changeDatumCborHex;

    @JsonProperty("change_datum_hash")
    private String changeDatumHash;

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getChangeAddress() { return changeAddress; }
    public void setChangeAddress(String changeAddress) { this.changeAddress = changeAddress; }

    public List<TxOperation> getOperations() { return operations; }
    public void setOperations(List<TxOperation> operations) { this.operations = operations; }

    public String getTxType() { return txType; }
    public void setTxType(String txType) { this.txType = txType; }

    public String getChangeDatumCborHex() { return changeDatumCborHex; }
    public void setChangeDatumCborHex(String changeDatumCborHex) { this.changeDatumCborHex = changeDatumCborHex; }

    public String getChangeDatumHash() { return changeDatumHash; }
    public void setChangeDatumHash(String changeDatumHash) { this.changeDatumHash = changeDatumHash; }

    public void validate(int index) {
        if (!"script_tx".equals(txType) && (from == null || from.isEmpty())) {
            throw new IllegalArgumentException("transactions[" + index + "]: 'from' address is required");
        }
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("transactions[" + index + "]: at least one operation is required");
        }
    }
}
