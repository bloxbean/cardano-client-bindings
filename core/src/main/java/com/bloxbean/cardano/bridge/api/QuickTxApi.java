package com.bloxbean.cardano.bridge.api;

import com.bloxbean.cardano.bridge.ErrorCodes;
import com.bloxbean.cardano.bridge.api.quicktx.QuickTxService;
import com.bloxbean.cardano.bridge.util.ErrorState;
import com.bloxbean.cardano.bridge.util.NativeString;
import com.bloxbean.cardano.bridge.util.ResultState;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;

public final class QuickTxApi {

    private static final QuickTxService service = new QuickTxService();

    private QuickTxApi() {}

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
