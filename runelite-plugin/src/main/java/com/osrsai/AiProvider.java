package com.osrsai;

public enum AiProvider {
    GEMINI("Gemini 2.5 Flash", "gemini-2.5-flash"),
    OPENAI("OpenAI GPT-4o", "gpt-4o"),
    CLAUDE("Anthropic Claude", "claude-3-5-sonnet-20240620"),
    GROK("xAI Grok", "grok-4-1-fast-non-reasoning");

    private final String name;
    private final String modelId;

    AiProvider(String name) {
        this.name = name;
        this.modelId = null;
    }

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

    @Override
    public String toString() {
        return name;
    }
}
