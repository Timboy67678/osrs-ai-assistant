package com.osrsai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

public class ClaudeProviderHandler implements ProviderHandler {
    private static final double LOW_TEMPERATURE = 0.2d;
    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";

    @Override
    public JsonObject buildRequestBody(String modelId, String context, String recentConversation, String question,
            boolean shareCharInfo) {
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
        for (AiService.ToolDefinition def : AiService.getToolRegistry()) {
            if (def.requiresCharacterInfo && !shareCharInfo) {
                continue;
            }

            JsonObject tool = new JsonObject();
            tool.addProperty("name", def.name);
            tool.addProperty("description", def.description);

            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");

            JsonObject properties = new JsonObject();
            JsonArray required = new JsonArray();

            for (AiService.ToolParameter p : def.parameters) {
                JsonObject prop = new JsonObject();
                if (p.type.startsWith("array")) {
                    prop.addProperty("type", "array");
                    JsonObject items = new JsonObject();
                    items.addProperty("type", p.type.endsWith("integer") ? "integer" : "string");
                    prop.add("items", items);
                } else {
                    prop.addProperty("type", p.type.toLowerCase(Locale.ROOT));
                }
                prop.addProperty("description", p.description);
                properties.add(p.name, prop);

                if (p.required) {
                    required.add(p.name);
                }
            }

            schema.add("properties", properties);
            schema.add("required", required);
            tool.add("input_schema", schema);
            tools.add(tool);
        }
        return tools;
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
    public void updateRequestWithToolResults(JsonObject requestBody, JsonObject responseRoot,
            List<AiService.ToolResult> results) {
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
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), jsonBody);
        return new Request.Builder()
                .url(CLAUDE_API_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(body)
                .build();
    }
}
