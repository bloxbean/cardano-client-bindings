package com.bloxbean.cardano.bridge.api.quicktx;

import com.bloxbean.cardano.bridge.util.JsonHelper;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.quicktx.AbstractTx;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service that builds unsigned Cardano transactions from a JSON spec
 * using QuickTxBuilder for automatic coin selection, fee calculation,
 * and change balancing.
 */
public class QuickTxService {

    /**
     * Build an unsigned transaction from a JSON spec string.
     *
     * @param specJson JSON transaction specification
     * @return JSON string with tx_cbor, tx_hash, fee
     */
    public String buildTransaction(String specJson) throws Exception {
        // Parse spec
        TxSpec spec = JsonHelper.fromJson(specJson, TxSpec.class);
        spec.validate();

        // Create suppliers — provider mode uses HTTP, static mode uses inline data
        UtxoSupplier utxoSupplier;
        ProtocolParamsSupplier ppSupplier;

        if (spec.getProvider() != null) {
            String providerUrl = spec.getProvider().getUrl();
            utxoSupplier = new YaciUtxoSupplier(providerUrl);
            ppSupplier = spec.getProtocolParams() != null
                    ? () -> spec.getProtocolParams()
                    : new YaciProtocolParamsSupplier(providerUrl);
        } else {
            utxoSupplier = new StaticUtxoSupplier(spec.getUtxos());
            ppSupplier = () -> spec.getProtocolParams();
        }

        // Create evaluator for script cost evaluation if provider is configured
        TransactionEvaluator evaluator = null;
        if (spec.getProvider() != null) {
            boolean enableEval = spec.getProvider().getEnableCostEvaluation() == null
                    || spec.getProvider().getEnableCostEvaluation();
            if (enableEval) {
                evaluator = new YaciTransactionEvaluator(spec.getProvider().getUrl());
            }
        }

        // Build with QuickTxBuilder
        QuickTxBuilder builder = new QuickTxBuilder(utxoSupplier, ppSupplier, null);

        // Detect compose vs single mode
        QuickTxBuilder.TxContext txContext;
        if (spec.getTransactions() != null && !spec.getTransactions().isEmpty()) {
            // Compose mode — each item can be Tx or ScriptTx
            AbstractTx<?>[] txs = spec.getTransactions().stream()
                    .map(item -> {
                        if ("script_tx".equals(item.getTxType())) {
                            return (AbstractTx<?>) ScriptTxSpecMapper.toScriptTx(item);
                        } else {
                            return (AbstractTx<?>) TxSpecMapper.toTx(item);
                        }
                    })
                    .toArray(AbstractTx[]::new);
            txContext = builder.compose(txs);
        } else {
            // Single mode
            if ("script_tx".equals(spec.getTxType())) {
                txContext = builder.compose(ScriptTxSpecMapper.toScriptTx(spec));
            } else {
                txContext = builder.compose(TxSpecMapper.toTx(spec));
            }
        }

        // Set additional signers count for fee estimation
        int signerCount;
        if (spec.getSignerCount() != null) {
            signerCount = spec.getSignerCount();
        } else if (spec.getTransactions() != null && !spec.getTransactions().isEmpty()) {
            signerCount = spec.getTransactions().size();
        } else {
            signerCount = 1;
        }
        txContext.additionalSignersCount(signerCount);

        // Set validity interval
        if (spec.getValidity() != null) {
            if (spec.getValidity().getValidFrom() != null) {
                txContext.validFrom(spec.getValidity().getValidFrom());
            }
            if (spec.getValidity().getValidTo() != null) {
                txContext.validTo(spec.getValidity().getValidTo());
            }
        }

        // Set merge outputs
        if (spec.getMergeOutputs() != null) {
            txContext.mergeOutputs(spec.getMergeOutputs());
        }

        // Set fee payer
        // For script_tx mode, feePayer is required since ScriptTx.from() is package-private.
        // TxContext.feePayer() sets both the feePayer and calls ScriptTx.from() internally.
        if (spec.getFeePayer() != null && !spec.getFeePayer().isEmpty()) {
            txContext.feePayer(spec.getFeePayer());
        } else if ("script_tx".equals(spec.getTxType())
                && spec.getFrom() != null && !spec.getFrom().isEmpty()) {
            txContext.feePayer(spec.getFrom());
        }

        // Set transaction evaluator for script cost evaluation
        if (evaluator != null) {
            txContext.withTxEvaluator(evaluator);
        }

        // Build the transaction
        Transaction transaction = txContext.build();

        // Serialize and compute hash
        String txCborHex = transaction.serializeToHex();
        byte[] txBodyBytes = CborSerializationUtil.serialize(transaction.getBody().serialize());
        String txHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(txBodyBytes));

        // Get fee from the built transaction
        String fee = transaction.getBody().getFee().toString();

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tx_cbor", txCborHex);
        result.put("tx_hash", txHash);
        result.put("fee", fee);

        return JsonHelper.toJson(result);
    }
}
