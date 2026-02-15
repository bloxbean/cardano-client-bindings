package com.bloxbean.cardano.bridge.api.quicktx;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadata;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataList;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataMap;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV1Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.spec.UnitInterval;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.ProtocolVersion;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.SingleHostAddr;
import com.bloxbean.cardano.client.transaction.spec.cert.SingleHostName;
import com.bloxbean.cardano.client.transaction.spec.cert.MultiHostName;
import com.bloxbean.cardano.client.transaction.spec.cert.Relay;
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
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.*;

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
                // Pool operations (Gap 3)
                case "register_pool":
                    applyRegisterPool(tx, op);
                    break;
                case "update_pool":
                    applyUpdatePool(tx, op);
                    break;
                case "retire_pool":
                    applyRetirePool(tx, op);
                    break;
                // Treasury donation (Gap 4)
                case "donate_to_treasury":
                    applyDonateToTreasury(tx, op);
                    break;
                // Attach native script standalone (Gap 5)
                case "attach_native_script":
                    applyAttachNativeScript(tx, op);
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
        if (op.getScriptRefCborHex() != null && !op.getScriptRefCborHex().isEmpty()) {
            Script refScript = parseScriptRef(op.getScriptRefCborHex(), op.getScriptRefType());
            tx.payToAddress(op.getAddress(), op.getAmounts(), refScript);
        } else {
            tx.payToAddress(op.getAddress(), op.getAmounts());
        }
    }

    private static void applyPayToContract(Tx tx, TxOperation op) {
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
            PlutusData datum;
            try {
                datum = PlutusData.deserialize(HexUtil.decodeHexString(op.getDatumCborHex()));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid datum CBOR: " + e.getMessage(), e);
            }
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
        if (op.getRefundAmount() != null && !op.getRefundAmount().isEmpty()
                && op.getRefundAddress() != null && !op.getRefundAddress().isEmpty()) {
            tx.unregisterDRep(credential, op.getRefundAddress(), new BigInteger(op.getRefundAmount()));
        } else if (op.getRefundAddress() != null && !op.getRefundAddress().isEmpty()) {
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
            case "no_confidence": {
                NoConfidence.NoConfidenceBuilder builder = NoConfidence.builder();
                GovActionId prevId = parsePrevGovActionId(op.getGovActionTxHash(), op.getGovActionIndex());
                if (prevId != null) builder.prevGovActionId(prevId);
                tx.createProposal(builder.build(), op.getReturnAddress(), anchor);
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
                tx.createProposal(builder.build(), op.getReturnAddress(), anchor);
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
                tx.createProposal(builder.build(), op.getReturnAddress(), anchor);
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
                tx.createProposal(builder.build(), op.getReturnAddress(), anchor);
                break;
            }
            case "parameter_change": {
                ParameterChangeAction.ParameterChangeActionBuilder builder = ParameterChangeAction.builder();
                GovActionId prevId = parsePrevGovActionId(op.getGovActionTxHash(), op.getGovActionIndex());
                if (prevId != null) builder.prevGovActionId(prevId);
                if (op.getPolicyHash() != null && !op.getPolicyHash().isEmpty()) {
                    builder.policyHash(HexUtil.decodeHexString(op.getPolicyHash()));
                }
                // ProtocolParamUpdate is complex — pass as CBOR hex if provided
                if (op.getProtocolParamUpdateJson() != null && !op.getProtocolParamUpdateJson().isEmpty()) {
                    throw new IllegalArgumentException(
                            "parameter_change protocol_param_update_json is not yet supported. " +
                            "Use the raw transaction builder for parameter change proposals.");
                }
                tx.createProposal(builder.build(), op.getReturnAddress(), anchor);
                break;
            }
            default:
                throw new IllegalArgumentException(
                        "Unsupported gov_action_type: " + op.getGovActionType());
        }
    }

    // --- Pool operations (Gap 3) ---

    private static void applyRegisterPool(Tx tx, TxOperation op) {
        tx.registerPool(buildPoolRegistration(op));
    }

    private static void applyUpdatePool(Tx tx, TxOperation op) {
        tx.updatePool(buildPoolRegistration(op));
    }

    private static PoolRegistration buildPoolRegistration(TxOperation op) {
        if (op.getOperator() == null) throw new IllegalArgumentException("Pool operation requires 'operator'");
        if (op.getVrfKeyHash() == null) throw new IllegalArgumentException("Pool operation requires 'vrf_key_hash'");
        if (op.getPledge() == null) throw new IllegalArgumentException("Pool operation requires 'pledge'");
        if (op.getCost() == null) throw new IllegalArgumentException("Pool operation requires 'cost'");
        if (op.getMarginNumerator() == null || op.getMarginDenominator() == null) {
            throw new IllegalArgumentException("Pool operation requires 'margin_numerator' and 'margin_denominator'");
        }
        if (op.getRewardAddress() == null) throw new IllegalArgumentException("Pool operation requires 'reward_address'");
        if (op.getPoolOwners() == null || op.getPoolOwners().isEmpty()) {
            throw new IllegalArgumentException("Pool operation requires 'pool_owners'");
        }

        PoolRegistration.PoolRegistrationBuilder builder = PoolRegistration.builder()
                .operator(HexUtil.decodeHexString(op.getOperator()))
                .vrfKeyHash(HexUtil.decodeHexString(op.getVrfKeyHash()))
                .pledge(new BigInteger(op.getPledge()))
                .cost(new BigInteger(op.getCost()))
                .margin(new UnitInterval(new BigInteger(op.getMarginNumerator()), new BigInteger(op.getMarginDenominator())))
                .rewardAccount(op.getRewardAddress())
                .poolOwners(new LinkedHashSet<>(op.getPoolOwners()));

        if (op.getRelays() != null) {
            builder.relays(parseRelays(op.getRelays()));
        }
        if (op.getPoolMetadataUrl() != null) {
            builder.poolMetadataUrl(op.getPoolMetadataUrl());
        }
        if (op.getPoolMetadataHash() != null) {
            builder.poolMetadataHash(op.getPoolMetadataHash());
        }

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static List<Relay> parseRelays(List<Map<String, Object>> relayList) {
        List<Relay> relays = new ArrayList<>();
        for (Map<String, Object> r : relayList) {
            String type = (String) r.get("type");
            if (type == null) throw new IllegalArgumentException("Relay requires 'type'");
            switch (type.toLowerCase()) {
                case "single_host_addr": {
                    SingleHostAddr.SingleHostAddrBuilder b = SingleHostAddr.builder();
                    if (r.get("port") != null) b.port(((Number) r.get("port")).intValue());
                    if (r.get("ipv4") != null) {
                        try {
                            b.ipv4((Inet4Address) Inet4Address.getByName((String) r.get("ipv4")));
                        } catch (UnknownHostException e) {
                            throw new IllegalArgumentException("Invalid ipv4: " + r.get("ipv4"), e);
                        }
                    }
                    if (r.get("ipv6") != null) {
                        try {
                            b.ipv6((Inet6Address) Inet6Address.getByName((String) r.get("ipv6")));
                        } catch (UnknownHostException e) {
                            throw new IllegalArgumentException("Invalid ipv6: " + r.get("ipv6"), e);
                        }
                    }
                    relays.add(b.build());
                    break;
                }
                case "single_host_name": {
                    SingleHostName.SingleHostNameBuilder b = SingleHostName.builder();
                    if (r.get("port") != null) b.port(((Number) r.get("port")).intValue());
                    b.dnsName((String) r.get("dns_name"));
                    relays.add(b.build());
                    break;
                }
                case "multi_host_name": {
                    relays.add(MultiHostName.builder().dnsName((String) r.get("dns_name")).build());
                    break;
                }
                default:
                    throw new IllegalArgumentException("Unknown relay type: " + type);
            }
        }
        return relays;
    }

    private static void applyRetirePool(Tx tx, TxOperation op) {
        if (op.getPoolId() == null) throw new IllegalArgumentException("retire_pool requires 'pool_id'");
        if (op.getEpoch() == null) throw new IllegalArgumentException("retire_pool requires 'epoch'");
        tx.retirePool(op.getPoolId(), op.getEpoch());
    }

    // --- Treasury donation (Gap 4) ---

    private static void applyDonateToTreasury(Tx tx, TxOperation op) {
        if (op.getTreasuryValue() == null) {
            throw new IllegalArgumentException("donate_to_treasury requires 'treasury_value'");
        }
        if (op.getDonationAmount() == null) {
            throw new IllegalArgumentException("donate_to_treasury requires 'donation_amount'");
        }
        tx.donateToTreasury(new BigInteger(op.getTreasuryValue()), new BigInteger(op.getDonationAmount()));
    }

    // --- Attach native script standalone (Gap 5) ---

    private static void applyAttachNativeScript(Tx tx, TxOperation op) {
        if (op.getScriptJson() == null) {
            throw new IllegalArgumentException("attach_native_script requires 'script_json'");
        }
        NativeScript script;
        try {
            script = NativeScript.deserializeJson(op.getScriptJson());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid native script JSON: " + e.getMessage(), e);
        }
        tx.attachNativeScript(script);
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
