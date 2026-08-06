package com.osrsai;

import com.google.gson.JsonObject;
import java.util.List;
import okhttp3.Request;

/**
 * Strategy interface defining API-specific payload construction, response parsing,
 * tool call extraction, and HTTP request building for AI model providers.
 */
public interface ProviderHandler {
    /**
     * Constructs the JSON request payload for sending a user question and conversation context to the AI API.
     *
     * @param modelId the API model identifier (e.g. "gpt-4o", "gemini-2.5-flash", "claude-3-5-sonnet-20240620")
     * @param context full player game context string
     * @param recentConversation recent chat turn history
     * @param question current user prompt
     * @param shareCharInfo {@code true} if character information and tools requiring character state are enabled
     * @return {@link JsonObject} containing the structured request payload
     */
    JsonObject buildRequestBody(String modelId, String context, String recentConversation, String question,
            boolean shareCharInfo);

    /**
     * Checks if the API response contains any function or tool call requests.
     *
     * @param responseRoot the parsed API response JSON root object
     * @return {@code true} if tool calls are present; {@code false} otherwise
     */
    boolean hasToolCalls(JsonObject responseRoot);

    /**
     * Extracts tool call requests from the API response object.
     *
     * @param responseRoot the parsed API response JSON root object
     * @return list of extracted {@link AiService.ToolCall} instances
     */
    List<AiService.ToolCall> extractToolCalls(JsonObject responseRoot);

    /**
     * Updates the request body with assistant tool calls and corresponding tool execution results for follow-up API turns.
     *
     * @param requestBody the ongoing API request body to update
     * @param responseRoot the preceding API response containing tool calls
     * @param results list of executed tool results
     */
    void updateRequestWithToolResults(JsonObject requestBody, JsonObject responseRoot,
            List<AiService.ToolResult> results);

    /**
     * Extracts final human-readable text response content from the API response object.
     *
     * @param responseRoot the parsed API response JSON root object
     * @return extracted response text string
     */
    String extractResponseText(JsonObject responseRoot);

    /**
     * Builds an HTTP {@link Request} object configured with headers, authentication, URL, and body for sending to the provider API.
     *
     * @param modelId model identifier string
     * @param apiKey user API key string
     * @param clientId optional client/organization ID string
     * @param jsonBody request body formatted as JSON string
     * @return fully constructed OkHttp {@link Request}
     */
    Request buildHttpRequest(String modelId, String apiKey, String clientId, String jsonBody);
}
