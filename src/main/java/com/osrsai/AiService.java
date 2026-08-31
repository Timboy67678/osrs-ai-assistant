package com.osrsai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.osrsai.context.GameContextBuilder;
import com.osrsai.navigation.ShortestPathHandler;
import com.osrsai.provider.AiProfile;
import com.osrsai.provider.AiProvider;
import com.osrsai.provider.ProviderHandler;
import com.osrsai.tools.*;
import com.osrsai.ui.OsrsAiPanel;
import com.osrsai.util.*;
import com.osrsai.util.Utilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.SoundEffectID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Central service class managing AI provider communication, recursive tool
 * execution loops,
 * and dispatching requests to modular game context builders and domain-specific
 * tool handlers.
 */
@Slf4j
public class AiService {
    private static final String DEFAULT_CUSTOM_ENDPOINT = "http://localhost:11434/v1/chat/completions";
    static final int MAX_DEPTH_COUNT = 15;

    // Constants maintained for backwards compatibility
    public static final int VARP_KINGDOM_FAVOUR = 73;
    public static final int VARP_KINGDOM_COFFER = 74;
    public static final int QUEST_STRUCT_PARAM_VARBIT = 299;
    public static final int QUEST_STRUCT_PARAM_VARP = 300;
    public static final int VARBIT_SPELLBOOK = 4070;

    @Inject
    private Client client;

    @Inject
    private OsrsAiConfig config;

    @Inject
    private Notifier notifier;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private Gson gson;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ScheduledExecutorService executorService;

    @Inject
    private ConfigManager configManager;

    @Inject
    private PluginManager pluginManager;

    @Inject
    private InfoBoxManager infoBoxManager;

    @Inject
    private EventBus eventBus;

    private final LocationResolver locationResolver = new LocationResolver();

    private final List<ItemContainerUtils.SimpleItem> cachedBankItems = new ArrayList<>();
    private long cachedBankTimestamp = 0;

    private OkHttpClient aiClient;
    private OkHttpClient wikiClient;

    // Modular tool handlers & services
    private ShortestPathHandler shortestPathHandler;
    private GameContextBuilder gameContextBuilder;
    private PlayerStateTools playerStateTools;
    private QuestAndDiaryTools questAndDiaryTools;
    private WorldEnvironmentTools worldEnvironmentTools;
    private EconomyTools economyTools;
    private ActivityTrackerTools activityTrackerTools;
    private FarmingAndTimerTools farmingAndTimerTools;

    public synchronized ShortestPathHandler getShortestPathHandler() {
        if (shortestPathHandler == null) {
            shortestPathHandler = new ShortestPathHandler(eventBus, config, locationResolver);
        }
        return shortestPathHandler;
    }

    public synchronized GameContextBuilder getGameContextBuilder() {
        if (gameContextBuilder == null) {
            gameContextBuilder = new GameContextBuilder(client, config, locationResolver);
        }
        return gameContextBuilder;
    }

    public synchronized PlayerStateTools getPlayerStateTools() {
        if (playerStateTools == null) {
            playerStateTools = new PlayerStateTools(client, itemManager, configManager, infoBoxManager, gson);
        }
        return playerStateTools;
    }

    public synchronized QuestAndDiaryTools getQuestAndDiaryTools() {
        if (questAndDiaryTools == null) {
            questAndDiaryTools = new QuestAndDiaryTools(client, configManager, gson);
        }
        return questAndDiaryTools;
    }

    public synchronized WorldEnvironmentTools getWorldEnvironmentTools() {
        if (worldEnvironmentTools == null) {
            worldEnvironmentTools = new WorldEnvironmentTools(client, itemManager, locationResolver, gson,
                    () -> Collections.unmodifiableList(cachedBankItems));
        }
        return worldEnvironmentTools;
    }

    public synchronized EconomyTools getEconomyTools() {
        if (economyTools == null) {
            economyTools = new EconomyTools(client, itemManager, gson,
                    () -> Collections.unmodifiableList(cachedBankItems),
                    () -> cachedBankTimestamp);
        }
        return economyTools;
    }

    public synchronized ActivityTrackerTools getActivityTrackerTools() {
        if (activityTrackerTools == null) {
            activityTrackerTools = new ActivityTrackerTools(client, itemManager, pluginManager, locationResolver,
                    getGameContextBuilder(), gson);
        }
        return activityTrackerTools;
    }

    public synchronized FarmingAndTimerTools getFarmingAndTimerTools() {
        if (farmingAndTimerTools == null) {
            farmingAndTimerTools = new FarmingAndTimerTools(client, configManager, infoBoxManager, gson);
        }
        return farmingAndTimerTools;
    }

    /**
     * Updates the offline bank cache whenever the bank item container updates.
     *
     * @param bankContainer the bank {@link ItemContainer}
     */
    public synchronized void updateCachedBank(ItemContainer bankContainer) {
        if (bankContainer != null) {
            cachedBankItems.clear();
            cachedBankItems.addAll(ItemContainerUtils.toSimpleItemList(bankContainer.getItems()));
            cachedBankTimestamp = System.currentTimeMillis();
            log.debug("Updated offline bank cache with {} items", cachedBankItems.size());
        }
    }

    private synchronized OkHttpClient getAiClient() {
        if (aiClient == null) {
            aiClient = okHttpClient.newBuilder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build();
        }
        return aiClient;
    }

    public synchronized OkHttpClient getWikiClient() {
        if (wikiClient == null) {
            wikiClient = okHttpClient.newBuilder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build();
        }
        return wikiClient;
    }

    /**
     * Retrieves the currently active AI provider profile configured by the user.
     *
     * @return active {@link AiProfile}, or {@code null} if none is selected or
     *         configured
     */
    public AiProfile getActiveProfile() {
        if (config == null) {
            return null;
        }
        String activeId = config.activeProfileId();
        if (activeId == null || activeId.isEmpty()) {
            return null;
        }
        String json = config.aiProfilesJson();
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            List<AiProfile> profiles = gson.fromJson(json, new TypeToken<ArrayList<AiProfile>>() {
            }.getType());
            if (profiles != null) {
                for (AiProfile p : profiles) {
                    if (p.getId().equals(activeId)) {
                        return p;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse AI profiles JSON", e);
        }
        return null;
    }

    /**
     * Asynchronously processes a user prompt: validates configuration, gathers game
     * state context on the
     * RuneLite client thread, and submits the payload to the active AI provider
     * endpoint.
     *
     * @param question the user's prompt string
     * @param panel    UI panel instance for rendering chat turns and progress
     *                 indicators
     */
    public void sendQuestion(String question, OsrsAiPanel panel) {
        final AiProfile activeProfile = getActiveProfile();
        if (activeProfile == null) {
            panel.addMessage("System",
                    "Please configure and activate an AI profile in the Profiles settings tab first.");
            panel.setThinking(false);
            return;
        }

        final String apiKey = activeProfile.getApiKey();
        final AiProvider provider = activeProfile.getProvider();
        if (provider != AiProvider.CUSTOM && (apiKey == null || apiKey.isEmpty())) {
            panel.addMessage("System", "Please set an API key for your active profile in the Profiles settings.");
            panel.setThinking(false);
            return;
        }

        panel.setThinking(true);
        try {
            String recentConversation = panel.getRecentConversationContext(question);

            clientThread.invokeLater(() -> {
                try {
                    final String gameContext = buildGameContext();
                    final String clientId = activeProfile.getClientId();
                    final String customModel = activeProfile.getCustomModel();
                    final String modelId = (customModel != null && !customModel.trim().isEmpty())
                            ? customModel.trim()
                            : provider.getModelId();
                    final String customEndpoint = activeProfile.getCustomEndpoint();
                    final String endpoint = (customEndpoint != null && !customEndpoint.trim().isEmpty())
                            ? customEndpoint.trim()
                            : DEFAULT_CUSTOM_ENDPOINT;

                    CompletableFuture.runAsync(() -> {
                        try {
                            ProviderHandler handler = provider.getHandler(endpoint);
                            JsonObject requestBody = handler.buildRequestBody(
                                    modelId,
                                    gameContext,
                                    recentConversation,
                                    question,
                                    config.shareCharacterInfo());

                            executeRequestLoop(provider, modelId, endpoint, apiKey, clientId, requestBody, 0, panel);
                        } catch (Throwable t) {
                            log.error("Error executing API request", t);
                            SwingUtilities.invokeLater(() -> {
                                panel.setThinking(false);
                                panel.addMessage("System", "Error preparing request: " + t.getMessage());
                            });
                        }
                    }, executorService).exceptionally(ex -> {
                        log.error("Error in async pipeline", ex);
                        SwingUtilities.invokeLater(() -> {
                            panel.setThinking(false);
                            panel.addMessage("System", "Error in pipeline: " + ex.getMessage());
                        });
                        return null;
                    });
                } catch (Throwable t) {
                    log.error("Error executing client thread prompt preparation", t);
                    SwingUtilities.invokeLater(() -> {
                        panel.setThinking(false);
                        panel.addMessage("System", "Error on client thread: " + t.getMessage());
                    });
                }
            });
        } catch (Throwable t) {
            log.error("Error scheduling client thread task", t);
            SwingUtilities.invokeLater(() -> {
                panel.setThinking(false);
                panel.addMessage("System", "Error starting request: " + t.getMessage());
            });
        }
    }

    private void executeRequestLoop(AiProvider provider, String modelId, String endpoint, String apiKey,
            String clientId, JsonObject requestBody,
            int depth, OsrsAiPanel panel) {
        int maxDepth = Math.max(1, Math.min(MAX_DEPTH_COUNT, config.maxSearchDepth()));
        ProviderHandler handler = provider.getHandler(endpoint);
        log.info("Sending request to AI provider {}. Model: {}. Depth: {}. Has tools: {}", provider, modelId, depth,
                requestBody.has("tools"));
        log.debug("Request body: {}", gson.toJson(requestBody));

        String jsonBody = gson.toJson(requestBody);
        Request request = handler.buildHttpRequest(modelId, apiKey, clientId, jsonBody);

        OkHttpClient clientHttp = getAiClient();

        clientHttp.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                log.error("Failed to call AI API", e);
                SwingUtilities.invokeLater(() -> {
                    panel.setThinking(false);
                    panel.addMessage("System", "Error calling AI: " + e.getMessage());
                });
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                try (Response resp = response) {
                    if (!resp.isSuccessful()) {
                        String errBody = "";
                        if (resp.body() != null) {
                            errBody = resp.body().string();
                        }
                        log.error("API returned error (code {}): {}", resp.code(), errBody);
                        final String errText = "AI returned an error code " + resp.code()
                                + (errBody.isEmpty() ? "" : ": " + errBody);
                        SwingUtilities.invokeLater(() -> {
                            panel.setThinking(false);
                            panel.addMessage("System", errText);
                        });
                        return;
                    }

                    if (resp.body() == null) {
                        SwingUtilities.invokeLater(() -> {
                            panel.setThinking(false);
                            panel.addMessage("System", "Empty response body from AI provider.");
                        });
                        return;
                    }
                    String responseBody = resp.body().string();
                    JsonObject root = gson.fromJson(responseBody, JsonObject.class);
                    log.info("Received response from AI provider {}: {}", provider,
                            root != null ? root.toString() : responseBody.replaceAll("\\s+", " ").trim());

                    boolean hasToolCalls = false;
                    List<ToolCall> toolCalls = new ArrayList<>();

                    if (depth < maxDepth && handler.hasToolCalls(root)) {
                        hasToolCalls = true;
                        toolCalls = handler.extractToolCalls(root);
                    }

                    if (hasToolCalls && !toolCalls.isEmpty()) {
                        log.info("AI requested tool calls: {}", gson.toJson(toolCalls));
                        executeToolsAsync(toolCalls).whenComplete((results, ex) -> {
                            if (ex != null) {
                                log.error("Error executing tools", ex);
                                SwingUtilities.invokeLater(() -> {
                                    panel.setThinking(false);
                                    panel.addMessage("System", "Error executing tools: " + ex.getMessage());
                                });
                                return;
                            }

                            handler.updateRequestWithToolResults(requestBody, root, results);

                            if (depth + 1 >= maxDepth) {
                                handler.disableToolCalling(requestBody);
                            }

                            executeRequestLoop(provider, modelId, endpoint, apiKey, clientId, requestBody, depth + 1,
                                    panel);
                        });
                    } else {
                        String aiResponseText = handler.extractResponseText(root);
                        String cleanResponse = aiResponseText.trim();

                        if (cleanResponse.isEmpty() || cleanResponse.startsWith("No content returned by")) {
                            if (handler.hasToolCalls(root) || depth >= maxDepth) {
                                cleanResponse = "I reached my search limit while trying to gather details. Please try rephrasing your question or checking that the required game screen is open.";
                            }
                        }

                        final String finalResponse = cleanResponse;
                        SwingUtilities.invokeLater(() -> {
                            panel.setThinking(false);
                            panel.addMessage("AI", finalResponse);
                            if (config.notifyOnResponse() && !finalResponse.isEmpty()) {
                                notifier.notify("AI Assistant: " + PromptUtils.truncateForNotification(finalResponse));
                                clientThread.invokeLater(
                                        () -> client.playSoundEffect(SoundEffectID.GE_ADD_OFFER_DINGALING));
                            }
                        });
                    }
                } catch (Throwable t) {
                    log.error("Failed to parse AI response", t);
                    SwingUtilities.invokeLater(() -> {
                        panel.setThinking(false);
                        panel.addMessage("System", "Failed to parse AI response: " + t.getMessage());
                    });
                }
            }
        });
    }

    private CompletableFuture<List<ToolResult>> executeToolsAsync(List<ToolCall> toolCalls) {
        CompletableFuture<List<ToolResult>> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            List<ToolResult> results = new ArrayList<>();
            for (ToolCall tc : toolCalls) {
                try {
                    log.info("Executing tool: {}", tc.name);

                    ToolDefinition def = OsrsToolRegistry.getTool(tc.name);

                    if (def == null) {
                        throw new IllegalArgumentException("Unknown tool: " + tc.name);
                    }

                    String output;
                    if (def.runOnClientThread) {
                        final AtomicReference<String> clientThreadResult = new AtomicReference<>();
                        final AtomicReference<Throwable> clientThreadError = new AtomicReference<>();
                        CompletableFuture<Void> clientFuture = new CompletableFuture<>();
                        clientThread.invokeLater(() -> {
                            try {
                                clientThreadResult.set(def.executor.execute(this, tc.args));
                                clientFuture.complete(null);
                            } catch (Throwable t) {
                                clientThreadError.set(t);
                                clientFuture.completeExceptionally(t);
                            }
                        });
                        clientFuture.join();
                        if (clientThreadError.get() != null) {
                            throw new Exception(clientThreadError.get());
                        }
                        output = clientThreadResult.get();
                    } else {
                        output = def.executor.execute(this, tc.args);
                    }
                    log.info("Tool {} returned result length: {}", tc.name, output.length());
                    results.add(new ToolResult(tc, output));
                } catch (Throwable t) {
                    log.error("Error executing tool: " + tc.name, t);
                    JsonObject err = new JsonObject();
                    err.addProperty("status", "error");
                    err.addProperty("message", t.getMessage() != null ? t.getMessage() : t.toString());
                    results.add(new ToolResult(tc, gson.toJson(err)));
                }
            }
            future.complete(results);
        }, executorService);
        return future;
    }

    // ==========================================
    // Tool Registry & Data Structures
    // ==========================================

    public static class ToolCall {
        public final String id;
        public final String name;
        public final JsonObject args;

        public ToolCall(String id, String name, JsonObject args) {
            this.id = id;
            this.name = name;
            this.args = args;
        }
    }

    public static class ToolResult {
        public final ToolCall call;
        public final String resultJson;

        public ToolResult(ToolCall call, String resultJson) {
            this.call = call;
            this.resultJson = resultJson;
        }
    }

    public static class ToolParameter {
        public final String name;
        public final String type;
        public final String description;
        public final boolean required;

        public ToolParameter(String name, String type, String description, boolean required) {
            this.name = name;
            this.type = type;
            this.description = description;
            this.required = required;
        }
    }

    @FunctionalInterface
    public interface ToolExecutor {
        String execute(AiService service, JsonObject args) throws Exception;
    }

    public static class ToolDefinition {
        public final String name;
        public final String description;
        public final List<ToolParameter> parameters = new ArrayList<>();
        public final boolean requiresCharacterInfo;
        public final boolean runOnClientThread;
        public final ToolExecutor executor;

        public ToolDefinition(String name, String description, boolean requiresCharacterInfo,
                boolean runOnClientThread, ToolExecutor executor) {
            this.name = name;
            this.description = description;
            this.requiresCharacterInfo = requiresCharacterInfo;
            this.runOnClientThread = runOnClientThread;
            this.executor = executor;
        }

        public ToolDefinition addParam(String name, String type, String description, boolean required) {
            this.parameters.add(new ToolParameter(name, type, description, required));
            return this;
        }
    }

    public static List<ToolDefinition> getToolRegistry() {
        return OsrsToolRegistry.getToolRegistry();
    }

    // ==========================================
    // Delegated Tool Executors & Helper Methods
    // ==========================================

    public static String buildSystemPrompt(String context, String recentConversation) {
        return PromptUtils.buildSystemPrompt(context, recentConversation);
    }

    public static String trimToPromptBudget(String text, int maxChars, String truncationLabel) {
        return PromptUtils.trimToPromptBudget(text, maxChars, truncationLabel);
    }

    public static String trimToPromptBudget(String text, int maxChars, String truncationLabel, boolean keepEnd) {
        return PromptUtils.trimToPromptBudget(text, maxChars, truncationLabel, keepEnd);
    }

    public static String extractSearchQuery(String question) {
        return WikiSearchUtil.extractSearchQuery(question);
    }

    public static String cleanWikitext(String wikitext) {
        return WikiSearchUtil.cleanWikitext(wikitext);
    }

    public String buildGameContext() {
        return getGameContextBuilder().buildGameContext();
    }

    public boolean setShortestPathTarget(WorldPoint targetPoint, WorldPoint startPoint,
            Map<String, Object> configOverrides) {
        return getShortestPathHandler().setShortestPathTarget(targetPoint, startPoint, configOverrides);
    }

    public boolean setShortestPathTarget(WorldPoint targetPoint) {
        return getShortestPathHandler().setShortestPathTarget(targetPoint);
    }

    public boolean clearShortestPathTarget() {
        return getShortestPathHandler().clearShortestPathTarget();
    }

    public String executeSetShortestPathTarget(JsonObject args) {
        return getShortestPathHandler().executeSetShortestPathTarget(args);
    }

    public String executeClearShortestPathTarget(JsonObject args) {
        return getShortestPathHandler().executeClearShortestPathTarget(args);
    }

    public String executeGetPlayerSkills(JsonObject args) {
        return getPlayerStateTools().executeGetPlayerSkills(args);
    }

    public String executeGetPlayerInventory(JsonObject args) {
        return getPlayerStateTools().executeGetPlayerInventory(args);
    }

    public String executeGetPlayerEquipment(JsonObject args) {
        return getPlayerStateTools().executeGetPlayerEquipment(args);
    }

    public String executeGetPlayerStatus(JsonObject args) {
        return getPlayerStateTools().executeGetPlayerStatus(args);
    }

    public String executeGetPlayerCurrenciesAndPoints(JsonObject args) {
        return getPlayerStateTools().executeGetPlayerCurrenciesAndPoints(args);
    }

    public String executeGetPlayerSlayerTask(JsonObject args) {
        return getPlayerStateTools().executeGetPlayerSlayerTask(args);
    }

    public String executeGetPlayerQuests(JsonObject args) {
        return getQuestAndDiaryTools().executeGetPlayerQuests(args);
    }

    public String executeGetPlayerAchievementDiaries(JsonObject args) {
        return getQuestAndDiaryTools().executeGetPlayerAchievementDiaries(args);
    }

    public String executeGetPlayerCombatAchievements(JsonObject args) {
        return getQuestAndDiaryTools().executeGetPlayerCombatAchievements(args);
    }

    public String executeGetPlayerBank(JsonObject args) {
        return getEconomyTools().executeGetPlayerBank(args);
    }

    public String executeGetItemStats(JsonObject args) {
        return getEconomyTools().executeGetItemStats(args);
    }

    public String executeGetPlayerGeOffers(JsonObject args) {
        return getEconomyTools().executeGetPlayerGeOffers(args);
    }

    public String executeGetMarketPrices(JsonObject args) {
        return getEconomyTools().executeGetMarketPrices(args);
    }

    public String executeGetPlayerClues(JsonObject args) {
        return getActivityTrackerTools().executeGetPlayerClues(args);
    }

    public String executeGetPlayerSailingStatus(JsonObject args) {
        return getActivityTrackerTools().executeGetPlayerSailingStatus(args);
    }

    public String executeGetSurroundingEnvironment(JsonObject args) {
        return getWorldEnvironmentTools().executeGetSurroundingEnvironment(args);
    }

    public String executeGetPlayerLocationDetails(JsonObject args) {
        return getWorldEnvironmentTools().executeGetPlayerLocationDetails(args);
    }

    public String executeGetPlayerTransportation(JsonObject args) {
        return getWorldEnvironmentTools().executeGetPlayerTransportation(args);
    }

    public String executeGetPlayerFarmingAndTimers(JsonObject args) {
        return getFarmingAndTimerTools().executeGetPlayerFarmingAndTimers(args);
    }

    public String executeSearchOsrsWiki(JsonObject args) {
        String query = (args != null && args.has("query")) ? args.get("query").getAsString() : "";
        if (query.isEmpty()) {
            return WikiSearchUtil.executeWikiSearch(getWikiClient(), gson, query);
        }

        String cleanedQuery = WikiSearchUtil.extractSearchQuery(query).trim();
        String activeTaskName = Utilities.getConfigValue(configManager, "slayer", "taskName");

        if (activeTaskName != null && !activeTaskName.isEmpty()
                && !cleanedQuery.toLowerCase().startsWith("slayer task/")) {
            if (isQueryRelatedToSlayerTask(cleanedQuery, activeTaskName)) {
                String slayerTaskQuery = "Slayer task/" + activeTaskName;
                log.info("Slayer task detected. Attempting wiki search for: {}", slayerTaskQuery);
                String slayerResult = WikiSearchUtil.executeWikiSearch(getWikiClient(), gson, slayerTaskQuery);

                if (!isNotFoundResult(slayerResult)) {
                    return slayerResult;
                }
                log.info("Slayer task page not found for '{}'. Falling back to original query: {}", slayerTaskQuery,
                        query);
            }
        }

        return WikiSearchUtil.executeWikiSearch(getWikiClient(), gson, query);
    }

    private boolean isQueryRelatedToSlayerTask(String query, String activeTask) {
        if (query == null || activeTask == null || query.isEmpty() || activeTask.isEmpty()) {
            return false;
        }
        String q = query.trim().toLowerCase();
        String t = activeTask.trim().toLowerCase();

        if (q.equals(t)) {
            return true;
        }

        String qNorm = normalizeSlayerTaskName(q);
        String tNorm = normalizeSlayerTaskName(t);
        if (qNorm.equals(tNorm)) {
            return true;
        }

        if (q.length() >= 3) {
            if (t.contains(q) || q.contains(t)) {
                return true;
            }
            if (tNorm.contains(qNorm) || qNorm.contains(tNorm)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeSlayerTaskName(String name) {
        if (name.endsWith("es")) {
            return name.substring(0, name.length() - 2);
        } else if (name.endsWith("s")) {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }

    private boolean isNotFoundResult(String jsonResult) {
        if (jsonResult == null || jsonResult.isEmpty()) {
            return true;
        }
        try {
            JsonObject obj = gson.fromJson(jsonResult, JsonObject.class);
            return obj != null && obj.has("status") && "not_found".equals(obj.get("status").getAsString());
        } catch (Exception e) {
            return false;
        }
    }

}
