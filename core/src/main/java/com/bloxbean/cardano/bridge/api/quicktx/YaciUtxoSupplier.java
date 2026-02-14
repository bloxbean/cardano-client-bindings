package com.bloxbean.cardano.bridge.api.quicktx;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class YaciUtxoSupplier implements UtxoSupplier {

    private static final TypeReference<List<Utxo>> UTXO_LIST_TYPE = new TypeReference<>() {};

    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper mapper;

    public YaciUtxoSupplier(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
    }

    @Override
    public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
        int pageNum = (page != null) ? page : 0;
        int count = (nrOfItems != null && nrOfItems > 0) ? nrOfItems : DEFAULT_NR_OF_ITEMS_TO_FETCH;
        String orderStr = (order == OrderEnum.desc) ? "desc" : "asc";

        String url = baseUrl + "/addresses/" + address + "/utxos"
                + "?page=" + pageNum + "&count=" + count + "&order=" + orderStr;

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                return Collections.emptyList();
            }

            return mapper.readValue(resp.body(), UTXO_LIST_TYPE);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch UTXOs from provider: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
        // Not needed for coin selection; return empty
        return Optional.empty();
    }
}
