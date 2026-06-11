package com.bloxbean.cardano.bridge.api;

import com.bloxbean.cardano.bridge.ErrorCodes;
import com.bloxbean.cardano.bridge.util.*;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.hdwallet.Wallet;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HD wallet entry points: create/restore a wallet and derive its addresses.
 *
 * <p>Unlike {@link AccountApi} (a single account), a {@code Wallet} exposes a sequence of addresses.
 * {@code networkId} uses {@code 0}=mainnet, {@code 1}=testnet, {@code 2}=preprod, {@code 3}=preview.
 * See {@link com.bloxbean.cardano.bridge.CclBridge} for the calling convention. Every entry point
 * here is a static GraalVM {@code @CEntryPoint}.
 */
public final class WalletApi {

    private static final int DEFAULT_ADDRESS_COUNT = 5;

    private WalletApi() {}

    /**
     * Creates a new HD wallet with a freshly generated mnemonic.
     *
     * <p>Exported as {@code ccl_wallet_create}. On success the result is a JSON object:
     * <pre>{@code {"mnemonic","stake_address","addresses":[ ...first 5 base addresses... ]}}</pre>
     *
     * @param thread    the current isolate thread
     * @param networkId 0=mainnet, 1=testnet, 2=preprod, 3=preview
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_INVALID_NETWORK} /
     *         {@link ErrorCodes#CCL_ERROR_GENERAL}
     */
    @CEntryPoint(name = "ccl_wallet_create")
    public static int create(IsolateThread thread, int networkId) {
        try {
            Network network = NetworkMapper.toNetwork(networkId);
            if (network == null) {
                ErrorState.set("Invalid network id: " + networkId);
                return ErrorCodes.CCL_ERROR_INVALID_NETWORK;
            }

            Wallet wallet = Wallet.create(network);
            Map<String, Object> result = buildWalletJson(wallet, DEFAULT_ADDRESS_COUNT);
            ResultState.set(JsonHelper.toJson(result));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_GENERAL;
        }
    }

    /**
     * Restores an HD wallet from an existing mnemonic.
     *
     * <p>Exported as {@code ccl_wallet_from_mnemonic}. On success the result is the same JSON object
     * as {@code ccl_wallet_create}.
     *
     * @param thread      the current isolate thread
     * @param mnemonicPtr the BIP-39 mnemonic phrase (UTF-8 C string)
     * @param networkId   0=mainnet, 1=testnet, 2=preprod, 3=preview
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_INVALID_NETWORK} /
     *         {@link ErrorCodes#CCL_ERROR_GENERAL}
     */
    @CEntryPoint(name = "ccl_wallet_from_mnemonic")
    public static int fromMnemonic(IsolateThread thread, CCharPointer mnemonicPtr, int networkId) {
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

            Wallet wallet = Wallet.createFromMnemonic(network, mnemonic);
            Map<String, Object> result = buildWalletJson(wallet, DEFAULT_ADDRESS_COUNT);
            ResultState.set(JsonHelper.toJson(result));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_GENERAL;
        }
    }

    /**
     * Derives a single base address at the given index from a wallet's mnemonic.
     *
     * <p>Exported as {@code ccl_wallet_get_address}. On success the result is the bech32 base address
     * at {@code index}.
     *
     * @param thread      the current isolate thread
     * @param mnemonicPtr the BIP-39 mnemonic phrase (UTF-8 C string)
     * @param networkId   0=mainnet, 1=testnet, 2=preprod, 3=preview
     * @param index       the address index to derive
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_INVALID_NETWORK} /
     *         {@link ErrorCodes#CCL_ERROR_GENERAL}
     */
    @CEntryPoint(name = "ccl_wallet_get_address")
    public static int getAddress(IsolateThread thread, CCharPointer mnemonicPtr,
                                 int networkId, int index) {
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

            Wallet wallet = Wallet.createFromMnemonic(network, mnemonic);
            String address = wallet.getBaseAddressString(index);
            ResultState.set(address);
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_GENERAL;
        }
    }

    private static Map<String, Object> buildWalletJson(Wallet wallet, int addressCount) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mnemonic", wallet.getMnemonic());
        map.put("stake_address", wallet.getStakeAddress());

        List<String> addresses = new ArrayList<>();
        for (int i = 0; i < addressCount; i++) {
            addresses.add(wallet.getBaseAddressString(i));
        }
        map.put("addresses", addresses);
        return map;
    }
}
