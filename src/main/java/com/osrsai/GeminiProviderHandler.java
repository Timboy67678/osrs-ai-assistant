package com.osrsai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

public class GeminiProviderHandler implements ProviderHandler {
    private static final double LOW_TEMPERATURE = 0.2d;
    private static final String GEMINI_API_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=";
    private final Gson gson = new Gson();

    @Override
    public JsonObject buildRequestBody(String modelId, String context, String recentConversation, String question,
            boolean shareCharInfo) {
        String fullPrompt = AiService.buildSystemPrompt(context, recentConversation)
                + "\n\nCURRENT USER QUESTION:\n"
                + question;

        JsonObject messageObj = new JsonObject();
        JsonArray partsArray = new JsonArray();
        JsonObject textObj = new JsonObject();
        textObj.addProperty("text", fullPrompt);
        partsArray.add(textObj);
        messageObj.add("parts", partsArray);

        JsonArray contentsArray = new JsonArray();
        contentsArray.add(messageObj);

        JsonObject bodyObj = new JsonObject();
        bodyObj.add("contents", contentsArray);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", LOW_TEMPERATURE);
        bodyObj.add("generationConfig", generationConfig);

        bodyObj.add("tools", buildGeminiTools(shareCharInfo));

        return bodyObj;
    }

    private JsonArray buildGeminiTools(boolean shareCharInfo) {
        JsonArray declarations = new JsonArray();
        for (AiService.ToolDefinition def : AiService.getToolRegistry()) {
            if (def.requiresCharacterInfo && !shareCharInfo) {
                continue;
            }

            JsonObject decl = new JsonObject();
            decl.addProperty("name", def.name);
            decl.addProperty("description", def.description);

            JsonObject params = new JsonObject();
            params.addProperty("type", "OBJECT");

            JsonObject properties = new JsonObject();
            JsonArray required = new JsonArray();

            for (AiService.ToolParameter p : def.parameters) {
                JsonObject prop = new JsonObject();
                if (p.type.startsWith("array")) {
                    prop.addProperty("type", "ARRAY");
                    JsonObject items = new JsonObject();
                    items.addProperty("type", p.type.endsWith("integer") ? "INTEGER" : "STRING");
                    prop.add("items", items);
                } else {
                    prop.addProperty("type", p.type.toUpperCase(Locale.ROOT));
                }
                prop.addProperty("description", p.description);
                properties.add(p.name, prop);

                if (p.required) {
                    required.add(p.name);
                }
            }

            params.add("properties", properties);
            params.add("required", required);
            decl.add("parameters", params);
            declarations.add(decl);
        }

        JsonObject tool = new JsonObject();
        tool.add("functionDeclarations", declarations);

        JsonArray tools = new JsonArray();
        tools.add(tool);
        return tools;
    }

    @Override
    public boolean hasToolCalls(JsonObject responseRoot) {
        if (!responseRoot.has("candidates")) {
            return false;
        }
        JsonArray candidates = responseRoot.getAsJsonArray("candidates");
        if (candidates.size() == 0) {
            return false;
        }
        JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
        if (content == null || !content.has("parts")) {
            return false;
        }
        JsonArray parts = content.getAsJsonArray("parts");
        for (int i = 0; i < parts.size(); i++) {
            JsonObject part = parts.get(i).getAsJsonObject();
            if (part.has("functionCall")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<AiService.ToolCall> extractToolCalls(JsonObject responseRoot) {
        List<AiService.ToolCall> toolCalls = new ArrayList<>();
        JsonArray assistantParts = responseRoot.getAsJsonArray("candidates").get(0)
                .getAsJsonObject().getAsJsonObject("content").getAsJsonArray("parts");
        for (int i = 0; i < assistantParts.size(); i++) {
            JsonObject part = assistantParts.get(i).getAsJsonObject();
            if (part.has("functionCall")) {
                JsonObject fc = part.getAsJsonObject("functionCall");
                String name = fc.get("name").getAsString();
                JsonObject args = fc.getAsJsonObject("args");
                toolCalls.add(new AiService.ToolCall(null, name, args));
            }
        }
        return toolCalls;
    }

    @Override
    public void updateRequestWithToolResults(JsonObject requestBody, JsonObject responseRoot,
            List<AiService.ToolResult> results) {
        JsonArray contents = requestBody.getAsJsonArray("contents");

        // Add model message containing the function calls
        JsonArray assistantParts = responseRoot.getAsJsonArray("candidates").get(0)
                .getAsJsonObject().getAsJsonObject("content").getAsJsonArray("parts");
        JsonObject modelMsg = new JsonObject();
        modelMsg.addProperty("role", "model");
        modelMsg.add("parts", assistantParts);
        contents.add(modelMsg);

        // Add function message containing the function responses
        JsonObject functionMsg = new JsonObject();
        functionMsg.addProperty("role", "function");
        JsonArray partsArray = new JsonArray();
        for (AiService.ToolResult res : results) {
            JsonObject part = new JsonObject();
            JsonObject funcRes = new JsonObject();
            funcRes.addProperty("name", res.call.name);
            funcRes.add("response", gson.fromJson(res.resultJson, JsonObject.class));
            part.add("functionResponse", funcRes);
            partsArray.add(part);
        }
        functionMsg.add("parts", partsArray);
        contents.add(functionMsg);
    }

    @Override
    public String extractResponseText(JsonObject responseRoot) {
        if (responseRoot != null && responseRoot.has("candidates")) {
            JsonArray candidates = responseRoot.getAsJsonArray("candidates");
            if (candidates.size() > 0) {
                JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
                if (content != null && content.has("parts")) {
                    JsonArray parts = content.getAsJsonArray("parts");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < parts.size(); i++) {
                        JsonObject part = parts.get(i).getAsJsonObject();
                        if (part.has("text")) {
                            sb.append(part.get("text").getAsString());
                        }
                    }
                    if (sb.length() > 0) {
                        return sb.toString();
                    }
                }
            }
        }
        return "No content returned by Gemini.";
    }

    @Override
    public Request buildHttpRequest(String modelId, String apiKey, String clientId, String jsonBody) {
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), jsonBody);
        return new Request.Builder()
                .url(String.format(GEMINI_API_URL_TEMPLATE, modelId) + apiKey)
                .post(body)
                .build();
    }
}
