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
        for (AiService.ToolDefinition def : AiService.getToolRegistry()) {
            if (def.requiresCharacterInfo && !shareCharInfo) {
                continue;
            }

            JsonObject func = new JsonObject();
            func.addProperty("name", def.name);
            func.addProperty("description", def.description);

            JsonObject params = new JsonObject();
            params.addProperty("type", "object");

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
                    prop.addProperty("type", p.type);
                }
                prop.addProperty("description", p.description);
                properties.add(p.name, prop);

                if (p.required) {
                    required.add(p.name);
                }
            }

            params.add("properties", properties);
            params.add("required", required);
            func.add("parameters", params);

            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.add("function", func);
            tools.add(tool);
        }
        return tools;
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
        if (responseRoot != null && responseRoot.has("choices")) {
            JsonArray choices = responseRoot.getAsJsonArray("choices");
            if (choices.size() > 0) {
                JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (message != null) {
                    if (message.has("content") && !message.get("content").isJsonNull()) {
                        String content = message.get("content").getAsString();
                        if (content != null && !content.trim().isEmpty()) {
                            return content;
                        }
                    }
                    if (message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()) {
                        String reasoning = message.get("reasoning_content").getAsString();
                        if (reasoning != null && !reasoning.trim().isEmpty()) {
                            return reasoning;
                        }
                    }
                    if (message.has("reasoning") && !message.get("reasoning").isJsonNull()) {
                        String reasoning = message.get("reasoning").getAsString();
                        if (reasoning != null && !reasoning.trim().isEmpty()) {
                            return reasoning;
                        }
                    }
                }
            }
        }
        return "No content returned by the AI.";
    }

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
