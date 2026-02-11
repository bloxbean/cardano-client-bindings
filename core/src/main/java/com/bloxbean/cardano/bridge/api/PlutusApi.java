package com.bloxbean.cardano.bridge.api;

import com.bloxbean.cardano.bridge.ErrorCodes;
import com.bloxbean.cardano.bridge.util.ErrorState;
import com.bloxbean.cardano.bridge.util.NativeString;
import com.bloxbean.cardano.bridge.util.ResultState;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;

public final class PlutusApi {

    private PlutusApi() {}

    @CEntryPoint(name = "ccl_plutus_data_hash")
    public static int datumHash(IsolateThread thread, CCharPointer datumCborHexPtr) {
        try {
            String datumCborHex = NativeString.toJavaString(datumCborHexPtr);
            if (datumCborHex == null || datumCborHex.isEmpty()) {
                ErrorState.set("Datum CBOR hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            byte[] datumBytes = HexUtil.decodeHexString(datumCborHex);
            PlutusData plutusData = PlutusData.deserialize(datumBytes);
            String hash = plutusData.getDatumHash();
            ResultState.set(hash);
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_SERIALIZATION;
        }
    }

    @CEntryPoint(name = "ccl_plutus_data_to_json")
    public static int toJson(IsolateThread thread, CCharPointer cborHexPtr) {
        try {
            String cborHex = NativeString.toJavaString(cborHexPtr);
            if (cborHex == null || cborHex.isEmpty()) {
                ErrorState.set("CBOR hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            byte[] bytes = HexUtil.decodeHexString(cborHex);
            PlutusData plutusData = PlutusData.deserialize(bytes);
            String json = com.bloxbean.cardano.bridge.util.JsonHelper.toJson(plutusData);
            ResultState.set(json);
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_SERIALIZATION;
        }
    }

    @CEntryPoint(name = "ccl_plutus_data_from_json")
    public static int fromJson(IsolateThread thread, CCharPointer jsonPtr) {
        try {
            String json = NativeString.toJavaString(jsonPtr);
            if (json == null || json.isEmpty()) {
                ErrorState.set("JSON is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            PlutusData plutusData = com.bloxbean.cardano.bridge.util.JsonHelper.fromJson(json, PlutusData.class);
            ResultState.set(plutusData.serializeToHex());
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_SERIALIZATION;
        }
    }
}
