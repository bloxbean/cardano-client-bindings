package com.bloxbean.cardano.bridge.api;

import com.bloxbean.cardano.bridge.api.quicktx.QuickTxService;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.quicktx.serialization.TxPlan;
import com.bloxbean.cardano.client.quicktx.serialization.YamlSerializer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fee estimated at build time must cover the transaction after all of its witnesses are
 * attached: the ledger rejects {@code fee < min_fee(signed size)} with {@code FeeTooSmallUTxO}.
 *
 * <p>The witness budget is derived from the plan's certificate intents
 * ({@code QuickTxService#countCertificateWitnesses}); before that, a blanket one-extra-witness
 * budget per tx underestimated the fee of any tx combining certificate roles — the
 * {@link #feeCoversStakeAndDrepCertificatesInOneTx()} case failed by ~4,400 lovelace.
 */
class FeeWitnessBudgetTest {

    private static final String TEST_MNEMONIC =
            "test walk nut penalty hip pave soap entry language right filter choice";
    private static final String FAKE_TX_HASH = "a".repeat(64);

    private final QuickTxService service = new QuickTxService();

    private String protocolParamsJson;
    private long minFeeA;
    private long minFeeB;
    private Account account;
    private String sender;

    @BeforeEach
    void setUp() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("protocol-params.json")) {
            protocolParamsJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        var params = YamlSerializer.getYamlMapper().readTree(protocolParamsJson);
        minFeeA = params.get("min_fee_a").asLong();
        minFeeB = params.get("min_fee_b").asLong();
        account = Account.createFromMnemonic(Networks.testnet(), TEST_MNEMONIC, 0, 0);
        sender = account.baseAddress();
    }

    private String utxos() {
        return """
            [{"tx_hash":"%s","output_index":0,"address":"%s",
              "amount":[{"unit":"lovelace","quantity":"2000000000"}]}]
            """.formatted(FAKE_TX_HASH, sender);
    }

    /** Build the tx offline, apply the given signers, and assert paid fee ≥ min fee of the signed size. */
    private void assertFeeCoversSignedSize(Tx tx, UnaryOperator<Transaction> signer) throws Exception {
        assertFeeCoversSignedSize(TxPlan.from(tx).feePayer(sender).toYaml(), utxos(), signer);
    }

    private void assertFeeCoversSignedSize(String yaml, String utxosJson,
                                           UnaryOperator<Transaction> signer) throws Exception {
        var result = YamlSerializer.getYamlMapper()
                .readTree(service.buildTransaction(yaml, utxosJson, protocolParamsJson, null));

        long fee = Long.parseLong(result.get("fee").asText());
        Transaction unsigned = Transaction.deserialize(HexUtil.decodeHexString(result.get("tx_cbor").asText()));
        Transaction signed = signer.apply(unsigned);

        long signedSize = signed.serialize().length;
        long minFee = minFeeA * signedSize + minFeeB;
        assertTrue(fee >= minFee,
                "fee " + fee + " must cover min fee " + minFee + " for signed size " + signedSize
                        + "B (short by " + (minFee - fee) + ")");
    }

    /**
     * Regression: one tx carrying both a stake and a DRep certificate needs three witnesses
     * (payment + stake + DRep). The old blanket budget covered only two, so the signed tx's min
     * fee exceeded the paid fee and a node would reject it.
     */
    @Test
    void feeCoversStakeAndDrepCertificatesInOneTx() throws Exception {
        Tx tx = new Tx()
                .registerStakeAddress(account.stakeAddress())
                .registerDRep(account.drepCredential())
                .from(sender);
        assertFeeCoversSignedSize(tx,
                t -> account.signWithDRepKey(account.signWithStakeKey(account.sign(t))));
    }

    /** A single-certificate tx (payment + stake witnesses) must stay covered. */
    @Test
    void feeCoversSingleCertificate() throws Exception {
        Tx tx = new Tx().registerStakeAddress(account.stakeAddress()).from(sender);
        assertFeeCoversSignedSize(tx, t -> account.signWithStakeKey(account.sign(t)));
    }

    /** A plain payment (payment witness only) must stay covered with a zero extra-witness budget. */
    @Test
    void feeCoversPlainPayment() throws Exception {
        Tx tx = new Tx().payToAddress(account.enterpriseAddress(), Amount.ada(5)).from(sender);
        assertFeeCoversSignedSize(tx, account::sign);
    }

    /**
     * Regression (caught live by the DevKit suite): spending from a native-script address whose
     * UTXO covers the whole transaction selects no vkey-owned inputs, so CCL's UTXO-derived signer
     * count is zero — the script's {@code sig} witness must be budgeted from the plan's script, or
     * the node rejects with {@code FeeTooSmallUTxO} (observed short by exactly one witness).
     */
    @Test
    void feeCoversNativeScriptSpendFromScriptOnlyInputs() throws Exception {
        byte[] paymentKeyHash = new com.bloxbean.cardano.client.address.Address(sender)
                .getPaymentCredentialHash().orElseThrow();
        var script = new com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey(
                HexUtil.encodeHexString(paymentKeyHash));
        String scriptHex = HexUtil.encodeHexString(script.serializeScriptBody());
        byte[] scriptAddrBytes = new byte[29];
        scriptAddrBytes[0] = (byte) 0x70; // testnet enterprise script address header
        System.arraycopy(script.getScriptHash(), 0, scriptAddrBytes, 1, 28);
        String scriptAddress = new com.bloxbean.cardano.client.address.Address(scriptAddrBytes).toBech32();

        String scriptTxHash = "b".repeat(64);
        String yaml = """
            version: 1.0
            context:
              fee_payer: %s
            transaction:
              - tx:
                  from: %s
                  change_address: %s
                  inputs:
                    - type: collect_from
                      utxo_refs:
                        - tx_hash: %s
                          output_index: 0
                  intents:
                    - type: payment
                      address: %s
                      amounts:
                        - unit: lovelace
                          quantity: "3000000"
                  scripts:
                    - type: native_script
                      script_hex: %s
            """.formatted(sender, sender, sender, scriptTxHash, sender, scriptHex);
        // Only the script-address UTXO is supplied, so the built tx has no vkey-owned inputs.
        String scriptOnlyUtxos = """
            [{"tx_hash":"%s","output_index":0,"address":"%s",
              "amount":[{"unit":"lovelace","quantity":"10000000"}]}]
            """.formatted(scriptTxHash, scriptAddress);

        assertFeeCoversSignedSize(yaml, scriptOnlyUtxos, account::sign);
    }

    /**
     * Plan-level {@code required_signers} are stamped into the tx body and each must be witnessed,
     * but CCL's fee estimation never counts them — the budget has to.
     */
    @Test
    void feeCoversRequiredSigners() throws Exception {
        Account cosigner = Account.createFromMnemonic(Networks.testnet(), TEST_MNEMONIC, 0, 5);
        String cosignerKeyHash = HexUtil.encodeHexString(
                new com.bloxbean.cardano.client.address.Address(cosigner.baseAddress())
                        .getPaymentCredentialHash().orElseThrow());

        String yaml = """
            version: 1.0
            context:
              fee_payer: %s
              required_signers:
                - %s
            transaction:
              - tx:
                  from: %s
                  intents:
                    - type: payment
                      address: %s
                      amounts:
                        - unit: lovelace
                          quantity: "3000000"
            """.formatted(sender, cosignerKeyHash, sender, account.enterpriseAddress());

        assertFeeCoversSignedSize(yaml, utxos(), t -> cosigner.sign(account.sign(t)));
    }
}
