package com.osrsai;

import com.google.gson.JsonObject;
import java.util.List;
import okhttp3.Request;

public interface ProviderHandler {
    JsonObject buildRequestBody(String modelId, String context, String recentConversation, String question,
            boolean shareCharInfo);

    boolean hasToolCalls(JsonObject responseRoot);

    List<AiService.ToolCall> extractToolCalls(JsonObject responseRoot);

    void updateRequestWithToolResults(JsonObject requestBody, JsonObject responseRoot,
            List<AiService.ToolResult> results);

    String extractResponseText(JsonObject responseRoot);

    Request buildHttpRequest(String modelId, String apiKey, String clientId, String jsonBody);
}
