package com.bloxbean.cardano.bridge.api.quicktx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ProviderConfig {

    @JsonProperty("name")
    private String name;

    @JsonProperty("url")
    private String url;

    @JsonProperty("api_key")
    private String apiKey;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public void validate() {
        if (name == null || name.isEmpty())
            throw new IllegalArgumentException("provider 'name' is required");
        if (url == null || url.isEmpty())
            throw new IllegalArgumentException("provider 'url' is required");
        if (!"yaci".equals(name))
            throw new IllegalArgumentException("Unsupported provider: " + name + ". Supported: yaci");
    }
}
