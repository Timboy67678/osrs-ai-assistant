package com.osrsai.provider;

import com.google.gson.annotations.SerializedName;

/**
 * Enumeration of supported AI service providers and their default model
 * identifiers.
 * <p>
 * Groups each model under its base {@link ProviderType} to streamline handler
 * dispatch.
 */
public enum AiProvider {
    // Google Gemini
    @SerializedName(value = "GEMINI_3_6_FLASH", alternate = { "gemini_3_6_flash" })
    GEMINI_3_6_FLASH("Gemini 3.6 Flash", "gemini-3.6-flash", ProviderType.GEMINI),

    @SerializedName(value = "GEMINI_2_5_FLASH", alternate = { "GEMINI", "gemini", "gemini_2_5_flash" })
    GEMINI_2_5_FLASH("Gemini 2.5 Flash", "gemini-2.5-flash", ProviderType.GEMINI),

    @SerializedName(value = "GEMINI_2_5_PRO", alternate = { "GEMINI_PRO", "gemini_pro", "gemini_2_5_pro" })
    GEMINI_2_5_PRO("Gemini 2.5 Pro", "gemini-2.5-pro", ProviderType.GEMINI),

    // Anthropic Claude
    @SerializedName(value = "CLAUDE_3_7", alternate = { "CLAUDE", "claude", "claude_3_7" })
    CLAUDE_3_7("Claude Sonnet 3.7", "claude-3-7-sonnet-20250219", ProviderType.CLAUDE),

    @SerializedName(value = "CLAUDE_3_5", alternate = { "claude_3_5" })
    CLAUDE_3_5("Claude Sonnet 3.5", "claude-3-5-sonnet-20241022", ProviderType.CLAUDE),

    @SerializedName(value = "CLAUDE_HAIKU", alternate = { "claude_haiku" })
    CLAUDE_HAIKU("Claude Haiku 3.5", "claude-3-5-haiku-20241022", ProviderType.CLAUDE),

    // OpenAI
    @SerializedName(value = "OPENAI", alternate = { "openai" })
    OPENAI("OpenAI GPT-4o", "gpt-4o", ProviderType.OPENAI),

    @SerializedName(value = "OPENAI_MINI", alternate = { "openai_mini" })
    OPENAI_MINI("OpenAI GPT-4o Mini", "gpt-4o-mini", ProviderType.OPENAI),

    @SerializedName(value = "OPENAI_O3_MINI", alternate = { "openai_o3_mini" })
    OPENAI_O3_MINI("OpenAI o3-mini", "o3-mini", ProviderType.OPENAI),

    @SerializedName(value = "OPENAI_O1", alternate = { "openai_o1" })
    OPENAI_O1("OpenAI o1", "o1", ProviderType.OPENAI),

    // xAI Grok
    @SerializedName(value = "GROK_4_3", alternate = { "GROK", "grok", "grok_4_3" })
    GROK_4_3("Grok 4.3", "grok-4.3", ProviderType.GROK),

    @SerializedName(value = "GROK_4_20_REASONING", alternate = { "GROK_REASONING", "grok_reasoning",
            "grok_4_20_reasoning" })
    GROK_4_20_REASONING("Grok 4.20 Reasoning", "grok-4.20-0309-reasoning", ProviderType.GROK),

    @SerializedName(value = "GROK_3", alternate = { "grok_3" })
    GROK_3("Grok 3", "grok-3", ProviderType.GROK),

    @SerializedName(value = "GROK_3_REASONING", alternate = { "grok_3_reasoning" })
    GROK_3_REASONING("Grok 3 Reasoning", "grok-3-reasoning", ProviderType.GROK),

    // Custom
    @SerializedName(value = "CUSTOM", alternate = { "custom" })
    CUSTOM("Custom", "custom", ProviderType.CUSTOM);

    /**
     * Category of AI API provider backend protocol.
     */
    public enum ProviderType {
        GEMINI, OPENAI, CLAUDE, GROK, CUSTOM
    }

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String GROK_API_URL = "https://api.x.ai/v1/chat/completions";

    private static final ProviderHandler GEMINI_HANDLER = new GeminiProviderHandler();
    private static final ProviderHandler OPENAI_HANDLER = new OpenAiProviderHandler(OPENAI_API_URL);
    private static final ProviderHandler CLAUDE_HANDLER = new ClaudeProviderHandler();
    private static final ProviderHandler GROK_HANDLER = new OpenAiProviderHandler(GROK_API_URL);

    private final String name;
    private final String modelId;
    private final ProviderType type;

    /**
     * Constructs an {@code AiProvider} entry.
     *
     * @param name    human-readable display name
     * @param modelId default API model identifier
     * @param type    underlying provider category
     */
    AiProvider(String name, String modelId, ProviderType type) {
        this.name = name;
        this.modelId = modelId;
        this.type = type;
    }

    /**
     * Gets the human-readable display name of the provider.
     *
     * @return display name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the default API model identifier for this provider.
     *
     * @return model identifier string
     */
    public String getModelId() {
        return modelId;
    }

    /**
     * Gets the underlying {@link ProviderType} category.
     *
     * @return provider category
     */
    public ProviderType getType() {
        return type;
    }

    /**
     * Gets the {@link ProviderHandler} instance configured for this provider.
     *
     * @param customEndpoint custom HTTP API endpoint URL (used when provider is
     *                       {@link #CUSTOM})
     * @return the {@link ProviderHandler} implementation
     */
    public ProviderHandler getHandler(String customEndpoint) {
        switch (type) {
            case GEMINI:
                return GEMINI_HANDLER;
            case OPENAI:
                return OPENAI_HANDLER;
            case CLAUDE:
                return CLAUDE_HANDLER;
            case GROK:
                return GROK_HANDLER;
            case CUSTOM:
                return new OpenAiProviderHandler(customEndpoint);
            default:
                throw new IllegalStateException("Unexpected provider type: " + type);
        }
    }

    /**
     * Returns the display name of the provider.
     *
     * @return display name
     */
    @Override
    public String toString() {
        return name;
    }
}
