package com.bloxbean.cardano.bridge.api;

import com.bloxbean.cardano.bridge.util.ResultState;
import com.bloxbean.cardano.bridge.util.ErrorState;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.MnemonicUtil;
import com.bloxbean.cardano.client.crypto.bip39.Words;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CryptoApiTest {

    @BeforeEach
    void setUp() {
        ResultState.clear();
        ErrorState.clear();
    }

    @Test
    void testBlake2b256() {
        byte[] data = "hello".getBytes();
        byte[] hash = Blake2bUtil.blake2bHash256(data);
        assertNotNull(hash);
        assertEquals(32, hash.length);
    }

    @Test
    void testBlake2b224() {
        byte[] data = "hello".getBytes();
        byte[] hash = Blake2bUtil.blake2bHash224(data);
        assertNotNull(hash);
        assertEquals(28, hash.length);
    }

    @Test
    void testGenerateMnemonic24() {
        String mnemonic = MnemonicUtil.generateNew(Words.TWENTY_FOUR);
        assertNotNull(mnemonic);
        String[] words = mnemonic.split(" ");
        assertEquals(24, words.length);
    }

    @Test
    void testGenerateMnemonic12() {
        String mnemonic = MnemonicUtil.generateNew(Words.TWELVE);
        assertNotNull(mnemonic);
        String[] words = mnemonic.split(" ");
        assertEquals(12, words.length);
    }

    @Test
    void testValidateMnemonic() {
        String mnemonic = MnemonicUtil.generateNew(Words.TWENTY_FOUR);
        assertDoesNotThrow(() -> MnemonicUtil.validateMnemonic(mnemonic));
    }

    @Test
    void testInvalidMnemonic() {
        assertThrows(Exception.class, () ->
            MnemonicUtil.validateMnemonic("invalid mnemonic phrase that is not valid at all")
        );
    }

    @Test
    void testSignAndVerify() {
        // Generate a key pair from an account (uses BIP32-ED25519 extended 64-byte keys)
        com.bloxbean.cardano.client.account.Account account =
            new com.bloxbean.cardano.client.account.Account(
                com.bloxbean.cardano.client.common.model.Networks.mainnet());

        byte[] message = "test message".getBytes();
        byte[] privateKey = account.privateKeyBytes();
        byte[] publicKey = account.publicKeyBytes();

        // Use signExtended for BIP32-ED25519 extended private keys (64 bytes)
        byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider().signExtended(message, privateKey);
        assertNotNull(signature);
        assertEquals(64, signature.length);

        boolean valid = CryptoConfiguration.INSTANCE.getSigningProvider().verify(signature, message, publicKey);
        assertTrue(valid);
    }

    @Test
    void testVerifyWithWrongKey() {
        com.bloxbean.cardano.client.account.Account account1 =
            new com.bloxbean.cardano.client.account.Account(
                com.bloxbean.cardano.client.common.model.Networks.mainnet());
        com.bloxbean.cardano.client.account.Account account2 =
            new com.bloxbean.cardano.client.account.Account(
                com.bloxbean.cardano.client.common.model.Networks.mainnet());

        byte[] message = "test message".getBytes();
        byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider().signExtended(message, account1.privateKeyBytes());
        boolean valid = CryptoConfiguration.INSTANCE.getSigningProvider().verify(signature, message, account2.publicKeyBytes());
        assertFalse(valid);
    }
}
