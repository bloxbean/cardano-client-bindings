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
import co.nstant.in.cbor.model.Array;
import com.bloxbean.cardano.client.quicktx.AbstractTx;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.intent.MintingIntent;
import com.bloxbean.cardano.client.quicktx.intent.NativeScriptAttachmentIntent;
import com.bloxbean.cardano.client.quicktx.intent.TxIntent;
import com.bloxbean.cardano.client.quicktx.serialization.TxPlan;
import com.bloxbean.cardano.client.transaction.spec.script.NativeScript;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptAll;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptAny;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptAtLeast;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.quicktx.serialization.YamlSerializer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        } else {
            // No caller-supplied units: fall back to Scalus, which evaluates the script(s) offline
            // (runs the UPLC engine in-process, no network). Requires cost models in the protocol
            // params. TODO(evaluators): expose this as a pluggable Evaluator with a graceful path
            // when cost models are absent (Scalus MachineParams.defaultPlutusV2PostConwayParams) and
            // a remote (Blockfrost /utils/txs/evaluate) fallback.
            txContext.withTxEvaluator(
                    new scalus.bloxbean.ScalusTransactionEvaluator(protocolParams, utxoSupplier));
        }

        // Budget witnesses for fee estimation of the (still unsigned) transaction. CCL already
        // counts the payment-key witnesses implied by the selected input UTXOs; what it cannot see
        // is which certificate keys will witness later, so derive that from the plan's intents.
        // (The previous blanket max(1, txCount) covered at most one certificate role per tx and
        // underestimated the fee of any tx combining roles — e.g. stake_registration +
        // drep_registration — which a node rejects with FeeTooSmallUTxO.)
        txContext.additionalSignersCount(countCertificateWitnesses(plan));

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

    /**
     * Number of additional vkey witnesses the plan's certificates and votes will need on top of the
     * payment-key witnesses CCL derives from the selected input UTXOs.
     *
     * <p>Each tx contributes one witness per <em>distinct key role</em> its intents require: the
     * stake key (stake registration/deregistration/delegation/withdrawal, vote delegation), the
     * DRep key (DRep lifecycle), the voter key (votes), and the pool key (pool lifecycle). Several
     * intents sharing a role still need only one witness. Native scripts (minting policies and
     * {@code native_script} attachments) contribute one witness per distinct {@code ScriptPubkey}
     * key hash in the script tree. Plain payment/metadata/donation/proposal and Plutus intents add
     * none — the fee payer's witness is already counted from the UTXOs.
     */
    private static int countCertificateWitnesses(TxPlan plan) {
        // Plan-level required_signers are stamped into the tx body (compose ->
        // withRequiredSigners) and each must be witnessed, but CCL's fee estimation never counts
        // them. One may coincide with the fee payer's own credential (already counted from the
        // UTXOs) — that only overpays by one witness; underbudgeting gets the tx rejected.
        int total = plan.getRequiredSigners() == null ? 0 : plan.getRequiredSigners().size();
        for (AbstractTx<?> tx : plan.getTxs()) {
            List<TxIntent> intents = tx.getIntentions();
            if (intents == null) {
                continue;
            }
            Set<String> roles = new HashSet<>();
            for (TxIntent intent : intents) {
                switch (intent.getType()) {
                    case "stake_registration":
                    case "stake_deregistration":
                    case "stake_delegation":
                    case "stake_withdrawal":
                    case "voting_delegation":
                        roles.add("stake");
                        break;
                    case "drep_registration":
                    case "drep_update":
                    case "drep_deregistration":
                        roles.add("drep");
                        break;
                    case "voting":
                        roles.add("voter");
                        break;
                    case "pool_registration":
                    case "pool_update":
                    case "pool_retirement":
                        roles.add("pool");
                        break;
                    default:
                        break;
                }
                // Native scripts carry their required signer key hashes in the plan itself:
                // budget one witness per distinct ScriptPubkey. This matters when the tx spends
                // from a script address — such inputs contribute no vkey signer to CCL's
                // UTXO-derived count, so without this the script's witness goes unbudgeted
                // (observed live: a sig-script spend whose script UTXO covered the whole tx was
                // rejected with FeeTooSmallUTxO, short by exactly one witness).
                NativeScript nativeScript = nativeScriptOf(intent);
                if (nativeScript != null) {
                    collectScriptKeyHashes(nativeScript, roles);
                }
            }
            total += roles.size();
        }
        return total;
    }

    /** The native script an intent carries, if any — deserialized from hex when not yet resolved. */
    private static NativeScript nativeScriptOf(TxIntent intent) {
        try {
            if (intent instanceof NativeScriptAttachmentIntent attachment) {
                if (attachment.getScript() != null) {
                    return attachment.getScript();
                }
                return deserializeNativeScript(attachment.getScriptHex());
            }
            if (intent instanceof MintingIntent minting) {
                if (minting.getScript() instanceof NativeScript script) {
                    return script;
                }
                if (minting.getScript() == null && Integer.valueOf(0).equals(minting.getScriptType())) {
                    return deserializeNativeScript(minting.getScriptHex());
                }
            }
        } catch (Exception e) {
            // Unparseable script: leave it unbudgeted — the build itself surfaces the real error.
        }
        return null;
    }

    private static NativeScript deserializeNativeScript(String hex) throws Exception {
        if (hex == null || hex.isEmpty()) {
            return null;
        }
        byte[] bytes = HexUtil.decodeHexString(hex);
        return NativeScript.deserialize((Array) CborSerializationUtil.deserialize(bytes));
    }

    /**
     * Adds one role entry per distinct {@code ScriptPubkey} key hash in the script tree. For
     * {@code any}/{@code atLeast} scripts every key is counted — possibly more witnesses than the
     * signer will attach, which only overpays; underbudgeting gets the tx rejected.
     */
    private static void collectScriptKeyHashes(NativeScript script, Set<String> roles) {
        if (script instanceof ScriptPubkey pubkey) {
            roles.add("nskey:" + pubkey.getKeyHash());
        } else if (script instanceof ScriptAll all) {
            all.getScripts().forEach(s -> collectScriptKeyHashes(s, roles));
        } else if (script instanceof ScriptAny any) {
            any.getScripts().forEach(s -> collectScriptKeyHashes(s, roles));
        } else if (script instanceof ScriptAtLeast atLeast) {
            atLeast.getScripts().forEach(s -> collectScriptKeyHashes(s, roles));
        }
        // RequireTimeBefore / RequireTimeAfter carry no keys.
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
