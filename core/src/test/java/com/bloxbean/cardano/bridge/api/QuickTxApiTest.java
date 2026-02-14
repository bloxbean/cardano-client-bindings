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

class QuickTxApiTest {

    private static final String TEST_MNEMONIC =
            "test walk nut penalty hip pave soap entry language right filter choice";

    private final ObjectMapper mapper = new ObjectMapper();
    private final QuickTxService service = new QuickTxService();
    private String protocolParamsJson;

    // Generate deterministic addresses from test mnemonic
    private String sender;
    private String sender2;
    private String receiver1;
    private String receiver2;

    @BeforeEach
    void setUp() throws IOException {
        InputStream is = getClass().getClassLoader().getResourceAsStream("protocol-params.json");
        protocolParamsJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        Account senderAccount = Account.createFromMnemonic(Networks.testnet(), TEST_MNEMONIC, 0, 0);
        sender = senderAccount.baseAddress();
        sender2 = Account.createFromMnemonic(Networks.testnet(), TEST_MNEMONIC, 0, 1).baseAddress();
        receiver1 = new Account(Networks.testnet()).baseAddress();
        receiver2 = new Account(Networks.testnet()).baseAddress();
    }

    @Test
    void testSimpleAdaPayment() throws Exception {
        String spec = buildSpec("""
            "operations": [
                {"type": "pay_to_address", "address": "%s",
                 "amounts": [{"unit": "lovelace", "quantity": "5000000"}]}
            ]
            """.formatted(receiver1));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);

        assertNotNull(json.get("tx_cbor").asText());
        assertFalse(json.get("tx_cbor").asText().isEmpty());
        assertNotNull(json.get("tx_hash").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
        assertTrue(Long.parseLong(json.get("fee").asText()) > 0);
    }

    @Test
    void testMultiplePayments() throws Exception {
        String spec = buildSpec("""
            "operations": [
                {"type": "pay_to_address", "address": "%s",
                 "amounts": [{"unit": "lovelace", "quantity": "5000000"}]},
                {"type": "pay_to_address", "address": "%s",
                 "amounts": [{"unit": "lovelace", "quantity": "3000000"}]}
            ]
            """.formatted(receiver1, receiver2));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);

        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    @Test
    void testWithMetadata() throws Exception {
        String spec = buildSpec("""
            "operations": [
                {"type": "pay_to_address", "address": "%s",
                 "amounts": [{"unit": "lovelace", "quantity": "2000000"}]},
                {"type": "attach_metadata", "label": 674,
                 "metadata": {"msg": ["Hello from CCL Bridge"]}}
            ]
            """.formatted(receiver1));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);

        assertNotNull(json.get("tx_cbor").asText());
        // Fee should be slightly higher with metadata
        assertTrue(Long.parseLong(json.get("fee").asText()) > 0);
    }

    @Test
    void testWithChangeAddress() throws Exception {
        // change_address = sender is the typical case; change goes back to sender
        String spec = """
            {
                "operations": [
                    {"type": "pay_to_address", "address": "%s",
                     "amounts": [{"unit": "lovelace", "quantity": "5000000"}]}
                ],
                "from": "%s",
                "change_address": "%s",
                "utxos": [
                    {"tx_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                     "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "100000000"}]}
                ],
                "protocol_params": %s,
                "signer_count": 1
            }
            """.formatted(receiver1, sender, sender, sender, protocolParamsJson);

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
    }

    @Test
    void testWithValidityInterval() throws Exception {
        String spec = """
            {
                "operations": [
                    {"type": "pay_to_address", "address": "%s",
                     "amounts": [{"unit": "lovelace", "quantity": "2000000"}]}
                ],
                "from": "%s",
                "utxos": [
                    {"tx_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                     "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "100000000"}]}
                ],
                "protocol_params": %s,
                "validity": {"valid_from": 1000, "valid_to": 50000},
                "signer_count": 1
            }
            """.formatted(receiver1, sender, sender, protocolParamsJson);

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
    }

    @Test
    void testInsufficientFunds() {
        String spec = """
            {
                "operations": [
                    {"type": "pay_to_address", "address": "%s",
                     "amounts": [{"unit": "lovelace", "quantity": "200000000"}]}
                ],
                "from": "%s",
                "utxos": [
                    {"tx_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                     "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "1000000"}]}
                ],
                "protocol_params": %s,
                "signer_count": 1
            }
            """.formatted(receiver1, sender, sender, protocolParamsJson);

        assertThrows(Exception.class, () -> service.buildTransaction(spec));
    }

    @Test
    void testMissingOperations() {
        String spec = """
            {
                "operations": [],
                "from": "%s",
                "utxos": [
                    {"tx_hash": "aaaa", "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "1000000"}]}
                ],
                "protocol_params": %s
            }
            """.formatted(sender, sender, protocolParamsJson);

        assertThrows(IllegalArgumentException.class, () -> service.buildTransaction(spec));
    }

    @Test
    void testMissingFrom() {
        String spec = """
            {
                "operations": [
                    {"type": "pay_to_address", "address": "%s",
                     "amounts": [{"unit": "lovelace", "quantity": "2000000"}]}
                ],
                "utxos": [
                    {"tx_hash": "aaaa", "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "1000000"}]}
                ],
                "protocol_params": %s
            }
            """.formatted(receiver1, sender, protocolParamsJson);

        assertThrows(IllegalArgumentException.class, () -> service.buildTransaction(spec));
    }

    @Test
    void testMissingUtxos() {
        String spec = """
            {
                "operations": [
                    {"type": "pay_to_address", "address": "%s",
                     "amounts": [{"unit": "lovelace", "quantity": "2000000"}]}
                ],
                "from": "%s",
                "protocol_params": %s
            }
            """.formatted(receiver1, sender, protocolParamsJson);

        assertThrows(IllegalArgumentException.class, () -> service.buildTransaction(spec));
    }

    @Test
    void testProviderModeSkipsUtxoValidation() {
        // Provider mode should not require utxos or protocol_params in the spec
        // (it will fail at HTTP fetch, not at validation)
        String spec = """
            {
                "operations": [
                    {"type": "pay_to_address", "address": "%s",
                     "amounts": [{"unit": "lovelace", "quantity": "2000000"}]}
                ],
                "from": "%s",
                "provider": {"name": "yaci", "url": "http://localhost:9999/api/v1"}
            }
            """.formatted(receiver1, sender);

        // Should fail with connection error, NOT IllegalArgumentException
        Exception ex = assertThrows(Exception.class, () -> service.buildTransaction(spec));
        assertFalse(ex instanceof IllegalArgumentException,
                "Should not fail validation — expected HTTP error, got: " + ex.getMessage());
    }

    @Test
    void testProviderModeInvalidProviderName() {
        String spec = """
            {
                "operations": [
                    {"type": "pay_to_address", "address": "%s",
                     "amounts": [{"unit": "lovelace", "quantity": "2000000"}]}
                ],
                "from": "%s",
                "provider": {"name": "unknown", "url": "http://localhost:9999/api/v1"}
            }
            """.formatted(receiver1, sender);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.buildTransaction(spec));
        assertTrue(ex.getMessage().contains("Unsupported provider"));
    }

    @Test
    void testProviderModeMissingName() {
        String spec = """
            {
                "operations": [
                    {"type": "pay_to_address", "address": "%s",
                     "amounts": [{"unit": "lovelace", "quantity": "2000000"}]}
                ],
                "from": "%s",
                "provider": {"url": "http://localhost:9999/api/v1"}
            }
            """.formatted(receiver1, sender);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.buildTransaction(spec));
        assertTrue(ex.getMessage().contains("'name' is required"));
    }

    @Test
    void testMissingProtocolParams() {
        String spec = """
            {
                "operations": [
                    {"type": "pay_to_address", "address": "%s",
                     "amounts": [{"unit": "lovelace", "quantity": "2000000"}]}
                ],
                "from": "%s",
                "utxos": [
                    {"tx_hash": "aaaa", "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "1000000"}]}
                ]
            }
            """.formatted(receiver1, sender, sender);

        assertThrows(IllegalArgumentException.class, () -> service.buildTransaction(spec));
    }

    @Test
    void testMultiAssetPayment() throws Exception {
        String policyId = "a".repeat(56);
        String unit = policyId + "546f6b656e"; // "Token" hex
        String spec = """
            {
                "operations": [
                    {"type": "pay_to_address", "address": "%s",
                     "amounts": [
                         {"unit": "lovelace", "quantity": "2000000"},
                         {"unit": "%s", "quantity": "100"}
                     ]}
                ],
                "from": "%s",
                "utxos": [
                    {"tx_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                     "output_index": 0, "address": "%s",
                     "amount": [
                         {"unit": "lovelace", "quantity": "100000000"},
                         {"unit": "%s", "quantity": "500"}
                     ]}
                ],
                "protocol_params": %s,
                "signer_count": 1
            }
            """.formatted(receiver1, unit, sender, sender, unit, protocolParamsJson);

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    // --- Compose (multi-Tx) tests ---

    @Test
    void testComposeTwoSenders() throws Exception {
        String spec = """
            {
                "transactions": [
                    {
                        "from": "%s",
                        "operations": [
                            {"type": "pay_to_address", "address": "%s",
                             "amounts": [{"unit": "lovelace", "quantity": "5000000"}]}
                        ]
                    },
                    {
                        "from": "%s",
                        "operations": [
                            {"type": "pay_to_address", "address": "%s",
                             "amounts": [{"unit": "lovelace", "quantity": "3000000"}]}
                        ]
                    }
                ],
                "fee_payer": "%s",
                "utxos": [
                    {"tx_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                     "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "100000000"}]},
                    {"tx_hash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                     "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "100000000"}]}
                ],
                "protocol_params": %s
            }
            """.formatted(sender, receiver1, sender2, receiver2,
                          sender, sender, sender2, protocolParamsJson);

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);

        assertNotNull(json.get("tx_cbor").asText());
        assertFalse(json.get("tx_cbor").asText().isEmpty());
        assertEquals(64, json.get("tx_hash").asText().length());
        assertTrue(Long.parseLong(json.get("fee").asText()) > 0);
    }

    @Test
    void testComposeMissingFeePayer() {
        String spec = """
            {
                "transactions": [
                    {
                        "from": "%s",
                        "operations": [
                            {"type": "pay_to_address", "address": "%s",
                             "amounts": [{"unit": "lovelace", "quantity": "5000000"}]}
                        ]
                    },
                    {
                        "from": "%s",
                        "operations": [
                            {"type": "pay_to_address", "address": "%s",
                             "amounts": [{"unit": "lovelace", "quantity": "3000000"}]}
                        ]
                    }
                ],
                "utxos": [
                    {"tx_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                     "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "100000000"}]}
                ],
                "protocol_params": %s
            }
            """.formatted(sender, receiver1, sender2, receiver2,
                          sender, protocolParamsJson);

        assertThrows(IllegalArgumentException.class, () -> service.buildTransaction(spec));
    }

    @Test
    void testComposeMissingFromInItem() {
        String spec = """
            {
                "transactions": [
                    {
                        "from": "%s",
                        "operations": [
                            {"type": "pay_to_address", "address": "%s",
                             "amounts": [{"unit": "lovelace", "quantity": "5000000"}]}
                        ]
                    },
                    {
                        "operations": [
                            {"type": "pay_to_address", "address": "%s",
                             "amounts": [{"unit": "lovelace", "quantity": "3000000"}]}
                        ]
                    }
                ],
                "fee_payer": "%s",
                "utxos": [
                    {"tx_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                     "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "100000000"}]}
                ],
                "protocol_params": %s
            }
            """.formatted(sender, receiver1, receiver2,
                          sender, sender, protocolParamsJson);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.buildTransaction(spec));
        assertTrue(ex.getMessage().contains("transactions[1]"));
    }

    @Test
    void testComposeWithMetadata() throws Exception {
        String spec = """
            {
                "transactions": [
                    {
                        "from": "%s",
                        "operations": [
                            {"type": "pay_to_address", "address": "%s",
                             "amounts": [{"unit": "lovelace", "quantity": "5000000"}]},
                            {"type": "attach_metadata", "label": 674,
                             "metadata": {"msg": ["Compose test"]}}
                        ]
                    },
                    {
                        "from": "%s",
                        "operations": [
                            {"type": "pay_to_address", "address": "%s",
                             "amounts": [{"unit": "lovelace", "quantity": "3000000"}]}
                        ]
                    }
                ],
                "fee_payer": "%s",
                "utxos": [
                    {"tx_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                     "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "100000000"}]},
                    {"tx_hash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                     "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "100000000"}]}
                ],
                "protocol_params": %s
            }
            """.formatted(sender, receiver1, sender2, receiver2,
                          sender, sender, sender2, protocolParamsJson);

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertTrue(Long.parseLong(json.get("fee").asText()) > 0);
    }

    @Test
    void testComposeSignerCountDefault() throws Exception {
        // When signer_count is omitted in compose mode, it defaults to number of transactions
        String spec = """
            {
                "transactions": [
                    {
                        "from": "%s",
                        "operations": [
                            {"type": "pay_to_address", "address": "%s",
                             "amounts": [{"unit": "lovelace", "quantity": "5000000"}]}
                        ]
                    },
                    {
                        "from": "%s",
                        "operations": [
                            {"type": "pay_to_address", "address": "%s",
                             "amounts": [{"unit": "lovelace", "quantity": "3000000"}]}
                        ]
                    }
                ],
                "fee_payer": "%s",
                "utxos": [
                    {"tx_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                     "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "100000000"}]},
                    {"tx_hash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                     "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "100000000"}]}
                ],
                "protocol_params": %s
            }
            """.formatted(sender, receiver1, sender2, receiver2,
                          sender, sender, sender2, protocolParamsJson);

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        // Should build successfully with default signer count = 2
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    // --- Staking tests ---

    @Test
    void testRegisterStakeAddress() throws Exception {
        String spec = buildSpec("""
            "operations": [
                {"type": "register_stake_address", "address": "%s"}
            ]
            """.formatted(sender));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertFalse(json.get("tx_cbor").asText().isEmpty());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    @Test
    void testDeregisterStakeAddress() throws Exception {
        String spec = buildSpec("""
            "operations": [
                {"type": "deregister_stake_address", "address": "%s"}
            ]
            """.formatted(sender));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    @Test
    void testDeregisterStakeAddressWithRefund() throws Exception {
        String spec = buildSpec("""
            "operations": [
                {"type": "deregister_stake_address", "address": "%s",
                 "refund_address": "%s"}
            ]
            """.formatted(sender, receiver1));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    @Test
    void testDelegateTo() throws Exception {
        // pool1... bech32 format; using a fake but valid-looking pool ID
        String poolId = "pool1pu5jlj4q9w9jlxeu370a3c9myx47md5j5m2str0naunn2q3lkdy";
        String spec = buildSpec("""
            "operations": [
                {"type": "delegate_to", "address": "%s",
                 "pool_id": "%s"}
            ]
            """.formatted(sender, poolId));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    @Test
    void testWithdraw() throws Exception {
        Account senderAccount = Account.createFromMnemonic(Networks.testnet(), TEST_MNEMONIC, 0, 0);
        String stakeAddr = senderAccount.stakeAddress();

        String spec = buildSpec("""
            "operations": [
                {"type": "withdraw", "reward_address": "%s",
                 "amount": "5000000"}
            ]
            """.formatted(stakeAddr));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    @Test
    void testWithdrawWithReceiver() throws Exception {
        Account senderAccount = Account.createFromMnemonic(Networks.testnet(), TEST_MNEMONIC, 0, 0);
        String stakeAddr = senderAccount.stakeAddress();

        String spec = buildSpec("""
            "operations": [
                {"type": "withdraw", "reward_address": "%s",
                 "amount": "5000000", "receiver": "%s"}
            ]
            """.formatted(stakeAddr, receiver1));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    // --- DRep tests ---

    @Test
    void testRegisterDRep() throws Exception {
        String credentialHash = "ab".repeat(28);
        String spec = buildSpec("""
            "operations": [
                {"type": "register_drep", "credential_hash": "%s",
                 "credential_type": "key"}
            ]
            """.formatted(credentialHash));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    @Test
    void testRegisterDRepWithAnchor() throws Exception {
        String credentialHash = "ab".repeat(28);
        String dataHash = "cd".repeat(32);
        String spec = buildSpec("""
            "operations": [
                {"type": "register_drep", "credential_hash": "%s",
                 "credential_type": "key",
                 "anchor_url": "https://example.com/drep.json",
                 "anchor_data_hash": "%s"}
            ]
            """.formatted(credentialHash, dataHash));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    @Test
    void testUnregisterDRep() throws Exception {
        String credentialHash = "ab".repeat(28);
        String spec = buildSpec("""
            "operations": [
                {"type": "unregister_drep", "credential_hash": "%s",
                 "credential_type": "key"}
            ]
            """.formatted(credentialHash));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    @Test
    void testUpdateDRep() throws Exception {
        String credentialHash = "ab".repeat(28);
        String dataHash = "cd".repeat(32);
        String spec = buildSpec("""
            "operations": [
                {"type": "update_drep", "credential_hash": "%s",
                 "credential_type": "key",
                 "anchor_url": "https://example.com/drep-v2.json",
                 "anchor_data_hash": "%s"}
            ]
            """.formatted(credentialHash, dataHash));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    // --- Voting tests ---

    @Test
    void testDelegateVotingPowerToKeyHash() throws Exception {
        String drepHash = "ab".repeat(28);
        String spec = buildSpec("""
            "operations": [
                {"type": "delegate_voting_power_to", "address": "%s",
                 "drep_type": "key_hash", "drep_hash": "%s"}
            ]
            """.formatted(sender, drepHash));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    @Test
    void testDelegateVotingPowerToAbstain() throws Exception {
        String spec = buildSpec("""
            "operations": [
                {"type": "delegate_voting_power_to", "address": "%s",
                 "drep_type": "abstain"}
            ]
            """.formatted(sender));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    @Test
    void testCreateVote() throws Exception {
        String voterHash = "ab".repeat(28);
        String govTxHash = "cd".repeat(32);
        String spec = buildSpec("""
            "operations": [
                {"type": "create_vote", "voter_type": "drep_key_hash",
                 "voter_hash": "%s",
                 "gov_action_tx_hash": "%s",
                 "gov_action_index": 0, "vote": "yes"}
            ]
            """.formatted(voterHash, govTxHash));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    @Test
    void testCreateVoteWithAnchor() throws Exception {
        String voterHash = "ab".repeat(28);
        String govTxHash = "cd".repeat(32);
        String anchorDataHash = "ef".repeat(32);
        String spec = buildSpec("""
            "operations": [
                {"type": "create_vote", "voter_type": "drep_key_hash",
                 "voter_hash": "%s",
                 "gov_action_tx_hash": "%s",
                 "gov_action_index": 0, "vote": "no",
                 "anchor_url": "https://example.com/rationale.json",
                 "anchor_data_hash": "%s"}
            ]
            """.formatted(voterHash, govTxHash, anchorDataHash));

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    // --- Governance proposal tests ---

    @Test
    void testCreateInfoActionProposal() throws Exception {
        Account senderAccount = Account.createFromMnemonic(Networks.testnet(), TEST_MNEMONIC, 0, 0);
        String stakeAddr = senderAccount.stakeAddress();
        String anchorDataHash = "ab".repeat(32);

        // gov_action_deposit = 1000 ADA, so we need a large UTXO
        String spec = """
            {
                "operations": [
                    {"type": "create_proposal", "gov_action_type": "info_action",
                     "return_address": "%s",
                     "anchor_url": "https://example.com/proposal.json",
                     "anchor_data_hash": "%s"}
                ],
                "from": "%s",
                "utxos": [
                    {"tx_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                     "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "2000000000"}]}
                ],
                "protocol_params": %s,
                "signer_count": 1
            }
            """.formatted(stakeAddr, anchorDataHash, sender, sender, protocolParamsJson);

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    @Test
    void testCreateTreasuryWithdrawalsProposal() throws Exception {
        Account senderAccount = Account.createFromMnemonic(Networks.testnet(), TEST_MNEMONIC, 0, 0);
        String stakeAddr = senderAccount.stakeAddress();
        String anchorDataHash = "ab".repeat(32);

        // gov_action_deposit = 1000 ADA, so we need a large UTXO
        String spec = """
            {
                "operations": [
                    {"type": "create_proposal", "gov_action_type": "treasury_withdrawals",
                     "return_address": "%s",
                     "anchor_url": "https://example.com/proposal.json",
                     "anchor_data_hash": "%s",
                     "withdrawals": [
                         {"reward_address": "%s", "amount": "1000000"}
                     ]}
                ],
                "from": "%s",
                "utxos": [
                    {"tx_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                     "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "2000000000"}]}
                ],
                "protocol_params": %s,
                "signer_count": 1
            }
            """.formatted(stakeAddr, anchorDataHash, stakeAddr, sender, sender, protocolParamsJson);

        String result = service.buildTransaction(spec);
        JsonNode json = mapper.readTree(result);
        assertNotNull(json.get("tx_cbor").asText());
        assertEquals(64, json.get("tx_hash").asText().length());
    }

    @Test
    void testCreateVoteInvalidVoteValue() {
        String voterHash = "ab".repeat(28);
        String govTxHash = "cd".repeat(32);
        String spec = buildSpec("""
            "operations": [
                {"type": "create_vote", "voter_type": "drep_key_hash",
                 "voter_hash": "%s",
                 "gov_action_tx_hash": "%s",
                 "gov_action_index": 0, "vote": "maybe"}
            ]
            """.formatted(voterHash, govTxHash));

        assertThrows(Exception.class, () -> service.buildTransaction(spec));
    }

    @Test
    void testUnsupportedGovActionType() {
        Account senderAccount = Account.createFromMnemonic(Networks.testnet(), TEST_MNEMONIC, 0, 0);
        String stakeAddr = senderAccount.stakeAddress();
        String spec = buildSpec("""
            "operations": [
                {"type": "create_proposal", "gov_action_type": "hard_fork",
                 "return_address": "%s",
                 "anchor_url": "https://example.com/proposal.json",
                 "anchor_data_hash": "abcdef"}
            ]
            """.formatted(stakeAddr));

        assertThrows(Exception.class, () -> service.buildTransaction(spec));
    }

    /**
     * Helper to build a standard spec JSON with common defaults.
     */
    private String buildSpec(String operationsFragment) {
        return """
            {
                %s,
                "from": "%s",
                "utxos": [
                    {"tx_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                     "output_index": 0, "address": "%s",
                     "amount": [{"unit": "lovelace", "quantity": "100000000"}]}
                ],
                "protocol_params": %s,
                "signer_count": 1
            }
            """.formatted(operationsFragment, sender, sender, protocolParamsJson);
    }
}
