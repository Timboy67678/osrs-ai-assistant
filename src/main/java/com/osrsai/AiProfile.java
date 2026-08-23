package com.osrsai;

import java.util.UUID;

/**
 * Data model representing a user-configured AI provider profile.
 * <p>
 * Holds configuration details such as profile name, provider type (Gemini,
 * OpenAI, Claude, Grok, Custom),
 * API keys, optional client/organization IDs, custom model identifiers, and
 * custom API endpoints.
 */
public class AiProfile {
    private String id;
    private String name;
    private AiProvider provider;
    private String apiKey;
    private String clientId;
    private String customModel;
    private String customEndpoint;

    /**
     * Constructs a new {@code AiProfile} initialized with a random UUID, default
     * name,
     * and default provider (Gemini).
     */
    public AiProfile() {
        this.id = UUID.randomUUID().toString();
        this.name = "New Profile";
        this.provider = AiProvider.GROK;
        this.apiKey = "";
        this.clientId = "";
        this.customModel = "";
        this.customEndpoint = "";
    }

    /**
     * Gets the unique identifier of this profile.
     *
     * @return profile UUID string
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the unique identifier of this profile.
     *
     * @param id unique profile ID
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Gets the user-facing display name of this profile.
     *
     * @return profile name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the user-facing display name of this profile.
     *
     * @param name profile display name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Gets the AI provider type associated with this profile.
     *
     * @return the {@link AiProvider} enum value
     */
    public AiProvider getProvider() {
        return provider;
    }

    /**
     * Sets the AI provider type associated with this profile.
     *
     * @param provider the {@link AiProvider} enum value
     */
    public void setProvider(AiProvider provider) {
        this.provider = provider;
    }

    /**
     * Gets the API key configured for this profile.
     *
     * @return API key string
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Sets the API key configured for this profile.
     *
     * @param apiKey API key string
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Gets the optional client ID / Organization ID configured for this profile.
     *
     * @return client or organization ID string
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * Sets the optional client ID / Organization ID configured for this profile.
     *
     * @param clientId client or organization ID string
     */
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    /**
     * Gets the custom model identifier (used when provider is CUSTOM or model
     * override is specified).
     *
     * @return custom model name string
     */
    public String getCustomModel() {
        return customModel;
    }

    /**
     * Sets the custom model identifier.
     *
     * @param customModel custom model name string
     */
    public void setCustomModel(String customModel) {
        this.customModel = customModel;
    }

    /**
     * Gets the custom HTTP endpoint URL (used when provider is CUSTOM).
     *
     * @return custom API endpoint URL string
     */
    public String getCustomEndpoint() {
        return customEndpoint;
    }

    /**
     * Sets the custom HTTP endpoint URL.
     *
     * @param customEndpoint custom API endpoint URL string
     */
    public void setCustomEndpoint(String customEndpoint) {
        this.customEndpoint = customEndpoint;
    }

    /**
     * Returns the name of the profile for display in UI components.
     *
     * @return profile name
     */
    @Override
    public String toString() {
        return name;
    }
}
