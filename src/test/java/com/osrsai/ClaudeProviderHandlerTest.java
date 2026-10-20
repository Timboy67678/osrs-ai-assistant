package com.osrsai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.osrsai.provider.ClaudeProviderHandler;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class ClaudeProviderHandlerTest {
    private final Gson gson = new Gson();
    private final ClaudeProviderHandler handler = new ClaudeProviderHandler();

    @Test
    public void testBuildRequestBodyIncludesSystemAndMessagesAndTools() {
        JsonObject body = handler.buildRequestBody("claude-sonnet-5-5", "Game Context", "Recent Chat", "Where am I?", true);

        Assert.assertEquals("claude-sonnet-5-5", body.get("model").getAsString());
        Assert.assertTrue(body.has("system"));
        Assert.assertTrue(body.has("messages"));
        Assert.assertTrue(body.has("tools"));
        Assert.assertEquals(16000, body.get("max_tokens").getAsInt());
        Assert.assertFalse("current Claude models reject temperature", body.has("temperature"));
        Assert.assertEquals("default", body.get("fallbacks").getAsString());
    }

    @Test
    public void testHaikuOmitsServerFallback() {
        JsonObject body = handler.buildRequestBody("claude-haiku-5-5", "Ctx", "None", "Hi", true);
        Assert.assertFalse(body.has("fallbacks"));
        Assert.assertNull(handler.buildHttpRequest("claude-haiku-5-5", "k", null, "{}").header("anthropic-beta"));
        Assert.assertEquals("server-side-fallback-2026-07-01",
                handler.buildHttpRequest("claude-sonnet-5-5", "k", null, "{}").header("anthropic-beta"));
    }

    @Test
    public void testHasToolCallsAndExtractToolCalls() {
        String json = "{\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_123\",\"name\":\"get_player_skills\",\"input\":{\"skill\":\"Strength\"}}]}";
        JsonObject root = gson.fromJson(json, JsonObject.class);

        Assert.assertTrue(handler.hasToolCalls(root));
        List<AiService.ToolCall> calls = handler.extractToolCalls(root);
        Assert.assertEquals(1, calls.size());
        Assert.assertEquals("toolu_123", calls.get(0).id);
        Assert.assertEquals("get_player_skills", calls.get(0).name);
        Assert.assertEquals("Strength", calls.get(0).args.get("skill").getAsString());
    }

    @Test
    public void testUpdateRequestWithToolResults() {
        JsonObject requestBody = new JsonObject();
        requestBody.add("messages", new com.google.gson.JsonArray());

        String responseJson = "{\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_123\",\"name\":\"get_player_skills\",\"input\":{}}]}";
        JsonObject responseRoot = gson.fromJson(responseJson, JsonObject.class);

        AiService.ToolCall tc = new AiService.ToolCall("toolu_123", "get_player_skills", new JsonObject());
        AiService.ToolResult tr = new AiService.ToolResult(tc, "{\"status\":\"success\"}");

        handler.updateRequestWithToolResults(requestBody, responseRoot, Collections.singletonList(tr));

        com.google.gson.JsonArray messages = requestBody.getAsJsonArray("messages");
        Assert.assertEquals(2, messages.size());
        Assert.assertEquals("assistant", messages.get(0).getAsJsonObject().get("role").getAsString());
        Assert.assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
    }

    @Test
    public void testRefusalProducesReadableMessage() {
        JsonObject root = gson.fromJson("{\"stop_reason\":\"refusal\",\"content\":[]}", JsonObject.class);
        Assert.assertTrue(handler.extractResponseText(root).startsWith("Claude declined"));
    }

    @Test
    public void testExtractResponseText() {
        String json = "{\"content\":[{\"type\":\"text\",\"text\":\"Your Slayer level is 85.\"}]}";
        JsonObject root = gson.fromJson(json, JsonObject.class);

        String text = handler.extractResponseText(root);
        Assert.assertEquals("Your Slayer level is 85.", text);
    }
}
