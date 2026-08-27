package com.bloxbean.cardano.bridge.api.account;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lifecycle and public-data invariants of the managed-account registry (ADR-0016): handles are
 * opaque and never reused as another account after close; unknown/closed/stale handles fail with a
 * typed error; close is idempotent; account info never carries secrets.
 */
class ManagedAccountServiceTest {

    private static final String TEST_MNEMONIC =
            "test walk nut penalty hip pave soap entry language right filter choice";
    private static final int TESTNET = 1;

    private final List<Long> opened = new ArrayList<>();

    private long open(int accountIndex, int addressIndex) {
        long handle = ManagedAccountService.openMnemonic(TESTNET, TEST_MNEMONIC, accountIndex, addressIndex);
        opened.add(handle);
        return handle;
    }

    @AfterEach
    void tearDown() {
        opened.forEach(ManagedAccountService::close);
        opened.clear();
    }

    @Test
    void openReturnsNonZeroHandle_andInfoMatchesCclDerivation() {
        long handle = open(0, 0);
        assertTrue(handle > 0, "handles start at 1; 0 is never valid");

        Map<String, Object> info = ManagedAccountService.info(handle);
        Account reference = Account.createFromMnemonic(Networks.testnet(), TEST_MNEMONIC, 0, 0);
        assertEquals(reference.baseAddress(), info.get("base_address"));
        assertEquals(reference.enterpriseAddress(), info.get("enterprise_address"));
        assertEquals(reference.stakeAddress(), info.get("stake_address"));
        assertEquals(reference.drepId(), info.get("drep_id"));
        assertEquals(TESTNET, info.get("network"));
        assertEquals(0, info.get("account_index"));
        assertEquals(0, info.get("address_index"));
    }

    @Test
    void infoNeverContainsSecrets() {
        Map<String, Object> info = ManagedAccountService.info(open(0, 0));
        for (var entry : info.entrySet()) {
            String key = entry.getKey().toLowerCase();
            assertFalse(key.contains("mnemonic") || key.contains("private") || key.contains("secret"),
                    "secret-bearing key in account info: " + entry.getKey());
            String value = String.valueOf(entry.getValue());
            assertFalse(value.contains(TEST_MNEMONIC.split(" ")[0] + " "),
                    "mnemonic words leaked through info value: " + entry.getKey());
        }
    }

    @Test
    void distinctHandles_forSameAndDifferentDerivations() {
        long first = open(0, 0);
        long again = open(0, 0);   // same derivation opened twice: independent handles
        long other = open(0, 1);
        assertNotEquals(first, again);
        assertNotEquals(first, other);

        // Interleaved use: each handle answers for its own derivation.
        assertNotEquals(ManagedAccountService.info(first).get("base_address"),
                ManagedAccountService.info(other).get("base_address"));
        assertEquals(ManagedAccountService.info(first).get("base_address"),
                ManagedAccountService.info(again).get("base_address"));
    }

    @Test
    void unknownHandleFails_withTypedException() {
        assertThrows(ManagedAccountService.UnknownHandleException.class,
                () -> ManagedAccountService.info(999_999_999L));
        assertThrows(ManagedAccountService.UnknownHandleException.class,
                () -> ManagedAccountService.info(0L), "0 must never be a valid handle");
    }

    @Test
    void useAfterClose_failsWithTypedException_andCloseIsIdempotent() {
        long handle = open(0, 0);
        ManagedAccountService.close(handle);
        assertThrows(ManagedAccountService.UnknownHandleException.class,
                () -> ManagedAccountService.info(handle));
        assertDoesNotThrow(() -> ManagedAccountService.close(handle), "double-close must be a no-op");
        assertDoesNotThrow(() -> ManagedAccountService.close(123_456_789L), "closing unknown is a no-op");
    }

    @Test
    void closedHandleIsNeverReassigned() {
        long first = open(0, 0);
        ManagedAccountService.close(first);
        long next = open(0, 1);
        assertNotEquals(first, next, "handles are never reused after close");
        assertThrows(ManagedAccountService.UnknownHandleException.class,
                () -> ManagedAccountService.info(first), "the closed handle stays dead");
    }

    // --- Signing: typed role mask, byte-identical with the mnemonic-per-call path ---

    /** An unsigned stake-registration tx built offline, for signing tests. */
    private String unsignedTx() throws Exception {
        var service = new com.bloxbean.cardano.bridge.api.quicktx.QuickTxService();
        Account reference = Account.createFromMnemonic(Networks.testnet(), TEST_MNEMONIC, 0, 0);
        String yaml = com.bloxbean.cardano.client.quicktx.serialization.TxPlan
                .from(new com.bloxbean.cardano.client.quicktx.Tx()
                        .registerStakeAddress(reference.stakeAddress())
                        .from(reference.baseAddress()))
                .feePayer(reference.baseAddress()).toYaml();
        String utxos = """
            [{"tx_hash":"%s","output_index":0,"address":"%s",
              "amount":[{"unit":"lovelace","quantity":"2000000000"}]}]
            """.formatted("a".repeat(64), reference.baseAddress());
        String params;
        try (var is = getClass().getClassLoader().getResourceAsStream("protocol-params.json")) {
            params = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var result = com.bloxbean.cardano.client.quicktx.serialization.YamlSerializer
                .getYamlMapper().readTree(service.buildTransaction(yaml, utxos, params, null, 1));
        return result.get("tx_cbor").asText();
    }

    /** Sign via the mnemonic-per-call mechanism (what ccl_account_sign_tx_multi does). */
    private String signLegacy(String txCborHex, boolean stake, boolean drep) throws Exception {
        Account account = Account.createFromMnemonic(Networks.testnet(), TEST_MNEMONIC, 0, 0);
        var tx = com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(
                com.bloxbean.cardano.client.util.HexUtil.decodeHexString(txCborHex));
        tx = account.sign(tx);
        if (stake) tx = account.signWithStakeKey(tx);
        if (drep) tx = account.signWithDRepKey(tx);
        return tx.serializeToHex();
    }

    @Test
    void signParity_byteIdenticalWithLegacyPath_acrossRoleCombos() throws Exception {
        long handle = open(0, 0);
        String unsigned = unsignedTx();

        assertEquals(signLegacy(unsigned, false, false),
                ManagedAccountService.signTx(handle, unsigned,
                        ManagedAccountService.ROLE_PAYMENT));
        assertEquals(signLegacy(unsigned, true, false),
                ManagedAccountService.signTx(handle, unsigned,
                        ManagedAccountService.ROLE_PAYMENT | ManagedAccountService.ROLE_STAKE));
        assertEquals(signLegacy(unsigned, true, true),
                ManagedAccountService.signTx(handle, unsigned,
                        ManagedAccountService.ROLE_PAYMENT | ManagedAccountService.ROLE_STAKE
                                | ManagedAccountService.ROLE_DREP));
    }

    @Test
    void signMaskValidation_zeroAndUnknownBitsRejected() throws Exception {
        long handle = open(0, 0);
        String unsigned = unsignedTx();
        assertThrows(IllegalArgumentException.class,
                () -> ManagedAccountService.signTx(handle, unsigned, 0),
                "empty mask must not silently sign with every key");
        assertThrows(IllegalArgumentException.class,
                () -> ManagedAccountService.signTx(handle, unsigned, 1 << 7));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedAccountService.signTx(handle, "  ", ManagedAccountService.ROLE_PAYMENT));
    }

    @Test
    void signOnClosedHandle_failsTyped() throws Exception {
        long handle = open(0, 0);
        String unsigned = unsignedTx();
        ManagedAccountService.close(handle);
        assertThrows(ManagedAccountService.UnknownHandleException.class,
                () -> ManagedAccountService.signTx(handle, unsigned,
                        ManagedAccountService.ROLE_PAYMENT));
    }

    @Test
    void invalidOpenArguments_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ManagedAccountService.openMnemonic(99, TEST_MNEMONIC, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedAccountService.openMnemonic(TESTNET, "  ", 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedAccountService.openMnemonic(TESTNET, TEST_MNEMONIC, -1, 0));
        assertThrows(Exception.class,
                () -> ManagedAccountService.openMnemonic(TESTNET, "not a valid mnemonic at all", 0, 0));
    }
}
