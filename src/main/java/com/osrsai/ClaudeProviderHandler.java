package com.osrsai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

public class ClaudeProviderHandler implements ProviderHandler {
    private static final double LOW_TEMPERATURE = 0.2d;
    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";

    @Override
    public JsonObject buildRequestBody(String modelId, String context, String recentConversation, String question, boolean shareCharInfo) {
        JsonObject bodyObj = new JsonObject();
        bodyObj.addProperty("model", modelId);
        bodyObj.addProperty("system", AiService.buildSystemPrompt(context, recentConversation));
        bodyObj.addProperty("max_tokens", 1024);
        bodyObj.addProperty("temperature", LOW_TEMPERATURE);

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", question);

        messages.add(userMessage);
        bodyObj.add("messages", messages);

        bodyObj.add("tools", buildClaudeTools(shareCharInfo));

        return bodyObj;
    }

    private JsonArray buildClaudeTools(boolean shareCharInfo) {
        JsonArray tools = new JsonArray();
        if (shareCharInfo) {
            tools.add(createClaudeFunction("get_player_skills",
                    "Retrieve the player's current levels (both real and boosted) for all skills."));
            tools.add(createClaudeFunction("get_player_inventory",
                    "Retrieve the items currently in the player's inventory."));
            tools.add(createClaudeFunction("get_player_equipment",
                    "Retrieve the items currently equipped by the player."));
            tools.add(createClaudeFunction("get_player_slayer_task",
                    "Retrieve the player's current Slayer task, remaining quantity, current Slayer points, and current streak."));
            tools.add(createClaudeFunction("get_player_quests",
                    "Retrieve the player's quest points and list of in-progress quests."));
            tools.add(createClaudeFunction("get_player_achievement_diaries",
                    "Retrieve the player's Achievement Diary completion progress for all regions and tiers (Easy, Medium, Hard, Elite)."));
            tools.add(createClaudeFunction("get_player_bank",
                    "Retrieve the items currently in the player's bank. Only works if the bank interface is open."));
        }
        tools.add(createClaudeFunctionWithParams("search_osrs_wiki",
                "Search the Old School RuneScape Wiki for authoritative mechanics, stats, requirements, and information on items, monsters, spells, quests, or activities.",
                createClaudeStringParam("query",
                        "The exact entity or topic to search for (e.g. 'Sharp Eye', 'Abyssal whip', 'Barrows').")));
        return tools;
    }

    private JsonObject createClaudeFunction(String name, String description) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        tool.add("input_schema", schema);
        return tool;
    }

    private JsonObject createClaudeFunctionWithParams(String name, String description, JsonObject properties) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        for (String key : properties.keySet()) {
            required.add(key);
        }
        schema.add("required", required);

        tool.add("input_schema", schema);
        return tool;
    }

    private JsonObject createClaudeStringParam(String name, String description) {
        JsonObject prop = new JsonObject();
        JsonObject val = new JsonObject();
        val.addProperty("type", "string");
        val.addProperty("description", description);
        prop.add(name, val);
        return prop;
    }

    @Override
    public boolean hasToolCalls(JsonObject responseRoot) {
        if (!responseRoot.has("content")) {
            return false;
        }
        JsonArray content = responseRoot.getAsJsonArray("content");
        for (int i = 0; i < content.size(); i++) {
            JsonObject item = content.get(i).getAsJsonObject();
            if (item.has("type") && "tool_use".equals(item.get("type").getAsString())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<AiService.ToolCall> extractToolCalls(JsonObject responseRoot) {
        List<AiService.ToolCall> toolCalls = new ArrayList<>();
        JsonArray assistantContent = responseRoot.getAsJsonArray("content");
        for (int i = 0; i < assistantContent.size(); i++) {
            JsonObject item = assistantContent.get(i).getAsJsonObject();
            if (item.has("type") && "tool_use".equals(item.get("type").getAsString())) {
                String id = item.get("id").getAsString();
                String name = item.get("name").getAsString();
                JsonObject input = item.getAsJsonObject("input");
                toolCalls.add(new AiService.ToolCall(id, name, input));
            }
        }
        return toolCalls;
    }

    @Override
    public void updateRequestWithToolResults(JsonObject requestBody, JsonObject responseRoot, List<AiService.ToolResult> results) {
        JsonArray messages = requestBody.getAsJsonArray("messages");

        // Add assistant message with tool use block(s)
        JsonArray assistantContent = responseRoot.getAsJsonArray("content");
        JsonObject assistantMsg = new JsonObject();
        assistantMsg.addProperty("role", "assistant");
        assistantMsg.add("content", assistantContent);
        messages.add(assistantMsg);

        // Add user message containing all tool results
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        JsonArray contentArray = new JsonArray();
        for (AiService.ToolResult res : results) {
            JsonObject resultObj = new JsonObject();
            resultObj.addProperty("type", "tool_result");
            resultObj.addProperty("tool_use_id", res.call.id);
            resultObj.addProperty("content", res.resultJson);
            contentArray.add(resultObj);
        }
        userMsg.add("content", contentArray);
        messages.add(userMsg);
    }

    @Override
    public String extractResponseText(JsonObject responseRoot) {
        return responseRoot.getAsJsonArray("content")
                .get(0).getAsJsonObject()
                .get("text").getAsString();
    }

    @Override
    public Request buildHttpRequest(String modelId, String apiKey, String clientId, String jsonBody) {
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        return new Request.Builder()
                .url(CLAUDE_API_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(body)
                .build();
    }
}
