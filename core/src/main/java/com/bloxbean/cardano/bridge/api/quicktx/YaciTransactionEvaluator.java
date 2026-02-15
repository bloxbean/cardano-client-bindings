package com.bloxbean.cardano.bridge.api.quicktx;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TransactionEvaluator that uses Yaci Store / Blockfrost-compatible
 * evaluate endpoint for script cost evaluation.
 *
 * POST {baseUrl}/utils/txs/evaluate
 * Content-Type: application/cbor
 * Body: hex-encoded transaction CBOR
 *
 * Response: Ogmios JSON format with EvaluationResult map.
 */
public class YaciTransactionEvaluator implements TransactionEvaluator {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public YaciTransactionEvaluator(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) throws ApiException {
        return evaluateTx(cbor);
    }

    @Override
    public Result<List<EvaluationResult>> evaluateTx(byte[] cbor) throws ApiException {
        try {
            String txCborHex = HexUtil.encodeHexString(cbor);
            String url = baseUrl + "/utils/txs/evaluate";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/cbor")
                    .POST(HttpRequest.BodyPublishers.ofString(txCborHex))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return Result.<List<EvaluationResult>>error("Evaluation failed: " + response.body())
                        .code(response.statusCode());
            }

            List<EvaluationResult> results = parseResponse(response.body());
            return Result.<List<EvaluationResult>>success(response.body())
                    .code(200)
                    .withValue(results);

        } catch (Exception e) {
            throw new ApiException("Failed to evaluate transaction: " + e.getMessage(), e);
        }
    }

    private List<EvaluationResult> parseResponse(String responseBody) throws Exception {
        List<EvaluationResult> results = new ArrayList<>();
        JsonNode root = objectMapper.readTree(responseBody);

        // Ogmios format: { "type": "jsonwsp/response", "result": { "EvaluationResult": { "spend:0": {...}, ... } } }
        JsonNode evalResult = root.path("result").path("EvaluationResult");
        if (evalResult.isMissingNode()) {
            // Try alternative format: direct result
            evalResult = root.path("result");
        }
        if (evalResult.isMissingNode() || !evalResult.isObject()) {
            return results;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = evalResult.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey(); // e.g., "spend:0", "mint:0"
            JsonNode value = entry.getValue();

            String[] parts = key.split(":");
            if (parts.length != 2) continue;

            RedeemerTag tag = parseRedeemerTag(parts[0]);
            int index = Integer.parseInt(parts[1]);

            long memory = value.path("memory").asLong();
            long steps = value.path("steps").asLong();

            results.add(EvaluationResult.builder()
                    .redeemerTag(tag)
                    .index(index)
                    .exUnits(ExUnits.builder()
                            .mem(BigInteger.valueOf(memory))
                            .steps(BigInteger.valueOf(steps))
                            .build())
                    .build());
        }

        return results;
    }

    private RedeemerTag parseRedeemerTag(String tag) {
        return switch (tag.toLowerCase()) {
            case "spend" -> RedeemerTag.Spend;
            case "mint" -> RedeemerTag.Mint;
            case "cert", "publish", "certificate" -> RedeemerTag.Cert;
            case "reward", "withdrawal", "withdraw" -> RedeemerTag.Reward;
            case "vote", "voting" -> RedeemerTag.Voting;
            case "propose", "proposing" -> RedeemerTag.Proposing;
            default -> throw new IllegalArgumentException("Unknown redeemer tag: " + tag);
        };
    }
}
