package com.osrsai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Implementation of {@link ProviderHandler} for OpenAI-compatible chat completion APIs (OpenAI, Grok, Custom endpoints).
 * <p>
 * Supports standard OpenAI JSON schema for messages, system prompt, max token caps (including reasoning models),
 * function calling tools, and HTTP Authorization header authentication.
 */
public class OpenAiProviderHandler implements ProviderHandler {
    private static final double LOW_TEMPERATURE = 0.2d;
    private final String apiUrl;
    private final Gson gson = new Gson();

    /**
     * Constructs an {@code OpenAiProviderHandler} for a specific target API endpoint URL.
     *
     * @param apiUrl target endpoint URL (e.g. OpenAI chat completions or Grok endpoint)
     */
    public OpenAiProviderHandler(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    @Override
    public JsonObject buildRequestBody(String modelId, String context, String recentConversation, String question,
            boolean shareCharInfo) {
        JsonObject bodyObj = new JsonObject();
        bodyObj.addProperty("model", modelId);
        bodyObj.addProperty("temperature", LOW_TEMPERATURE);

        String lowerModel = modelId != null ? modelId.toLowerCase() : "";
        boolean isReasoningModel = lowerModel.startsWith("o1")
                || lowerModel.startsWith("o3")
        		|| lowerModel.contains("reasoning")
        		|| lowerModel.contains("r1");
        if (isReasoningModel) {
            bodyObj.addProperty("max_completion_tokens", 16384);
        } else {
            bodyObj.addProperty("max_tokens", 8192);
            bodyObj.addProperty("max_completion_tokens", 8192);
        }

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

    /**
     * Builds OpenAI tool definitions from registered plugin tools.
     *
     * @param shareCharInfo {@code true} if character-specific tools should be included
     * @return {@link JsonArray} of function tool declarations
     */
    protected JsonArray buildOpenAiTools(boolean shareCharInfo) {
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

    /**
     * Helper method to extract the assistant message object from the response choices array or outputs array.
     *
     * @param responseRoot API response JSON object
     * @return assistant message {@link JsonObject}, or {@code null} if not found
     */
    private JsonObject getAssistantMessage(JsonObject responseRoot) {
        if (responseRoot == null) {
            return null;
        }
        if (responseRoot.has("choices")) {
            JsonArray choices = responseRoot.getAsJsonArray("choices");
            if (choices.size() > 0 && choices.get(0).isJsonObject()) {
                JsonObject choice = choices.get(0).getAsJsonObject();
                if (choice.has("message")) {
                    return choice.getAsJsonObject("message");
                }
            }
        }
        if (responseRoot.has("outputs")) {
            JsonArray outputs = responseRoot.getAsJsonArray("outputs");
            if (outputs.size() > 0 && outputs.get(0).isJsonObject()) {
                JsonObject output = outputs.get(0).getAsJsonObject();
                if (output.has("message")) {
                    JsonObject msg = output.getAsJsonObject("message");
                    if (msg.has("toolCalls") && !msg.has("tool_calls")) {
                        msg.add("tool_calls", msg.get("toolCalls"));
                    }
                    return msg;
                }
            }
        }
        return null;
    }

    @Override
    public boolean hasToolCalls(JsonObject responseRoot) {
        JsonObject message = getAssistantMessage(responseRoot);
        return message != null && message.has("tool_calls") && message.getAsJsonArray("tool_calls").size() > 0;
    }

    @Override
    public List<AiService.ToolCall> extractToolCalls(JsonObject responseRoot) {
        List<AiService.ToolCall> toolCalls = new ArrayList<>();
        JsonObject assistantMessage = getAssistantMessage(responseRoot);
        if (assistantMessage == null || !assistantMessage.has("tool_calls")) {
            return toolCalls;
        }
        JsonArray tcArray = assistantMessage.getAsJsonArray("tool_calls");
        for (int i = 0; i < tcArray.size(); i++) {
            JsonObject tc = tcArray.get(i).getAsJsonObject();
            String id = tc.has("id") && !tc.get("id").isJsonNull() ? tc.get("id").getAsString() : "call_" + i;
            JsonObject func = tc.getAsJsonObject("function");
            String name = func.get("name").getAsString();
            String argsStr = func.get("arguments").isJsonObject()
                    ? gson.toJson(func.getAsJsonObject("arguments"))
                    : func.get("arguments").getAsString();
            JsonObject args = gson.fromJson(argsStr, JsonObject.class);
            toolCalls.add(new AiService.ToolCall(id, name, args));
        }
        return toolCalls;
    }

    @Override
    public void updateRequestWithToolResults(JsonObject requestBody, JsonObject responseRoot,
            List<AiService.ToolResult> results) {
        JsonArray messages = requestBody.getAsJsonArray("messages");

        // Add assistant message (which contains the tool calls)
        JsonObject assistantMessage = getAssistantMessage(responseRoot);
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        if (assistantMessage != null) {
            if (assistantMessage.has("content") && !assistantMessage.get("content").isJsonNull()) {
                msg.add("content", assistantMessage.get("content"));
            } else {
                msg.addProperty("content", "");
            }
            if (assistantMessage.has("tool_calls")) {
                msg.add("tool_calls", assistantMessage.getAsJsonArray("tool_calls"));
            }
        }
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
        if (responseRoot != null) {
            JsonObject message = getAssistantMessage(responseRoot);
            if (message != null) {
                if (message.has("content") && !message.get("content").isJsonNull()) {
                    String content = message.get("content").getAsString();
                    if (content != null && !content.trim().isEmpty()) {
                        return content;
                    }
                }
                if (message.has("text") && !message.get("text").isJsonNull()) {
                    String text = message.get("text").getAsString();
                    if (text != null && !text.trim().isEmpty()) {
                        return text;
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
