package com.bloxbean.cardano.bridge.api;

import com.bloxbean.cardano.bridge.ErrorCodes;
import com.bloxbean.cardano.bridge.util.ResultState;
import com.bloxbean.cardano.bridge.util.ErrorState;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountApiTest {

    private static final String TEST_MNEMONIC =
            "test walk nut penalty hip pave soap entry language right filter choice";

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ResultState.clear();
        ErrorState.clear();
    }

    @Test
    void testAccountCreate() {
        Account account = new Account(Networks.mainnet());
        assertNotNull(account.mnemonic());
        assertNotNull(account.baseAddress());
        assertTrue(account.baseAddress().startsWith("addr1"));
    }

    @Test
    void testAccountFromMnemonic() {
        Account account = Account.createFromMnemonic(Networks.mainnet(), TEST_MNEMONIC, 0, 0);
        assertNotNull(account.baseAddress());
        assertTrue(account.baseAddress().startsWith("addr1"));

        // Same mnemonic should produce same address
        Account account2 = Account.createFromMnemonic(Networks.mainnet(), TEST_MNEMONIC, 0, 0);
        assertEquals(account.baseAddress(), account2.baseAddress());
    }

    @Test
    void testAccountFromMnemonicTestnet() {
        Account account = Account.createFromMnemonic(Networks.testnet(), TEST_MNEMONIC, 0, 0);
        assertNotNull(account.baseAddress());
        assertTrue(account.baseAddress().startsWith("addr_test1"));
    }

    @Test
    void testAccountPrivateKey() {
        Account account = Account.createFromMnemonic(Networks.mainnet(), TEST_MNEMONIC, 0, 0);
        byte[] privateKey = account.privateKeyBytes();
        assertNotNull(privateKey);
        assertTrue(privateKey.length > 0);
    }

    @Test
    void testAccountPublicKey() {
        Account account = Account.createFromMnemonic(Networks.mainnet(), TEST_MNEMONIC, 0, 0);
        byte[] publicKey = account.publicKeyBytes();
        assertNotNull(publicKey);
        assertEquals(32, publicKey.length);
    }

    @Test
    void testAccountDrepId() {
        Account account = Account.createFromMnemonic(Networks.mainnet(), TEST_MNEMONIC, 0, 0);
        String drepId = account.drepId();
        assertNotNull(drepId);
        assertTrue(drepId.startsWith("drep1"));
    }

    @Test
    void testAccountDifferentIndices() {
        Account account0 = Account.createFromMnemonic(Networks.mainnet(), TEST_MNEMONIC, 0, 0);
        Account account1 = Account.createFromMnemonic(Networks.mainnet(), TEST_MNEMONIC, 0, 1);
        assertNotEquals(account0.baseAddress(), account1.baseAddress());
    }

    @Test
    void testAccountAddresses() {
        Account account = Account.createFromMnemonic(Networks.mainnet(), TEST_MNEMONIC, 0, 0);
        assertNotNull(account.baseAddress());
        assertNotNull(account.enterpriseAddress());
        assertNotNull(account.stakeAddress());
        assertNotNull(account.changeAddress());

        assertTrue(account.enterpriseAddress().startsWith("addr1"));
        assertTrue(account.stakeAddress().startsWith("stake1"));
    }

    // --- Negative / Error Tests ---

    @Test
    void testAccountFromInvalidMnemonic() {
        assertThrows(Exception.class, () ->
            Account.createFromMnemonic(Networks.mainnet(),
                "invalid words that are not a valid mnemonic phrase at all here now", 0, 0)
        );
    }

    @Test
    void testAccountFromEmptyMnemonic() {
        assertThrows(Exception.class, () ->
            Account.createFromMnemonic(Networks.mainnet(), "", 0, 0)
        );
    }

    @Test
    void testAccountFromPartialMnemonic() {
        // Only 6 words - too short for a valid mnemonic
        assertThrows(Exception.class, () ->
            Account.createFromMnemonic(Networks.mainnet(), "test walk nut penalty hip pave", 0, 0)
        );
    }
}
