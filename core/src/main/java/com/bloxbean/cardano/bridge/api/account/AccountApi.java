package com.bloxbean.cardano.bridge.api.account;

import com.bloxbean.cardano.bridge.ErrorCodes;
import com.bloxbean.cardano.bridge.util.ErrorState;
import com.bloxbean.cardano.bridge.util.JsonHelper;
import com.bloxbean.cardano.bridge.util.NativeString;
import com.bloxbean.cardano.bridge.util.ResultState;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CLongPointer;

/**
 * Managed Account entry points (ADR-0016): open an account once and receive an opaque handle;
 * subsequent operations take the handle instead of the mnemonic.
 *
 * <p>Handles are isolate-scoped {@code uint64} identifiers (never 0). Unknown, closed, or foreign
 * handles fail with {@link ErrorCodes#CCL_ERROR_INVALID_HANDLE}. Closing is explicit and
 * idempotent; all handles die with the isolate.
 *
 * <p>See {@link com.bloxbean.cardano.bridge.CclBridge} for the calling convention.
 */
public final class AccountApi {

    private AccountApi() {}

    /**
     * Opens an account from a mnemonic at fixed derivation indices.
     *
     * <p>Exported as {@code ccl_account_open_mnemonic}. On success writes the handle to
     * {@code out_handle} and returns {@link ErrorCodes#CCL_SUCCESS}. The account's network and
     * derivation path are fixed for the handle's lifetime; no result string is produced (fetch
     * public data with {@code ccl_account_get_info}).
     *
     * @param thread       the current isolate thread
     * @param networkId    0=mainnet, 1=testnet, 2=preprod, 3=preview
     * @param mnemonicPtr  the BIP-39 mnemonic phrase (UTF-8 C string)
     * @param accountIndex HD account index (typically 0)
     * @param addressIndex HD address index (typically 0)
     * @param outHandle    receives the opaque account handle (must be non-null)
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_INVALID_ARGUMENT} /
     *         {@link ErrorCodes#CCL_ERROR_INVALID_NETWORK} /
     *         {@link ErrorCodes#CCL_ERROR_INVALID_MNEMONIC} / {@link ErrorCodes#CCL_ERROR_GENERAL}
     */
    @CEntryPoint(name = "ccl_account_open_mnemonic")
    public static int openMnemonic(IsolateThread thread, int networkId, CCharPointer mnemonicPtr,
                                   int accountIndex, int addressIndex, CLongPointer outHandle) {
        try {
            if (outHandle.isNull()) {
                ErrorState.set("out_handle must be non-null");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }
            String mnemonic = NativeString.toJavaString(mnemonicPtr);
            long handle = AccountService.openMnemonic(networkId, mnemonic, accountIndex, addressIndex);
            outHandle.write(handle);
            return ErrorCodes.CCL_SUCCESS;
        } catch (IllegalArgumentException e) {
            ErrorState.set(e.getMessage());
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("network")) {
                return ErrorCodes.CCL_ERROR_INVALID_NETWORK;
            }
            return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
        } catch (Exception e) {
            String msg = e.getMessage();
            ErrorState.set(msg);
            if (msg != null && msg.toLowerCase().contains("mnemonic")) {
                return ErrorCodes.CCL_ERROR_INVALID_MNEMONIC;
            }
            return ErrorCodes.CCL_ERROR_GENERAL;
        }
    }

    /**
     * Public information for an open account.
     *
     * <p>Exported as {@code ccl_account_get_info}. On success the result is a JSON object:
     * <pre>{@code {"base_address","enterprise_address","stake_address","network","account_index",
     * "address_index","drep_id"}}</pre>
     * It contains public data only — never the mnemonic or private keys.
     *
     * @param thread the current isolate thread
     * @param handle an open account handle
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_INVALID_HANDLE} /
     *         {@link ErrorCodes#CCL_ERROR_GENERAL}
     */
    @CEntryPoint(name = "ccl_account_get_info")
    public static int getInfo(IsolateThread thread, long handle) {
        try {
            ResultState.set(JsonHelper.toJson(AccountService.info(handle)));
            return ErrorCodes.CCL_SUCCESS;
        } catch (AccountService.UnknownHandleException e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_INVALID_HANDLE;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_GENERAL;
        }
    }

    /**
     * Signs a transaction with the account keys selected by a typed role mask.
     *
     * <p>Exported as {@code ccl_account_sign_tx_handle}. {@code role_mask} bits: {@code 1}=payment,
     * {@code 2}=stake, {@code 4}=DRep, {@code 8}=committee cold, {@code 16}=committee hot. The mask
     * is unordered (witnesses form a set); keys are applied in canonical order so signed outputs are
     * byte-identical across wrappers. A zero or unknown-bit mask fails — the API never silently
     * signs with every key the account controls. On success the result is the signed transaction
     * CBOR hex.
     *
     * @param thread      the current isolate thread
     * @param handle      an open account handle
     * @param txCborPtr   the unsigned (or partially signed) transaction CBOR hex (UTF-8 C string)
     * @param roleMask    bit mask of signing roles (non-zero, known bits only)
     * @return {@link ErrorCodes#CCL_SUCCESS}, or {@link ErrorCodes#CCL_ERROR_INVALID_HANDLE} /
     *         {@link ErrorCodes#CCL_ERROR_INVALID_ARGUMENT} /
     *         {@link ErrorCodes#CCL_ERROR_INVALID_TRANSACTION}
     */
    @CEntryPoint(name = "ccl_account_sign_tx_handle")
    public static int signTx(IsolateThread thread, long handle, CCharPointer txCborPtr, int roleMask) {
        try {
            String txCborHex = NativeString.toJavaString(txCborPtr);
            ResultState.set(AccountService.signTx(handle, txCborHex, roleMask));
            return ErrorCodes.CCL_SUCCESS;
        } catch (AccountService.UnknownHandleException e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_INVALID_HANDLE;
        } catch (IllegalArgumentException e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_INVALID_TRANSACTION;
        }
    }

    /**
     * Closes an account handle, releasing its native state.
     *
     * <p>Exported as {@code ccl_account_close}. Idempotent: closing an unknown or already-closed
     * handle succeeds (returns {@link ErrorCodes#CCL_SUCCESS}) — double-close must be safe for
     * wrapper finalizers.
     *
     * @param thread the current isolate thread
     * @param handle the account handle to close
     * @return {@link ErrorCodes#CCL_SUCCESS}
     */
    @CEntryPoint(name = "ccl_account_close")
    public static int close(IsolateThread thread, long handle) {
        try {
            AccountService.close(handle);
            return ErrorCodes.CCL_SUCCESS;
        } catch (Exception e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_GENERAL;
        }
    }
}
