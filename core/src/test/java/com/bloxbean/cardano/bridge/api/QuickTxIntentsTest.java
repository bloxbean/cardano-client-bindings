package com.bloxbean.cardano.bridge.api;

import com.bloxbean.cardano.bridge.api.quicktx.QuickTxService;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.quicktx.serialization.TxPlan;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.Voter;
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the bridge builds each non-payment TxPlan intent (staking, governance, DRep, voting,
 * proposals, …) offline. Each operation is built programmatically with CCL, serialized to TxPlan
 * YAML via {@link TxPlan#toYaml()} (so the exact intent shape is authoritative), then built through
 * the bridge with caller-supplied UTXOs + protocol parameters.
 */
class QuickTxIntentsTest {

    private static final String TEST_MNEMONIC =
            "test walk nut penalty hip pave soap entry language right filter choice";
    private static final String FAKE_TX_HASH = "a".repeat(64);
    private static final String POOL_ID = "pool1pu5jlj4q9w9jlxeu370a3c9myx47md5j5m2str0naunn2q3lkdy";
    private static final String GOV_ACTION_TX = "12745f09b138d4d0a11a560b4591ebb830cf12336347606d2edbbf1893d395c6";

    private final QuickTxService service = new QuickTxService();

    private String protocolParamsJson;
    private Account account;
    private String sender;
    private String stakeAddress;
    private Credential drepCredential;

    @BeforeEach
    void setUp() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("protocol-params.json")) {
            protocolParamsJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        account = Account.createFromMnemonic(Networks.testnet(), TEST_MNEMONIC, 0, 0);
        sender = account.baseAddress();
        stakeAddress = account.stakeAddress();
        drepCredential = account.drepCredential();
    }

    /** A single 2000-ADA UTXO at {@code sender} — enough to cover deposits (gov action = 1000 ADA). */
    private String utxos() {
        return """
            [{"tx_hash":"%s","output_index":0,"address":"%s",
              "amount":[{"unit":"lovelace","quantity":"2000000000"}]}]
            """.formatted(FAKE_TX_HASH, sender);
    }

    /**
     * Build a Tx through the bridge (TxPlan YAML -> offline build) and assert it produced CBOR. Also
     * writes the generated TxPlan YAML to {@code build/intent-yamls/<name>.yaml} so the wrapper
     * end-to-end tests (Go) can drive the exact same intent through the native library.
     */
    private void assertBuilds(String name, Tx tx) throws Exception {
        String yaml = TxPlan.from(tx).feePayer(sender).toYaml();

        java.nio.file.Path dir = java.nio.file.Path.of("build/intent-yamls");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve(name + ".yaml"), yaml);

        String resultYaml = service.buildTransaction(yaml, utxos(), protocolParamsJson, null);
        var result = com.bloxbean.cardano.client.quicktx.serialization.YamlSerializer
                .getYamlMapper().readTree(resultYaml);
        assertFalse(result.get("tx_cbor").asText().isEmpty(), "tx_cbor should not be empty");
        assertEquals(64, result.get("tx_hash").asText().length());
        assertTrue(Long.parseLong(result.get("fee").asText()) > 0);
    }

    private Anchor anchor() {
        return new Anchor("https://example.com/meta.json",
                com.bloxbean.cardano.client.util.HexUtil.decodeHexString(FAKE_TX_HASH));
    }

    // --- Staking ---

    @Test
    void stakeRegistration() throws Exception {
        assertBuilds("stake_registration", new Tx().registerStakeAddress(stakeAddress).from(sender));
    }

    @Test
    void stakeDelegation() throws Exception {
        assertBuilds("stake_delegation", new Tx().registerStakeAddress(stakeAddress).delegateTo(stakeAddress, POOL_ID).from(sender));
    }

    @Test
    void stakeDeregistration() throws Exception {
        assertBuilds("stake_deregistration", new Tx().deregisterStakeAddress(stakeAddress, sender).from(sender));
    }

    @Test
    void stakeWithdrawal() throws Exception {
        assertBuilds("stake_withdrawal", new Tx().withdraw(stakeAddress, BigInteger.ZERO).from(sender));
    }

    // --- Treasury ---

    @Test
    void donation() throws Exception {
        assertBuilds("donation", new Tx()
                .donateToTreasury(BigInteger.valueOf(1_000_000_000L), BigInteger.valueOf(1_000_000L))
                .from(sender));
    }

    // --- DRep ---

    @Test
    void drepRegistration() throws Exception {
        assertBuilds("drep_registration", new Tx().registerDRep(drepCredential, anchor()).from(sender));
    }

    @Test
    void drepDeregistration() throws Exception {
        assertBuilds("drep_deregistration", new Tx().unregisterDRep(drepCredential).from(sender));
    }

    @Test
    void drepUpdate() throws Exception {
        assertBuilds("drep_update", new Tx().updateDRep(drepCredential, anchor()).from(sender));
    }

    // --- Voting & proposals ---

    @Test
    void voting() throws Exception {
        Voter voter = new Voter(VoterType.DREP_KEY_HASH, drepCredential);
        assertBuilds("voting", new Tx()
                .createVote(voter, new GovActionId(GOV_ACTION_TX, 0), Vote.YES, anchor())
                .from(sender));
    }

    @Test
    void votingDelegation() throws Exception {
        assertBuilds("voting_delegation", new Tx()
                .delegateVotingPowerTo(new Address(stakeAddress), DRep.abstain())
                .from(sender));
    }

    @Test
    void governanceProposalInfoAction() throws Exception {
        assertBuilds("governance_proposal", new Tx()
                .createProposal(new InfoAction(), stakeAddress, anchor())
                .from(sender));
    }
}
