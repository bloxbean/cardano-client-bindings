package com.bloxbean.cardano.bridge.api;

import com.bloxbean.cardano.bridge.ErrorCodes;
import com.bloxbean.cardano.bridge.util.ErrorState;
import com.bloxbean.cardano.bridge.util.NativeString;
import com.bloxbean.cardano.bridge.util.ResultState;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.script.NativeScript;
import com.bloxbean.cardano.client.util.HexUtil;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ScriptApi {

    private ScriptApi() {}

    @CEntryPoint(name = "ccl_script_native_from_json")
    public static int nativeScriptFromJson(IsolateThread thread, CCharPointer jsonPtr) {
        try {
            String json = NativeString.toJavaString(jsonPtr);
            if (json == null || json.isEmpty()) {
                ErrorState.set("JSON is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            NativeScript script = NativeScript.deserializeJson(json);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("policy_id", script.getPolicyId());
            result.put("script_hash", HexUtil.encodeHexString(script.getScriptHash()));
            result.put("cbor_hex", HexUtil.encodeHexString(script.serialize()));

            ResultState.set(com.bloxbean.cardano.bridge.util.JsonHelper.toJson(result));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_SERIALIZATION;
        }
    }

    @CEntryPoint(name = "ccl_script_hash")
    public static int scriptHash(IsolateThread thread, CCharPointer scriptCborHexPtr, int scriptType) {
        try {
            String scriptCborHex = NativeString.toJavaString(scriptCborHexPtr);
            if (scriptCborHex == null || scriptCborHex.isEmpty()) {
                ErrorState.set("Script CBOR hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            byte[] scriptBytes = HexUtil.decodeHexString(scriptCborHex);

            // For all scripts: hash is blake2b-224 of (type_byte || script_bytes)
            byte[] prefixed = new byte[scriptBytes.length + 1];
            prefixed[0] = (byte) scriptType;
            System.arraycopy(scriptBytes, 0, prefixed, 1, scriptBytes.length);
            byte[] hash = Blake2bUtil.blake2bHash224(prefixed);
            ResultState.set(HexUtil.encodeHexString(hash));

            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_SERIALIZATION;
        }
    }
}
