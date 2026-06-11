package com.bloxbean.cardano.bridge.api;

import com.bloxbean.cardano.bridge.ErrorCodes;
import com.bloxbean.cardano.bridge.api.quicktx.QuickTxService;
import com.bloxbean.cardano.bridge.util.ErrorState;
import com.bloxbean.cardano.bridge.util.NativeString;
import com.bloxbean.cardano.bridge.util.ResultState;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;

/**
 * QuickTx entry point: build an unsigned transaction from a declarative JSON spec.
 *
 * <p>This is the bridge's transaction-construction engine. A single entry point takes a JSON
 * <em>spec</em> (the recipe) and returns the built transaction as CBOR. The fluent transaction
 * builders in the language wrappers are sugar that assemble this JSON spec; the spec is the real
 * API contract — see {@code docs/quicktx.md} for its full schema.
 *
 * <p>See {@link com.bloxbean.cardano.bridge.CclBridge} for the calling convention. The single entry
 * point here is a static GraalVM {@code @CEntryPoint}.
 */
public final class QuickTxApi {

    private static final QuickTxService service = new QuickTxService();

    private QuickTxApi() {}

    /**
     * Builds an unsigned transaction from a QuickTx JSON spec.
     *
     * <p>Exported as {@code ccl_quicktx_build}. The spec is a JSON object describing the transaction:
     * an {@code operations} array (e.g. {@code pay_to_address}, {@code mint_assets},
     * {@code register_stake_address}, {@code create_proposal}, Plutus {@code collect_from} with a
     * redeemer, …), plus {@code from}, and either inline {@code utxos} + {@code protocol_params}
     * (offline) or a {@code provider} URL (the bridge fetches them). Optional fields include
     * {@code change_address}, {@code fee_payer}, {@code validity}, {@code merge_outputs}, and
     * {@code signer_count} (for fee budgeting). See {@code docs/quicktx.md} for the full schema.
     *
     * <p>On success the result is a JSON object:
     * <pre>{@code {"tx_cbor","tx_hash","fee"}}</pre>
     * where {@code tx_cbor} is the unsigned transaction in CBOR (sign it with
     * {@code ccl_account_sign_tx} / {@code ccl_tx_sign_with_secret_key}, then submit it yourself).
     *
     * @param thread      the current isolate thread
     * @param specJsonPtr the QuickTx spec as JSON (UTF-8 C string)
     * @return {@link ErrorCodes#CCL_SUCCESS}; on failure
     *         {@link ErrorCodes#CCL_ERROR_INVALID_ARGUMENT} (bad spec),
     *         {@link ErrorCodes#CCL_ERROR_INSUFFICIENT_FUNDS}, or
     *         {@link ErrorCodes#CCL_ERROR_TX_BUILD}
     */
    @CEntryPoint(name = "ccl_quicktx_build")
    public static int build(IsolateThread thread, CCharPointer specJsonPtr) {
        try {
            String specJson = NativeString.toJavaString(specJsonPtr);
            if (specJson == null || specJson.isEmpty()) {
                ErrorState.set("Transaction spec JSON is required");
                return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
            }

            String resultJson = service.buildTransaction(specJson);
            ResultState.set(resultJson);
            return ErrorCodes.CCL_SUCCESS;
        } catch (IllegalArgumentException e) {
            ErrorState.set(e.getMessage());
            return ErrorCodes.CCL_ERROR_INVALID_ARGUMENT;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.toLowerCase().contains("not enough")) {
                ErrorState.set(msg);
                return ErrorCodes.CCL_ERROR_INSUFFICIENT_FUNDS;
            }
            ErrorState.set(msg != null ? msg : e.getClass().getName());
            return ErrorCodes.CCL_ERROR_TX_BUILD;
        }
    }
}
