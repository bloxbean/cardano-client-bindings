package com.bloxbean.cardano.bridge.api.quicktx;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadata;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataList;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataMap;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.Voter;
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.TreasuryWithdrawalsAction;
import com.bloxbean.cardano.client.transaction.spec.script.NativeScript;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps a TxSpec (parsed from JSON) into a CCL Tx object.
 */
public class TxSpecMapper {

    public static Tx toTx(TxSpec spec) {
        Tx tx = new Tx();
        tx.from(spec.getFrom());
        if (spec.getChangeAddress() != null && !spec.getChangeAddress().isEmpty()) {
            tx.withChangeAddress(spec.getChangeAddress());
        }
        applyOperations(tx, spec.getOperations());
        return tx;
    }

    public static Tx toTx(TxItemSpec item) {
        Tx tx = new Tx();
        tx.from(item.getFrom());
        if (item.getChangeAddress() != null && !item.getChangeAddress().isEmpty()) {
            tx.withChangeAddress(item.getChangeAddress());
        }
        applyOperations(tx, item.getOperations());
        return tx;
    }

    private static void applyOperations(Tx tx, List<TxOperation> operations) {
        for (TxOperation op : operations) {
            switch (op.getType()) {
                case "pay_to_address":
                    applyPayToAddress(tx, op);
                    break;
                case "pay_to_contract":
                    applyPayToContract(tx, op);
                    break;
                case "mint_assets":
                    applyMintAssets(tx, op);
                    break;
                case "attach_metadata":
                    applyAttachMetadata(tx, op);
                    break;
                case "collect_from":
                    applyCollectFrom(tx, op);
                    break;
                // Staking
                case "register_stake_address":
                    applyRegisterStakeAddress(tx, op);
                    break;
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
                default:
                    throw new IllegalArgumentException("Unknown operation type: " + op.getType());
            }
        }
    }

    private static void applyPayToAddress(Tx tx, TxOperation op) {
        if (op.getAddress() == null) {
            throw new IllegalArgumentException("pay_to_address requires 'address'");
        }
        if (op.getAmounts() == null || op.getAmounts().isEmpty()) {
            throw new IllegalArgumentException("pay_to_address requires 'amounts'");
        }
        tx.payToAddress(op.getAddress(), op.getAmounts());
    }

    private static void applyPayToContract(Tx tx, TxOperation op) {
        if (op.getAddress() == null) {
            throw new IllegalArgumentException("pay_to_contract requires 'address'");
        }
        if (op.getAmounts() == null || op.getAmounts().isEmpty()) {
            throw new IllegalArgumentException("pay_to_contract requires 'amounts'");
        }

        if (op.getDatumCborHex() != null && !op.getDatumCborHex().isEmpty()) {
            PlutusData datum;
            try {
                datum = PlutusData.deserialize(HexUtil.decodeHexString(op.getDatumCborHex()));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid datum CBOR: " + e.getMessage(), e);
            }
            tx.payToContract(op.getAddress(), op.getAmounts(), datum);
        } else if (op.getDatumHash() != null && !op.getDatumHash().isEmpty()) {
            tx.payToContract(op.getAddress(), op.getAmounts(), op.getDatumHash());
        } else {
            throw new IllegalArgumentException("pay_to_contract requires 'datum_cbor_hex' or 'datum_hash'");
        }
    }

    private static void applyMintAssets(Tx tx, TxOperation op) {
        if (op.getScriptJson() == null) {
            throw new IllegalArgumentException("mint_assets requires 'script_json'");
        }
        if (op.getAssets() == null || op.getAssets().isEmpty()) {
            throw new IllegalArgumentException("mint_assets requires 'assets'");
        }
        if (op.getReceiver() == null || op.getReceiver().isEmpty()) {
            throw new IllegalArgumentException("mint_assets requires 'receiver' address");
        }

        NativeScript script;
        try {
            script = NativeScript.deserializeJson(op.getScriptJson());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid native script JSON: " + e.getMessage(), e);
        }

        List<Asset> assets = new ArrayList<>();
        for (TxOperation.MintAsset ma : op.getAssets()) {
            assets.add(new Asset(ma.getName(), new BigInteger(ma.getQuantity())));
        }

        tx.mintAssets(script, assets, op.getReceiver());
    }

    @SuppressWarnings("unchecked")
    private static void applyAttachMetadata(Tx tx, TxOperation op) {
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

    private static void applyCollectFrom(Tx tx, TxOperation op) {
        if (op.getCollectUtxos() == null || op.getCollectUtxos().isEmpty()) {
            throw new IllegalArgumentException("collect_from requires 'collect_utxos'");
        }
        tx.collectFrom(op.getCollectUtxos());
    }

    // --- Staking ---

    private static void applyRegisterStakeAddress(Tx tx, TxOperation op) {
        if (op.getAddress() == null) {
            throw new IllegalArgumentException("register_stake_address requires 'address'");
        }
        tx.registerStakeAddress(op.getAddress());
    }

    private static void applyDeregisterStakeAddress(Tx tx, TxOperation op) {
        if (op.getAddress() == null) {
            throw new IllegalArgumentException("deregister_stake_address requires 'address'");
        }
        if (op.getRefundAddress() != null && !op.getRefundAddress().isEmpty()) {
            tx.deregisterStakeAddress(op.getAddress(), op.getRefundAddress());
        } else {
            tx.deregisterStakeAddress(op.getAddress());
        }
    }

    private static void applyDelegateTo(Tx tx, TxOperation op) {
        if (op.getAddress() == null) {
            throw new IllegalArgumentException("delegate_to requires 'address'");
        }
        if (op.getPoolId() == null || op.getPoolId().isEmpty()) {
            throw new IllegalArgumentException("delegate_to requires 'pool_id'");
        }
        tx.delegateTo(op.getAddress(), op.getPoolId());
    }

    private static void applyWithdraw(Tx tx, TxOperation op) {
        if (op.getRewardAddress() == null) {
            throw new IllegalArgumentException("withdraw requires 'reward_address'");
        }
        if (op.getAmount() == null) {
            throw new IllegalArgumentException("withdraw requires 'amount'");
        }
        BigInteger amount = new BigInteger(op.getAmount());
        if (op.getReceiver() != null && !op.getReceiver().isEmpty()) {
            tx.withdraw(op.getRewardAddress(), amount, op.getReceiver());
        } else {
            tx.withdraw(op.getRewardAddress(), amount);
        }
    }

    // --- DRep ---

    private static void applyRegisterDRep(Tx tx, TxOperation op) {
        if (op.getCredentialHash() == null) {
            throw new IllegalArgumentException("register_drep requires 'credential_hash'");
        }
        Credential credential = parseCredential(op.getCredentialHash(), op.getCredentialType());
        Anchor anchor = parseAnchor(op.getAnchorUrl(), op.getAnchorDataHash());
        if (anchor != null) {
            tx.registerDRep(credential, anchor);
        } else {
            tx.registerDRep(credential);
        }
    }

    private static void applyUnregisterDRep(Tx tx, TxOperation op) {
        if (op.getCredentialHash() == null) {
            throw new IllegalArgumentException("unregister_drep requires 'credential_hash'");
        }
        Credential credential = parseCredential(op.getCredentialHash(), op.getCredentialType());
        if (op.getRefundAddress() != null && !op.getRefundAddress().isEmpty()) {
            tx.unregisterDRep(credential, op.getRefundAddress());
        } else {
            tx.unregisterDRep(credential);
        }
    }

    private static void applyUpdateDRep(Tx tx, TxOperation op) {
        if (op.getCredentialHash() == null) {
            throw new IllegalArgumentException("update_drep requires 'credential_hash'");
        }
        Credential credential = parseCredential(op.getCredentialHash(), op.getCredentialType());
        Anchor anchor = parseAnchor(op.getAnchorUrl(), op.getAnchorDataHash());
        if (anchor != null) {
            tx.updateDRep(credential, anchor);
        } else {
            tx.updateDRep(credential);
        }
    }

    // --- Voting ---

    private static void applyDelegateVotingPowerTo(Tx tx, TxOperation op) {
        if (op.getAddress() == null) {
            throw new IllegalArgumentException("delegate_voting_power_to requires 'address'");
        }
        if (op.getDrepType() == null) {
            throw new IllegalArgumentException("delegate_voting_power_to requires 'drep_type'");
        }
        DRep drep = parseDRep(op.getDrepType(), op.getDrepHash());
        tx.delegateVotingPowerTo(op.getAddress(), drep);
    }

    private static void applyCreateVote(Tx tx, TxOperation op) {
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

        if (anchor != null) {
            tx.createVote(voter, govActionId, vote, anchor);
        } else {
            tx.createVote(voter, govActionId, vote);
        }
    }

    // --- Governance proposals ---

    private static void applyCreateProposal(Tx tx, TxOperation op) {
        if (op.getGovActionType() == null) {
            throw new IllegalArgumentException("create_proposal requires 'gov_action_type'");
        }
        if (op.getReturnAddress() == null) {
            throw new IllegalArgumentException("create_proposal requires 'return_address'");
        }

        Anchor anchor = parseAnchor(op.getAnchorUrl(), op.getAnchorDataHash());

        switch (op.getGovActionType().toLowerCase()) {
            case "info_action": {
                InfoAction action = InfoAction.builder().build();
                tx.createProposal(action, op.getReturnAddress(), anchor);
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
                tx.createProposal(action, op.getReturnAddress(), anchor);
                break;
            }
            default:
                throw new IllegalArgumentException(
                        "Unsupported gov_action_type: " + op.getGovActionType());
        }
    }

    // --- Helper methods ---

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
            default -> DRep.addrKeyHash(drepHash); // key_hash
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
