package com.osrsai;

public enum AiProvider {
    GEMINI("Gemini 2.5 Flash", "gemini-2.5-flash"),
    OPENAI("OpenAI GPT-4o", "gpt-4o"),
    CLAUDE("Anthropic Claude", "claude-3-5-sonnet-20240620"),
    GROK("Grok 4.3", "grok-4.3"),
    CUSTOM("Custom (OpenAI Compatible)", "custom");

    private static final ProviderHandler GEMINI_HANDLER = new GeminiProviderHandler();
    private static final ProviderHandler OPENAI_HANDLER = new OpenAiProviderHandler(
            "https://api.openai.com/v1/chat/completions");
    private static final ProviderHandler CLAUDE_HANDLER = new ClaudeProviderHandler();
    private static final ProviderHandler GROK_HANDLER = new OpenAiProviderHandler(
            "https://api.x.ai/v1/chat/completions");

    private final String name;
    private final String modelId;

    AiProvider(String name, String modelId) {
        this.name = name;
        this.modelId = modelId;
    }

    public String getName() {
        return name;
    }

    public String getModelId() {
        return modelId;
    }

    public ProviderHandler getHandler(String customEndpoint) {
        switch (this) {
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
                throw new IllegalStateException("Unexpected provider: " + this);
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
