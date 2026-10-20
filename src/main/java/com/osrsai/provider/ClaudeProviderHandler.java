package com.osrsai.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrsai.AiService;
import com.osrsai.tools.OsrsToolRegistry;
import com.osrsai.util.PromptUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Implementation of {@link ProviderHandler} for Anthropic's Claude API.
 * <p>
 * Formats requests using Anthropic's Messages API specification (system prompt,
 * messages array, tool definitions with input_schema)
 * and parses response structures for tool use blocks and text content.
 */
public class ClaudeProviderHandler implements ProviderHandler {
    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private static final int MAX_TOKENS = 16000;

    @Override
    public JsonObject buildRequestBody(String modelId, String context, String recentConversation, String question,
            boolean shareCharInfo) {
        JsonObject bodyObj = new JsonObject();
        bodyObj.addProperty("model", modelId);
        bodyObj.addProperty("system", PromptUtils.buildSystemPrompt(context, recentConversation));
        // Current Claude models reject custom sampling parameters such as temperature.
        bodyObj.addProperty("max_tokens", MAX_TOKENS);
        if (supportsServerFallback(modelId)) {
            // If a safety classifier declines the request, let the API retry it on a suitable model.
            bodyObj.addProperty("fallbacks", "default");
        }

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", question);

        messages.add(userMessage);
        bodyObj.add("messages", messages);

        bodyObj.add("tools", buildClaudeTools(shareCharInfo));
        // Automatic caching moves a breakpoint to the end of the conversation, so each step of a
        // tool loop re-reads everything sent in the previous step from cache.
        bodyObj.add("cache_control", ephemeralCacheControl());

        return bodyObj;
    }

    private static JsonObject ephemeralCacheControl() {
        JsonObject cacheControl = new JsonObject();
        cacheControl.addProperty("type", "ephemeral");
        return cacheControl;
    }

    /**
     * Server-side refusal fallbacks exist for the Opus, Sonnet 5.5 and Fable lines; Haiku has none.
     */
    static boolean supportsServerFallback(String modelId) {
        if (modelId == null) {
            return false;
        }
        String m = modelId.toLowerCase(Locale.ROOT);
        return m.startsWith("claude-opus-5") || m.startsWith("claude-sonnet-5-5") || m.startsWith("claude-fable-5");
    }

    /**
     * Builds Anthropic tool definitions from registered plugin tools.
     *
     * @param shareCharInfo {@code true} if character-specific tools should be
     *                      included
     * @return {@link JsonArray} of tool definitions formatted with JSON Schema
     *         input schemas
     */
    private JsonArray buildClaudeTools(boolean shareCharInfo) {
        JsonArray tools = new JsonArray();
        for (AiService.ToolDefinition def : OsrsToolRegistry.getToolRegistry()) {
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
        if (tools.size() > 0) {
            // Tool definitions are identical for every question, so cache them as a shared prefix.
            tools.get(tools.size() - 1).getAsJsonObject().add("cache_control", ephemeralCacheControl());
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
    public void disableToolCalling(JsonObject requestBody) {
        JsonObject toolChoice = new JsonObject();
        toolChoice.addProperty("type", "none");
        requestBody.add("tool_choice", toolChoice);
    }

    @Override
    public String extractResponseText(JsonObject responseRoot) {
        if (responseRoot != null && responseRoot.has("stop_reason") && !responseRoot.get("stop_reason").isJsonNull()
                && "refusal".equals(responseRoot.get("stop_reason").getAsString())) {
            return "Claude declined to answer this request. Try rephrasing your question.";
        }
        if (responseRoot != null && responseRoot.has("content")) {
            JsonArray content = responseRoot.getAsJsonArray("content");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.size(); i++) {
                JsonObject item = content.get(i).getAsJsonObject();
                if (item.has("type") && "text".equals(item.get("type").getAsString()) && item.has("text")) {
                    sb.append(item.get("text").getAsString());
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        return "No content returned by Claude.";
    }

    @Override
    public Request buildHttpRequest(String modelId, String apiKey, String clientId, String jsonBody) {
        // RuneLite ships OkHttp 3.x at runtime, which only has the (MediaType, String) overload.
        @SuppressWarnings("deprecation")
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), jsonBody);
        Request.Builder builder = new Request.Builder()
                .url(CLAUDE_API_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(body);
        if (supportsServerFallback(modelId)) {
            builder.header("anthropic-beta", FALLBACK_BETA);
        }
        return builder.build();
    }
}
