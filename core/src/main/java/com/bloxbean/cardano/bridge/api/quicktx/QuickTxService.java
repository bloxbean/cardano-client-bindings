package com.bloxbean.cardano.bridge.api.quicktx;

import com.bloxbean.cardano.bridge.util.JsonHelper;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.serialization.TxPlan;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds unsigned Cardano transactions from a CCL {@link TxPlan} (YAML), fully offline.
 *
 * <p>The transaction is defined by a TxPlan YAML document; the caller supplies the chain data
 * (UTXOs and protocol parameters) as JSON. No backend/provider is used and the transaction is never
 * submitted — the result is the unsigned CBOR plus its hash and fee.
 *
 * <p>Plutus script transactions are not yet supported here: building one requires execution-unit
 * evaluation, for which there is no offline evaluator. Such a build fails with a clear error.
 */
public class QuickTxService {

    /**
     * Build an unsigned transaction from a TxPlan YAML document and caller-supplied chain data.
     *
     * @param yaml               the TxPlan YAML defining the transaction(s)
     * @param utxosJson          JSON array of UTXOs available to the sender (CCL {@code Utxo} model)
     * @param protocolParamsJson JSON protocol parameters (CCL {@code ProtocolParams} model)
     * @return JSON string with {@code tx_cbor}, {@code tx_hash}, {@code fee}
     */
    public String buildTransaction(String yaml, String utxosJson, String protocolParamsJson) throws Exception {
        TxPlan plan = TxPlan.from(yaml);

        List<Utxo> utxos = parseUtxos(utxosJson);
        ProtocolParams protocolParams = JsonHelper.fromJson(protocolParamsJson, ProtocolParams.class);

        UtxoSupplier utxoSupplier = new StaticUtxoSupplier(utxos);
        ProtocolParamsSupplier ppSupplier = () -> protocolParams;

        // No TransactionProcessor (offline; never submits). compose(plan) applies the plan's
        // context (fee payer, validity, deposit mode, required signers, …) to the TxContext.
        QuickTxBuilder builder = new QuickTxBuilder(utxoSupplier, ppSupplier, null);
        QuickTxBuilder.TxContext txContext = builder.compose(plan);

        // Budget witnesses for fee estimation of the (still unsigned) transaction.
        txContext.additionalSignersCount(Math.max(1, plan.getTxs().size()));

        Transaction transaction = txContext.build();

        String txCborHex = transaction.serializeToHex();
        byte[] txBodyBytes = CborSerializationUtil.serialize(transaction.getBody().serialize());
        String txHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(txBodyBytes));
        String fee = transaction.getBody().getFee().toString();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tx_cbor", txCborHex);
        result.put("tx_hash", txHash);
        result.put("fee", fee);
        return JsonHelper.toJson(result);
    }

    private static List<Utxo> parseUtxos(String utxosJson) throws Exception {
        if (utxosJson == null || utxosJson.isBlank()) {
            return Collections.emptyList();
        }
        Utxo[] utxos = JsonHelper.fromJson(utxosJson, Utxo[].class);
        return utxos != null ? Arrays.asList(utxos) : Collections.emptyList();
    }
}
