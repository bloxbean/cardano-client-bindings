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

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getChangeAddress() { return changeAddress; }
    public void setChangeAddress(String changeAddress) { this.changeAddress = changeAddress; }

    public List<TxOperation> getOperations() { return operations; }
    public void setOperations(List<TxOperation> operations) { this.operations = operations; }

    public void validate(int index) {
        if (from == null || from.isEmpty()) {
            throw new IllegalArgumentException("transactions[" + index + "]: 'from' address is required");
        }
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("transactions[" + index + "]: at least one operation is required");
        }
    }
}
