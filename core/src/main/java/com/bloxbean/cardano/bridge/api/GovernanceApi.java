package com.bloxbean.cardano.bridge.api;

import com.bloxbean.cardano.bridge.ErrorCodes;
import com.bloxbean.cardano.bridge.util.*;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.governance.keys.CommitteeColdKey;
import com.bloxbean.cardano.client.governance.keys.CommitteeHotKey;
import com.bloxbean.cardano.client.governance.keys.DRepKey;
import com.bloxbean.cardano.client.util.HexUtil;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;

import java.util.LinkedHashMap;
import java.util.Map;

public final class GovernanceApi {

    private GovernanceApi() {}

    @CEntryPoint(name = "ccl_gov_drep_key_from_mnemonic")
    public static int drepKeyFromMnemonic(IsolateThread thread, CCharPointer mnemonicPtr,
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
            DRepKey drepKey = account.drepKey();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("drep_id", account.drepId());
            result.put("verification_key", HexUtil.encodeHexString(drepKey.verificationKey()));
            result.put("verification_key_hash", HexUtil.encodeHexString(drepKey.verificationKeyHash()));
            result.put("bech32_verification_key", drepKey.bech32VerificationKey());
            result.put("bech32_verification_key_hash", drepKey.bech32VerificationKeyHash());

            ResultState.set(JsonHelper.toJson(result));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_GENERAL;
        }
    }

    @CEntryPoint(name = "ccl_gov_committee_cold_key_from_mnemonic")
    public static int committeeColdKeyFromMnemonic(IsolateThread thread, CCharPointer mnemonicPtr,
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
            CommitteeColdKey coldKey = account.committeeColdKey();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", coldKey.id());
            result.put("verification_key", HexUtil.encodeHexString(coldKey.verificationKey()));
            result.put("verification_key_hash", HexUtil.encodeHexString(coldKey.verificationKeyHash()));
            result.put("bech32_verification_key", coldKey.bech32VerificationKey());
            result.put("bech32_verification_key_hash", coldKey.bech32VerificationKeyHash());

            ResultState.set(JsonHelper.toJson(result));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_GENERAL;
        }
    }

    @CEntryPoint(name = "ccl_gov_committee_hot_key_from_mnemonic")
    public static int committeeHotKeyFromMnemonic(IsolateThread thread, CCharPointer mnemonicPtr,
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
            CommitteeHotKey hotKey = account.committeeHotKey();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", hotKey.id());
            result.put("verification_key", HexUtil.encodeHexString(hotKey.verificationKey()));
            result.put("verification_key_hash", HexUtil.encodeHexString(hotKey.verificationKeyHash()));
            result.put("bech32_verification_key", hotKey.bech32VerificationKey());
            result.put("bech32_verification_key_hash", hotKey.bech32VerificationKeyHash());

            ResultState.set(JsonHelper.toJson(result));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_GENERAL;
        }
    }
}
