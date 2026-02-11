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

public final class AccountApi {

    private AccountApi() {}

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
