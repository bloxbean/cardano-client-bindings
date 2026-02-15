package com.bloxbean.cardano.bridge.api.quicktx;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadata;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataList;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataMap;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.PlutusV1Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.spec.UnitInterval;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.ProtocolVersion;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;
import com.bloxbean.cardano.client.transaction.spec.governance.Constitution;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.Voter;
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.*;
import com.bloxbean.cardano.client.spec.Script;
import com.bloxbean.cardano.client.transaction.spec.script.NativeScript;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.*;

/**
 * Maps a TxSpec/TxItemSpec (parsed from JSON) into a CCL ScriptTx object.
 */
public class ScriptTxSpecMapper {

    public static ScriptTx toScriptTx(TxSpec spec) {
        ScriptTx tx = new ScriptTx();
        // Note: ScriptTx.from() is package-private, so 'from' is set by TxContext via feePayer
        if (spec.getChangeAddress() != null && !spec.getChangeAddress().isEmpty()) {
            applyChangeAddress(tx, spec.getChangeAddress(), spec.getChangeDatumCborHex(), spec.getChangeDatumHash());
        }
        applyOperations(tx, spec.getOperations());
        return tx;
    }

    public static ScriptTx toScriptTx(TxItemSpec item) {
        ScriptTx tx = new ScriptTx();
        // Note: ScriptTx.from() is package-private, so 'from' is set by TxContext via feePayer
        if (item.getChangeAddress() != null && !item.getChangeAddress().isEmpty()) {
            applyChangeAddress(tx, item.getChangeAddress(), item.getChangeDatumCborHex(), item.getChangeDatumHash());
        }
        applyOperations(tx, item.getOperations());
        return tx;
    }

    private static void applyChangeAddress(ScriptTx tx, String changeAddress, String datumCborHex, String datumHash) {
        if (datumCborHex != null && !datumCborHex.isEmpty()) {
            PlutusData datum = parseRedeemer(datumCborHex);
            tx.withChangeAddress(changeAddress, datum);
        } else if (datumHash != null && !datumHash.isEmpty()) {
            tx.withChangeAddress(changeAddress, datumHash);
        } else {
            tx.withChangeAddress(changeAddress);
        }
    }

    private static void applyOperations(ScriptTx tx, List<TxOperation> operations) {
        for (TxOperation op : operations) {
            switch (op.getType()) {
                case "pay_to_address":
                    applyPayToAddress(tx, op);
                    break;
                case "pay_to_contract":
                    applyPayToContract(tx, op);
                    break;
                case "attach_metadata":
                    applyAttachMetadata(tx, op);
                    break;
                case "collect_from":
                    applyCollectFrom(tx, op);
                    break;
                case "read_from":
                    applyReadFrom(tx, op);
                    break;
                case "mint_plutus_assets":
                    applyMintPlutusAssets(tx, op);
                    break;
                case "attach_spending_validator":
                    applyAttachValidator(tx, op, "spending");
                    break;
                case "attach_certificate_validator":
                    applyAttachValidator(tx, op, "certificate");
                    break;
                case "attach_reward_validator":
                    applyAttachValidator(tx, op, "reward");
                    break;
                case "attach_proposing_validator":
                    applyAttachValidator(tx, op, "proposing");
                    break;
                case "attach_voting_validator":
                    applyAttachValidator(tx, op, "voting");
                    break;
                // Staking (register_stake_address not available in ScriptTx)
                case "register_stake_address":
                    throw new IllegalArgumentException(
                            "register_stake_address is not supported in script_tx mode. Use regular tx mode.");
                case "deregister_stake_address":
                    applyDeregisterStakeAddress(tx, op);
                    break;
                case "delegate_to":
                    applyDelegateTo(tx, op);
                    break;
                case "withdraw":
                    applyWithdraw(tx, op);
                    break;
                // DRep
                case "register_drep":
                    applyRegisterDRep(tx, op);
                    break;
                case "unregister_drep":
                    applyUnregisterDRep(tx, op);
                    break;
                case "update_drep":
                    applyUpdateDRep(tx, op);
                    break;
                // Voting
                case "delegate_voting_power_to":
                    applyDelegateVotingPowerTo(tx, op);
                    break;
                case "create_vote":
                    applyCreateVote(tx, op);
                    break;
                // Governance
                case "create_proposal":
                    applyCreateProposal(tx, op);
                    break;
                // Treasury donation (Gap 4)
                case "donate_to_treasury":
                    applyDonateToTreasury(tx, op);
                    break;
                case "mint_assets":
                    throw new IllegalArgumentException(
                            "Use 'mint_plutus_assets' instead of 'mint_assets' in script_tx mode");
                default:
                    throw new IllegalArgumentException("Unknown operation type: " + op.getType());
            }
        }
    }

    private static void applyPayToAddress(ScriptTx tx, TxOperation op) {
        if (op.getAddress() == null) {
            throw new IllegalArgumentException("pay_to_address requires 'address'");
        }
        if (op.getAmounts() == null || op.getAmounts().isEmpty()) {
            throw new IllegalArgumentException("pay_to_address requires 'amounts'");
        }
        if (op.getScriptRefCborHex() != null && !op.getScriptRefCborHex().isEmpty()) {
            Script refScript = parseScriptRef(op.getScriptRefCborHex(), op.getScriptRefType());
            tx.payToAddress(op.getAddress(), op.getAmounts(), refScript);
        } else {
            tx.payToAddress(op.getAddress(), op.getAmounts());
        }
    }

    private static void applyPayToContract(ScriptTx tx, TxOperation op) {
        if (op.getAddress() == null) {
            throw new IllegalArgumentException("pay_to_contract requires 'address'");
        }
        if (op.getAmounts() == null || op.getAmounts().isEmpty()) {
            throw new IllegalArgumentException("pay_to_contract requires 'amounts'");
        }

        Script refScript = null;
        if (op.getScriptRefCborHex() != null && !op.getScriptRefCborHex().isEmpty()) {
            refScript = parseScriptRef(op.getScriptRefCborHex(), op.getScriptRefType());
        }

        if (op.getDatumCborHex() != null && !op.getDatumCborHex().isEmpty()) {
            PlutusData datum = parseRedeemer(op.getDatumCborHex());
            if (refScript != null) {
                tx.payToContract(op.getAddress(), op.getAmounts(), datum, refScript);
            } else {
                tx.payToContract(op.getAddress(), op.getAmounts(), datum);
            }
        } else if (op.getDatumHash() != null && !op.getDatumHash().isEmpty()) {
            tx.payToContract(op.getAddress(), op.getAmounts(), op.getDatumHash());
        } else {
            throw new IllegalArgumentException("pay_to_contract requires 'datum_cbor_hex' or 'datum_hash'");
        }
    }

    @SuppressWarnings("unchecked")
    private static void applyAttachMetadata(ScriptTx tx, TxOperation op) {
        if (op.getLabel() == null) {
            throw new IllegalArgumentException("attach_metadata requires 'label'");
        }
        if (op.getMetadata() == null) {
            throw new IllegalArgumentException("attach_metadata requires 'metadata'");
        }

        CBORMetadata cborMetadata = new CBORMetadata();
        BigInteger label = BigInteger.valueOf(op.getLabel());
        Object metaValue = op.getMetadata();

        if (metaValue instanceof String) {
            cborMetadata.put(label, (String) metaValue);
        } else if (metaValue instanceof Number) {
            cborMetadata.put(label, BigInteger.valueOf(((Number) metaValue).longValue()));
        } else if (metaValue instanceof List) {
            CBORMetadataList list = buildMetadataList((List<Object>) metaValue);
            cborMetadata.put(label, list);
        } else if (metaValue instanceof Map) {
            CBORMetadataMap map = buildMetadataMap((Map<String, Object>) metaValue);
            cborMetadata.put(label, map);
        } else {
            throw new IllegalArgumentException("Unsupported metadata value type: " +
                    (metaValue != null ? metaValue.getClass().getName() : "null"));
        }

        tx.attachMetadata(cborMetadata);
    }

    @SuppressWarnings("unchecked")
    private static CBORMetadataList buildMetadataList(List<Object> items) {
        CBORMetadataList list = new CBORMetadataList();
        for (Object item : items) {
            if (item instanceof String) {
                list.add((String) item);
            } else if (item instanceof Number) {
                list.add(BigInteger.valueOf(((Number) item).longValue()));
            } else if (item instanceof List) {
                list.add(buildMetadataList((List<Object>) item));
            } else if (item instanceof Map) {
                list.add(buildMetadataMap((Map<String, Object>) item));
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static CBORMetadataMap buildMetadataMap(Map<String, Object> map) {
        CBORMetadataMap metaMap = new CBORMetadataMap();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) {
                metaMap.put(key, (String) value);
            } else if (value instanceof Number) {
                metaMap.put(key, BigInteger.valueOf(((Number) value).longValue()));
            } else if (value instanceof List) {
                metaMap.put(key, buildMetadataList((List<Object>) value));
            } else if (value instanceof Map) {
                metaMap.put(key, buildMetadataMap((Map<String, Object>) value));
            }
        }
        return metaMap;
    }

    private static void applyCollectFrom(ScriptTx tx, TxOperation op) {
        if (op.getCollectUtxos() == null || op.getCollectUtxos().isEmpty()) {
            throw new IllegalArgumentException("collect_from requires 'collect_utxos'");
        }

        if (op.getRedeemerCborHex() != null && !op.getRedeemerCborHex().isEmpty()) {
            PlutusData redeemer = parseRedeemer(op.getRedeemerCborHex());
            PlutusData datum = null;
            if (op.getDatumCborHex() != null && !op.getDatumCborHex().isEmpty()) {
                datum = parseRedeemer(op.getDatumCborHex());
            }
            if (datum != null) {
                tx.collectFrom(op.getCollectUtxos(), redeemer, datum);
            } else {
                tx.collectFrom(op.getCollectUtxos(), redeemer);
            }
        } else {
            tx.collectFrom(op.getCollectUtxos());
        }
    }

    private static void applyReadFrom(ScriptTx tx, TxOperation op) {
        if (op.getReferenceInputs() == null || op.getReferenceInputs().isEmpty()) {
            throw new IllegalArgumentException("read_from requires 'reference_inputs'");
        }
        for (TxOperation.ReferenceInput ref : op.getReferenceInputs()) {
            if (ref.getTxHash() == null || ref.getTxHash().isEmpty()) {
                throw new IllegalArgumentException("read_from reference_input requires 'tx_hash'");
            }
            tx.readFrom(ref.getTxHash(), ref.getOutputIndex());
        }
    }

    private static void applyMintPlutusAssets(ScriptTx tx, TxOperation op) {
        if (op.getScriptCborHex() == null || op.getScriptCborHex().isEmpty()) {
            throw new IllegalArgumentException("mint_plutus_assets requires 'script_cbor_hex'");
        }
        if (op.getScriptType() == null || op.getScriptType().isEmpty()) {
            throw new IllegalArgumentException("mint_plutus_assets requires 'script_type'");
        }
        if (op.getAssets() == null || op.getAssets().isEmpty()) {
            throw new IllegalArgumentException("mint_plutus_assets requires 'assets'");
        }
        if (op.getRedeemerCborHex() == null || op.getRedeemerCborHex().isEmpty()) {
            throw new IllegalArgumentException("mint_plutus_assets requires 'redeemer_cbor_hex'");
        }

        PlutusScript script = parsePlutusScript(op.getScriptCborHex(), op.getScriptType());
        PlutusData redeemer = parseRedeemer(op.getRedeemerCborHex());

        List<Asset> assets = new ArrayList<>();
        for (TxOperation.MintAsset ma : op.getAssets()) {
            assets.add(new Asset(ma.getName(), new BigInteger(ma.getQuantity())));
        }

        PlutusData outputDatum = null;
        if (op.getOutputDatumCborHex() != null && !op.getOutputDatumCborHex().isEmpty()) {
            outputDatum = parseRedeemer(op.getOutputDatumCborHex());
        }

        if (op.getReceiver() != null && !op.getReceiver().isEmpty()) {
            if (outputDatum != null) {
                tx.mintAsset(script, assets, redeemer, op.getReceiver(), outputDatum);
            } else {
                tx.mintAsset(script, assets, redeemer, op.getReceiver());
            }
        } else {
            tx.mintAsset(script, assets, redeemer);
        }
    }

    private static void applyAttachValidator(ScriptTx tx, TxOperation op, String validatorType) {
        if (op.getScriptCborHex() == null || op.getScriptCborHex().isEmpty()) {
            throw new IllegalArgumentException("attach_" + validatorType + "_validator requires 'script_cbor_hex'");
        }
        if (op.getScriptType() == null || op.getScriptType().isEmpty()) {
            throw new IllegalArgumentException("attach_" + validatorType + "_validator requires 'script_type'");
        }

        PlutusScript script = parsePlutusScript(op.getScriptCborHex(), op.getScriptType());

        switch (validatorType) {
            case "spending":
                tx.attachSpendingValidator(script);
                break;
            case "certificate":
                tx.attachCertificateValidator(script);
                break;
            case "reward":
                tx.attachRewardValidator(script);
                break;
            case "proposing":
                tx.attachProposingValidator(script);
                break;
            case "voting":
                tx.attachVotingValidator(script);
                break;
            default:
                throw new IllegalArgumentException("Unknown validator type: " + validatorType);
        }
    }

    // --- Staking ---
    // Note: In ScriptTx, deregisterStakeAddress, delegateTo, and withdraw always require a redeemer

    private static void applyDeregisterStakeAddress(ScriptTx tx, TxOperation op) {
        if (op.getAddress() == null) {
            throw new IllegalArgumentException("deregister_stake_address requires 'address'");
        }
        if (op.getRedeemerCborHex() == null || op.getRedeemerCborHex().isEmpty()) {
            throw new IllegalArgumentException("deregister_stake_address in script_tx mode requires 'redeemer_cbor_hex'");
        }
        PlutusData redeemer = parseRedeemer(op.getRedeemerCborHex());

        if (op.getRefundAddress() != null && !op.getRefundAddress().isEmpty()) {
            tx.deregisterStakeAddress(op.getAddress(), redeemer, op.getRefundAddress());
        } else {
            tx.deregisterStakeAddress(op.getAddress(), redeemer);
        }
    }

    private static void applyDelegateTo(ScriptTx tx, TxOperation op) {
        if (op.getAddress() == null) {
            throw new IllegalArgumentException("delegate_to requires 'address'");
        }
        if (op.getPoolId() == null || op.getPoolId().isEmpty()) {
            throw new IllegalArgumentException("delegate_to requires 'pool_id'");
        }
        if (op.getRedeemerCborHex() == null || op.getRedeemerCborHex().isEmpty()) {
            throw new IllegalArgumentException("delegate_to in script_tx mode requires 'redeemer_cbor_hex'");
        }
        PlutusData redeemer = parseRedeemer(op.getRedeemerCborHex());
        tx.delegateTo(op.getAddress(), op.getPoolId(), redeemer);
    }

    private static void applyWithdraw(ScriptTx tx, TxOperation op) {
        if (op.getRewardAddress() == null) {
            throw new IllegalArgumentException("withdraw requires 'reward_address'");
        }
        if (op.getAmount() == null) {
            throw new IllegalArgumentException("withdraw requires 'amount'");
        }
        if (op.getRedeemerCborHex() == null || op.getRedeemerCborHex().isEmpty()) {
            throw new IllegalArgumentException("withdraw in script_tx mode requires 'redeemer_cbor_hex'");
        }
        BigInteger amount = new BigInteger(op.getAmount());
        PlutusData redeemer = parseRedeemer(op.getRedeemerCborHex());

        if (op.getReceiver() != null && !op.getReceiver().isEmpty()) {
            tx.withdraw(op.getRewardAddress(), amount, redeemer, op.getReceiver());
        } else {
            tx.withdraw(op.getRewardAddress(), amount, redeemer);
        }
    }

    // --- DRep ---

    private static void applyRegisterDRep(ScriptTx tx, TxOperation op) {
        if (op.getCredentialHash() == null) {
            throw new IllegalArgumentException("register_drep requires 'credential_hash'");
        }
        if (op.getRedeemerCborHex() == null || op.getRedeemerCborHex().isEmpty()) {
            throw new IllegalArgumentException("register_drep in script_tx mode requires 'redeemer_cbor_hex'");
        }
        Credential credential = parseCredential(op.getCredentialHash(), op.getCredentialType());
        Anchor anchor = parseAnchor(op.getAnchorUrl(), op.getAnchorDataHash());
        PlutusData redeemer = parseRedeemer(op.getRedeemerCborHex());

        if (anchor != null) {
            tx.registerDRep(credential, anchor, redeemer);
        } else {
            tx.registerDRep(credential, redeemer);
        }
    }

    private static void applyUnregisterDRep(ScriptTx tx, TxOperation op) {
        if (op.getCredentialHash() == null) {
            throw new IllegalArgumentException("unregister_drep requires 'credential_hash'");
        }
        if (op.getRedeemerCborHex() == null || op.getRedeemerCborHex().isEmpty()) {
            throw new IllegalArgumentException("unregister_drep in script_tx mode requires 'redeemer_cbor_hex'");
        }
        Credential credential = parseCredential(op.getCredentialHash(), op.getCredentialType());
        PlutusData redeemer = parseRedeemer(op.getRedeemerCborHex());
        String refundAddress = op.getRefundAddress() != null ? op.getRefundAddress() : null;
        BigInteger refundAmount = op.getRefundAmount() != null && !op.getRefundAmount().isEmpty()
                ? new BigInteger(op.getRefundAmount()) : null;

        tx.unRegisterDRep(credential, refundAddress, refundAmount, redeemer);
    }

    private static void applyUpdateDRep(ScriptTx tx, TxOperation op) {
        if (op.getCredentialHash() == null) {
            throw new IllegalArgumentException("update_drep requires 'credential_hash'");
        }
        if (op.getRedeemerCborHex() == null || op.getRedeemerCborHex().isEmpty()) {
            throw new IllegalArgumentException("update_drep in script_tx mode requires 'redeemer_cbor_hex'");
        }
        Credential credential = parseCredential(op.getCredentialHash(), op.getCredentialType());
        Anchor anchor = parseAnchor(op.getAnchorUrl(), op.getAnchorDataHash());
        PlutusData redeemer = parseRedeemer(op.getRedeemerCborHex());

        tx.updateDRep(credential, anchor, redeemer);
    }

    // --- Voting ---

    private static void applyDelegateVotingPowerTo(ScriptTx tx, TxOperation op) {
        if (op.getAddress() == null) {
            throw new IllegalArgumentException("delegate_voting_power_to requires 'address'");
        }
        if (op.getDrepType() == null) {
            throw new IllegalArgumentException("delegate_voting_power_to requires 'drep_type'");
        }
        if (op.getRedeemerCborHex() == null || op.getRedeemerCborHex().isEmpty()) {
            throw new IllegalArgumentException("delegate_voting_power_to in script_tx mode requires 'redeemer_cbor_hex'");
        }
        DRep drep = parseDRep(op.getDrepType(), op.getDrepHash());
        PlutusData redeemer = parseRedeemer(op.getRedeemerCborHex());
        Address address = new Address(op.getAddress());

        tx.delegateVotingPowerTo(address, drep, redeemer);
    }

    private static void applyCreateVote(ScriptTx tx, TxOperation op) {
        if (op.getVoterType() == null) {
            throw new IllegalArgumentException("create_vote requires 'voter_type'");
        }
        if (op.getVoterHash() == null) {
            throw new IllegalArgumentException("create_vote requires 'voter_hash'");
        }
        if (op.getGovActionTxHash() == null) {
            throw new IllegalArgumentException("create_vote requires 'gov_action_tx_hash'");
        }
        if (op.getGovActionIndex() == null) {
            throw new IllegalArgumentException("create_vote requires 'gov_action_index'");
        }
        if (op.getVote() == null) {
            throw new IllegalArgumentException("create_vote requires 'vote'");
        }

        Voter voter = parseVoter(op.getVoterType(), op.getVoterHash());
        GovActionId govActionId = new GovActionId(op.getGovActionTxHash(), op.getGovActionIndex());
        Vote vote = parseVote(op.getVote());
        Anchor anchor = parseAnchor(op.getAnchorUrl(), op.getAnchorDataHash());
        PlutusData redeemer = op.getRedeemerCborHex() != null ? parseRedeemer(op.getRedeemerCborHex()) : null;

        if (redeemer == null) {
            throw new IllegalArgumentException("create_vote in script_tx mode requires 'redeemer_cbor_hex'");
        }
        tx.createVote(voter, govActionId, vote, anchor, redeemer);
    }

    // --- Governance proposals ---

    private static void applyCreateProposal(ScriptTx tx, TxOperation op) {
        if (op.getGovActionType() == null) {
            throw new IllegalArgumentException("create_proposal requires 'gov_action_type'");
        }
        if (op.getReturnAddress() == null) {
            throw new IllegalArgumentException("create_proposal requires 'return_address'");
        }
        if (op.getRedeemerCborHex() == null || op.getRedeemerCborHex().isEmpty()) {
            throw new IllegalArgumentException("create_proposal in script_tx mode requires 'redeemer_cbor_hex'");
        }

        Anchor anchor = parseAnchor(op.getAnchorUrl(), op.getAnchorDataHash());
        PlutusData redeemer = parseRedeemer(op.getRedeemerCborHex());

        switch (op.getGovActionType().toLowerCase()) {
            case "info_action": {
                InfoAction action = InfoAction.builder().build();
                tx.createProposal(action, op.getReturnAddress(), anchor, redeemer);
                break;
            }
            case "treasury_withdrawals": {
                TreasuryWithdrawalsAction action = TreasuryWithdrawalsAction.builder().build();
                if (op.getWithdrawals() != null) {
                    for (Map<String, String> w : op.getWithdrawals()) {
                        String rewardAddr = w.get("reward_address");
                        String amt = w.get("amount");
                        if (rewardAddr == null || amt == null) {
                            throw new IllegalArgumentException(
                                    "Each withdrawal requires 'reward_address' and 'amount'");
                        }
                        action.addWithdrawal(new Withdrawal(rewardAddr, new BigInteger(amt)));
                    }
                }
                tx.createProposal(action, op.getReturnAddress(), anchor, redeemer);
                break;
            }
            case "no_confidence": {
                NoConfidence.NoConfidenceBuilder builder = NoConfidence.builder();
                GovActionId prevId = parsePrevGovActionId(op.getGovActionTxHash(), op.getGovActionIndex());
                if (prevId != null) builder.prevGovActionId(prevId);
                tx.createProposal(builder.build(), op.getReturnAddress(), anchor, redeemer);
                break;
            }
            case "update_committee": {
                UpdateCommittee.UpdateCommitteeBuilder builder = UpdateCommittee.builder();
                GovActionId prevId = parsePrevGovActionId(op.getGovActionTxHash(), op.getGovActionIndex());
                if (prevId != null) builder.prevGovActionId(prevId);
                if (op.getMembersToRemove() != null) {
                    Set<Credential> removals = new LinkedHashSet<>();
                    for (Map<String, String> m : op.getMembersToRemove()) {
                        removals.add(parseCredential(m.get("hash"), m.get("type")));
                    }
                    builder.membersForRemoval(removals);
                }
                if (op.getNewMembers() != null) {
                    Map<Credential, Integer> newMembersMap = new LinkedHashMap<>();
                    for (Map<String, Object> m : op.getNewMembers()) {
                        Credential cred = parseCredential((String) m.get("hash"), (String) m.get("type"));
                        int epoch = ((Number) m.get("epoch")).intValue();
                        newMembersMap.put(cred, epoch);
                    }
                    builder.newMembersAndTerms(newMembersMap);
                }
                if (op.getQuorumNumerator() != null && op.getQuorumDenominator() != null) {
                    builder.quorumThreshold(new UnitInterval(
                            new BigInteger(op.getQuorumNumerator()),
                            new BigInteger(op.getQuorumDenominator())));
                }
                tx.createProposal(builder.build(), op.getReturnAddress(), anchor, redeemer);
                break;
            }
            case "new_constitution": {
                NewConstitution.NewConstitutionBuilder builder = NewConstitution.builder();
                GovActionId prevId = parsePrevGovActionId(op.getGovActionTxHash(), op.getGovActionIndex());
                if (prevId != null) builder.prevGovActionId(prevId);
                Anchor constitutionAnchor = parseAnchor(op.getConstitutionAnchorUrl(), op.getConstitutionAnchorDataHash());
                Constitution.ConstitutionBuilder cBuilder = Constitution.builder();
                if (constitutionAnchor != null) cBuilder.anchor(constitutionAnchor);
                if (op.getConstitutionScriptHash() != null) cBuilder.scripthash(op.getConstitutionScriptHash());
                builder.constitution(cBuilder.build());
                tx.createProposal(builder.build(), op.getReturnAddress(), anchor, redeemer);
                break;
            }
            case "hard_fork_initiation": {
                HardForkInitiationAction.HardForkInitiationActionBuilder builder = HardForkInitiationAction.builder();
                GovActionId prevId = parsePrevGovActionId(op.getGovActionTxHash(), op.getGovActionIndex());
                if (prevId != null) builder.prevGovActionId(prevId);
                if (op.getProtocolVersionMajor() == null || op.getProtocolVersionMinor() == null) {
                    throw new IllegalArgumentException(
                            "hard_fork_initiation requires 'protocol_version_major' and 'protocol_version_minor'");
                }
                builder.protocolVersion(new ProtocolVersion(op.getProtocolVersionMajor(), op.getProtocolVersionMinor()));
                tx.createProposal(builder.build(), op.getReturnAddress(), anchor, redeemer);
                break;
            }
            case "parameter_change": {
                ParameterChangeAction.ParameterChangeActionBuilder builder = ParameterChangeAction.builder();
                GovActionId prevId = parsePrevGovActionId(op.getGovActionTxHash(), op.getGovActionIndex());
                if (prevId != null) builder.prevGovActionId(prevId);
                if (op.getPolicyHash() != null && !op.getPolicyHash().isEmpty()) {
                    builder.policyHash(HexUtil.decodeHexString(op.getPolicyHash()));
                }
                if (op.getProtocolParamUpdateJson() != null && !op.getProtocolParamUpdateJson().isEmpty()) {
                    throw new IllegalArgumentException(
                            "parameter_change protocol_param_update_json is not yet supported. " +
                            "Use the raw transaction builder for parameter change proposals.");
                }
                tx.createProposal(builder.build(), op.getReturnAddress(), anchor, redeemer);
                break;
            }
            default:
                throw new IllegalArgumentException(
                        "Unsupported gov_action_type: " + op.getGovActionType());
        }
    }

    // --- Treasury donation (Gap 4) ---

    private static void applyDonateToTreasury(ScriptTx tx, TxOperation op) {
        if (op.getTreasuryValue() == null) {
            throw new IllegalArgumentException("donate_to_treasury requires 'treasury_value'");
        }
        if (op.getDonationAmount() == null) {
            throw new IllegalArgumentException("donate_to_treasury requires 'donation_amount'");
        }
        tx.donateToTreasury(new BigInteger(op.getTreasuryValue()), new BigInteger(op.getDonationAmount()));
    }

    // --- Helper methods ---

    private static GovActionId parsePrevGovActionId(String txHash, Integer index) {
        if (txHash == null || txHash.isEmpty()) return null;
        return new GovActionId(txHash, index != null ? index : 0);
    }

    private static Script parseScriptRef(String cborHex, String scriptRefType) {
        if (scriptRefType == null || scriptRefType.isEmpty()) {
            throw new IllegalArgumentException("script_ref_type is required when script_ref_cbor_hex is provided. " +
                    "Supported: plutus_v1, plutus_v2, plutus_v3");
        }
        return switch (scriptRefType.toLowerCase()) {
            case "plutus_v1" -> PlutusV1Script.builder().cborHex(cborHex).build();
            case "plutus_v2" -> PlutusV2Script.builder().cborHex(cborHex).build();
            case "plutus_v3" -> PlutusV3Script.builder().cborHex(cborHex).build();
            default -> throw new IllegalArgumentException("Unsupported script_ref_type: " + scriptRefType
                    + ". Supported: plutus_v1, plutus_v2, plutus_v3");
        };
    }

    static PlutusScript parsePlutusScript(String cborHex, String type) {
        return switch (type.toLowerCase()) {
            case "plutus_v1" -> PlutusV1Script.builder().cborHex(cborHex).build();
            case "plutus_v2" -> PlutusV2Script.builder().cborHex(cborHex).build();
            case "plutus_v3" -> PlutusV3Script.builder().cborHex(cborHex).build();
            default -> throw new IllegalArgumentException("Unsupported script_type: " + type
                    + ". Supported: plutus_v1, plutus_v2, plutus_v3");
        };
    }

    static PlutusData parseRedeemer(String cborHex) {
        try {
            return PlutusData.deserialize(HexUtil.decodeHexString(cborHex));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid CBOR hex: " + e.getMessage(), e);
        }
    }

    private static Credential parseCredential(String hash, String type) {
        if ("script".equalsIgnoreCase(type)) {
            return Credential.fromScript(hash);
        }
        return Credential.fromKey(hash);
    }

    private static Anchor parseAnchor(String url, String dataHash) {
        if (url == null || dataHash == null) return null;
        return Anchor.builder()
                .anchorUrl(url)
                .anchorDataHash(HexUtil.decodeHexString(dataHash))
                .build();
    }

    private static DRep parseDRep(String drepType, String drepHash) {
        return switch (drepType.toLowerCase()) {
            case "abstain" -> DRep.abstain();
            case "no_confidence" -> DRep.noConfidence();
            case "script_hash" -> DRep.scriptHash(drepHash);
            default -> DRep.addrKeyHash(drepHash);
        };
    }

    private static Vote parseVote(String voteStr) {
        return switch (voteStr.toLowerCase()) {
            case "yes" -> Vote.YES;
            case "no" -> Vote.NO;
            case "abstain" -> Vote.ABSTAIN;
            default -> throw new IllegalArgumentException("Invalid vote value: " + voteStr
                    + ". Must be 'yes', 'no', or 'abstain'");
        };
    }

    private static Voter parseVoter(String voterType, String voterHash) {
        Credential credential = Credential.fromKey(voterHash);
        VoterType type = switch (voterType.toLowerCase()) {
            case "drep_key_hash" -> VoterType.DREP_KEY_HASH;
            case "drep_script_hash" -> {
                credential = Credential.fromScript(voterHash);
                yield VoterType.DREP_SCRIPT_HASH;
            }
            case "staking_pool_key_hash" -> VoterType.STAKING_POOL_KEY_HASH;
            case "constitutional_committee_hot_key_hash" ->
                    VoterType.CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH;
            case "constitutional_committee_hot_script_hash" -> {
                credential = Credential.fromScript(voterHash);
                yield VoterType.CONSTITUTIONAL_COMMITTEE_HOT_SCRIPT_HASH;
            }
            default -> throw new IllegalArgumentException("Invalid voter_type: " + voterType);
        };
        return new Voter(type, credential);
    }
}
