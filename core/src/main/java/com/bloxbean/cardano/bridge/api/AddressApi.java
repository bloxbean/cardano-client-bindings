package com.bloxbean.cardano.bridge.api;

import com.bloxbean.cardano.bridge.ErrorCodes;
import com.bloxbean.cardano.bridge.util.*;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.util.HexUtil;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AddressApi {

    private AddressApi() {}

    @CEntryPoint(name = "ccl_address_info")
    public static int info(IsolateThread thread, CCharPointer bech32Ptr) {
        try {
            String bech32 = NativeString.toJavaString(bech32Ptr);
            if (bech32 == null || bech32.isEmpty()) {
                ErrorState.set("Address is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            Address address = new Address(bech32);
            Map<String, Object> result = new LinkedHashMap<>();

            AddressType type = address.getAddressType();
            result.put("type", type != null ? type.name() : "Unknown");

            Network network = address.getNetwork();
            result.put("network_id", network != null ? network.getNetworkId() : -1);

            address.getPaymentCredentialHash().ifPresent(hash ->
                result.put("payment_credential_hash", HexUtil.encodeHexString(hash))
            );

            address.getDelegationCredentialHash().ifPresent(hash ->
                result.put("delegation_credential_hash", HexUtil.encodeHexString(hash))
            );

            result.put("is_pubkey_payment", address.isPubKeyHashInPaymentPart());
            result.put("is_script_payment", address.isScriptHashInPaymentPart());

            ResultState.set(JsonHelper.toJson(result));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_INVALID_ADDRESS;
        }
    }

    @CEntryPoint(name = "ccl_address_to_bytes")
    public static int toBytes(IsolateThread thread, CCharPointer bech32Ptr) {
        try {
            String bech32 = NativeString.toJavaString(bech32Ptr);
            if (bech32 == null || bech32.isEmpty()) {
                ErrorState.set("Address is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            Address address = new Address(bech32);
            ResultState.set(HexUtil.encodeHexString(address.getBytes()));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_INVALID_ADDRESS;
        }
    }

    @CEntryPoint(name = "ccl_address_from_bytes")
    public static int fromBytes(IsolateThread thread, CCharPointer hexBytesPtr) {
        try {
            String hexBytes = NativeString.toJavaString(hexBytesPtr);
            if (hexBytes == null || hexBytes.isEmpty()) {
                ErrorState.set("Hex bytes are required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            byte[] bytes = HexUtil.decodeHexString(hexBytes);
            Address address = new Address(bytes);
            ResultState.set(address.toBech32());
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_INVALID_ADDRESS;
        }
    }

    @CEntryPoint(name = "ccl_address_validate")
    public static int validate(IsolateThread thread, CCharPointer bech32Ptr) {
        try {
            String bech32 = NativeString.toJavaString(bech32Ptr);
            if (bech32 == null || bech32.isEmpty()) {
                ErrorState.set("Address is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            // Try to parse - if it doesn't throw, it's valid
            new Address(bech32);
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_INVALID_ADDRESS;
        }
    }
}
