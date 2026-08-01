package com.osrsai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;

public class OpenAiProviderHandlerTest {
    private final Gson gson = new Gson();

    @Test
    public void testBuildRequestBodySetsMaxTokensForReasoningModel() {
        OpenAiProviderHandler handler = new OpenAiProviderHandler("http://localhost:11434/v1/chat/completions");
        JsonObject body = handler.buildRequestBody("grok-4.20-0309-reasoning", "ctx", "recent", "q", true);

        Assert.assertFalse(body.has("max_tokens"));
        Assert.assertTrue(body.has("max_completion_tokens"));
        Assert.assertEquals(16384, body.get("max_completion_tokens").getAsInt());
    }

    @Test
    public void testBuildRequestBodySetsMaxTokensForStandardModel() {
        OpenAiProviderHandler handler = new OpenAiProviderHandler("http://localhost:11434/v1/chat/completions");
        JsonObject body = handler.buildRequestBody("gpt-4o", "ctx", "recent", "q", true);

        Assert.assertTrue(body.has("max_tokens"));
        Assert.assertEquals(8192, body.get("max_tokens").getAsInt());
        Assert.assertTrue(body.has("max_completion_tokens"));
        Assert.assertEquals(8192, body.get("max_completion_tokens").getAsInt());
    }

    @Test
    public void testExtractResponseTextHandlesChoicesAndOutputs() {
        OpenAiProviderHandler handler = new OpenAiProviderHandler("http://localhost:11434/v1/chat/completions");

        // Standard OpenAI choices
        String choicesJson = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Standard choice response\"}}]}";
        JsonObject choicesObj = gson.fromJson(choicesJson, JsonObject.class);
        Assert.assertEquals("Standard choice response", handler.extractResponseText(choicesObj));

        // Custom gateway outputs format
        String outputsJson = "{\"outputs\":[{\"message\":{\"role\":\"assistant\",\"text\":\"Gateway output response\"}}]}";
        JsonObject outputsObj = gson.fromJson(outputsJson, JsonObject.class);
        Assert.assertEquals("Gateway output response", handler.extractResponseText(outputsObj));
    }
}
