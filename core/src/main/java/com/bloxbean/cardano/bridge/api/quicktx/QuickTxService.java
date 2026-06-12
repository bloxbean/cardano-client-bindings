package com.bloxbean.cardano.bridge.api.quicktx;

import com.bloxbean.cardano.bridge.util.JsonHelper;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.impl.StaticTransactionEvaluator;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.serialization.TxPlan;
import com.bloxbean.cardano.client.quicktx.serialization.YamlSerializer;
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
 * <p>Plutus script transactions are supported when the caller supplies the redeemers' execution
 * units (memory + CPU steps). Computing those units requires running the script in a UPLC
 * evaluator, which the caller does out-of-band (Ogmios, Blockfrost, Aiken, Scalus, …) and passes
 * in — exactly as it supplies UTXOs and protocol parameters. With units supplied, a
 * {@link StaticTransactionEvaluator} stamps them onto the redeemers, fully offline. A script
 * transaction built without execution units fails (no offline evaluator runs the script).
 */
public class QuickTxService {

    /**
     * Build an unsigned transaction from a TxPlan YAML document and caller-supplied chain data.
     *
     * @param yaml               the TxPlan YAML defining the transaction(s)
     * @param utxosJson          JSON array of UTXOs available to the sender (CCL {@code Utxo} model)
     * @param protocolParamsJson JSON protocol parameters (CCL {@code ProtocolParams} model)
     * @param execUnitsJson      JSON array of redeemer execution units ({@code [{"mem","steps"}]},
     *                           one per redeemer in transaction order); null/empty for non-script txs
     * @return JSON string with {@code tx_cbor}, {@code tx_hash}, {@code fee}
     */
    public String buildTransaction(String yaml, String utxosJson, String protocolParamsJson,
                                   String execUnitsJson) throws Exception {
        TxPlan plan = TxPlan.from(yaml);

        List<Utxo> utxos = parseUtxos(utxosJson);
        ProtocolParams protocolParams = JsonHelper.fromJson(protocolParamsJson, ProtocolParams.class);

        UtxoSupplier utxoSupplier = new StaticUtxoSupplier(utxos);
        ProtocolParamsSupplier ppSupplier = () -> protocolParams;

        // No TransactionProcessor (offline; never submits). compose(plan) applies the plan's
        // context (fee payer, validity, deposit mode, required signers, …) to the TxContext.
        QuickTxBuilder builder = new QuickTxBuilder(utxoSupplier, ppSupplier, null);
        QuickTxBuilder.TxContext txContext = builder.compose(plan);

        // Plutus script cost: when the caller supplies execution units, a static evaluator stamps
        // them onto the redeemers (offline). The caller computes them however it likes (Ogmios,
        // Blockfrost, Aiken, Scalus); the bridge does not run the script.
        List<ExUnits> execUnits = parseExUnits(execUnitsJson);
        if (!execUnits.isEmpty()) {
            txContext.withTxEvaluator(new StaticTransactionEvaluator(execUnits));
        }

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
        return YamlSerializer.serialize(result);
    }

    private static List<Utxo> parseUtxos(String utxosJson) throws Exception {
        if (utxosJson == null || utxosJson.isBlank()) {
            return Collections.emptyList();
        }
        Utxo[] utxos = JsonHelper.fromJson(utxosJson, Utxo[].class);
        return utxos != null ? Arrays.asList(utxos) : Collections.emptyList();
    }

    private static List<ExUnits> parseExUnits(String execUnitsJson) throws Exception {
        if (execUnitsJson == null || execUnitsJson.isBlank()) {
            return Collections.emptyList();
        }
        ExUnits[] exUnits = JsonHelper.fromJson(execUnitsJson, ExUnits[].class);
        return exUnits != null ? Arrays.asList(exUnits) : Collections.emptyList();
    }
}
