package com.bloxbean.cardano.bridge.api.quicktx;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class YaciProtocolParamsSupplier implements ProtocolParamsSupplier {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper mapper;

    public YaciProtocolParamsSupplier(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
    }

    @Override
    public ProtocolParams getProtocolParams() {
        String url = baseUrl + "/epochs/parameters";

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                throw new RuntimeException("Failed to fetch protocol params: HTTP " + resp.statusCode());
            }

            ProtocolParams params = mapper.readValue(resp.body(), ProtocolParams.class);
            applyDevnetGovDefaults(params);
            return params;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch protocol params from provider: " + e.getMessage(), e);
        }
    }

    /**
     * Yaci DevKit's admin {@code /epochs/parameters} endpoint returns {@code null} for the
     * Conway-era governance parameters (deposits, lifetimes). Building a governance proposal
     * or DRep registration reads the deposit and throws a {@link NullPointerException} when it
     * is null. Fill the standard Yaci DevKit devnet defaults so those operations build (and
     * match the devnet's on-chain values so they also submit).
     */
    private static void applyDevnetGovDefaults(ProtocolParams params) {
        if (params.getGovActionDeposit() == null)
            params.setGovActionDeposit(BigInteger.valueOf(1_000_000_000L)); // 1000 ADA
        if (params.getDrepDeposit() == null)
            params.setDrepDeposit(BigInteger.valueOf(2_000_000L));          // 2 ADA
        if (params.getGovActionLifetime() == null)
            params.setGovActionLifetime(10);
        if (params.getDrepActivity() == null)
            params.setDrepActivity(20);
    }
}
