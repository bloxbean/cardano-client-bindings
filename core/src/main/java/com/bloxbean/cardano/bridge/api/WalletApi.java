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

public final class WalletApi {

    private static final int DEFAULT_ADDRESS_COUNT = 5;

    private WalletApi() {}

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
