package com.osrsai;

/**
 * Enumeration of supported AI service providers and their default model identifiers.
 * <p>
 * Maps each provider type (Gemini, OpenAI, Claude, Grok, Custom) to its default model ID
 * and provides factory logic to retrieve the appropriate {@link ProviderHandler} instance.
 */
public enum AiProvider {
    /** Google Gemini provider (default: gemini-2.5-flash). */
    GEMINI("Gemini 2.5 Flash", "gemini-2.5-flash"),

    /** OpenAI GPT-4o provider (default: gpt-4o). */
    OPENAI("OpenAI GPT-4o", "gpt-4o"),

    /** Anthropic Claude provider (default: claude-3-5-sonnet-20240620). */
    CLAUDE("Claude Sonnet 3.5", "claude-3-5-sonnet-20240620"),

    /** xAI Grok provider (default: grok-4.3). */
    GROK("Grok 4.3", "grok-4.3"),

    /** xAI Grok Reasoning provider (default: grok-4.20-0309-reasoning). */
    GROK_REASONING("Grok 4.20 Reasoning", "grok-4.20-0309-reasoning"),

    /** Custom OpenAI-compatible HTTP API provider. */
    CUSTOM("Custom", "custom");

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String GROK_API_URL = "https://api.x.ai/v1/chat/completions";

    private static final ProviderHandler GEMINI_HANDLER = new GeminiProviderHandler();
    private static final ProviderHandler OPENAI_HANDLER = new OpenAiProviderHandler(OPENAI_API_URL);
    private static final ProviderHandler CLAUDE_HANDLER = new ClaudeProviderHandler();
    private static final ProviderHandler GROK_HANDLER = new OpenAiProviderHandler(GROK_API_URL);

    private final String name;
    private final String modelId;

    /**
     * Constructs an {@code AiProvider} entry.
     *
     * @param name human-readable display name
     * @param modelId default API model identifier
     */
    AiProvider(String name, String modelId) {
        this.name = name;
        this.modelId = modelId;
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
     * Gets the {@link ProviderHandler} instance configured for this provider.
     *
     * @param customEndpoint custom HTTP API endpoint URL (used when provider is {@link #CUSTOM})
     * @return the {@link ProviderHandler} implementation
     * @throws IllegalStateException if an unexpected provider is encountered
     */
    public ProviderHandler getHandler(String customEndpoint) {
        switch (this) {
            case GEMINI:
                return GEMINI_HANDLER;
            case OPENAI:
                return OPENAI_HANDLER;
            case CLAUDE:
                return CLAUDE_HANDLER;
            case GROK:
            case GROK_REASONING:
                return GROK_HANDLER;
            case CUSTOM:
                return new OpenAiProviderHandler(customEndpoint);
            default:
                throw new IllegalStateException("Unexpected provider: " + this);
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
