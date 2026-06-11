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

/**
 * Governance key-derivation entry points (Conway era): DRep and constitutional-committee keys.
 *
 * <p>All keys are derived from a mnemonic via CIP-1852/CIP-105 paths. {@code networkId} uses
 * {@code 0}=mainnet, {@code 1}=testnet, {@code 2}=preprod, {@code 3}=preview; address index is 0.
 * See {@link com.bloxbean.cardano.bridge.CclBridge} for the calling convention. Every entry point
 * here is a static GraalVM {@code @CEntryPoint}.
 */
public final class GovernanceApi {

    private GovernanceApi() {}

    /**
     * Derives the DRep key pair from a mnemonic.
     *
     * <p>Exported as {@code ccl_gov_drep_key_from_mnemonic}. On success the result is a JSON object:
     * <pre>{@code {"drep_id","verification_key","verification_key_hash",
     *  "bech32_verification_key","bech32_verification_key_hash"}}</pre>
     *
     * @param thread       the current isolate thread
     * @param mnemonicPtr  the BIP-39 mnemonic phrase (UTF-8 C string)
     * @param networkId    0=mainnet, 1=testnet, 2=preprod, 3=preview
     * @param accountIndex HD account index
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_INVALID_NETWORK} /
     *         {@link ErrorCodes#CCL_ERROR_GENERAL}
     */
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

    /**
     * Derives the constitutional-committee <em>cold</em> key pair from a mnemonic.
     *
     * <p>Exported as {@code ccl_gov_committee_cold_key_from_mnemonic}. On success the result is a
     * JSON object:
     * <pre>{@code {"id","verification_key","verification_key_hash",
     *  "bech32_verification_key","bech32_verification_key_hash"}}</pre>
     *
     * @param thread       the current isolate thread
     * @param mnemonicPtr  the BIP-39 mnemonic phrase (UTF-8 C string)
     * @param networkId    0=mainnet, 1=testnet, 2=preprod, 3=preview
     * @param accountIndex HD account index
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_INVALID_NETWORK} /
     *         {@link ErrorCodes#CCL_ERROR_GENERAL}
     */
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

    /**
     * Derives the constitutional-committee <em>hot</em> key pair from a mnemonic.
     *
     * <p>Exported as {@code ccl_gov_committee_hot_key_from_mnemonic}. On success the result is the
     * same JSON shape as {@code ccl_gov_committee_cold_key_from_mnemonic}
     * ({@code id}, {@code verification_key}, {@code verification_key_hash}, and the bech32 forms).
     *
     * @param thread       the current isolate thread
     * @param mnemonicPtr  the BIP-39 mnemonic phrase (UTF-8 C string)
     * @param networkId    0=mainnet, 1=testnet, 2=preprod, 3=preview
     * @param accountIndex HD account index
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_INVALID_NETWORK} /
     *         {@link ErrorCodes#CCL_ERROR_GENERAL}
     */
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
