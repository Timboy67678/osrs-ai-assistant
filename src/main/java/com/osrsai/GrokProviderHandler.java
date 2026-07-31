package com.osrsai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.config.ConfigManager;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.regex.Pattern;

public class GrokProviderHandler extends OpenAiProviderHandler {
    private static ConfigManager configManager;
    private static final String GROK_CHAT_URL = "https://api.x.ai/v1/chat/completions";
    private static final String GROK_RESPONSES_URL = "https://api.x.ai/v1/responses";

    private static final Pattern CITATION_LINK_PATTERN = Pattern.compile("\\[\\[\\d+\\]\\]\\(https?://[^)]+\\)");
    private static final Pattern CITATION_BRACKET_PATTERN = Pattern.compile("\\[\\[\\d+\\]\\]");

    public GrokProviderHandler() {
        super(GROK_CHAT_URL);
    }

    public static void setConfigManager(ConfigManager manager) {
        configManager = manager;
    }

    private boolean isNativeSearchEnabled() {
        if (configManager != null) {
            String configVal = configManager.getConfiguration(OsrsAiPlugin.CONFIG_GROUP, "enableNativeWebSearch");
            if (configVal != null) {
                return Boolean.parseBoolean(configVal);
            }
        }
        return false;
    }

    private int getMaxToolCalls() {
        if (configManager != null) {
            try {
                String val = configManager.getConfiguration(OsrsAiPlugin.CONFIG_GROUP, "maxSearchDepth");
                if (val != null) {
                    return Math.max(1, Math.min(Integer.parseInt(val), 5));
                }
            } catch (Exception ignored) {
            }
        }
        return 2;
    }

    @Override
    public Request buildHttpRequest(String modelId, String apiKey, String clientId, String jsonBody) {
        if (isNativeSearchEnabled()) {
            RequestBody body = RequestBody.create(MediaType.parse("application/json"), jsonBody);
            return new Request.Builder()
                    .url(GROK_RESPONSES_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();
        }
        return super.buildHttpRequest(modelId, apiKey, clientId, jsonBody);
    }

    @Override
    public JsonObject buildRequestBody(String modelId, String context, String recentConversation, String question,
            boolean shareCharInfo) {
        if (isNativeSearchEnabled()) {
            JsonObject bodyObj = new JsonObject();
            bodyObj.addProperty("model", modelId);
            bodyObj.addProperty("max_tool_calls", getMaxToolCalls());
            bodyObj.addProperty("max_output_tokens", 2048);

            String baseSystemPrompt = AiService.buildSystemPrompt(context, recentConversation);
            String cleanSystemPrompt = baseSystemPrompt.replaceAll("(?s)AVAILABLE TOOLS:.*?GROUNDING RULES:",
                    "GROUNDING RULES:");
            cleanSystemPrompt += "\n\nNATIVE SEARCH RULES:\n"
                    + "- Answer directly using search snippets. Do NOT call browse_page to load full web articles.\n"
                    + "- Keep response concise: 1-2 short paragraphs max.\n"
                    + "- Speak directly in second person ('you/your'). NEVER write third-person evaluation notes.";
            JsonArray input = new JsonArray();
            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty("content", cleanSystemPrompt);
            input.add(systemMsg);

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", question);
            input.add(userMsg);

            bodyObj.add("input", input);
            bodyObj.add("tools", buildGrokResponsesTools(shareCharInfo));

            return bodyObj;
        }

        return super.buildRequestBody(modelId, context, recentConversation, question, shareCharInfo);
    }

    private JsonArray buildGrokResponsesTools(boolean shareCharInfo) {
        JsonArray tools = new JsonArray();
        for (AiService.ToolDefinition def : AiService.getToolRegistry()) {
            if (def.requiresCharacterInfo && !shareCharInfo) {
                continue;
            }

            if ("search_osrs_wiki".equals(def.name)) {
                continue;
            }

            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.addProperty("name", def.name);
            tool.addProperty("description", def.description);

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
            tool.add("parameters", params);

            tools.add(tool);
        }

        JsonObject webSearchTool = new JsonObject();
        webSearchTool.addProperty("type", "web_search");
        webSearchTool.addProperty("name", "web_search");

        JsonArray allowedDomains = new JsonArray();
        allowedDomains.add("oldschool.runescape.wiki");
        webSearchTool.add("allowed_domains", allowedDomains);

        tools.add(webSearchTool);
        return tools;
    }

    @Override
    public boolean hasToolCalls(JsonObject responseRoot) {
        if (responseRoot != null && responseRoot.has("output")) {
            JsonArray outputArray = responseRoot.getAsJsonArray("output");
            for (int i = 0; i < outputArray.size(); i++) {
                JsonObject item = outputArray.get(i).getAsJsonObject();
                if (item.has("type")) {
                    String type = item.get("type").getAsString();
                    if ("function_call".equals(type) || "tool_call".equals(type)) {
                        return true;
                    }
                }
            }
            return false;
        }
        return super.hasToolCalls(responseRoot);
    }

    @Override
    public List<AiService.ToolCall> extractToolCalls(JsonObject responseRoot) {
        if (responseRoot != null && responseRoot.has("output")) {
            List<AiService.ToolCall> toolCalls = new ArrayList<>();
            JsonArray outputArray = responseRoot.getAsJsonArray("output");
            for (int i = 0; i < outputArray.size(); i++) {
                JsonObject item = outputArray.get(i).getAsJsonObject();
                if (item.has("type")) {
                    String type = item.get("type").getAsString();
                    if ("function_call".equals(type) || "tool_call".equals(type)) {
                        String id = item.has("id") ? item.get("id").getAsString()
                                : (item.has("call_id") ? item.get("call_id").getAsString() : "call_" + i);
                        String name = item.has("name") ? item.get("name").getAsString() : "";
                        JsonObject args = new JsonObject();
                        if (item.has("arguments")) {
                            JsonElement argsElem = item.get("arguments");
                            if (argsElem.isJsonObject()) {
                                args = argsElem.getAsJsonObject();
                            } else if (argsElem.isJsonPrimitive()) {
                                String argsStr = argsElem.getAsString();
                                try {
                                    args = new com.google.gson.JsonParser().parse(argsStr).getAsJsonObject();
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        if (!name.isEmpty()) {
                            toolCalls.add(new AiService.ToolCall(id, name, args));
                        }
                    }
                }
            }
            if (!toolCalls.isEmpty()) {
                return toolCalls;
            }
        }
        return super.extractToolCalls(responseRoot);
    }

    @Override
    public void updateRequestWithToolResults(JsonObject requestBody, JsonObject responseRoot,
            List<AiService.ToolResult> results) {
        if (requestBody.has("input") && responseRoot != null && responseRoot.has("output")) {
            JsonArray input = requestBody.getAsJsonArray("input");
            JsonArray output = responseRoot.getAsJsonArray("output");

            for (int i = 0; i < output.size(); i++) {
                input.add(output.get(i));
            }

            for (AiService.ToolResult res : results) {
                JsonObject toolMsg = new JsonObject();
                toolMsg.addProperty("role", "tool");
                toolMsg.addProperty("type", "function_response");
                toolMsg.addProperty("call_id", res.call.id);
                toolMsg.addProperty("content", res.resultJson);
                input.add(toolMsg);
            }
            return;
        }
        super.updateRequestWithToolResults(requestBody, responseRoot, results);
    }

    @Override
    public String extractResponseText(JsonObject responseRoot) {
        if (responseRoot != null && responseRoot.has("output")) {
            JsonArray outputArray = responseRoot.getAsJsonArray("output");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < outputArray.size(); i++) {
                JsonObject item = outputArray.get(i).getAsJsonObject();
                if (item.has("type") && "message".equals(item.get("type").getAsString()) && item.has("content")) {
                    JsonElement contentElem = item.get("content");
                    if (contentElem.isJsonArray()) {
                        JsonArray contentArray = contentElem.getAsJsonArray();
                        for (int j = 0; j < contentArray.size(); j++) {
                            JsonObject contentObj = contentArray.get(j).getAsJsonObject();
                            if (contentObj.has("text") && !contentObj.get("text").isJsonNull()) {
                                sb.append(contentObj.get("text").getAsString());
                            }
                        }
                    } else if (contentElem.isJsonPrimitive()) {
                        sb.append(contentElem.getAsString());
                    }
                }
            }
            if (sb.length() == 0) {
                for (int i = 0; i < outputArray.size(); i++) {
                    JsonObject item = outputArray.get(i).getAsJsonObject();
                    if (item.has("type") && "reasoning".equals(item.get("type").getAsString()) && item.has("summary")) {
                        JsonElement summaryElem = item.get("summary");
                        if (summaryElem.isJsonArray()) {
                            JsonArray summaryArray = summaryElem.getAsJsonArray();
                            for (int j = 0; j < summaryArray.size(); j++) {
                                JsonObject sumObj = summaryArray.get(j).getAsJsonObject();
                                if (sumObj.has("text") && !sumObj.get("text").isJsonNull()) {
                                    sb.append(sumObj.get("text").getAsString());
                                }
                            }
                        }
                    }
                }
            }
            if (sb.length() > 0) {
                String text = sb.toString();
                text = CITATION_LINK_PATTERN.matcher(text).replaceAll("");
                text = CITATION_BRACKET_PATTERN.matcher(text).replaceAll("");
                return text;
            }
        }
        return super.extractResponseText(responseRoot);
    }
}
