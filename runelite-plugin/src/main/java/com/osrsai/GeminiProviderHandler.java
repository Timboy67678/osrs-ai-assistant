package com.osrsai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

public class GeminiProviderHandler implements ProviderHandler {
    private static final double LOW_TEMPERATURE = 0.2d;
    private static final String GEMINI_API_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=";
    private final Gson gson = new Gson();

    @Override
    public JsonObject buildRequestBody(String modelId, String context, String recentConversation, String question, boolean shareCharInfo) {
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
        if (shareCharInfo) {
            declarations.add(createGeminiFunction("get_player_skills",
                    "Retrieve the player's current levels (both real and boosted) for all skills."));
            declarations.add(createGeminiFunction("get_player_inventory",
                    "Retrieve the items currently in the player's inventory."));
            declarations.add(createGeminiFunction("get_player_equipment",
                    "Retrieve the items currently equipped by the player."));
            declarations.add(createGeminiFunction("get_player_slayer_task",
                    "Retrieve the player's current Slayer task, remaining quantity, and current Slayer points."));
            declarations.add(createGeminiFunction("get_player_quests",
                    "Retrieve the player's quest points and list of in-progress quests."));
            declarations.add(createGeminiFunction("get_player_bank",
                    "Retrieve the items currently in the player's bank. Only works if the bank interface is open."));
        }
        declarations.add(createGeminiFunctionWithParams("search_osrs_wiki",
                "Search the Old School RuneScape Wiki for authoritative mechanics, stats, requirements, and information on items, monsters, spells, quests, or activities.",
                createGeminiStringParam("query",
                        "The exact entity or topic to search for (e.g. 'Sharp Eye', 'Abyssal whip', 'Barrows').")));

        JsonObject tool = new JsonObject();
        tool.add("functionDeclarations", declarations);

        JsonArray tools = new JsonArray();
        tools.add(tool);
        return tools;
    }

    private JsonObject createGeminiFunction(String name, String description) {
        JsonObject decl = new JsonObject();
        decl.addProperty("name", name);
        decl.addProperty("description", description);
        JsonObject params = new JsonObject();
        params.addProperty("type", "OBJECT");
        params.add("properties", new JsonObject());
        decl.add("parameters", params);
        return decl;
    }

    private JsonObject createGeminiFunctionWithParams(String name, String description, JsonObject properties) {
        JsonObject decl = new JsonObject();
        decl.addProperty("name", name);
        decl.addProperty("description", description);

        JsonObject params = new JsonObject();
        params.addProperty("type", "OBJECT");
        params.add("properties", properties);

        JsonArray required = new JsonArray();
        for (String key : properties.keySet()) {
            required.add(key);
        }
        params.add("required", required);

        decl.add("parameters", params);
        return decl;
    }

    private JsonObject createGeminiStringParam(String name, String description) {
        JsonObject prop = new JsonObject();
        JsonObject val = new JsonObject();
        val.addProperty("type", "STRING");
        val.addProperty("description", description);
        prop.add(name, val);
        return prop;
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
    public void updateRequestWithToolResults(JsonObject requestBody, JsonObject responseRoot, List<AiService.ToolResult> results) {
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
        return responseRoot.getAsJsonArray("candidates")
                .get(0).getAsJsonObject()
                .getAsJsonObject("content")
                .getAsJsonArray("parts")
                .get(0).getAsJsonObject()
                .get("text").getAsString();
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
