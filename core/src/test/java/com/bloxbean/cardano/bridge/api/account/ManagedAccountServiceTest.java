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
