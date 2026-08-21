package com.osrsai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.InstanceTemplates;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Prayer;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.Varbits;
import net.runelite.api.widgets.Widget;
import net.runelite.api.SoundEffectID;
import net.runelite.api.ParamID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.Notifier;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.http.api.item.ItemStats;
import net.runelite.http.api.item.ItemEquipmentStats;

import javax.swing.SwingUtilities;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.jetbrains.annotations.NotNull;

/**
 * Central service class managing AI provider communication, recursive tool
 * execution loops,
 * in-game context building, and tool handler execution.
 */
@Slf4j
public class AiService {
    // Constants
    private static final int MAX_TOOLTIP_LENGTH = 150;

    // URI Constants
    private static final String DEFAULT_CUSTOM_ENDPOINT = "http://localhost:11434/v1/chat/completions"; // Ollama

    // Global Constants
    static final int MAX_DEPTH_COUNT = 15;
    private static final Pattern PATTERN_HTML_TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern PATTERN_WHITESPACE = Pattern.compile("\\s+");

    // Game Varbits & Varplayer (Varp) IDs
    static final int VARBIT_SPELLBOOK = 4070;
    static final int VARBIT_TITHE_FARM_POINTS = 4893;
    static final int VARBIT_WILDERNESS_LEVEL = 5963;
    static final int VARBIT_SAILING_STATE = 9999;
    static final int VARP_SPECIAL_ATTACK_PERCENT = 300;
    static final int VARP_NMZ_REWARD_POINTS = 1056;
    static final int VARP_PEST_CONTROL_POINTS = 261;
    static final int POISON_VENOM_THRESHOLD = 1000000;

    // Combat Achievement Enums & Struct Parameters
    private static final int CA_BOSS_ENUM = 3971;
    private static final int CA_TIER_EASY_ENUM = 3981;
    private static final int CA_TIER_MEDIUM_ENUM = 3982;
    private static final int CA_TIER_HARD_ENUM = 3983;
    private static final int CA_TIER_ELITE_ENUM = 3984;
    private static final int CA_TIER_MASTER_ENUM = 3985;
    private static final int CA_TIER_GRANDMASTER_ENUM = 3986;

    private static final int CA_STRUCT_PARAM_TASK_ID = 1306;
    private static final int CA_STRUCT_PARAM_NAME = 1308;
    private static final int CA_STRUCT_PARAM_DESCRIPTION = 1309;
    private static final int CA_STRUCT_PARAM_TYPE = 1311;
    private static final int CA_STRUCT_PARAM_BOSS_ID = 1312;

    // Vessel & Sailing Telemetry Defaults
    private static final int DEFAULT_VESSEL_HULL_HEALTH_PERCENT = 100;
    private static final int DEFAULT_VESSEL_SPEED_KNOTS = 0;
    private static final String DEFAULT_VESSEL_SHIP_TYPE = "Skiff / Small Boat";
    private static final String DEFAULT_VESSEL_SAIL_TRIM = "Full";
    private static final String DEFAULT_VESSEL_WIND_VECTOR = "Tailwind (NW)";
    private static final String DEFAULT_VESSEL_ANCHOR_STATUS = "Raised";

    // Quest Struct Param IDs
    static final int QUEST_STRUCT_PARAM_VARBIT = 299;
    static final int QUEST_STRUCT_PARAM_VARP = 300;

    // Currency & Activity Item IDs
    private static final int ITEM_ID_MARK_OF_GRACE = 11849;
    private static final int ITEM_ID_GOLDEN_NUGGET = 12012;
    private static final int ITEM_ID_ABYSSAL_PEARL = 26884;
    private static final int ITEM_ID_TOKKUL = 6529;
    private static final int ITEM_ID_STARDUST = 25527;
    private static final int ITEM_ID_ARCHERY_TICKET = 1464;
    private static final int ITEM_ID_MERMAIDS_TEAR = 27433;

    // Coordinate & Map Navigation Constants
    private static final int MAX_SURFACE_WORLD_Y_COORDINATE = 5000;
    private static final int OSRS_UNDERGROUND_Y_OFFSET_STEP = 6400;

    private static final Map<Integer, String> CA_TIER_MAP = Map.of(
            CA_TIER_EASY_ENUM, "Easy",
            CA_TIER_MEDIUM_ENUM, "Medium",
            CA_TIER_HARD_ENUM, "Hard",
            CA_TIER_ELITE_ENUM, "Elite",
            CA_TIER_MASTER_ENUM, "Master",
            CA_TIER_GRANDMASTER_ENUM, "Grandmaster");

    private static final Map<Integer, String> CA_TYPE_MAP = Map.of(
            1, "Stamina",
            2, "Perfection",
            3, "Kill Count",
            4, "Mechanical",
            5, "Restriction",
            6, "Speed");

    private static final int[] CA_VARP_IDS = new int[] {
            VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1,
            VarPlayerID.CA_TASK_COMPLETED_2, VarPlayerID.CA_TASK_COMPLETED_3,
            VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5,
            VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7,
            VarPlayerID.CA_TASK_COMPLETED_8, VarPlayerID.CA_TASK_COMPLETED_9,
            VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
            VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13,
            VarPlayerID.CA_TASK_COMPLETED_14, VarPlayerID.CA_TASK_COMPLETED_15,
            VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
            VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19
    };

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

    private OkHttpClient aiClient;
    private OkHttpClient wikiClient;

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

    private synchronized OkHttpClient getWikiClient() {
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
     * state context on the RuneLite
     * client thread, and submits the payload to the active AI provider endpoint.
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

        OkHttpClient aiClient = getAiClient();

        aiClient.newCall(request).enqueue(new Callback() {
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
                    log.info("Received response from AI provider {}: {}", provider, responseBody);
                    JsonObject root = gson.fromJson(responseBody, JsonObject.class);

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
                                requestBody.remove("tools");
                            }

                            executeRequestLoop(provider, modelId, endpoint, apiKey, clientId, requestBody, depth + 1,
                                    panel);
                        });
                    } else {
                        String aiResponseText = handler.extractResponseText(root);
                        String cleanResponse = aiResponseText.trim();

                        if (cleanResponse.isEmpty() && handler.hasToolCalls(root)) {
                            cleanResponse = "I reached my search limit while trying to gather details. Please try rephrasing your question or checking that the required game screen is open.";
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

    /**
     * Data structure representing an AI-requested tool invocation call.
     */
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

    /**
     * Data structure representing the execution result output of a tool call.
     */
    public static class ToolResult {
        public final ToolCall call;
        public final String resultJson;

        public ToolResult(ToolCall call, String resultJson) {
            this.call = call;
            this.resultJson = resultJson;
        }
    }

    /**
     * Data structure defining a tool parameter attribute (name, type, description,
     * required status).
     */
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

    /**
     * Functional interface for executing a registered tool.
     */
    @FunctionalInterface
    public interface ToolExecutor {
        /**
         * Executes the tool logic with the provided AI arguments.
         *
         * @param service active {@link AiService} instance
         * @param args    tool input arguments formatted as JSON object
         * @return JSON string or text output of the tool execution
         * @throws Exception if an error occurs during tool execution
         */
        String execute(AiService service, JsonObject args) throws Exception;
    }

    /**
     * Definition of a tool available to the AI assistant, including its parameters
     * and execution metadata.
     */
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

        /**
         * Adds a parameter definition to this tool.
         *
         * @param name        parameter name
         * @param type        data type (string, integer, boolean, array_integer,
         *                    array_string)
         * @param description parameter description
         * @param required    {@code true} if parameter is mandatory
         * @return this {@link ToolDefinition} instance for chaining
         */
        public ToolDefinition addParam(String name, String type, String description, boolean required) {
            this.parameters.add(new ToolParameter(name, type, description, required));
            return this;
        }
    }

    /**
     * Gets the global registry of tool definitions.
     *
     * @return unmodifiable list of {@link ToolDefinition} instances
     */
    public static List<ToolDefinition> getToolRegistry() {
        return OsrsToolRegistry.getToolRegistry();
    }

    private String normalizeSkillName(String input) {
        if (input == null) {
            return null;
        }
        String clean = input.trim().toLowerCase();
        switch (clean) {
            case "wc":
            case "woodcut":
                return "woodcutting";
            case "rc":
            case "runecrafting":
                return "runecraft";
            case "hp":
            case "hitpoint":
                return "hitpoints";
            case "range":
                return "ranged";
            case "fm":
                return "firemaking";
            case "fletch":
                return "fletching";
            case "con":
                return "construction";
            case "cook":
                return "cooking";
            case "craft":
                return "crafting";
            case "slay":
                return "slayer";
            case "str":
                return "strength";
            case "def":
            case "defense":
                return "defence";
            case "att":
                return "attack";
            case "agil":
                return "agility";
            case "thiev":
            case "thief":
                return "thieving";
            case "herb":
                return "herblore";
            case "farm":
                return "farming";
            case "hunt":
                return "hunter";
            default:
                return clean;
        }
    }

    private void addMilestoneXp(JsonObject skillData, int currentXp, Integer targetLevel) {
        int[] milestones = { 60, 65, 70, 75, 80, 85, 90, 92, 95, 99 };
        for (int level : milestones) {
            int targetXp = Experience.getXpForLevel(level);
            if (currentXp < targetXp) {
                skillData.addProperty("xpTo" + level, targetXp - currentXp);
            }
        }
        if (targetLevel != null && targetLevel >= 1 && targetLevel <= Experience.MAX_VIRT_LEVEL) {
            int targetXp = Experience.getXpForLevel(targetLevel);
            skillData.addProperty("requestedTargetLevel", targetLevel);
            skillData.addProperty("targetLevelXp", targetXp);
            skillData.addProperty("xpToTargetLevel", Math.max(0, targetXp - currentXp));
        }
    }

    /**
     * Normalizes a WorldPoint coordinate for ShortestPath pathfinding.
     * If the Y coordinate is an underground offset (y >=
     * MAX_SURFACE_WORLD_Y_COORDINATE), normalizes it down to the corresponding
     * surface level coordinate so that the map overlay draws correctly on the
     * surface world map.
     */
    private WorldPoint normalizeShortestPathPoint(WorldPoint point) {
        if (point == null) {
            return null;
        }
        int y = point.getY();
        if (y >= MAX_SURFACE_WORLD_Y_COORDINATE) {
            int surfaceY = y;
            while (surfaceY >= MAX_SURFACE_WORLD_Y_COORDINATE) {
                surfaceY -= OSRS_UNDERGROUND_Y_OFFSET_STEP;
            }
            if (surfaceY > 0) {
                log.info("Normalized underground coordinate WorldPoint({}, {}, {}) to surface WorldPoint({}, {}, {})",
                        point.getX(), y, point.getPlane(), point.getX(), surfaceY, point.getPlane());
                return new WorldPoint(point.getX(), surfaceY, point.getPlane());
            }
        }
        return point;
    }

    boolean setShortestPathTarget(WorldPoint targetPoint, WorldPoint startPoint, Map<String, Object> configOverrides) {
        try {
            if (eventBus != null && targetPoint != null) {
                targetPoint = normalizeShortestPathPoint(targetPoint);
                startPoint = normalizeShortestPathPoint(startPoint);

                Map<String, Object> data = new HashMap<>();
                if (startPoint != null) {
                    data.put("start", startPoint);
                }
                data.put("target", targetPoint);
                if (configOverrides != null && !configOverrides.isEmpty()) {
                    data.put("config", configOverrides);
                }
                eventBus.post(new PluginMessage("shortestpath", "path", data));
                log.info("Posted ShortestPath PluginMessage path event for target {} (start: {}, config: {})",
                        targetPoint, startPoint, configOverrides);
                return true;
            }
        } catch (Exception e) {
            log.error("Failed to set Shortest Path target via PluginMessage event bus", e);
        }
        return false;
    }

    boolean setShortestPathTarget(WorldPoint targetPoint) {
        return setShortestPathTarget(targetPoint, null, null);
    }

    boolean clearShortestPathTarget() {
        try {
            if (eventBus != null) {
                eventBus.post(new PluginMessage("shortestpath", "clear"));
                log.info("Posted ShortestPath PluginMessage clear event");
                return true;
            }
        } catch (Exception e) {
            log.error("Failed to post Shortest Path clear event", e);
        }
        return false;
    }

    /**
     * Executes the 'set_shortest_path_target' tool to set a destination overlay
     * marker in the Shortest Path plugin via PluginMessage.
     *
     * @param args JSON arguments containing "x", "y", optional "plane", optional
     *             "locationName", optional "startX", "startY", "startPlane", and
     *             optional "avoidWilderness"
     * @return JSON response string with status and outcome message
     */
    String executeSetShortestPathTarget(JsonObject args) {
        JsonObject result = new JsonObject();
        if (args == null || !args.has("x") || !args.has("y")) {
            result.addProperty("status", "error");
            result.addProperty("message", "Missing required parameters: x and y.");
            return result.toString();
        }

        try {
            if (!config.useShortestPath()) {
                result.addProperty("status", "error");
                result.addProperty("message",
                        "Shortest Path target setting is disabled in the OSRS AI Assistant plugin config.");
                return result.toString();
            }

            int x = args.get("x").getAsInt();
            int y = args.get("y").getAsInt();
            int plane = (args.has("plane") && !args.get("plane").isJsonNull()) ? args.get("plane").getAsInt() : 0;
            String locationName = (args.has("locationName") && !args.get("locationName").isJsonNull())
                    ? args.get("locationName").getAsString()
                    : "Destination";

            WorldPoint targetPoint = new WorldPoint(x, y, plane);

            WorldPoint startPoint = null;
            if (args.has("startX") && args.has("startY") && !args.get("startX").isJsonNull()
                    && !args.get("startY").isJsonNull()) {
                int startX = args.get("startX").getAsInt();
                int startY = args.get("startY").getAsInt();
                int startPlane = (args.has("startPlane") && !args.get("startPlane").isJsonNull())
                        ? args.get("startPlane").getAsInt()
                        : 0;
                startPoint = new WorldPoint(startX, startY, startPlane);
            }

            Map<String, Object> configOverrides = new HashMap<>();
            if (args.has("avoidWilderness") && !args.get("avoidWilderness").isJsonNull()) {
                configOverrides.put("avoidWilderness", args.get("avoidWilderness").getAsBoolean());
            }

            boolean success = setShortestPathTarget(targetPoint, startPoint,
                    configOverrides.isEmpty() ? null : configOverrides);

            if (success) {
                result.addProperty("status", "success");
                result.addProperty("message",
                        "Successfully set Shortest Path target to " + locationName + " at " + targetPoint.toString()
                                + (startPoint != null ? " (starting from " + startPoint.toString() + ")" : "")
                                + (!configOverrides.isEmpty() ? " with config overrides: " + configOverrides : ""));
            } else {
                result.addProperty("status", "error");
                result.addProperty("message", "Shortest Path plugin is not installed or enabled in RuneLite.");
            }
        } catch (Exception e) {
            log.error("Failed to execute set_shortest_path_target tool", e);
            result.addProperty("status", "error");
            result.addProperty("message", "Exception: " + e.getMessage());
        }

        return result.toString();
    }

    /**
     * Executes the 'clear_shortest_path_target' tool to clear any active route
     * overlay in the Shortest Path plugin.
     *
     * @param args JSON arguments (unused)
     * @return JSON response string with status and outcome message
     */
    String executeClearShortestPathTarget(JsonObject args) {
        JsonObject result = new JsonObject();
        try {
            if (!config.useShortestPath()) {
                result.addProperty("status", "error");
                result.addProperty("message", "Shortest Path integration is disabled in plugin config.");
                return result.toString();
            }

            boolean success = clearShortestPathTarget();
            if (success) {
                result.addProperty("status", "success");
                result.addProperty("message", "Successfully cleared Shortest Path target and route overlay.");
            } else {
                result.addProperty("status", "error");
                result.addProperty("message", "Failed to send clear message to Shortest Path plugin.");
            }
        } catch (Exception e) {
            log.error("Failed to execute clear_shortest_path_target tool", e);
            result.addProperty("status", "error");
            result.addProperty("message", "Exception: " + e.getMessage());
        }
        return result.toString();
    }

    /**
     * Executes the 'get_player_skills' tool to retrieve player skill levels, XP,
     * and target level milestones.
     *
     * @param args JSON arguments with optional "skill" filter name and optional
     *             "targetLevel" integer
     * @return JSON string of skill stats and remaining XP thresholds
     */
    String executeGetPlayerSkills(JsonObject args) {
        JsonObject result = new JsonObject();
        String filterSkill = (args != null && args.has("skill") && !args.get("skill").isJsonNull())
                ? normalizeSkillName(args.get("skill").getAsString())
                : null;
        Integer targetLevel = (args != null && args.has("targetLevel") && !args.get("targetLevel").isJsonNull())
                ? args.get("targetLevel").getAsInt()
                : null;

        for (Skill skill : Skill.values()) {
            if (!"OVERALL".equals(skill.name())) {
                String skillName = skill.getName();
                if (filterSkill != null && !skillName.toLowerCase().equals(filterSkill)) {
                    continue;
                }

                JsonObject skillData = new JsonObject();
                skillData.addProperty("boosted", client.getBoostedSkillLevel(skill));
                skillData.addProperty("real", client.getRealSkillLevel(skill));
                int xp = client.getSkillExperience(skill);
                skillData.addProperty("xp", xp);

                int nextLevel = Experience.getLevelForXp(xp) + 1;
                if (nextLevel <= Experience.MAX_VIRT_LEVEL) {
                    int nextXp = Experience.getXpForLevel(nextLevel);
                    skillData.addProperty("nextLevelXp", nextXp);
                    skillData.addProperty("xpToNextLevel", Math.max(0, nextXp - xp));
                } else {
                    skillData.addProperty("nextLevelXp", -1);
                    skillData.addProperty("xpToNextLevel", 0);
                }

                addMilestoneXp(skillData, xp, targetLevel);

                result.add(skillName, skillData);
            }
        }
        return gson.toJson(result);
    }

    /**
     * Executes the 'get_player_inventory' tool to inspect current inventory
     * contents, quantities, GE prices, and HA prices.
     *
     * @param args tool arguments
     * @return JSON string mapping item names to inventory quantities and prices
     */
    String executeGetPlayerInventory(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject invItems = new JsonObject();
        ItemContainer invContainer = client.getItemContainer(InventoryID.INVENTORY);
        if (invContainer != null) {
            invItems = ItemContainerUtils.aggregateItemsWithPrices(client, itemManager, invContainer, null, 0);
        }
        result.add("items", invItems);
        return gson.toJson(result);
    }

    /**
     * Executes the 'get_player_equipment' tool to inspect currently equipped worn
     * equipment items and stats across all equipment slots.
     *
     * @param args tool arguments
     * @return JSON string of equipped items organized by equipment slot name
     */
    String executeGetPlayerEquipment(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject eqSlots = new JsonObject();
        ItemContainer eqContainer = client.getItemContainer(InventoryID.EQUIPMENT);
        if (eqContainer != null) {
            Item[] items = eqContainer.getItems();
            for (int i = 0; i < items.length; i++) {
                Item item = items[i];
                if (item == null || item.getId() <= 0) {
                    continue;
                }
                net.runelite.api.ItemComposition comp = null;
                if (itemManager != null) {
                    try {
                        comp = itemManager.getItemComposition(item.getId());
                    } catch (Exception ignored) {
                    }
                }
                String itemName = (comp != null && comp.getName() != null && !comp.getName().trim().isEmpty())
                        ? comp.getName()
                        : "Item " + item.getId();

                String slotName = ItemContainerUtils.getSlotName(i);
                JsonObject itemDetail = new JsonObject();
                itemDetail.addProperty("id", item.getId());
                itemDetail.addProperty("name", itemName);
                itemDetail.addProperty("qty", item.getQuantity());

                int gePrice = 0;
                if (itemManager != null) {
                    try {
                        gePrice = itemManager.getItemPrice(item.getId());
                    } catch (Exception ignored) {
                    }
                }
                if (gePrice <= 0 && "Coins".equals(itemName)) {
                    gePrice = 1;
                }
                itemDetail.addProperty("gePrice", gePrice);
                itemDetail.addProperty("haPrice", comp != null ? comp.getHaPrice() : 0);

                ItemStats stats = (itemManager != null) ? itemManager.getItemStats(item.getId(), false) : null;
                if (stats != null && stats.getEquipment() != null) {
                    ItemEquipmentStats eq = stats.getEquipment();
                    boolean hasCombatStats = eq.getAstab() != 0 || eq.getAslash() != 0 || eq.getAcrush() != 0
                            || eq.getAmagic() != 0 || eq.getArange() != 0 || eq.getDstab() != 0
                            || eq.getDslash() != 0 || eq.getDcrush() != 0 || eq.getDmagic() != 0
                            || eq.getDrange() != 0 || eq.getStr() != 0 || eq.getRstr() != 0
                            || eq.getMdmg() != 0 || eq.getPrayer() != 0;
                    if (hasCombatStats) {
                        JsonObject statsObj = new JsonObject();
                        if (eq.getAstab() != 0)
                            statsObj.addProperty("astab", eq.getAstab());
                        if (eq.getAslash() != 0)
                            statsObj.addProperty("aslash", eq.getAslash());
                        if (eq.getAcrush() != 0)
                            statsObj.addProperty("ascrush", eq.getAcrush());
                        if (eq.getAmagic() != 0)
                            statsObj.addProperty("asmagic", eq.getAmagic());
                        if (eq.getArange() != 0)
                            statsObj.addProperty("asrange", eq.getArange());
                        if (eq.getDstab() != 0)
                            statsObj.addProperty("dstab", eq.getDstab());
                        if (eq.getDslash() != 0)
                            statsObj.addProperty("dslash", eq.getDslash());
                        if (eq.getDcrush() != 0)
                            statsObj.addProperty("dcrush", eq.getDcrush());
                        if (eq.getDmagic() != 0)
                            statsObj.addProperty("dmagic", eq.getDmagic());
                        if (eq.getDrange() != 0)
                            statsObj.addProperty("drange", eq.getDrange());
                        if (eq.getStr() != 0)
                            statsObj.addProperty("str", eq.getStr());
                        if (eq.getRstr() != 0)
                            statsObj.addProperty("rstr", eq.getRstr());
                        if (eq.getMdmg() != 0)
                            statsObj.addProperty("mdmg", eq.getMdmg());
                        if (eq.getPrayer() != 0)
                            statsObj.addProperty("prayer", eq.getPrayer());
                        if (eq.getAspeed() != 0)
                            statsObj.addProperty("aspeed", eq.getAspeed());
                        itemDetail.add("stats", statsObj);
                    }
                }
                eqSlots.add(slotName, itemDetail);
            }
        }
        result.add("slots", eqSlots);
        return gson.toJson(result);
    }

    /**
     * Executes the 'get_player_slayer_task' tool to retrieve the active Slayer task
     * monster, remaining count, assigned Slayer master, location, points, and task
     * streak.
     *
     * @param args tool arguments
     * @return JSON string containing Slayer task details
     */
    String executeGetPlayerSlayerTask(JsonObject args) {
        JsonObject result = new JsonObject();
        String taskName = getConfigValue("slayer", "taskName");
        String amount = getConfigValue("slayer", "amount");
        String taskLocation = getConfigValue("slayer", "taskLocation", "location");
        String slayerMaster = getConfigValue("slayer", "slayerMaster", "masterName", "master", "taskMaster");
        String pointsStr = getConfigValue("slayer", "points");
        String streakStr = getConfigValue("slayer", "streak");

        int pointsVal = 0;
        if (pointsStr != null && !pointsStr.isEmpty()) {
            try {
                pointsVal = Integer.parseInt(pointsStr);
            } catch (NumberFormatException ignored) {
            }
        }
        if (pointsVal != 0) {
            result.addProperty("points", pointsVal);
        }
        if (streakStr != null && !streakStr.isEmpty()) {
            try {
                result.addProperty("streak", Integer.parseInt(streakStr));
            } catch (NumberFormatException ignored) {
            }
        }
        if (taskName != null && !taskName.isEmpty() && amount != null) {
            result.addProperty("task", taskName);
            result.addProperty("quantity", Integer.parseInt(amount));
            if (taskLocation != null && !taskLocation.isEmpty() && !"None".equalsIgnoreCase(taskLocation.trim())) {
                result.addProperty("location", taskLocation.trim());
            }
            if (slayerMaster != null && !slayerMaster.isEmpty() && !"None".equalsIgnoreCase(slayerMaster.trim())) {
                result.addProperty("slayerMaster", slayerMaster.trim());
            }
        } else {
            result.addProperty("task", "None");
            result.addProperty("quantity", 0);
        }
        return gson.toJson(result);
    }

    private String getConfigValue(String group, String... keys) {
        if (configManager == null) {
            return null;
        }
        for (String key : keys) {
            String val = configManager.getRSProfileConfiguration(group, key);
            if (val == null || val.isEmpty()) {
                val = configManager.getConfiguration(group, key);
            }
            if (val != null && !val.isEmpty()) {
                return val;
            }
        }
        return null;
    }

    /**
     * Executes the 'get_player_quests' tool to retrieve quest completion counts,
     * total Quest Points, and lists of in-progress/not-started/completed quests.
     *
     * @param args JSON arguments with optional "status" filter and optional "quest"
     *             name search term
     * @return JSON string of quest completion status
     */
    String executeGetPlayerQuests(JsonObject args) {
        JsonObject result = new JsonObject();
        int qp = client.getVarpValue(VarPlayerID.QP);
        result.addProperty("questPoints", qp);

        String statusFilter = (args != null && args.has("status"))
                ? args.get("status").getAsString().trim().toUpperCase()
                : "DEFAULT";

        String questFilter = (args != null && args.has("quest"))
                ? args.get("quest").getAsString().trim().toLowerCase()
                : null;

        int completedCount = 0;
        int inProgressCount = 0;
        int notStartedCount = 0;

        JsonArray completed = new JsonArray();
        JsonArray inProgress = new JsonArray();
        JsonArray notStarted = new JsonArray();

        boolean includeCompleted = "COMPLETED".equals(statusFilter) || "ALL".equals(statusFilter)
                || (questFilter != null);
        boolean includeInProgress = "DEFAULT".equals(statusFilter) || "IN_PROGRESS".equals(statusFilter)
                || "ALL".equals(statusFilter) || (questFilter != null);
        boolean includeNotStarted = "NOT_STARTED".equals(statusFilter)
                || "ALL".equals(statusFilter) || (questFilter != null);

        for (Quest quest : Quest.values()) {
            QuestState state = null;
            try {
                state = quest.getState(client);
            } catch (Exception e) {
                // Ignore missing mock state for un-stubbed quests in tests or detached state
            }
            if (state == null) {
                continue;
            }
            if (state == QuestState.FINISHED) {
                completedCount++;
            } else if (state == QuestState.IN_PROGRESS) {
                inProgressCount++;
            } else if (state == QuestState.NOT_STARTED) {
                notStartedCount++;
            }

            if (questFilter != null && !quest.getName().toLowerCase().contains(questFilter)) {
                continue;
            }

            if (state == QuestState.FINISHED) {
                if (includeCompleted) {
                    completed.add(quest.getName());
                }
            } else if (state == QuestState.IN_PROGRESS) {
                if (includeInProgress) {
                    JsonObject questObj = new JsonObject();
                    questObj.addProperty("name", quest.getName());
                    int stage = getQuestStageValue(quest);
                    if (stage != -1) {
                        questObj.addProperty("stage", stage);
                    }
                    inProgress.add(questObj);
                }
            } else if (state == QuestState.NOT_STARTED) {
                if (includeNotStarted) {
                    notStarted.add(quest.getName());
                }
            }
        }

        result.addProperty("completedCount", completedCount);
        result.addProperty("inProgressCount", inProgressCount);
        result.addProperty("notStartedCount", notStartedCount);

        if (includeInProgress) {
            result.add("inProgressQuests", inProgress);
        }
        if (includeNotStarted) {
            result.add("notStartedQuests", notStarted);
        }
        if (includeCompleted) {
            result.add("completedQuests", completed);
        }

        return gson.toJson(result);
    }

    private int getQuestStageValue(Quest quest) {
        if (client == null || quest == null) {
            return -1;
        }
        try {
            net.runelite.api.StructComposition struct = client.getStructComposition(quest.getId());
            if (struct != null) {
                int varbitId = struct.getIntValue(QUEST_STRUCT_PARAM_VARBIT);
                if (varbitId > 0) {
                    return client.getVarbitValue(varbitId);
                }
                int varpId = struct.getIntValue(QUEST_STRUCT_PARAM_VARP);
                if (varpId > 0) {
                    return client.getVarpValue(varpId);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to read quest struct for quest {}", quest.getName(), e);
        }
        return -1;
    }

    /**
     * Executes the 'get_player_achievement_diaries' tool to retrieve Achievement
     * Diary completion progress across all regions and tiers.
     *
     * @param args tool arguments
     * @return JSON string of diary tier completion statuses
     */
    String executeGetPlayerAchievementDiaries(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject diaries = new JsonObject();
        diaries.add("Ardougne", createDiaryProgress(Varbits.DIARY_ARDOUGNE_EASY, Varbits.DIARY_ARDOUGNE_MEDIUM,
                Varbits.DIARY_ARDOUGNE_HARD, Varbits.DIARY_ARDOUGNE_ELITE));
        diaries.add("Desert", createDiaryProgress(Varbits.DIARY_DESERT_EASY, Varbits.DIARY_DESERT_MEDIUM,
                Varbits.DIARY_DESERT_HARD, Varbits.DIARY_DESERT_ELITE));
        diaries.add("Falador", createDiaryProgress(Varbits.DIARY_FALADOR_EASY, Varbits.DIARY_FALADOR_MEDIUM,
                Varbits.DIARY_FALADOR_HARD, Varbits.DIARY_FALADOR_ELITE));
        diaries.add("Fremennik", createDiaryProgress(Varbits.DIARY_FREMENNIK_EASY,
                Varbits.DIARY_FREMENNIK_MEDIUM, Varbits.DIARY_FREMENNIK_HARD, Varbits.DIARY_FREMENNIK_ELITE));
        diaries.add("Kandarin", createDiaryProgress(Varbits.DIARY_KANDARIN_EASY, Varbits.DIARY_KANDARIN_MEDIUM,
                Varbits.DIARY_KANDARIN_HARD, Varbits.DIARY_KANDARIN_ELITE));
        diaries.add("Karamja", createDiaryProgress(Varbits.DIARY_KARAMJA_EASY, Varbits.DIARY_KARAMJA_MEDIUM,
                Varbits.DIARY_KARAMJA_HARD, Varbits.DIARY_KARAMJA_ELITE));
        diaries.add("Kourend", createDiaryProgress(Varbits.DIARY_KOUREND_EASY, Varbits.DIARY_KOUREND_MEDIUM,
                Varbits.DIARY_KOUREND_HARD, Varbits.DIARY_KOUREND_ELITE));
        diaries.add("Lumbridge", createDiaryProgress(Varbits.DIARY_LUMBRIDGE_EASY,
                Varbits.DIARY_LUMBRIDGE_MEDIUM, Varbits.DIARY_LUMBRIDGE_HARD, Varbits.DIARY_LUMBRIDGE_ELITE));
        diaries.add("Morytania", createDiaryProgress(Varbits.DIARY_MORYTANIA_EASY,
                Varbits.DIARY_MORYTANIA_MEDIUM, Varbits.DIARY_MORYTANIA_HARD, Varbits.DIARY_MORYTANIA_ELITE));
        diaries.add("Varrock", createDiaryProgress(Varbits.DIARY_VARROCK_EASY, Varbits.DIARY_VARROCK_MEDIUM,
                Varbits.DIARY_VARROCK_HARD, Varbits.DIARY_VARROCK_ELITE));
        diaries.add("Western", createDiaryProgress(Varbits.DIARY_WESTERN_EASY, Varbits.DIARY_WESTERN_MEDIUM,
                Varbits.DIARY_WESTERN_HARD, Varbits.DIARY_WESTERN_ELITE));
        diaries.add("Wilderness",
                createDiaryProgress(Varbits.DIARY_WILDERNESS_EASY, Varbits.DIARY_WILDERNESS_MEDIUM,
                        Varbits.DIARY_WILDERNESS_HARD, Varbits.DIARY_WILDERNESS_ELITE));
        result.add("diaries", diaries);
        return gson.toJson(result);
    }

    /**
     * Executes the 'get_player_combat_achievements' tool to retrieve Combat
     * Achievement tier completions, boss kill counts, and filtered task details.
     *
     * @param args JSON arguments with optional "tier", "boss", "completed", and
     *             "taskName" filters
     * @return JSON string of Combat Achievement tiers, kill counts, and matching
     *         tasks
     */
    String executeGetPlayerCombatAchievements(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject tiers = new JsonObject();
        tiers.addProperty("Easy", getCombatAchievementTierStatus(Varbits.COMBAT_ACHIEVEMENT_TIER_EASY));
        tiers.addProperty("Medium", getCombatAchievementTierStatus(Varbits.COMBAT_ACHIEVEMENT_TIER_MEDIUM));
        tiers.addProperty("Hard", getCombatAchievementTierStatus(Varbits.COMBAT_ACHIEVEMENT_TIER_HARD));
        tiers.addProperty("Elite", getCombatAchievementTierStatus(Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE));
        tiers.addProperty("Master", getCombatAchievementTierStatus(Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER));
        tiers.addProperty("Grandmaster", getCombatAchievementTierStatus(Varbits.COMBAT_ACHIEVEMENT_TIER_GRANDMASTER));
        result.add("tiers", tiers);

        String filterTier = (args != null && args.has("tier")) ? args.get("tier").getAsString().trim().toLowerCase()
                : null;
        String filterBoss = (args != null && args.has("boss")) ? args.get("boss").getAsString().trim().toLowerCase()
                : null;
        Boolean filterCompleted = (args != null && args.has("completed")) ? args.get("completed").getAsBoolean() : null;
        String filterTaskName = (args != null && args.has("taskName"))
                ? args.get("taskName").getAsString().trim().toLowerCase()
                : null;

        JsonObject killCounts = new JsonObject();
        String profileKey = configManager.getRSProfileKey();
        if (profileKey != null) {
            List<String> keys = configManager.getRSProfileConfigurationKeys("killcount", profileKey, "");
            if (keys != null) {
                List<String> sortedKeys = new ArrayList<>(keys);
                Collections.sort(sortedKeys);
                for (String key : sortedKeys) {
                    if (filterBoss != null && !key.toLowerCase().contains(filterBoss)) {
                        continue;
                    }
                    String valueStr = getConfigValue("killcount", key);
                    if (valueStr != null) {
                        try {
                            int count = Integer.parseInt(valueStr);
                            killCounts.addProperty(key, count);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }
        result.add("killCounts", killCounts);

        boolean hasFilters = (filterTier != null || filterBoss != null || filterCompleted != null
                || filterTaskName != null);

        if (hasFilters) {
            JsonArray tasks = new JsonArray();
            net.runelite.api.EnumComposition bossEnum = client.getEnum(CA_BOSS_ENUM);

            for (Map.Entry<Integer, String> tierEntry : CA_TIER_MAP.entrySet()) {
                int enumId = tierEntry.getKey();
                String tierName = tierEntry.getValue();

                if (filterTier != null && !tierName.toLowerCase().equals(filterTier)) {
                    continue;
                }

                net.runelite.api.EnumComposition enumComp = client.getEnum(enumId);
                if (enumComp == null) {
                    continue;
                }

                int[] structIds = enumComp.getIntVals();
                for (int structId : structIds) {
                    net.runelite.api.StructComposition struct = client.getStructComposition(structId);
                    if (struct == null) {
                        continue;
                    }

                    String name = struct.getStringValue(CA_STRUCT_PARAM_NAME);
                    if (name == null) {
                        name = "Unknown";
                    }
                    String description = struct.getStringValue(CA_STRUCT_PARAM_DESCRIPTION);
                    if (description == null) {
                        description = "";
                    }
                    int id = struct.getIntValue(CA_STRUCT_PARAM_TASK_ID);
                    int typeId = struct.getIntValue(CA_STRUCT_PARAM_TYPE);
                    String type = CA_TYPE_MAP.get(typeId);
                    if (type == null) {
                        type = "Unknown";
                    }
                    int bossId = struct.getIntValue(CA_STRUCT_PARAM_BOSS_ID);
                    String bossName = getBossName(bossEnum, bossId);
                    if (bossName == null) {
                        bossName = "Unknown";
                    }

                    boolean completed = false;
                    try {
                        if (id >= 0 && id < CA_VARP_IDS.length * 32) {
                            int varpIndex = id / 32;
                            int bitIndex = id % 32;
                            if (varpIndex < CA_VARP_IDS.length) {
                                int varpValue = client.getVarpValue(CA_VARP_IDS[varpIndex]);
                                completed = (varpValue & (1 << bitIndex)) != 0;
                            }
                        }
                    } catch (Exception ignored) {
                    }

                    if (filterCompleted != null && completed != filterCompleted) {
                        continue;
                    }
                    if (filterBoss != null && !bossName.toLowerCase().contains(filterBoss)) {
                        continue;
                    }
                    if (filterTaskName != null && !name.toLowerCase().contains(filterTaskName)) {
                        continue;
                    }

                    JsonObject taskObj = new JsonObject();
                    taskObj.addProperty("id", id);
                    taskObj.addProperty("name", name);
                    taskObj.addProperty("description", description);
                    taskObj.addProperty("tier", tierName);
                    taskObj.addProperty("type", type);
                    taskObj.addProperty("boss", bossName);
                    taskObj.addProperty("completed", completed);
                    tasks.add(taskObj);
                }
            }
            result.add("tasks", tasks);
        }

        return gson.toJson(result);
    }

    private String getBossName(net.runelite.api.EnumComposition bossEnum, int bossId) {
        if (bossEnum != null) {
            try {
                return bossEnum.getStringValue(bossId);
            } catch (Exception e) {
                log.warn("Failed to get boss name for ID {}: {}", bossId, e.getMessage());
            }
        }
        return "Unknown";
    }

    private String getCombatAchievementTierStatus(int varbitId) {
        int val = client.getVarbitValue(varbitId);
        switch (val) {
            case 0:
                return "Not Started";
            case 1:
            case 2:
                return "Completed";
            default:
                return val > 0 ? "Completed" : "Not Started";
        }
    }

    String executeGetPlayerBank(JsonObject args) {
        JsonObject result = new JsonObject();
        ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
        if (bankContainer == null || bankContainer.getItems().length == 0) {
            result.addProperty("status", "error");
            result.addProperty("message",
                    "The bank is not currently open. Ask the player to open their bank if they want you to check bank items.");
        } else {
            String filter = (args != null && args.has("filter")) ? args.get("filter").getAsString() : null;
            int minValue = (args != null && args.has("minValue")) ? args.get("minValue").getAsInt() : 0;
            result.addProperty("status", "success");
            result.addProperty("bankOpen", true);
            if (filter != null) {
                result.addProperty("filterApplied", filter);
            }
            result.add("items",
                    ItemContainerUtils.aggregateItemsWithPrices(client, itemManager, bankContainer, filter, minValue));
        }
        return gson.toJson(result);
    }

    String executeGetItemStats(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject itemsStats = new JsonObject();
        if (args != null) {
            if (args.has("itemIds")) {
                JsonArray ids = args.getAsJsonArray("itemIds");
                for (int i = 0; i < ids.size(); i++) {
                    int itemId = ids.get(i).getAsInt();
                    itemsStats.add(String.valueOf(itemId), ItemContainerUtils.buildItemStatsJson(itemManager, itemId));
                }
            }
            if (args.has("itemNames")) {
                JsonArray names = args.getAsJsonArray("itemNames");
                for (int i = 0; i < names.size(); i++) {
                    String itemName = names.get(i).getAsString();
                    Integer itemId = ItemContainerUtils.findItemIdInContainers(client, itemManager, itemName);
                    if (itemId != null) {
                        itemsStats.add(itemName, ItemContainerUtils.buildItemStatsJson(itemManager, itemId));
                    } else {
                        JsonObject errorObj = new JsonObject();
                        errorObj.addProperty("error", "Item '" + itemName
                                + "' not found in game containers or item database. You MUST call 'search_osrs_wiki' with query '"
                                + itemName + "' to look up its stats on the OSRS Wiki before making claims.");
                        itemsStats.add(itemName, errorObj);
                    }
                }
            }
        }
        result.add("items", itemsStats);
        return gson.toJson(result);
    }

    String executeGetPlayerClues(JsonObject args) throws Exception {
        JsonObject result = new JsonObject();
        result.add("inventoryClues", extractClueItems(InventoryID.INVENTORY, "Inventory"));
        result.add("bankClues", extractClueItems(InventoryID.BANK, "Bank"));
        result.add("activeClue", extractActiveClueDetails());
        return gson.toJson(result);
    }

    private JsonArray extractClueItems(InventoryID inventoryId, String location) {
        JsonArray clueItems = new JsonArray();
        ItemContainer container = client.getItemContainer(inventoryId);
        if (container != null) {
            for (Item item : container.getItems()) {
                if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) {
                    continue;
                }
                ItemComposition comp = client.getItemDefinition(item.getId());
                if (comp != null && comp.getIntValue(ParamID.CLUE_SCROLL) != -1) {
                    JsonObject clueItem = new JsonObject();
                    clueItem.addProperty("id", item.getId());
                    clueItem.addProperty("name", comp.getName());
                    clueItem.addProperty("qty", item.getQuantity());
                    clueItem.addProperty("location", location);
                    clueItems.add(clueItem);
                }
            }
        }
        return clueItems;
    }

    private JsonObject extractActiveClueDetails() {
        JsonObject activeClueObj = new JsonObject();
        if (pluginManager == null) {
            activeClueObj.addProperty("status", "RuneLite's built-in Clue Scroll plugin was not found.");
            return activeClueObj;
        }

        net.runelite.client.plugins.cluescrolls.ClueScrollPlugin cluePlugin = null;
        for (net.runelite.client.plugins.Plugin p : pluginManager.getPlugins()) {
            if (p instanceof net.runelite.client.plugins.cluescrolls.ClueScrollPlugin) {
                cluePlugin = (net.runelite.client.plugins.cluescrolls.ClueScrollPlugin) p;
                break;
            }
        }

        if (cluePlugin == null) {
            activeClueObj.addProperty("status", "RuneLite's built-in Clue Scroll plugin was not found.");
            return activeClueObj;
        }

        if (!pluginManager.isPluginEnabled(cluePlugin)) {
            activeClueObj.addProperty("status",
                    "RuneLite's built-in Clue Scroll plugin is disabled in the client settings. Ask the player to enable it.");
            return activeClueObj;
        }

        net.runelite.client.plugins.cluescrolls.clues.ClueScroll clue = cluePlugin.getClue();
        if (clue == null) {
            activeClueObj.addProperty("status",
                    "No active clue scroll step loaded. Ask the player to read/open their clue scroll once to activate tracking.");
            return activeClueObj;
        }

        activeClueObj.addProperty("status", "Active clue scroll detected");
        activeClueObj.addProperty("type", clue.getClass().getSimpleName());

        try {
            activeClueObj.add("details", formatClueDetails(clue, cluePlugin));
        } catch (Throwable t) {
            activeClueObj.addProperty("error", "Failed to format clue details: " + t.getMessage());
        }

        return activeClueObj;
    }

    private JsonArray formatClueDetails(net.runelite.client.plugins.cluescrolls.clues.ClueScroll clue,
            net.runelite.client.plugins.cluescrolls.ClueScrollPlugin cluePlugin) {
        net.runelite.client.ui.overlay.components.PanelComponent panel = new net.runelite.client.ui.overlay.components.PanelComponent();
        clue.makeOverlayHint(panel, cluePlugin);

        JsonArray hintLines = new JsonArray();
        for (Object child : panel.getChildren()) {
            if (child instanceof net.runelite.client.ui.overlay.components.LineComponent) {
                net.runelite.client.ui.overlay.components.LineComponent lc = (net.runelite.client.ui.overlay.components.LineComponent) child;
                String left = readDeclaredFieldString(lc, "left");
                String right = readDeclaredFieldString(lc, "right");

                if (left != null && !left.trim().isEmpty()) {
                    if (right != null && !right.trim().isEmpty()) {
                        hintLines.add(left + ": " + right);
                    } else {
                        hintLines.add(left);
                    }
                }
            } else if (child instanceof net.runelite.client.ui.overlay.components.TitleComponent) {
                net.runelite.client.ui.overlay.components.TitleComponent tc = (net.runelite.client.ui.overlay.components.TitleComponent) child;
                String text = readDeclaredFieldString(tc, "text");

                if (text != null && !text.trim().isEmpty()) {
                    hintLines.add(text);
                }
            } else {
                hintLines.add(child.toString());
            }
        }
        return hintLines;
    }

    private String readDeclaredFieldString(Object obj, String fieldName) {
        try {
            java.lang.reflect.Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return (String) field.get(obj);
        } catch (Exception ignored) {
            return null;
        }
    }

    String executeSearchOsrsWiki(JsonObject args) {
        String query = args.has("query") ? args.get("query").getAsString() : "";
        return WikiSearchUtil.executeWikiSearch(getWikiClient(), gson, query);
    }

    static String extractSearchQuery(String question) {
        return WikiSearchUtil.extractSearchQuery(question);
    }

    static String cleanWikitext(String wikitext) {
        return WikiSearchUtil.cleanWikitext(wikitext);
    }

    String executeGetPlayerStatus(JsonObject args) {
        JsonObject result = new JsonObject();

        int specPercent = client.getVarpValue(VARP_SPECIAL_ATTACK_PERCENT) / 10;
        result.addProperty("specialAttackPercent", specPercent);

        result.addProperty("runEnergy", client.getEnergy() / 100);
        result.addProperty("weightKg", client.getWeight());

        JsonObject hp = new JsonObject();
        hp.addProperty("current", client.getBoostedSkillLevel(Skill.HITPOINTS));
        hp.addProperty("max", client.getRealSkillLevel(Skill.HITPOINTS));
        result.add("hitpoints", hp);

        JsonObject prayer = new JsonObject();
        prayer.addProperty("current", client.getBoostedSkillLevel(Skill.PRAYER));
        prayer.addProperty("max", client.getRealSkillLevel(Skill.PRAYER));
        result.add("prayer", prayer);

        JsonArray activePrayers = new JsonArray();
        for (Prayer p : Prayer.values()) {
            try {
                if (client.getVarbitValue(p.getVarbit()) == 1) {
                    activePrayers.add(p.name());
                }
            } catch (Exception ignored) {
            }
        }
        result.add("activePrayers", activePrayers);

        int poisonVarp = client.getVarpValue(VarPlayerID.POISON);
        String status = "Healthy";
        if (poisonVarp > 0 && poisonVarp < POISON_VENOM_THRESHOLD) {
            status = "Poisoned (" + poisonVarp + " dmg)";
        } else if (poisonVarp >= POISON_VENOM_THRESHOLD) {
            int venomDmg = (poisonVarp - POISON_VENOM_THRESHOLD) / 5 + 6;
            status = "Venomed (" + venomDmg + " dmg)";
        }
        result.addProperty("poisonState", status);

        JsonObject boostedSkills = new JsonObject();
        for (Skill s : Skill.values()) {
            if ("OVERALL".equals(s.name()))
                continue;
            int real = client.getRealSkillLevel(s);
            int boosted = client.getBoostedSkillLevel(s);
            if (real != boosted) {
                JsonObject bData = new JsonObject();
                bData.addProperty("boosted", boosted);
                bData.addProperty("real", real);
                boostedSkills.add(s.getName(), bData);
            }
        }
        result.add("boostedSkills", boostedSkills);

        JsonArray activeInfoBoxes = new JsonArray();
        if (infoBoxManager != null) {
            int count = 0;
            for (InfoBox box : infoBoxManager.getInfoBoxes()) {
                if (count >= 30) {
                    break;
                }
                try {
                    String text = box.getText();
                    if (text == null) {
                        text = "";
                    } else {
                        text = text.trim();
                    }

                    // Skip zero/empty/dormant infoboxes
                    if (text.isEmpty() || text.equals("0") || text.equals("0/0") || text.equals("0%")) {
                        continue;
                    }

                    JsonObject boxObj = new JsonObject();
                    String name = box.getName();
                    if (name == null || name.trim().isEmpty()) {
                        name = box.getClass().getSimpleName();
                    }
                    boxObj.addProperty("name", name);
                    boxObj.addProperty("text", text);

                    String tooltip = box.getTooltip();
                    if (tooltip != null && !tooltip.trim().isEmpty()) {
                        String noHtml = PATTERN_HTML_TAGS.matcher(tooltip).replaceAll(" ");
                        String cleanTooltip = PATTERN_WHITESPACE.matcher(noHtml).replaceAll(" ").trim();
                        if (cleanTooltip.length() > MAX_TOOLTIP_LENGTH) {
                            cleanTooltip = cleanTooltip.substring(0, MAX_TOOLTIP_LENGTH - 3) + "...";
                        }
                        boxObj.addProperty("tooltip", cleanTooltip);
                    }

                    activeInfoBoxes.add(boxObj);
                    count++;
                } catch (Exception ignored) {
                }
            }
        }
        result.add("activeInfoBoxes", activeInfoBoxes);

        return gson.toJson(result);
    }

    String executeGetPlayerCurrenciesAndPoints(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject points = new JsonObject();

        try {
            int nmz = client.getVarpValue(VARP_NMZ_REWARD_POINTS);
            points.addProperty("nightmareZonePoints", nmz);
        } catch (Exception ignored) {
        }

        try {
            int pc = client.getVarpValue(VARP_PEST_CONTROL_POINTS);
            points.addProperty("pestControlCommendations", pc);
        } catch (Exception ignored) {
        }

        try {
            int tithe = client.getVarbitValue(VARBIT_TITHE_FARM_POINTS);
            points.addProperty("titheFarmPoints", tithe);
        } catch (Exception ignored) {
        }

        try {
            String pts = getConfigValue("slayer", "points");
            if (pts != null && !pts.isEmpty()) {
                points.addProperty("slayerPoints", Integer.parseInt(pts));
            }
            String strk = getConfigValue("slayer", "streak");
            if (strk != null && !strk.isEmpty()) {
                points.addProperty("slayerStreak", Integer.parseInt(strk));
            }
        } catch (Exception ignored) {
        }

        Map<String, Integer> targetItemNames = Map.of(
                "Mark of grace", ITEM_ID_MARK_OF_GRACE,
                "Golden nugget", ITEM_ID_GOLDEN_NUGGET,
                "Abyssal pearl", ITEM_ID_ABYSSAL_PEARL,
                "Tokkul", ITEM_ID_TOKKUL,
                "Stardust", ITEM_ID_STARDUST,
                "Archery ticket", ITEM_ID_ARCHERY_TICKET,
                "Mermaid's tear", ITEM_ID_MERMAIDS_TEAR);

        JsonObject itemCurrencies = new JsonObject();
        Map<Integer, Long> counts = new HashMap<>();
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv != null) {
            for (Item item : inv.getItems()) {
                if (item != null && item.getId() > 0) {
                    counts.put(item.getId(), counts.getOrDefault(item.getId(), 0L) + item.getQuantity());
                }
            }
        }
        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        if (bank != null) {
            for (Item item : bank.getItems()) {
                if (item != null && item.getId() > 0) {
                    counts.put(item.getId(), counts.getOrDefault(item.getId(), 0L) + item.getQuantity());
                }
            }
        }

        for (Map.Entry<String, Integer> entry : targetItemNames.entrySet()) {
            String name = entry.getKey();
            int targetId = entry.getValue();
            long total = counts.getOrDefault(targetId, 0L);
            if (total > 0) {
                itemCurrencies.addProperty(name, total);
            }
        }
        points.add("currencyItems", itemCurrencies);
        result.add("pointsAndCurrencies", points);

        return gson.toJson(result);
    }

    static class VesselWidgetData {
        boolean foundVesselUi = false;
        String shipName = null;
        int currentHp = -1;
        int maxHp = -1;
        String sailingActivity = null;
        List<String> facilities = new ArrayList<>();
    }

    VesselWidgetData scanVesselWidgets() {
        VesselWidgetData data = new VesselWidgetData();
        if (client == null) {
            return data;
        }

        try {
            Widget[] roots = client.getWidgetRoots();
            if (roots != null) {
                for (Widget root : roots) {
                    scanWidgetNode(root, data);
                }
            }
        } catch (Exception e) {
            log.debug("Error scanning widgets for vessel telemetry", e);
        }

        return data;
    }

    private void scanWidgetNode(Widget widget, VesselWidgetData data) {
        if (widget == null || widget.isSelfHidden()) {
            return;
        }

        String text = widget.getText();
        if (text != null && !text.isEmpty()) {
            String cleanText = PATTERN_HTML_TAGS.matcher(text).replaceAll("").trim();
            if (!cleanText.isEmpty()) {
                if (cleanText.matches("^\\d{1,4}\\s*/\\s*\\d{1,4}$")) {
                    String[] parts = cleanText.split("/");
                    try {
                        data.currentHp = Integer.parseInt(parts[0].trim());
                        data.maxHp = Integer.parseInt(parts[1].trim());
                        data.foundVesselUi = true;
                    } catch (NumberFormatException ignored) {
                    }
                }

                String lower = cleanText.toLowerCase();
                if (lower.equals("facilities") || lower.equals("steering") || lower.startsWith("repairs")) {
                    data.foundVesselUi = true;
                    if (!data.facilities.contains(cleanText)) {
                        data.facilities.add(cleanText);
                    }
                }

                if (lower.contains("charting") || lower.contains("weather pattern")) {
                    data.sailingActivity = cleanText;
                    data.foundVesselUi = true;
                }

                if (cleanText.length() >= 3 && cleanText.length() <= 35) {
                    if (lower.contains("clipper") || lower.contains("sloop") || lower.contains("skiff")
                            || lower.contains("brig") || lower.contains("frigate") || lower.contains("boat")
                            || lower.contains("galleon")) {
                        data.shipName = cleanText;
                        data.foundVesselUi = true;
                    }
                }
            }
        }

        Widget[] children = widget.getChildren();
        if (children != null) {
            for (Widget child : children) {
                scanWidgetNode(child, data);
            }
        }
        Widget[] nested = widget.getNestedChildren();
        if (nested != null) {
            for (Widget child : nested) {
                scanWidgetNode(child, data);
            }
        }
        Widget[] dynamic = widget.getDynamicChildren();
        if (dynamic != null) {
            for (Widget child : dynamic) {
                scanWidgetNode(child, data);
            }
        }
        Widget[] staticChildren = widget.getStaticChildren();
        if (staticChildren != null) {
            for (Widget child : staticChildren) {
                scanWidgetNode(child, data);
            }
        }
    }

    String executeGetPlayerSailingStatus(JsonObject args) {
        JsonObject result = new JsonObject();
        boolean includeCargo = true;
        if (args != null && args.has("includeCargo") && !args.get("includeCargo").isJsonNull()) {
            includeCargo = args.get("includeCargo").getAsBoolean();
        }

        JsonObject skillData = new JsonObject();
        Skill sailingSkill = null;
        for (Skill s : Skill.values()) {
            if ("SAILING".equalsIgnoreCase(s.name())) {
                sailingSkill = s;
                break;
            }
        }
        if (sailingSkill != null) {
            try {
                int realLvl = client.getRealSkillLevel(sailingSkill);
                int boostLvl = client.getBoostedSkillLevel(sailingSkill);
                int exp = client.getSkillExperience(sailingSkill);
                skillData.addProperty("realLevel", realLvl);
                skillData.addProperty("boostedLevel", boostLvl);
                skillData.addProperty("experience", exp);
            } catch (Exception ignored) {
            }
        } else {
            skillData.addProperty("status", "Sailing skill API pending/available via client updates");
        }
        result.add("sailingSkill", skillData);

        VesselWidgetData wData = scanVesselWidgets();

        JsonObject vessel = new JsonObject();
        boolean aboardVessel = wData.foundVesselUi;
        String shipType = (wData.shipName != null) ? wData.shipName : DEFAULT_VESSEL_SHIP_TYPE;
        int hullHealthPct = DEFAULT_VESSEL_HULL_HEALTH_PERCENT;
        if (wData.currentHp > 0 && wData.maxHp > 0) {
            hullHealthPct = (int) Math.round(((double) wData.currentHp / wData.maxHp) * 100.0);
            vessel.addProperty("currentHullHp", wData.currentHp);
            vessel.addProperty("maxHullHp", wData.maxHp);
        }
        int speedKnots = DEFAULT_VESSEL_SPEED_KNOTS;
        String sailTrim = DEFAULT_VESSEL_SAIL_TRIM;
        String windVector = DEFAULT_VESSEL_WIND_VECTOR;
        String anchorState = DEFAULT_VESSEL_ANCHOR_STATUS;

        try {
            int sailingStateVarbit = client.getVarbitValue(VARBIT_SAILING_STATE);
            if (sailingStateVarbit > 0) {
                aboardVessel = true;
            }
        } catch (Exception ignored) {
        }

        vessel.addProperty("aboardVessel", aboardVessel);
        vessel.addProperty("shipName", shipType);
        vessel.addProperty("shipType", shipType);
        vessel.addProperty("hullHealthPercent", hullHealthPct);
        vessel.addProperty("speedKnots", speedKnots);
        vessel.addProperty("sailTrim", sailTrim);
        vessel.addProperty("windVector", windVector);
        vessel.addProperty("anchorStatus", anchorState);
        if (wData.sailingActivity != null) {
            vessel.addProperty("activeActivity", wData.sailingActivity);
        }
        if (!wData.facilities.isEmpty()) {
            JsonArray facArray = new JsonArray();
            for (String fac : wData.facilities) {
                facArray.add(fac);
            }
            vessel.add("facilities", facArray);
        }
        result.add("vesselStatus", vessel);

        JsonObject locObj = new JsonObject();
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer != null) {
            WorldPoint wp = localPlayer.getWorldLocation();
            if (wp != null) {
                boolean inInstance = isInInstance(localPlayer);
                InstanceTemplates template = getInstanceTemplate(localPlayer, wp);
                locObj.addProperty("locationName", locationResolver.describeForAi(wp, inInstance, template));
                locObj.addProperty("regionId", wp.getRegionID());
                locObj.addProperty("coordinates", wp.getX() + ", " + wp.getY() + ", " + wp.getPlane());
                locObj.addProperty("inInstance", inInstance);
            }
        }
        result.add("location", locObj);

        if (includeCargo) {
            JsonArray cargoArray = new JsonArray();
            try {
                ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
                if (inv != null) {
                    for (Item item : inv.getItems()) {
                        if (item != null && item.getId() > 0) {
                            ItemComposition comp = itemManager.getItemComposition(item.getId());
                            if (comp != null && comp.getName() != null) {
                                String lower = comp.getName().toLowerCase();
                                if (lower.contains("plank") || lower.contains("sail") || lower.contains("cannon")
                                        || lower.contains("salvage") || lower.contains("rum") || lower.contains("fish")
                                        || lower.contains("ore") || lower.contains("wood")) {
                                    JsonObject cItem = new JsonObject();
                                    cItem.addProperty("id", item.getId());
                                    cItem.addProperty("name", comp.getName());
                                    cItem.addProperty("quantity", item.getQuantity());
                                    cargoArray.add(cItem);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            result.add("cargoHoldItems", cargoArray);
        }

        return gson.toJson(result);
    }

    String executeGetPlayerLocationDetails(JsonObject args) {
        JsonObject result = new JsonObject();
        Player localPlayer = client.getLocalPlayer();

        int wildyLevel = 0;
        try {
            wildyLevel = client.getVarbitValue(VARBIT_WILDERNESS_LEVEL);
        } catch (Exception ignored) {
        }
        result.addProperty("wildernessLevel", wildyLevel);
        result.addProperty("inWilderness", wildyLevel > 0);

        boolean isMulti = false;
        try {
            isMulti = client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1;
        } catch (Exception ignored) {
        }
        result.addProperty("multiCombat", isMulti);

        boolean inInstance = isInInstance(localPlayer);
        result.addProperty("instancedArea", inInstance);

        if (localPlayer != null) {
            WorldPoint wp = localPlayer.getWorldLocation();
            if (wp != null) {
                InstanceTemplates instanceTemplate = getInstanceTemplate(localPlayer, wp);
                result.addProperty("locationName", locationResolver.describeForAi(wp, inInstance, instanceTemplate));
                result.addProperty("coordinates", wp.getX() + ", " + wp.getY() + ", " + wp.getPlane());
                result.addProperty("regionId", wp.getRegionID());
            }
        }

        JsonArray worldTypes = new JsonArray();
        for (WorldType wt : client.getWorldType()) {
            worldTypes.add(wt.name());
        }
        result.add("worldTypes", worldTypes);

        return gson.toJson(result);
    }

    String executeGetPlayerTransportation(JsonObject args) {
        JsonObject result = new JsonObject();

        // 1. Unlocked Transportation Networks & Quests
        JsonObject networks = new JsonObject();

        QuestState fairytale2 = getQuestStateSafe(Quest.FAIRYTALE_II__CURE_A_QUEEN);
        boolean fairyRingsUnlocked = (fairytale2 == QuestState.IN_PROGRESS || fairytale2 == QuestState.FINISHED);
        networks.addProperty("fairyRings", fairyRingsUnlocked ? "UNLOCKED" : "LOCKED");

        boolean stafflessFairyRings = false;
        try {
            int lumbridgeElite = client.getVarbitValue(Varbits.DIARY_LUMBRIDGE_ELITE);
            stafflessFairyRings = (lumbridgeElite == 1);
        } catch (Exception ignored) {
        }
        networks.addProperty("stafflessFairyRings", stafflessFairyRings);

        QuestState treeGnomeVillage = getQuestStateSafe(Quest.TREE_GNOME_VILLAGE);
        networks.addProperty("spiritTrees", (treeGnomeVillage == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        QuestState grandTree = getQuestStateSafe(Quest.THE_GRAND_TREE);
        networks.addProperty("gnomeGliders", (grandTree == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        QuestState enlightenedJourney = getQuestStateSafe(Quest.ENLIGHTENED_JOURNEY);
        networks.addProperty("hotAirBalloons", (enlightenedJourney == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        QuestState ghostsAhoy = getQuestStateSafe(Quest.GHOSTS_AHOY);
        networks.addProperty("ectophial", (ghostsAhoy == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        QuestState tasteOfHope = getQuestStateSafe(Quest.A_TASTE_OF_HOPE);
        networks.addProperty("drakkansMedallion",
                (tasteOfHope == QuestState.IN_PROGRESS || tasteOfHope == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        QuestState mm2 = getQuestStateSafe(Quest.MONKEY_MADNESS_II);
        networks.addProperty("royalSeedPod", (mm2 == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        QuestState clientOfKourend = getQuestStateSafe(Quest.CLIENT_OF_KOUREND);
        networks.addProperty("kharedstsMemoirs", (clientOfKourend == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        QuestState kingdomDivided = getQuestStateSafe(Quest.A_KINGDOM_DIVIDED);
        networks.addProperty("bookOfTheDead", (kingdomDivided == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        result.add("unlockedNetworks", networks);

        // 2. Magic & Spellbook Teleports
        JsonObject magicObj = new JsonObject();
        int spellbookVal = 0;
        try {
            spellbookVal = client.getVarbitValue(VARBIT_SPELLBOOK);
        } catch (Exception ignored) {
        }
        String spellbookName = PromptUtils.describeSpellbook(spellbookVal);
        magicObj.addProperty("currentSpellbook", spellbookName);

        int magicLevel = client.getRealSkillLevel(Skill.MAGIC);
        int magicBoosted = client.getBoostedSkillLevel(Skill.MAGIC);
        magicObj.addProperty("magicLevelBase", magicLevel);
        magicObj.addProperty("magicLevelBoosted", magicBoosted);

        JsonArray unlockedTeleports = new JsonArray();
        int effectiveMagic = Math.max(magicLevel, magicBoosted);
        if ("Standard".equals(spellbookName)) {
            unlockedTeleports.add("Home Teleport (Lumbridge)");
            if (effectiveMagic >= 25)
                unlockedTeleports.add("Varrock Teleport (25)");
            if (effectiveMagic >= 31)
                unlockedTeleports.add("Lumbridge Teleport (31)");
            if (effectiveMagic >= 37)
                unlockedTeleports.add("Falador Teleport (37)");
            if (effectiveMagic >= 40)
                unlockedTeleports.add("Teleport to House (40)");
            if (effectiveMagic >= 45)
                unlockedTeleports.add("Camelot Teleport (45)");
            if (effectiveMagic >= 51)
                unlockedTeleports.add("Ardougne Teleport (51)");
            if (effectiveMagic >= 58)
                unlockedTeleports.add("Watchtower Teleport (58)");
            if (effectiveMagic >= 61)
                unlockedTeleports.add("Trollheim Teleport (61)");
            if (effectiveMagic >= 64)
                unlockedTeleports.add("Ape Atoll Teleport (64)");
            if (effectiveMagic >= 69)
                unlockedTeleports.add("Kourend Castle Teleport (69)");
        } else if ("Ancient Magicks".equals(spellbookName)) {
            unlockedTeleports.add("Edgeville Home Teleport");
            if (effectiveMagic >= 54)
                unlockedTeleports.add("Paddewwa Teleport (54)");
            if (effectiveMagic >= 60)
                unlockedTeleports.add("Senntisten Teleport (60)");
            if (effectiveMagic >= 66)
                unlockedTeleports.add("Kharyrll Teleport (66)");
            if (effectiveMagic >= 72)
                unlockedTeleports.add("Lassar Teleport (72)");
            if (effectiveMagic >= 78)
                unlockedTeleports.add("Dareeyak Teleport (78)");
            if (effectiveMagic >= 84)
                unlockedTeleports.add("Carrallangar Teleport (84)");
            if (effectiveMagic >= 90)
                unlockedTeleports.add("Annakarl Teleport (90)");
            if (effectiveMagic >= 96)
                unlockedTeleports.add("Ghorrock Teleport (96)");
        } else if ("Lunar".equals(spellbookName)) {
            unlockedTeleports.add("Lunar Home Teleport");
            if (effectiveMagic >= 69)
                unlockedTeleports.add("Moonclan Teleport (69)");
            if (effectiveMagic >= 71)
                unlockedTeleports.add("Ourania Teleport (71)");
            if (effectiveMagic >= 72)
                unlockedTeleports.add("Waterbirth Teleport (72)");
            if (effectiveMagic >= 75)
                unlockedTeleports.add("Barbarian Teleport (75)");
            if (effectiveMagic >= 78)
                unlockedTeleports.add("Khazard Teleport (78)");
            if (effectiveMagic >= 85)
                unlockedTeleports.add("Fishing Guild Teleport (85)");
            if (effectiveMagic >= 87)
                unlockedTeleports.add("Catherby Teleport (87)");
            if (effectiveMagic >= 89)
                unlockedTeleports.add("Ice Plateau Teleport (89)");
        } else if ("Arceuus".equals(spellbookName)) {
            unlockedTeleports.add("Arceuus Home Teleport");
            if (effectiveMagic >= 38)
                unlockedTeleports.add("Arceuus Library Teleport (38)");
            if (effectiveMagic >= 40)
                unlockedTeleports.add("Draynor Manor Teleport (40)");
            if (effectiveMagic >= 40)
                unlockedTeleports.add("Salve Graveyard Teleport (40)");
            if (effectiveMagic >= 48)
                unlockedTeleports.add("Fenkenstrain's Castle Teleport (48)");
            if (effectiveMagic >= 61)
                unlockedTeleports.add("West Ardougne Teleport (61)");
            if (effectiveMagic >= 65)
                unlockedTeleports.add("Harmony Island Teleport (65)");
            if (effectiveMagic >= 71)
                unlockedTeleports.add("Cemetery Teleport (71)");
            if (effectiveMagic >= 83)
                unlockedTeleports.add("Barrows Teleport (83)");
            if (effectiveMagic >= 90)
                unlockedTeleports.add("Ape Atoll Teleport (90)");
        }
        magicObj.add("unlockedSpellTeleports", unlockedTeleports);
        result.add("magicAndSpellbook", magicObj);

        // 3. Construction & POH Features
        JsonObject pohObj = new JsonObject();
        int conLevel = client.getRealSkillLevel(Skill.CONSTRUCTION);
        pohObj.addProperty("constructionLevel", conLevel);
        pohObj.addProperty("portalChamberUnlocked", conLevel >= 50);
        pohObj.addProperty("portalNexusUnlocked", conLevel >= 72);
        pohObj.addProperty("basicJewelleryBoxUnlocked", conLevel >= 81);
        pohObj.addProperty("ornateJewelleryBoxUnlocked", conLevel >= 91);
        pohObj.addProperty("pohFairyRingUnlocked", conLevel >= 85);
        pohObj.addProperty("pohSpiritTreeUnlocked", conLevel >= 95);
        result.add("constructionAndPoh", pohObj);

        // 4. Available Teleport Items in Inventory, Equipment, and Bank
        JsonArray teleportItems = scanTeleportItems();
        result.add("availableTeleportItems", teleportItems);

        return gson.toJson(result);
    }

    private QuestState getQuestStateSafe(Quest quest) {
        if (quest == null)
            return QuestState.NOT_STARTED;
        try {
            QuestState state = quest.getState(client);
            return state != null ? state : QuestState.NOT_STARTED;
        } catch (Exception e) {
            return QuestState.NOT_STARTED;
        }
    }

    private JsonArray scanTeleportItems() {
        JsonArray found = new JsonArray();
        Set<String> uniqueFoundNames = new HashSet<>();

        List<InventoryID> containersToScan = Arrays.asList(
                InventoryID.INVENTORY,
                InventoryID.EQUIPMENT,
                InventoryID.BANK);

        String[] keywords = new String[] {
                "ring of dueling", "games necklace", "combat bracelet", "skills necklace",
                "necklace of passage", "digsite pendant", "xeric's talisman", "slayer ring",
                "rada's blessing", "pharaoh's sceptre", "royal seed pod", "ectophial",
                "drakkan's medallion", "teleport crystal", "ring of the elements",
                "teleport scroll", "master scroll book", "ardougne cloak", "kandarin headgear",
                "explorer's ring", "desert amulet", "morytania legs", "karamja gloves",
                "western banner", "fremennik boots", "dramen staff", "lunar staff",
                "book of the dead", "kharedst's memoirs", "teleport to house", "varrock teleport",
                "lumbridge teleport", "falador teleport", "camelot teleport", "ardougne teleport",
                "mythical cape"
        };

        for (InventoryID invId : containersToScan) {
            ItemContainer container = client.getItemContainer(invId);
            if (container == null)
                continue;

            Item[] items = container.getItems();
            if (items == null)
                continue;

            for (Item item : items) {
                if (item == null || item.getId() <= 0)
                    continue;

                String name = null;
                if (itemManager != null) {
                    try {
                        net.runelite.api.ItemComposition comp = itemManager.getItemComposition(item.getId());
                        if (comp != null && comp.getName() != null) {
                            name = comp.getName();
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (name == null)
                    continue;

                String lowerName = name.toLowerCase();
                for (String kw : keywords) {
                    if (lowerName.contains(kw) && !uniqueFoundNames.contains(name)) {
                        uniqueFoundNames.add(name);
                        JsonObject itemObj = new JsonObject();
                        itemObj.addProperty("name", name);
                        itemObj.addProperty("location", invId.name().toLowerCase());
                        found.add(itemObj);
                        break;
                    }
                }
            }
        }
        return found;
    }

    private String buildGameContext() {
        if (!config.shareCharacterInfo()) {
            return "Player is not sharing character details with the AI (this option is disabled in the settings).";
        }

        if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN) {
            return "Player is not logged in.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("PLAYER PROFILE:\n");

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer != null) {
            sb.append("Name: ").append(localPlayer.getName()).append("\n");
            sb.append("Combat Level: ").append(localPlayer.getCombatLevel()).append("\n");
        }

        Integer accountTypeVarbit = null;
        if (client.getLocalPlayer() != null) {
            accountTypeVarbit = client.getVarbitValue(Varbits.ACCOUNT_TYPE);
        }
        sb.append("Account Type: ").append(PromptUtils.describeAccountType(accountTypeVarbit)).append("\n");
        sb.append("World: ").append(client.getWorld()).append("\n");
        sb.append("Total Level: ").append(client.getTotalLevel()).append("\n");
        sb.append("Combat & Key Skills: ")
                .append("Attack ").append(client.getRealSkillLevel(Skill.ATTACK)).append(", ")
                .append("Strength ").append(client.getRealSkillLevel(Skill.STRENGTH)).append(", ")
                .append("Defence ").append(client.getRealSkillLevel(Skill.DEFENCE)).append(", ")
                .append("Ranged ").append(client.getRealSkillLevel(Skill.RANGED)).append(", ")
                .append("Prayer ").append(client.getRealSkillLevel(Skill.PRAYER)).append(", ")
                .append("Magic ").append(client.getRealSkillLevel(Skill.MAGIC)).append(", ")
                .append("Hitpoints ").append(client.getRealSkillLevel(Skill.HITPOINTS)).append(", ")
                .append("Slayer ").append(client.getRealSkillLevel(Skill.SLAYER)).append("\n");
        int spellbookVar = client.getVarbitValue(VARBIT_SPELLBOOK);
        sb.append("Active Spellbook: ").append(PromptUtils.describeSpellbook(spellbookVar)).append("\n");
        sb.append("Hitpoints: Current ")
                .append(client.getBoostedSkillLevel(Skill.HITPOINTS))
                .append(" (Base Level ")
                .append(client.getRealSkillLevel(Skill.HITPOINTS))
                .append(")\n");
        sb.append("Prayer Points: Current ")
                .append(client.getBoostedSkillLevel(Skill.PRAYER))
                .append(" (Base Level ")
                .append(client.getRealSkillLevel(Skill.PRAYER))
                .append(")\n");

        for (Skill s : Skill.values()) {
            if ("SAILING".equalsIgnoreCase(s.name())) {
                try {
                    sb.append("Sailing Skill: Base Level ")
                            .append(client.getRealSkillLevel(s))
                            .append(" (Boosted ")
                            .append(client.getBoostedSkillLevel(s))
                            .append(")\n");
                } catch (Exception ignored) {
                }
                break;
            }
        }

        VesselWidgetData vData = scanVesselWidgets();
        if (vData.foundVesselUi) {
            sb.append("\nCURRENTLY ABOARD VESSEL:\n");
            sb.append("Vessel Status: ABOARD VESSEL\n");
            sb.append("Vessel Name: ").append(vData.shipName != null ? vData.shipName : "Sailing Vessel").append("\n");
            if (vData.currentHp > 0 && vData.maxHp > 0) {
                int hpPct = (int) Math.round(((double) vData.currentHp / vData.maxHp) * 100.0);
                sb.append("Hull Health: ").append(vData.currentHp).append("/").append(vData.maxHp)
                        .append(" (").append(hpPct).append("%)\n");
            }
            if (vData.sailingActivity != null) {
                sb.append("Current Activity: ").append(vData.sailingActivity).append("\n");
            }
            if (!vData.facilities.isEmpty()) {
                sb.append("Active Facilities: ").append(String.join(", ", vData.facilities)).append("\n");
            }
        }
        sb.append("\nTEMPORARY CURRENT LOCATION (where player is standing right now):\n");
        if (localPlayer != null) {
            WorldPoint wp = localPlayer.getWorldLocation();
            if (wp != null) {
                InstanceTemplates instanceTemplate = getInstanceTemplate(localPlayer, wp);
                boolean inInstance = isInInstance(localPlayer);
                sb.append("Location Name: ").append(locationResolver.describeForAi(wp, inInstance, instanceTemplate))
                        .append("\n");
                sb.append("Coordinates: ").append(wp.getX()).append(", ").append(wp.getY()).append(", Plane ")
                        .append(wp.getPlane()).append("\n");
                sb.append("Region ID: ").append(wp.getRegionID()).append("\n");
                sb.append("Instanced Area: ").append(inInstance ? "Yes" : "No").append("\n");
            }
        }
        sb.append("\n");

        return trimToPromptBudget(sb.toString(), PromptUtils.MAX_CONTEXT_CHARACTERS,
                "...[game context truncated for prompt budget]");
    }

    static String trimToPromptBudget(String text, int maxChars, String truncationLabel) {
        return PromptUtils.trimToPromptBudget(text, maxChars, truncationLabel);
    }

    static String trimToPromptBudget(String text, int maxChars, String truncationLabel, boolean keepEnd) {
        return PromptUtils.trimToPromptBudget(text, maxChars, truncationLabel, keepEnd);
    }

    static String buildSystemPrompt(String context, String recentConversation) {
        return PromptUtils.buildSystemPrompt(context, recentConversation);
    }

    private boolean isInInstance(Player localPlayer) {
        if (localPlayer != null) {
            WorldView worldView = localPlayer.getWorldView();
            if (worldView != null) {
                return worldView.isInstance();
            }
        }

        return client.getTopLevelWorldView() != null && client.getTopLevelWorldView().isInstance();
    }

    private InstanceTemplates getInstanceTemplate(Player localPlayer, WorldPoint worldPoint) {
        if (localPlayer == null) {
            return null;
        }
        WorldView worldView = localPlayer.getWorldView();
        if (worldView == null || !worldView.isInstance()) {
            return null;
        }

        LocalPoint localPoint = LocalPoint.fromWorld(worldView, worldPoint);
        if (localPoint == null) {
            localPoint = localPlayer.getLocalLocation();
        }

        if (localPoint == null) {
            return null;
        }

        int[][][] chunks = worldView.getInstanceTemplateChunks();
        if (chunks == null) {
            return null;
        }

        int plane = worldPoint.getPlane();
        int chunkX = localPoint.getSceneX() / 8;
        int chunkY = localPoint.getSceneY() / 8;
        if (plane < 0 || plane >= chunks.length
                || chunkX < 0 || chunkX >= chunks[plane].length
                || chunkY < 0 || chunkY >= chunks[plane][chunkX].length) {
            return null;
        }

        return InstanceTemplates.findMatch(chunks[plane][chunkX][chunkY]);
    }

    private String getDiaryStatus(int varbitId) {
        int val = client.getVarbitValue(varbitId);
        switch (val) {
            case 0:
                return "Not Started";
            case 1:
            case 2:
                return "Completed";
            default:
                return val > 0 ? "Completed" : "Not Started";
        }
    }

    private JsonObject createDiaryProgress(int easy, int med, int hard, int elite) {
        JsonObject obj = new JsonObject();
        obj.addProperty("Easy", getDiaryStatus(easy));
        obj.addProperty("Medium", getDiaryStatus(med));
        obj.addProperty("Hard", getDiaryStatus(hard));
        obj.addProperty("Elite", getDiaryStatus(elite));
        return obj;
    }

    @SuppressWarnings("unused")
    private String executeWikiSearch(String query) {
        return WikiSearchUtil.executeWikiSearch(getWikiClient(), gson, query);
    }

    @SuppressWarnings("unused")
    private String describeAccountType(Integer accountTypeVarbit) {
        return PromptUtils.describeAccountType(accountTypeVarbit);
    }

    @SuppressWarnings("unused")
    private String describeSpellbook(int val) {
        return PromptUtils.describeSpellbook(val);
    }

    @SuppressWarnings("unused")
    private JsonObject aggregateItemsWithPrices(ItemContainer container, String filter, int minValue) {
        return ItemContainerUtils.aggregateItemsWithPrices(client, itemManager, container, filter, minValue);
    }

}
