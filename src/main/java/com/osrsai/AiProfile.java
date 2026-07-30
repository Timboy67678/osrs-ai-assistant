package com.osrsai;

import java.util.UUID;

public class AiProfile {
    private String id;
    private String name;
    private AiProvider provider;
    private String apiKey;
    private String clientId;
    private String customModel;
    private String customEndpoint;

    public AiProfile() {
        this.id = UUID.randomUUID().toString();
        this.name = "New Profile";
        this.provider = AiProvider.GEMINI;
        this.apiKey = "";
        this.clientId = "";
        this.customModel = "";
        this.customEndpoint = "";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AiProvider getProvider() {
        return provider;
    }

    public void setProvider(AiProvider provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getCustomModel() {
        return customModel;
    }

    public void setCustomModel(String customModel) {
        this.customModel = customModel;
    }

    public String getCustomEndpoint() {
        return customEndpoint;
    }

    public void setCustomEndpoint(String customEndpoint) {
        this.customEndpoint = customEndpoint;
    }

    @Override
    public String toString() {
        return name;
    }
}
