package com.osrsai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.osrsai.provider.GeminiProviderHandler;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class GeminiProviderHandlerTest {
    private final Gson gson = new Gson();
    private final GeminiProviderHandler handler = new GeminiProviderHandler();

    @Test
    public void testBuildRequestBodyIncludesGenerationConfigAndTools() {
        JsonObject body = handler.buildRequestBody("gemini-2.5-flash", "Game Context", "Recent Chat", "Where am I?", true);

        Assert.assertTrue(body.has("contents"));
        Assert.assertTrue(body.has("generationConfig"));
        Assert.assertTrue(body.has("tools"));

        JsonObject config = body.getAsJsonObject("generationConfig");
        Assert.assertEquals(8192, config.get("maxOutputTokens").getAsInt());
    }

    @Test
    public void testHasToolCallsAndExtractToolCalls() {
        String json = "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"get_player_skills\",\"args\":{\"skill\":\"Attack\"}}}]}}]}";
        JsonObject root = gson.fromJson(json, JsonObject.class);

        Assert.assertTrue(handler.hasToolCalls(root));
        List<AiService.ToolCall> calls = handler.extractToolCalls(root);
        Assert.assertEquals(1, calls.size());
        Assert.assertEquals("get_player_skills", calls.get(0).name);
        Assert.assertEquals("Attack", calls.get(0).args.get("skill").getAsString());
    }

    @Test
    public void testUpdateRequestWithToolResultsUsesUserRole() {
        JsonObject requestBody = new JsonObject();
        requestBody.add("contents", new com.google.gson.JsonArray());

        String responseJson = "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"get_player_skills\",\"args\":{}}}]}}]}";
        JsonObject responseRoot = gson.fromJson(responseJson, JsonObject.class);

        AiService.ToolCall tc = new AiService.ToolCall("call_1", "get_player_skills", new JsonObject());
        AiService.ToolResult tr = new AiService.ToolResult(tc, "{\"status\":\"success\"}");

        handler.updateRequestWithToolResults(requestBody, responseRoot, Collections.singletonList(tr));

        com.google.gson.JsonArray contents = requestBody.getAsJsonArray("contents");
        Assert.assertEquals(2, contents.size());
        Assert.assertEquals("model", contents.get(0).getAsJsonObject().get("role").getAsString());
        Assert.assertEquals("user", contents.get(1).getAsJsonObject().get("role").getAsString());
    }

    @Test
    public void testExtractResponseText() {
        String json = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"You are standing at the Grand Exchange.\"}]}}]}";
        JsonObject root = gson.fromJson(json, JsonObject.class);

        String text = handler.extractResponseText(root);
        Assert.assertEquals("You are standing at the Grand Exchange.", text);
    }
}
