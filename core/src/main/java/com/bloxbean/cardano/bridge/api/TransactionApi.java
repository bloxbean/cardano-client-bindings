package com.bloxbean.cardano.bridge.api;

import com.bloxbean.cardano.bridge.ErrorCodes;
import com.bloxbean.cardano.bridge.util.ErrorState;
import com.bloxbean.cardano.bridge.util.NativeString;
import com.bloxbean.cardano.bridge.util.ResultState;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;

public final class TransactionApi {

    private TransactionApi() {}

    @CEntryPoint(name = "ccl_tx_sign_with_secret_key")
    public static int signWithSecretKey(IsolateThread thread, CCharPointer txCborHexPtr,
                                        CCharPointer skCborHexPtr) {
        try {
            String txCborHex = NativeString.toJavaString(txCborHexPtr);
            String skCborHex = NativeString.toJavaString(skCborHexPtr);

            if (txCborHex == null || txCborHex.isEmpty()) {
                ErrorState.set("Transaction CBOR hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }
            if (skCborHex == null || skCborHex.isEmpty()) {
                ErrorState.set("Secret key CBOR hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            Transaction tx = Transaction.deserialize(HexUtil.decodeHexString(txCborHex));
            SecretKey sk = new SecretKey(skCborHex);
            Transaction signedTx = TransactionSigner.INSTANCE.sign(tx, sk);
            ResultState.set(signedTx.serializeToHex());
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_INVALID_TRANSACTION;
        }
    }

    @CEntryPoint(name = "ccl_tx_hash")
    public static int hash(IsolateThread thread, CCharPointer txCborHexPtr) {
        try {
            String txCborHex = NativeString.toJavaString(txCborHexPtr);
            if (txCborHex == null || txCborHex.isEmpty()) {
                ErrorState.set("Transaction CBOR hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            Transaction tx = Transaction.deserialize(HexUtil.decodeHexString(txCborHex));
            byte[] txBodyBytes = CborSerializationUtil.serialize(tx.getBody().serialize());
            byte[] txHash = Blake2bUtil.blake2bHash256(txBodyBytes);
            ResultState.set(HexUtil.encodeHexString(txHash));
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_INVALID_TRANSACTION;
        }
    }

    @CEntryPoint(name = "ccl_tx_to_json")
    public static int toJson(IsolateThread thread, CCharPointer txCborHexPtr) {
        try {
            String txCborHex = NativeString.toJavaString(txCborHexPtr);
            if (txCborHex == null || txCborHex.isEmpty()) {
                ErrorState.set("Transaction CBOR hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            Transaction tx = Transaction.deserialize(HexUtil.decodeHexString(txCborHex));
            String json = com.bloxbean.cardano.bridge.util.JsonHelper.toJson(tx);
            ResultState.set(json);
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_SERIALIZATION;
        }
    }

    @CEntryPoint(name = "ccl_tx_from_json")
    public static int fromJson(IsolateThread thread, CCharPointer txJsonPtr) {
        try {
            String txJson = NativeString.toJavaString(txJsonPtr);
            if (txJson == null || txJson.isEmpty()) {
                ErrorState.set("Transaction JSON is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            Transaction tx = com.bloxbean.cardano.bridge.util.JsonHelper.fromJson(txJson, Transaction.class);
            ResultState.set(tx.serializeToHex());
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_SERIALIZATION;
        }
    }

    @CEntryPoint(name = "ccl_tx_deserialize")
    public static int deserialize(IsolateThread thread, CCharPointer txCborHexPtr) {
        try {
            String txCborHex = NativeString.toJavaString(txCborHexPtr);
            if (txCborHex == null || txCborHex.isEmpty()) {
                ErrorState.set("Transaction CBOR hex is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            Transaction tx = Transaction.deserialize(HexUtil.decodeHexString(txCborHex));
            String json = com.bloxbean.cardano.bridge.util.JsonHelper.toJson(tx);
            ResultState.set(json);
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_SERIALIZATION;
        }
    }
}
