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
        String yaml = TxPlan.from(tx).feePayer(sender).toYaml();
        var result = YamlSerializer.getYamlMapper()
                .readTree(service.buildTransaction(yaml, utxos(), protocolParamsJson, null));

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
}
