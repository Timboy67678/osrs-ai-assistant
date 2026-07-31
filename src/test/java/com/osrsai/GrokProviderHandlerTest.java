package com.osrsai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.runelite.client.config.ConfigManager;
import okhttp3.Request;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class GrokProviderHandlerTest {

    @Before
    public void setUp() {
        GrokProviderHandler.setConfigManager(null);
    }

    @Test
    public void testDefaultUsesStandardChatCompletionsWithLocalTools() {
        GrokProviderHandler handler = new GrokProviderHandler();
        JsonObject body = handler.buildRequestBody("grok-4.3", "context", "recent", "question", true);

        Assert.assertNotNull(body);
        Assert.assertTrue(body.has("messages"));
        Assert.assertTrue(body.has("tools"));

        Request req = handler.buildHttpRequest("grok-4.3", "testkey", null, "{}");
        Assert.assertEquals("https://api.x.ai/v1/chat/completions", req.url().toString());
    }

    @Test
    public void testResponsesApiWhenNativeSearchEnabled() {
        ConfigManager mockConfigManager = Mockito.mock(ConfigManager.class);
        Mockito.when(mockConfigManager.getConfiguration(OsrsAiPlugin.CONFIG_GROUP, "enableNativeWebSearch"))
                .thenReturn("true");

        GrokProviderHandler.setConfigManager(mockConfigManager);

        GrokProviderHandler handler = new GrokProviderHandler();
        JsonObject body = handler.buildRequestBody("grok-4.3", "context", "recent", "question", true);

        Assert.assertNotNull(body);
        Assert.assertTrue(body.has("input"));
        Assert.assertTrue(body.has("tools"));
        JsonArray tools = body.getAsJsonArray("tools");
        boolean foundWebSearch = false;
        for (int i = 0; i < tools.size(); i++) {
            JsonObject tool = tools.get(i).getAsJsonObject();
            if (tool.has("type") && "web_search".equals(tool.get("type").getAsString())) {
                foundWebSearch = true;
                break;
            }
        }
        Assert.assertTrue("web_search should be present in tools", foundWebSearch);

        Request req = handler.buildHttpRequest("grok-4.3", "testkey", null, "{}");
        Assert.assertEquals("https://api.x.ai/v1/responses", req.url().toString());

        JsonObject responseRoot = new JsonObject();
        JsonArray outputArray = new JsonArray();
        JsonObject msgObj = new JsonObject();
        msgObj.addProperty("type", "message");
        JsonArray contentArray = new JsonArray();
        JsonObject textObj = new JsonObject();
        textObj.addProperty("text", "Magic secateurs give a 10% yield boost.");
        contentArray.add(textObj);
        msgObj.add("content", contentArray);
        outputArray.add(msgObj);
        responseRoot.add("output", outputArray);

        String text = handler.extractResponseText(responseRoot);
        Assert.assertEquals("Magic secateurs give a 10% yield boost.", text);
    }
}
