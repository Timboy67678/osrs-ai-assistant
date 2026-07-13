package com.osrsai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

public class OpenAiProviderHandler implements ProviderHandler {
    private static final double LOW_TEMPERATURE = 0.2d;
    private final String apiUrl;
    private final Gson gson = new Gson();

    public OpenAiProviderHandler(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    @Override
    public JsonObject buildRequestBody(String modelId, String context, String recentConversation, String question,
            boolean shareCharInfo) {
        JsonObject bodyObj = new JsonObject();
        bodyObj.addProperty("model", modelId);
        bodyObj.addProperty("temperature", LOW_TEMPERATURE);

        JsonArray messages = new JsonArray();
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", AiService.buildSystemPrompt(context, recentConversation));

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", question);

        messages.add(systemMessage);
        messages.add(userMessage);
        bodyObj.add("messages", messages);

        bodyObj.add("tools", buildOpenAiTools(shareCharInfo));

        return bodyObj;
    }

    private JsonArray buildOpenAiTools(boolean shareCharInfo) {
        JsonArray tools = new JsonArray();
        if (shareCharInfo) {
            tools.add(createOpenAiFunction("get_player_skills",
                    "Retrieve the player's current levels (both real and boosted) for all skills."));
            tools.add(createOpenAiFunction("get_player_inventory",
                    "Retrieve the items, quantities, Grand Exchange prices, and High Alchemy values currently in the player's inventory."));
            tools.add(createOpenAiFunction("get_player_equipment",
                    "Retrieve the items, quantities, Grand Exchange prices, and High Alchemy values currently equipped by the player."));
            tools.add(createOpenAiFunction("get_player_slayer_task",
                    "Retrieve the player's current Slayer task, remaining quantity, current Slayer points, and current streak."));
            tools.add(createOpenAiFunction("get_player_quests",
                    "Retrieve the player's quest points, and lists of completed and in-progress quests."));
            tools.add(createOpenAiFunction("get_player_achievement_diaries",
                    "Retrieve the player's Achievement Diary completion progress for all regions and tiers (Easy, Medium, Hard, Elite)."));
            JsonObject bankParams = new JsonObject();
            bankParams.add("filter", createOpenAiParamObj("string", "Optional search query to filter bank items by name (case-insensitive). Use this if looking for specific items to avoid size limits."));
            bankParams.add("minValue", createOpenAiParamObj("integer", "Optional minimum value (Grand Exchange price or High Alch value) to filter items."));
            tools.add(createOpenAiFunctionWithOptionalParams("get_player_bank",
                    "Retrieve the items, quantities, Grand Exchange prices, and High Alchemy values currently in the player's bank. Only works if the bank interface is open.",
                    bankParams));
        }
        tools.add(createOpenAiFunctionWithParams("search_osrs_wiki",
                "Search the Old School RuneScape Wiki for authoritative mechanics, stats, requirements, locations, farming patches, training methods, and information on items, monsters, spells, quests, or activities.",
                createOpenAiStringParam("query",
                        "The exact entity, location, farming patch, training method, or topic to search for (e.g. 'Sharp Eye', 'Abyssal whip', 'Barrows', 'Farming patches').")));
        return tools;
    }

    private JsonObject createOpenAiFunction(String name, String description) {
        JsonObject func = new JsonObject();
        func.addProperty("name", name);
        func.addProperty("description", description);
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        params.add("properties", new JsonObject());
        func.add("parameters", params);

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", func);
        return tool;
    }

    private JsonObject createOpenAiFunctionWithParams(String name, String description, JsonObject properties) {
        JsonObject func = new JsonObject();
        func.addProperty("name", name);
        func.addProperty("description", description);

        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        params.add("properties", properties);

        JsonArray required = new JsonArray();
        for (String key : properties.keySet()) {
            required.add(key);
        }
        params.add("required", required);

        func.add("parameters", params);

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", func);
        return tool;
    }

    private JsonObject createOpenAiParamObj(String type, String description) {
        JsonObject val = new JsonObject();
        val.addProperty("type", type);
        val.addProperty("description", description);
        return val;
    }

    private JsonObject createOpenAiFunctionWithOptionalParams(String name, String description, JsonObject properties) {
        JsonObject func = new JsonObject();
        func.addProperty("name", name);
        func.addProperty("description", description);

        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        params.add("properties", properties);
        params.add("required", new JsonArray());

        func.add("parameters", params);

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", func);
        return tool;
    }

    private JsonObject createOpenAiStringParam(String name, String description) {
        JsonObject prop = new JsonObject();
        prop.add(name, createOpenAiParamObj("string", description));
        return prop;
    }

    @Override
    public boolean hasToolCalls(JsonObject responseRoot) {
        if (!responseRoot.has("choices")) {
            return false;
        }
        JsonArray choices = responseRoot.getAsJsonArray("choices");
        if (choices.size() == 0) {
            return false;
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        return message != null && message.has("tool_calls") && message.getAsJsonArray("tool_calls").size() > 0;
    }

    @Override
    public List<AiService.ToolCall> extractToolCalls(JsonObject responseRoot) {
        List<AiService.ToolCall> toolCalls = new ArrayList<>();
        JsonObject assistantMessage = responseRoot.getAsJsonArray("choices").get(0)
                .getAsJsonObject().getAsJsonObject("message");
        JsonArray tcArray = assistantMessage.getAsJsonArray("tool_calls");
        for (int i = 0; i < tcArray.size(); i++) {
            JsonObject tc = tcArray.get(i).getAsJsonObject();
            String id = tc.get("id").getAsString();
            JsonObject func = tc.getAsJsonObject("function");
            String name = func.get("name").getAsString();
            JsonObject args = gson.fromJson(func.get("arguments").getAsString(), JsonObject.class);
            toolCalls.add(new AiService.ToolCall(id, name, args));
        }
        return toolCalls;
    }

    @Override
    public void updateRequestWithToolResults(JsonObject requestBody, JsonObject responseRoot,
            List<AiService.ToolResult> results) {
        JsonArray messages = requestBody.getAsJsonArray("messages");

        // Add assistant message (which contains the tool calls)
        JsonObject assistantMessage = responseRoot.getAsJsonArray("choices").get(0)
                .getAsJsonObject().getAsJsonObject("message");
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.add("tool_calls", assistantMessage.getAsJsonArray("tool_calls"));
        messages.add(msg);

        // Add a message for each tool result
        for (AiService.ToolResult res : results) {
            JsonObject toolMsg = new JsonObject();
            toolMsg.addProperty("role", "tool");
            toolMsg.addProperty("tool_call_id", res.call.id);
            toolMsg.addProperty("content", res.resultJson);
            messages.add(toolMsg);
        }
    }

    @Override
    public String extractResponseText(JsonObject responseRoot) {
        return responseRoot.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();
    }

    @SuppressWarnings("deprecation")
    @Override
    public Request buildHttpRequest(String modelId, String apiKey, String clientId, String jsonBody) {
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), jsonBody);
        Request.Builder builder = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .post(body);
        if (apiUrl.contains("api.openai.com") && clientId != null && !clientId.trim().isEmpty()) {
            builder.header("OpenAI-Organization", clientId);
        }
        return builder.build();
    }
}
