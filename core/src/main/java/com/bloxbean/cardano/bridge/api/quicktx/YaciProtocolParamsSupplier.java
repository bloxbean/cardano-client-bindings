package com.bloxbean.cardano.bridge.api.quicktx;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.fasterxml.jackson.databind.ObjectMapper;

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

            return mapper.readValue(resp.body(), ProtocolParams.class);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch protocol params from provider: " + e.getMessage(), e);
        }
    }
}
