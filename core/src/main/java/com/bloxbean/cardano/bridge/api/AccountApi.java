package com.bloxbean.cardano.bridge.api;

import com.bloxbean.cardano.bridge.ErrorCodes;
import com.bloxbean.cardano.bridge.util.*;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Account entry points: deterministic (HD) account creation, key derivation, and signing.
 *
 * <p>An "account" is a CIP-1852 HD account derived from a BIP-39 mnemonic. Methods that take a
 * {@code networkId} use {@code 0}=mainnet, {@code 1}=testnet, {@code 2}=preprod, {@code 3}=preview.
 * The {@code accountIndex}/{@code addressIndex} select the HD derivation path
 * ({@code m/1852'/1815'/account'/role/address}).
 *
 * <p>See {@link com.bloxbean.cardano.bridge.CclBridge} for the calling convention (status code +
 * thread-local result retrieved via {@code ccl_get_result}). Every entry point here is a static
 * GraalVM {@code @CEntryPoint}.
 */
public final class AccountApi {

    private AccountApi() {}

    /**
     * Creates a brand-new account with a freshly generated 24-word mnemonic.
     *
     * <p>Exported as {@code ccl_account_create}. On success the result is a JSON object:
     * <pre>{@code {"mnemonic","base_address","enterprise_address","stake_address","change_address"}}</pre>
     *
     * @param thread    the current isolate thread
     * @param networkId 0=mainnet, 1=testnet, 2=preprod, 3=preview
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_INVALID_NETWORK} /
     *         {@link ErrorCodes#CCL_ERROR_GENERAL}
     */
    @CEntryPoint(name = "ccl_account_create")
    public static int create(IsolateThread thread, int networkId) {
        try {
            Network network = NetworkMapper.toNetwork(networkId);
            if (network == null) {
                ErrorState.set("Invalid network id: " + networkId);
                return ErrorCodes.CCL_ERROR_INVALID_NETWORK;
            }

            Account account = new Account(network);
            Map<String, Object> result = buildAccountJson(account);
            ResultState.set(JsonHelper.toJson(result));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_GENERAL;
        }
    }

    /**
     * Restores an account from an existing mnemonic at the given derivation indices.
     *
     * <p>Exported as {@code ccl_account_from_mnemonic}. On success the result is the same JSON
     * object as {@code ccl_account_create}.
     *
     * @param thread       the current isolate thread
     * @param networkId    0=mainnet, 1=testnet, 2=preprod, 3=preview
     * @param mnemonicPtr  the BIP-39 mnemonic phrase (UTF-8 C string)
     * @param accountIndex HD account index (typically 0)
     * @param addressIndex HD address index (typically 0)
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_INVALID_NETWORK} /
     *         {@link ErrorCodes#CCL_ERROR_INVALID_MNEMONIC} / {@link ErrorCodes#CCL_ERROR_GENERAL}
     */
    @CEntryPoint(name = "ccl_account_from_mnemonic")
    public static int fromMnemonic(IsolateThread thread, int networkId,
                                   CCharPointer mnemonicPtr,
                                   int accountIndex, int addressIndex) {
        try {
            Network network = NetworkMapper.toNetwork(networkId);
            if (network == null) {
                ErrorState.set("Invalid network id: " + networkId);
                return ErrorCodes.CCL_ERROR_INVALID_NETWORK;
            }

            String mnemonic = NativeString.toJavaString(mnemonicPtr);
            if (mnemonic == null || mnemonic.isEmpty()) {
                ErrorState.set("Mnemonic is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            Account account = Account.createFromMnemonic(network, mnemonic, accountIndex, addressIndex);
            Map<String, Object> result = buildAccountJson(account);
            ResultState.set(JsonHelper.toJson(result));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.toLowerCase().contains("mnemonic")) {
                ErrorState.set(msg);
                return ErrorCodes.CCL_ERROR_INVALID_MNEMONIC;
            }
            ErrorState.set(msg);
            return ErrorCodes.CCL_ERROR_GENERAL;
        }
    }

    /**
     * Derives the account's extended private key.
     *
     * <p>Exported as {@code ccl_account_get_private_key}. On success the result is the hex-encoded
     * 64-byte extended BIP32-Ed25519 payment private key (128 hex chars). Note: a raw 32-byte
     * Ed25519 key (e.g. for {@code ccl_crypto_sign}) is the first 32 bytes / 64 hex chars.
     *
     * @param thread       the current isolate thread
     * @param mnemonicPtr  the BIP-39 mnemonic phrase (UTF-8 C string)
     * @param networkId    0=mainnet, 1=testnet, 2=preprod, 3=preview
     * @param accountIndex HD account index
     * @param addressIndex HD address index
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_INVALID_NETWORK} /
     *         {@link ErrorCodes#CCL_ERROR_CRYPTO}
     */
    @CEntryPoint(name = "ccl_account_get_private_key")
    public static int getPrivateKey(IsolateThread thread, CCharPointer mnemonicPtr,
                                    int networkId, int accountIndex, int addressIndex) {
        try {
            Network network = NetworkMapper.toNetwork(networkId);
            if (network == null) {
                ErrorState.set("Invalid network id: " + networkId);
                return ErrorCodes.CCL_ERROR_INVALID_NETWORK;
            }

            String mnemonic = NativeString.toJavaString(mnemonicPtr);
            if (mnemonic == null || mnemonic.isEmpty()) {
                ErrorState.set("Mnemonic is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            Account account = Account.createFromMnemonic(network, mnemonic, accountIndex, addressIndex);
            byte[] privateKeyBytes = account.privateKeyBytes();
            ResultState.set(HexUtil.encodeHexString(privateKeyBytes));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_CRYPTO;
        }
    }

    /**
     * Derives the account's public key.
     *
     * <p>Exported as {@code ccl_account_get_public_key}. On success the result is the hex-encoded
     * 32-byte Ed25519 payment public key (64 hex chars).
     *
     * @param thread       the current isolate thread
     * @param mnemonicPtr  the BIP-39 mnemonic phrase (UTF-8 C string)
     * @param networkId    0=mainnet, 1=testnet, 2=preprod, 3=preview
     * @param accountIndex HD account index
     * @param addressIndex HD address index
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_INVALID_NETWORK} /
     *         {@link ErrorCodes#CCL_ERROR_CRYPTO}
     */
    @CEntryPoint(name = "ccl_account_get_public_key")
    public static int getPublicKey(IsolateThread thread, CCharPointer mnemonicPtr,
                                   int networkId, int accountIndex, int addressIndex) {
        try {
            Network network = NetworkMapper.toNetwork(networkId);
            if (network == null) {
                ErrorState.set("Invalid network id: " + networkId);
                return ErrorCodes.CCL_ERROR_INVALID_NETWORK;
            }

            String mnemonic = NativeString.toJavaString(mnemonicPtr);
            if (mnemonic == null || mnemonic.isEmpty()) {
                ErrorState.set("Mnemonic is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            Account account = Account.createFromMnemonic(network, mnemonic, accountIndex, addressIndex);
            byte[] publicKeyBytes = account.publicKeyBytes();
            ResultState.set(HexUtil.encodeHexString(publicKeyBytes));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_CRYPTO;
        }
    }

    /**
     * Signs a transaction with the account's <em>payment</em> key.
     *
     * <p>Exported as {@code ccl_account_sign_tx}. On success the result is the signed transaction as
     * CBOR hex. Only the payment key is added; transactions whose certificates must be authorized by
     * the stake key (e.g. vote-power / stake delegation) also need the stake key, which this entry
     * point does not yet add.
     *
     * @param thread       the current isolate thread
     * @param mnemonicPtr  the BIP-39 mnemonic phrase (UTF-8 C string)
     * @param networkId    0=mainnet, 1=testnet, 2=preprod, 3=preview
     * @param accountIndex HD account index
     * @param addressIndex HD address index
     * @param txCborHexPtr the unsigned (or partially signed) transaction as CBOR hex
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_INVALID_NETWORK} /
     *         {@link ErrorCodes#CCL_ERROR_INVALID_TRANSACTION}
     */
    @CEntryPoint(name = "ccl_account_sign_tx")
    public static int signTx(IsolateThread thread, CCharPointer mnemonicPtr,
                             int networkId, int accountIndex, int addressIndex,
                             CCharPointer txCborHexPtr) {
        try {
            Network network = NetworkMapper.toNetwork(networkId);
            if (network == null) {
                ErrorState.set("Invalid network id: " + networkId);
                return ErrorCodes.CCL_ERROR_INVALID_NETWORK;
            }

            String mnemonic = NativeString.toJavaString(mnemonicPtr);
            if (mnemonic == null || mnemonic.isEmpty()) {
                ErrorState.set("Mnemonic is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            String txCborHex = NativeString.toJavaString(txCborHexPtr);
            if (txCborHex == null || txCborHex.isEmpty()) {
                ErrorState.set("Transaction CBOR hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            Account account = Account.createFromMnemonic(network, mnemonic, accountIndex, addressIndex);
            Transaction tx = Transaction.deserialize(HexUtil.decodeHexString(txCborHex));
            Transaction signedTx = account.sign(tx);
            ResultState.set(signedTx.serializeToHex());
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_INVALID_TRANSACTION;
        }
    }

    /**
     * Derives the account's governance DRep ID.
     *
     * <p>Exported as {@code ccl_account_get_drep_id}. On success the result is the bech32 DRep ID
     * (e.g. {@code drep1...}). Uses address index 0.
     *
     * @param thread       the current isolate thread
     * @param mnemonicPtr  the BIP-39 mnemonic phrase (UTF-8 C string)
     * @param networkId    0=mainnet, 1=testnet, 2=preprod, 3=preview
     * @param accountIndex HD account index
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_INVALID_NETWORK} /
     *         {@link ErrorCodes#CCL_ERROR_GENERAL}
     */
    @CEntryPoint(name = "ccl_account_get_drep_id")
    public static int getDrepId(IsolateThread thread, CCharPointer mnemonicPtr,
                                int networkId, int accountIndex) {
        try {
            Network network = NetworkMapper.toNetwork(networkId);
            if (network == null) {
                ErrorState.set("Invalid network id: " + networkId);
                return ErrorCodes.CCL_ERROR_INVALID_NETWORK;
            }

            String mnemonic = NativeString.toJavaString(mnemonicPtr);
            if (mnemonic == null || mnemonic.isEmpty()) {
                ErrorState.set("Mnemonic is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            Account account = Account.createFromMnemonic(network, mnemonic, accountIndex, 0);
            String drepId = account.drepId();
            ResultState.set(drepId);
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_GENERAL;
        }
    }

    private static Map<String, Object> buildAccountJson(Account account) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mnemonic", account.mnemonic());
        map.put("base_address", account.baseAddress());
        map.put("enterprise_address", account.enterpriseAddress());
        map.put("stake_address", account.stakeAddress());
        map.put("change_address", account.changeAddress());
        return map;
    }
}
