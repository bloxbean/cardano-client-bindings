package com.bloxbean.cardano.bridge.api;

import com.bloxbean.cardano.bridge.api.quicktx.QuickTxService;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds unsigned transactions from TxPlan YAML, fully offline, with static UTXOs + protocol params.
 */
class QuickTxApiTest {

    private static final String TEST_MNEMONIC =
            "test walk nut penalty hip pave soap entry language right filter choice";
    private static final String FAKE_TX_HASH = "a".repeat(64);

    private final ObjectMapper mapper = new ObjectMapper();
    private final QuickTxService service = new QuickTxService();

    private String protocolParamsJson;
    private String sender;
    private String receiver1;
    private String receiver2;

    @BeforeEach
    void setUp() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("protocol-params.json")) {
            protocolParamsJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        sender = Account.createFromMnemonic(Networks.testnet(), TEST_MNEMONIC, 0, 0).baseAddress();
        receiver1 = new Account(Networks.testnet()).baseAddress();
        receiver2 = new Account(Networks.testnet()).baseAddress();
    }

    /** A single 100-ADA UTXO at {@code sender}, as a JSON array of the CCL Utxo model. */
    private String utxos() {
        return """
            [{"tx_hash":"%s","output_index":0,"address":"%s",
              "amount":[{"unit":"lovelace","quantity":"100000000"}]}]
            """.formatted(FAKE_TX_HASH, sender);
    }

    private JsonNode build(String yaml) throws Exception {
        return mapper.readTree(service.buildTransaction(yaml, utxos(), protocolParamsJson));
    }

    private static void assertBuilt(JsonNode result) {
        assertFalse(result.get("tx_cbor").asText().isEmpty());
        assertEquals(64, result.get("tx_hash").asText().length());
        assertTrue(Long.parseLong(result.get("fee").asText()) > 0);
    }

    @Test
    void simplePayment() throws Exception {
        String yaml = """
            version: 1.0
            transaction:
              - tx:
                  from: %s
                  intents:
                    - type: payment
                      address: %s
                      amounts:
                        - unit: lovelace
                          quantity: "5000000"
            """.formatted(sender, receiver1);
        assertBuilt(build(yaml));
    }

    @Test
    void multiplePayments() throws Exception {
        String yaml = """
            version: 1.0
            transaction:
              - tx:
                  from: %s
                  intents:
                    - type: payment
                      address: %s
                      amounts:
                        - unit: lovelace
                          quantity: "5000000"
                    - type: payment
                      address: %s
                      amounts:
                        - unit: lovelace
                          quantity: "3000000"
            """.formatted(sender, receiver1, receiver2);
        assertBuilt(build(yaml));
    }

    // TODO: metadata intent — verify the exact TxPlan metadata YAML shape (custom serializer) and
    // re-add a paymentWithMetadata test.

    @Test
    void variableSubstitution() throws Exception {
        String yaml = """
            version: 1.0
            variables:
              to: %s
              amount: "4000000"
            transaction:
              - tx:
                  from: %s
                  intents:
                    - type: payment
                      address: ${to}
                      amounts:
                        - unit: lovelace
                          quantity: ${amount}
            """.formatted(receiver1, sender);
        assertBuilt(build(yaml));
    }

    @Test
    void insufficientFundsFails() {
        String yaml = """
            version: 1.0
            transaction:
              - tx:
                  from: %s
                  intents:
                    - type: payment
                      address: %s
                      amounts:
                        - unit: lovelace
                          quantity: "200000000"
            """.formatted(sender, receiver1);
        assertThrows(Exception.class, () -> service.buildTransaction(yaml, utxos(), protocolParamsJson));
    }
}
