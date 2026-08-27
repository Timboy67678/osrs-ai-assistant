package com.osrsai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import net.runelite.api.NPC;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.GameObject;
import net.runelite.api.Scene;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ObjectComposition;
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

    // Surrounding Environment Scanning Constants
    static final int DEFAULT_SURROUNDINGS_SCAN_RADIUS = 15;
    static final int MAX_SURROUNDINGS_SCAN_RADIUS = 30;
    static final int MIN_SURROUNDINGS_SCAN_RADIUS = 1;
    static final int MAX_SURROUNDINGS_NPC_COUNT = 20;
    static final int MAX_SURROUNDINGS_PLAYER_COUNT = 15;
    static final int MAX_SURROUNDINGS_GROUND_ITEM_COUNT = 15;
    static final int OBJECT_SCAN_MAX_RADIUS = 10;

    private static final String DEFAULT_CUSTOM_ENDPOINT = "http://localhost:11434/v1/chat/completions";

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

    // Minigame Points Varbit IDs
    static final int VARBIT_VALE_RESEARCH_POINTS = 16301;
    static final int VARBIT_MTA_TELEKINETIC_PIZZAZZ = 287;
    static final int VARBIT_MTA_ALCHEMIST_PIZZAZZ = 288;
    static final int VARBIT_MTA_ENCHANTING_PIZZAZZ = 289;
    static final int VARBIT_MTA_GRAVEYARD_PIZZAZZ = 290;
    static final int VARBIT_BA_ATTACKER_POINTS = 4761;
    static final int VARBIT_BA_DEFENDER_POINTS = 4762;
    static final int VARBIT_BA_COLLECTOR_POINTS = 4763;
    static final int VARBIT_BA_HEALER_POINTS = 4764;
    static final int VARBIT_CARPENTERS_POINTS = 10671;
    static final int VARBIT_GIANTS_FOUNDRY_REPUTATION = 13919;
    static final int VARBIT_VOLCANIC_MINE_POINTS = 5934;
    static final int VARBIT_LMS_POINTS = 9304;
    static final int VARBIT_BOUNTY_HUNTER_POINTS = 10079;

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

    // Coordinate & Map Navigation Constants
    private static final int MAX_SURFACE_WORLD_Y_COORDINATE = 5000;
    private static final int OSRS_UNDERGROUND_Y_OFFSET_STEP = 6400;

    // Fossil Island Birdhouse Varbits
    static final int VARBIT_BIRDHOUSE_MEADOW_NORTH = 6521;
    static final int VARBIT_BIRDHOUSE_MEADOW_SOUTH = 6522;
    static final int VARBIT_BIRDHOUSE_VALLEY_NORTH = 6523;
    static final int VARBIT_BIRDHOUSE_VALLEY_SOUTH = 6524;

    // Farming & Boss Growth Varbits
    static final int VARBIT_HESPORI_GROWTH = 7908;
    static final int HESPORI_STAGE_READY = 7;

    // Activity & Tracker Varps
    static final int VARP_TEARS_OF_GUTHIX_COOLDOWN = 452;
    static final int VARP_KINGDOM_FAVOUR = 73;
    static final int VARP_KINGDOM_COFFER = 74;
    static final double KINGDOM_MAX_FAVOUR_SCALE = 127.0;

    // Market, Prices & Alchemy Constants
    static final int ITEM_ID_NATURE_RUNE = 561;
    static final int DEFAULT_NATURE_RUNE_PRICE = 90;
    static final double LOW_ALCH_MULTIPLIER = 0.6;

    // Construction POH Level Thresholds
    static final int POH_LEVEL_PORTAL_CHAMBER = 50;
    static final int POH_LEVEL_PORTAL_NEXUS = 72;
    static final int POH_LEVEL_BASIC_JEWELLERY_BOX = 81;
    static final int POH_LEVEL_FAIRY_RING = 85;
    static final int POH_LEVEL_ORNATE_JEWELLERY_BOX = 91;
    static final int POH_LEVEL_SPIRIT_TREE = 95;

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

    private final List<ItemContainerUtils.SimpleItem> cachedBankItems = new ArrayList<>();
    private long cachedBankTimestamp = 0;

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
        if (args == null) {
            result.addProperty("status", "error");
            result.addProperty("message", "Missing required parameters.");
            return result.toString();
        }

        try {
            if (!config.useShortestPath()) {
                result.addProperty("status", "error");
                result.addProperty("message",
                        "Shortest Path target setting is disabled in the OSRS AI Assistant plugin config.");
                return result.toString();
            }

            WorldPoint targetPoint = null;
            String locationName = "Destination";

            if (args.has("x") && args.has("y") && !args.get("x").isJsonNull() && !args.get("y").isJsonNull()) {
                int x = args.get("x").getAsInt();
                int y = args.get("y").getAsInt();
                int plane = (args.has("plane") && !args.get("plane").isJsonNull()) ? args.get("plane").getAsInt() : 0;
                targetPoint = new WorldPoint(x, y, plane);
                if (args.has("locationName") && !args.get("locationName").isJsonNull()) {
                    locationName = args.get("locationName").getAsString();
                }
            } else {
                String poiQuery = null;
                if (args.has("poiName") && !args.get("poiName").isJsonNull()) {
                    poiQuery = args.get("poiName").getAsString();
                } else if (args.has("locationName") && !args.get("locationName").isJsonNull()) {
                    poiQuery = args.get("locationName").getAsString();
                }

                if (poiQuery != null) {
                    targetPoint = locationResolver.findCoordinatesByPoiName(poiQuery);
                    locationName = poiQuery;
                }
            }

            if (targetPoint == null) {
                result.addProperty("status", "error");
                result.addProperty("message",
                        "Missing coordinates (x, y) or unknown POI name. Please provide valid coordinates or a known POI name.");
                return result.toString();
            }

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

                String slotName = Utilities.getSlotName(i);
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
        diaries.add("Ardougne", createDiaryProgress(
                Varbits.DIARY_ARDOUGNE_EASY, 11,
                Varbits.DIARY_ARDOUGNE_MEDIUM, 13,
                Varbits.DIARY_ARDOUGNE_HARD, 12,
                Varbits.DIARY_ARDOUGNE_ELITE, 8));
        diaries.add("Desert", createDiaryProgress(
                Varbits.DIARY_DESERT_EASY, 11,
                Varbits.DIARY_DESERT_MEDIUM, 12,
                Varbits.DIARY_DESERT_HARD, 10,
                Varbits.DIARY_DESERT_ELITE, 6));
        diaries.add("Falador", createDiaryProgress(
                Varbits.DIARY_FALADOR_EASY, 11,
                Varbits.DIARY_FALADOR_MEDIUM, 14,
                Varbits.DIARY_FALADOR_HARD, 11,
                Varbits.DIARY_FALADOR_ELITE, 6));
        diaries.add("Fremennik", createDiaryProgress(
                Varbits.DIARY_FREMENNIK_EASY, 10,
                Varbits.DIARY_FREMENNIK_MEDIUM, 9,
                Varbits.DIARY_FREMENNIK_HARD, 10,
                Varbits.DIARY_FREMENNIK_ELITE, 6));
        diaries.add("Kandarin", createDiaryProgress(
                Varbits.DIARY_KANDARIN_EASY, 11,
                Varbits.DIARY_KANDARIN_MEDIUM, 11,
                Varbits.DIARY_KANDARIN_HARD, 11,
                Varbits.DIARY_KANDARIN_ELITE, 7));
        diaries.add("Karamja", createDiaryProgress(
                Varbits.DIARY_KARAMJA_EASY, 10,
                Varbits.DIARY_KARAMJA_MEDIUM, 19,
                Varbits.DIARY_KARAMJA_HARD, 10,
                Varbits.DIARY_KARAMJA_ELITE, 5));
        diaries.add("Kourend", createDiaryProgress(
                Varbits.DIARY_KOUREND_EASY, 12,
                Varbits.DIARY_KOUREND_MEDIUM, 13,
                Varbits.DIARY_KOUREND_HARD, 10,
                Varbits.DIARY_KOUREND_ELITE, 8));
        diaries.add("Lumbridge", createDiaryProgress(
                Varbits.DIARY_LUMBRIDGE_EASY, 12,
                Varbits.DIARY_LUMBRIDGE_MEDIUM, 12,
                Varbits.DIARY_LUMBRIDGE_HARD, 11,
                Varbits.DIARY_LUMBRIDGE_ELITE, 6));
        diaries.add("Morytania", createDiaryProgress(
                Varbits.DIARY_MORYTANIA_EASY, 11,
                Varbits.DIARY_MORYTANIA_MEDIUM, 11,
                Varbits.DIARY_MORYTANIA_HARD, 11,
                Varbits.DIARY_MORYTANIA_ELITE, 6));
        diaries.add("Varrock", createDiaryProgress(
                Varbits.DIARY_VARROCK_EASY, 14,
                Varbits.DIARY_VARROCK_MEDIUM, 13,
                Varbits.DIARY_VARROCK_HARD, 10,
                Varbits.DIARY_VARROCK_ELITE, 5));
        diaries.add("Western", createDiaryProgress(
                Varbits.DIARY_WESTERN_EASY, 11,
                Varbits.DIARY_WESTERN_MEDIUM, 13,
                Varbits.DIARY_WESTERN_HARD, 13,
                Varbits.DIARY_WESTERN_ELITE, 7));
        diaries.add("Wilderness", createDiaryProgress(
                Varbits.DIARY_WILDERNESS_EASY, 12,
                Varbits.DIARY_WILDERNESS_MEDIUM, 12,
                Varbits.DIARY_WILDERNESS_HARD, 10,
                Varbits.DIARY_WILDERNESS_ELITE, 7));
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
        String filter = (args != null && args.has("filter")) ? args.get("filter").getAsString() : null;
        int minValue = (args != null && args.has("minValue")) ? args.get("minValue").getAsInt() : 0;

        if (bankContainer != null && bankContainer.getItems().length > 0) {
            result.addProperty("status", "success");
            result.addProperty("bankOpen", true);
            result.addProperty("cached", false);
            if (filter != null) {
                result.addProperty("filterApplied", filter);
            }
            result.add("items",
                    ItemContainerUtils.aggregateItemsWithPrices(client, itemManager, bankContainer, filter, minValue));
        } else {
            synchronized (this) {
                if (!cachedBankItems.isEmpty()) {
                    result.addProperty("status", "success");
                    result.addProperty("bankOpen", false);
                    result.addProperty("cached", true);
                    result.addProperty("cachedItemCount", cachedBankItems.size());
                    long ageSeconds = cachedBankTimestamp > 0
                            ? Math.max(0, (System.currentTimeMillis() - cachedBankTimestamp) / 1000)
                            : 0;
                    result.addProperty("cachedAgeSeconds", ageSeconds);
                    if (filter != null) {
                        result.addProperty("filterApplied", filter);
                    }
                    result.add("items",
                            ItemContainerUtils.aggregateItemsWithPrices(client, itemManager, cachedBankItems, filter,
                                    minValue));
                } else {
                    result.addProperty("status", "error");
                    result.addProperty("message",
                            "The bank is not currently open and no bank cache is available. Ask the player to open their bank if they want you to check bank items.");
                }
            }
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
        if (query.isEmpty()) {
            return WikiSearchUtil.executeWikiSearch(getWikiClient(), gson, query);
        }

        String cleanedQuery = WikiSearchUtil.extractSearchQuery(query).trim();
        String activeTaskName = getConfigValue("slayer", "taskName");

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
            int vale = client.getVarbitValue(VARBIT_VALE_RESEARCH_POINTS);
            points.addProperty("valeResearchPoints", vale);
        } catch (Exception ignored) {
        }

        try {
            JsonObject mta = new JsonObject();
            mta.addProperty("telekineticPizzazz", client.getVarbitValue(VARBIT_MTA_TELEKINETIC_PIZZAZZ));
            mta.addProperty("alchemistPizzazz", client.getVarbitValue(VARBIT_MTA_ALCHEMIST_PIZZAZZ));
            mta.addProperty("enchantingPizzazz", client.getVarbitValue(VARBIT_MTA_ENCHANTING_PIZZAZZ));
            mta.addProperty("graveyardPizzazz", client.getVarbitValue(VARBIT_MTA_GRAVEYARD_PIZZAZZ));
            points.add("mageTrainingArenaPizzazzPoints", mta);
        } catch (Exception ignored) {
        }

        try {
            JsonObject ba = new JsonObject();
            ba.addProperty("attackerPoints", client.getVarbitValue(VARBIT_BA_ATTACKER_POINTS));
            ba.addProperty("defenderPoints", client.getVarbitValue(VARBIT_BA_DEFENDER_POINTS));
            ba.addProperty("collectorPoints", client.getVarbitValue(VARBIT_BA_COLLECTOR_POINTS));
            ba.addProperty("healerPoints", client.getVarbitValue(VARBIT_BA_HEALER_POINTS));
            points.add("barbarianAssaultHonorPoints", ba);
        } catch (Exception ignored) {
        }

        try {
            int carp = client.getVarbitValue(VARBIT_CARPENTERS_POINTS);
            points.addProperty("carpentersPoints", carp);
        } catch (Exception ignored) {
        }

        try {
            int gf = client.getVarbitValue(VARBIT_GIANTS_FOUNDRY_REPUTATION);
            points.addProperty("giantsFoundryReputation", gf);
        } catch (Exception ignored) {
        }

        try {
            int vm = client.getVarbitValue(VARBIT_VOLCANIC_MINE_POINTS);
            points.addProperty("volcanicMinePoints", vm);
        } catch (Exception ignored) {
        }

        try {
            int lms = client.getVarbitValue(VARBIT_LMS_POINTS);
            points.addProperty("lmsPoints", lms);
        } catch (Exception ignored) {
        }

        try {
            int bh = client.getVarbitValue(VARBIT_BOUNTY_HUNTER_POINTS);
            points.addProperty("bountyHunterPoints", bh);
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

        Set<String> targetNames = Set.of(
                "mark of grace",
                "golden nugget",
                "abyssal pearl",
                "tokkul",
                "stardust",
                "archery ticket",
                "mermaid's tear",
                "hallowed mark",
                "molch pearl",
                "castle wars ticket",
                "agility arena ticket",
                "warrior guild token",
                "zeal token",
                "blessed bone shard",
                "sunfire splinter",
                "trading stick",
                "piece of eight",
                "spirit flakes");

        JsonObject itemCurrencies = new JsonObject();
        Map<String, Long> nameCounts = new LinkedHashMap<>();

        List<ItemContainer> containers = new ArrayList<>();
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv != null) {
            containers.add(inv);
        }
        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        if (bank != null) {
            containers.add(bank);
        }

        for (ItemContainer container : containers) {
            for (Item item : container.getItems()) {
                if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) {
                    continue;
                }
                ItemComposition rawComp = (itemManager != null) ? itemManager.getItemComposition(item.getId()) : null;
                if (rawComp != null && rawComp.getPlaceholderTemplateId() != -1) {
                    continue;
                }
                int canonicalId = (itemManager != null) ? itemManager.canonicalize(item.getId()) : item.getId();
                ItemComposition comp = (itemManager != null) ? itemManager.getItemComposition(canonicalId) : rawComp;
                String name = (comp != null && comp.getName() != null) ? comp.getName().trim() : "";
                if (name.isEmpty()) {
                    continue;
                }

                String lowerName = name.toLowerCase();
                for (String target : targetNames) {
                    if (lowerName.contains(target)) {
                        nameCounts.put(name, nameCounts.getOrDefault(name, 0L) + item.getQuantity());
                        break;
                    }
                }
            }
        }

        for (Map.Entry<String, Long> entry : nameCounts.entrySet()) {
            if (entry.getValue() > 0) {
                itemCurrencies.addProperty(entry.getKey(), entry.getValue());
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
        String spellbookName = Utilities.describeSpellbook(spellbookVal);
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
        pohObj.addProperty("portalChamberUnlocked", conLevel >= POH_LEVEL_PORTAL_CHAMBER);
        pohObj.addProperty("portalNexusUnlocked", conLevel >= POH_LEVEL_PORTAL_NEXUS);
        pohObj.addProperty("basicJewelleryBoxUnlocked", conLevel >= POH_LEVEL_BASIC_JEWELLERY_BOX);
        pohObj.addProperty("ornateJewelleryBoxUnlocked", conLevel >= POH_LEVEL_ORNATE_JEWELLERY_BOX);
        pohObj.addProperty("pohFairyRingUnlocked", conLevel >= POH_LEVEL_FAIRY_RING);
        pohObj.addProperty("pohSpiritTreeUnlocked", conLevel >= POH_LEVEL_SPIRIT_TREE);
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

        // Also check cached bank items if live bank is closed
        if (client.getItemContainer(InventoryID.BANK) == null && !cachedBankItems.isEmpty()) {
            for (ItemContainerUtils.SimpleItem item : cachedBankItems) {
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
                        itemObj.addProperty("location", "bank (cached)");
                        found.add(itemObj);
                        break;
                    }
                }
            }
        }
        return found;
    }

    String executeGetSurroundingEnvironment(JsonObject args) {
        JsonObject result = new JsonObject();
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            result.addProperty("status", "error");
            result.addProperty("message", "Player is not currently logged in.");
            return gson.toJson(result);
        }

        WorldPoint playerLoc = localPlayer.getWorldLocation();
        int radius = (args != null && args.has("radius") && !args.get("radius").isJsonNull())
                ? Math.min(MAX_SURROUNDINGS_SCAN_RADIUS,
                        Math.max(MIN_SURROUNDINGS_SCAN_RADIUS, args.get("radius").getAsInt()))
                : DEFAULT_SURROUNDINGS_SCAN_RADIUS;

        result.addProperty("status", "success");
        result.addProperty("playerLocation",
                playerLoc.getX() + ", " + playerLoc.getY() + ", Plane " + playerLoc.getPlane());
        result.addProperty("scanRadiusTiles", radius);

        WorldView wv = client.getTopLevelWorldView();

        // 1. Nearby NPCs & Monsters
        JsonArray npcList = new JsonArray();
        Iterable<? extends NPC> npcs = wv != null ? wv.npcs() : client.getNpcs();
        if (npcs != null) {
            List<NPC> sortedNpcs = new ArrayList<>();
            for (NPC npc : npcs) {
                if (npc == null || npc.getName() == null || npc.getName().trim().isEmpty()) {
                    continue;
                }
                WorldPoint npcLoc = npc.getWorldLocation();
                if (npcLoc != null && playerLoc.distanceTo(npcLoc) <= radius) {
                    sortedNpcs.add(npc);
                }
            }
            sortedNpcs.sort(Comparator.comparingInt(n -> playerLoc.distanceTo(n.getWorldLocation())));

            int count = 0;
            for (NPC npc : sortedNpcs) {
                if (count >= MAX_SURROUNDINGS_NPC_COUNT) {
                    break;
                }
                JsonObject obj = new JsonObject();
                obj.addProperty("name", npc.getName());
                obj.addProperty("id", npc.getId());
                obj.addProperty("combatLevel", npc.getCombatLevel());
                obj.addProperty("distance", playerLoc.distanceTo(npc.getWorldLocation()));
                if (npc.getHealthScale() > 0 && npc.getHealthRatio() >= 0) {
                    int healthPct = (int) Math.round(((double) npc.getHealthRatio() / npc.getHealthScale()) * 100.0);
                    obj.addProperty("healthPercent", healthPct);
                }
                if (npc.getInteracting() != null && npc.getInteracting().getName() != null) {
                    obj.addProperty("target", npc.getInteracting().getName());
                }
                if (npc.getAnimation() != -1) {
                    obj.addProperty("animating", true);
                }
                npcList.add(obj);
                count++;
            }
        }
        result.add("nearbyNpcs", npcList);

        // 2. Nearby Players (Wilderness / Threat Awareness)
        JsonArray playerList = new JsonArray();
        Iterable<? extends Player> players = wv != null ? wv.players() : client.getPlayers();
        if (players != null) {
            List<Player> sortedPlayers = new ArrayList<>();
            for (Player p : players) {
                if (p == null || p == localPlayer || p.getName() == null) {
                    continue;
                }
                WorldPoint pLoc = p.getWorldLocation();
                if (pLoc != null && playerLoc.distanceTo(pLoc) <= radius) {
                    sortedPlayers.add(p);
                }
            }
            sortedPlayers.sort(Comparator.comparingInt(p -> playerLoc.distanceTo(p.getWorldLocation())));

            int pCount = 0;
            for (Player p : sortedPlayers) {
                if (pCount >= MAX_SURROUNDINGS_PLAYER_COUNT) {
                    break;
                }
                JsonObject pObj = new JsonObject();
                pObj.addProperty("name", p.getName());
                pObj.addProperty("combatLevel", p.getCombatLevel());
                pObj.addProperty("distance", playerLoc.distanceTo(p.getWorldLocation()));
                pObj.addProperty("skulled", p.getSkullIcon() != -1);
                if (p.getInteracting() != null && p.getInteracting().getName() != null) {
                    pObj.addProperty("interactingWith", p.getInteracting().getName());
                }
                playerList.add(pObj);
                pCount++;
            }
        }
        result.add("nearbyPlayers", playerList);

        // 3. Ground Items in render distance
        JsonArray groundItemList = new JsonArray();
        Scene scene = (wv != null && wv.getScene() != null) ? wv.getScene() : client.getScene();
        if (scene != null && scene.getTiles() != null) {
            int plane = playerLoc.getPlane();
            Tile[][][] tiles = scene.getTiles();
            if (plane >= 0 && plane < tiles.length && tiles[plane] != null) {
                LocalPoint localPoint = localPlayer.getLocalLocation();
                if (localPoint != null) {
                    int centerTileX = localPoint.getSceneX();
                    int centerTileY = localPoint.getSceneY();
                    int minX = Math.max(0, centerTileX - radius);
                    int maxX = Math.min(tiles[plane].length - 1, centerTileX + radius);
                    int minY = Math.max(0, centerTileY - radius);
                    int maxY = Math.min(tiles[plane][0].length - 1, centerTileY + radius);

                    List<JsonObject> groundItemsFound = new ArrayList<>();
                    for (int x = minX; x <= maxX; x++) {
                        for (int y = minY; y <= maxY; y++) {
                            Tile tile = tiles[plane][x][y];
                            if (tile == null) {
                                continue;
                            }
                            List<TileItem> items = tile.getGroundItems();
                            if (items != null) {
                                for (TileItem item : items) {
                                    if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) {
                                        continue;
                                    }
                                    ItemComposition comp = null;
                                    try {
                                        comp = itemManager.getItemComposition(item.getId());
                                    } catch (Exception ignored) {
                                    }
                                    String itemName = (comp != null && comp.getName() != null) ? comp.getName()
                                            : "Item " + item.getId();
                                    int gePrice = itemManager != null ? itemManager.getItemPrice(item.getId()) : 0;
                                    int haPrice = comp != null ? comp.getHaPrice() : 0;
                                    int dist = Math.max(Math.abs(x - centerTileX), Math.abs(y - centerTileY));

                                    JsonObject gObj = new JsonObject();
                                    gObj.addProperty("name", itemName);
                                    gObj.addProperty("id", item.getId());
                                    gObj.addProperty("quantity", item.getQuantity());
                                    gObj.addProperty("gePrice", gePrice);
                                    gObj.addProperty("haPrice", haPrice);
                                    gObj.addProperty("distance", dist);
                                    groundItemsFound.add(gObj);
                                }
                            }
                        }
                    }
                    groundItemsFound.sort((a, b) -> {
                        int valA = Math.max(a.get("gePrice").getAsInt(), a.get("haPrice").getAsInt())
                                * a.get("quantity").getAsInt();
                        int valB = Math.max(b.get("gePrice").getAsInt(), b.get("haPrice").getAsInt())
                                * b.get("quantity").getAsInt();
                        return Integer.compare(valB, valA);
                    });
                    int giCount = 0;
                    for (JsonObject gObj : groundItemsFound) {
                        if (giCount >= MAX_SURROUNDINGS_GROUND_ITEM_COUNT) {
                            break;
                        }
                        groundItemList.add(gObj);
                        giCount++;
                    }
                }
            }
        }
        result.add("nearbyGroundItems", groundItemList);

        // 4. Notable Nearby Game Objects
        JsonArray objectList = new JsonArray();
        if (scene != null && scene.getTiles() != null) {
            int plane = playerLoc.getPlane();
            Tile[][][] tiles = scene.getTiles();
            if (plane >= 0 && plane < tiles.length && tiles[plane] != null) {
                LocalPoint localPoint = localPlayer.getLocalLocation();
                if (localPoint != null) {
                    int centerTileX = localPoint.getSceneX();
                    int centerTileY = localPoint.getSceneY();
                    int minX = Math.max(0, centerTileX - Math.min(OBJECT_SCAN_MAX_RADIUS, radius));
                    int maxX = Math.min(tiles[plane].length - 1,
                            centerTileX + Math.min(OBJECT_SCAN_MAX_RADIUS, radius));
                    int minY = Math.max(0, centerTileY - Math.min(OBJECT_SCAN_MAX_RADIUS, radius));
                    int maxY = Math.min(tiles[plane][0].length - 1,
                            centerTileY + Math.min(OBJECT_SCAN_MAX_RADIUS, radius));

                    Set<String> seenObjects = new HashSet<>();
                    for (int x = minX; x <= maxX; x++) {
                        for (int y = minY; y <= maxY; y++) {
                            Tile tile = tiles[plane][x][y];
                            if (tile == null) {
                                continue;
                            }
                            GameObject[] gameObjs = tile.getGameObjects();
                            if (gameObjs != null) {
                                for (GameObject go : gameObjs) {
                                    if (go == null) {
                                        continue;
                                    }
                                    try {
                                        ObjectComposition oc = client.getObjectDefinition(go.getId());
                                        if (oc != null && oc.getName() != null && !oc.getName().trim().isEmpty()
                                                && !"null".equalsIgnoreCase(oc.getName())) {
                                            String name = oc.getName();
                                            String lower = name.toLowerCase();
                                            if (lower.contains("altar") || lower.contains("bank")
                                                    || lower.contains("booth")
                                                    || lower.contains("chest") || lower.contains("portal")
                                                    || lower.contains("fairy ring")
                                                    || lower.contains("furnace") || lower.contains("anvil")
                                                    || lower.contains("range")
                                                    || lower.contains("ladder") || lower.contains("trapdoor")
                                                    || lower.contains("stairs")
                                                    || lower.contains("shortcut") || lower.contains("tree")
                                                    || lower.contains("crevice")
                                                    || lower.contains("barrier") || lower.contains("entrance")
                                                    || lower.contains("tunnel")) {
                                                if (seenObjects.add(name)) {
                                                    int dist = Math.max(Math.abs(x - centerTileX),
                                                            Math.abs(y - centerTileY));
                                                    JsonObject oObj = new JsonObject();
                                                    oObj.addProperty("name", name);
                                                    oObj.addProperty("distance", dist);
                                                    objectList.add(oObj);
                                                }
                                            }
                                        }
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        result.add("nearbyNotableObjects", objectList);

        return gson.toJson(result);
    }

    String executeGetPlayerGeOffers(JsonObject args) {
        JsonObject result = new JsonObject();
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null || offers.length == 0) {
            result.addProperty("status", "empty");
            result.addProperty("message", "No Grand Exchange offer data available.");
            return gson.toJson(result);
        }

        result.addProperty("status", "success");
        JsonArray offerList = new JsonArray();
        int activeCount = 0;

        for (int i = 0; i < offers.length; i++) {
            GrandExchangeOffer offer = offers[i];
            if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("slot", i + 1);
            obj.addProperty("state", offer.getState().name());

            int itemId = offer.getItemId();
            obj.addProperty("itemId", itemId);
            String itemName = "Item " + itemId;
            if (itemManager != null) {
                try {
                    ItemComposition comp = itemManager.getItemComposition(itemId);
                    if (comp != null && comp.getName() != null) {
                        itemName = comp.getName();
                    }
                } catch (Exception ignored) {
                }
            }
            obj.addProperty("itemName", itemName);
            obj.addProperty("offerPrice", offer.getPrice());
            obj.addProperty("totalQuantity", offer.getTotalQuantity());
            obj.addProperty("transferredQuantity", offer.getQuantitySold());
            obj.addProperty("spentOrReceivedGp", offer.getSpent());

            int total = offer.getTotalQuantity();
            int transferred = offer.getQuantitySold();
            int pct = total > 0 ? (int) Math.round(((double) transferred / total) * 100.0) : 0;
            obj.addProperty("progressPercent", pct);

            offerList.add(obj);
            activeCount++;
        }

        result.addProperty("activeOrCompletedOffersCount", activeCount);
        result.add("offers", offerList);
        return gson.toJson(result);
    }

    String executeGetMarketPrices(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject itemsData = new JsonObject();

        int natureRunePrice = 0;
        try {
            natureRunePrice = itemManager != null ? itemManager.getItemPrice(ITEM_ID_NATURE_RUNE)
                    : DEFAULT_NATURE_RUNE_PRICE;
        } catch (Exception ignored) {
        }
        if (natureRunePrice <= 0) {
            natureRunePrice = DEFAULT_NATURE_RUNE_PRICE;
        }
        result.addProperty("natureRuneCost", natureRunePrice);

        List<Integer> targetItemIds = new ArrayList<>();
        if (args != null) {
            if (args.has("itemIds")) {
                JsonArray ids = args.getAsJsonArray("itemIds");
                for (int i = 0; i < ids.size(); i++) {
                    targetItemIds.add(ids.get(i).getAsInt());
                }
            }
            if (args.has("itemNames")) {
                JsonArray names = args.getAsJsonArray("itemNames");
                for (int i = 0; i < names.size(); i++) {
                    String name = names.get(i).getAsString();
                    Integer foundId = ItemContainerUtils.findItemIdInContainers(client, itemManager, name);
                    if (foundId != null) {
                        targetItemIds.add(foundId);
                    } else {
                        JsonObject notFound = new JsonObject();
                        notFound.addProperty("error", "Item '" + name + "' not found in game database.");
                        itemsData.add(name, notFound);
                    }
                }
            }
        }

        for (int itemId : targetItemIds) {
            ItemComposition comp = null;
            try {
                comp = itemManager != null ? itemManager.getItemComposition(itemId) : null;
            } catch (Exception ignored) {
            }
            String itemName = (comp != null && comp.getName() != null) ? comp.getName() : "Item " + itemId;
            int gePrice = itemManager != null ? itemManager.getItemPrice(itemId) : 0;
            int haPrice = comp != null ? comp.getHaPrice() : 0;
            int lowAlchPrice = comp != null ? (int) Math.floor(haPrice * LOW_ALCH_MULTIPLIER) : 0;
            int alchProfit = (haPrice > 0 && gePrice > 0) ? (haPrice - (gePrice + natureRunePrice)) : 0;

            JsonObject itemObj = new JsonObject();
            itemObj.addProperty("itemId", itemId);
            itemObj.addProperty("itemName", itemName);
            itemObj.addProperty("gePrice", gePrice);
            itemObj.addProperty("highAlchValue", haPrice);
            itemObj.addProperty("lowAlchValue", lowAlchPrice);
            itemObj.addProperty("highAlchProfitPerItem", alchProfit);
            itemObj.addProperty("isAlchProfitable", alchProfit > 0);

            itemsData.add(itemName, itemObj);
        }

        result.add("items", itemsData);
        return gson.toJson(result);
    }

    String executeGetPlayerFarmingAndTimers(JsonObject args) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "success");

        long nowSec = System.currentTimeMillis() / 1000L;

        // 1. Birdhouse run states (Fossil Island - live varbits + Time Tracking cache)
        JsonObject birdhouses = new JsonObject();
        int bh1 = client.getVarbitValue(VARBIT_BIRDHOUSE_MEADOW_NORTH);
        int bh2 = client.getVarbitValue(VARBIT_BIRDHOUSE_MEADOW_SOUTH);
        int bh3 = client.getVarbitValue(VARBIT_BIRDHOUSE_VALLEY_NORTH);
        int bh4 = client.getVarbitValue(VARBIT_BIRDHOUSE_VALLEY_SOUTH);

        String bhVal1 = getConfigValue("timetracking", "birdhouse.1626");
        String bhVal2 = getConfigValue("timetracking", "birdhouse.1627");
        String bhVal3 = getConfigValue("timetracking", "birdhouse.1628");
        String bhVal4 = getConfigValue("timetracking", "birdhouse.1629");

        long bhTime1 = parseTimestampOrDuration(bhVal1);
        long bhTime2 = parseTimestampOrDuration(bhVal2);
        long bhTime3 = parseTimestampOrDuration(bhVal3);
        long bhTime4 = parseTimestampOrDuration(bhVal4);

        boolean bh1Ready = (bh1 >= 3) || (bhVal1 != null
                && (bhVal1.contains("done") || bhVal1.contains("ready") || (bhTime1 > 0 && bhTime1 <= nowSec)));
        boolean bh2Ready = (bh2 >= 3) || (bhVal2 != null
                && (bhVal2.contains("done") || bhVal2.contains("ready") || (bhTime2 > 0 && bhTime2 <= nowSec)));
        boolean bh3Ready = (bh3 >= 3) || (bhVal3 != null
                && (bhVal3.contains("done") || bhVal3.contains("ready") || (bhTime3 > 0 && bhTime3 <= nowSec)));
        boolean bh4Ready = (bh4 >= 3) || (bhVal4 != null
                && (bhVal4.contains("done") || bhVal4.contains("ready") || (bhTime4 > 0 && bhTime4 <= nowSec)));

        int readyBhCount = (bh1Ready ? 1 : 0) + (bh2Ready ? 1 : 0) + (bh3Ready ? 1 : 0) + (bh4Ready ? 1 : 0);
        int activeBhCount = ((bh1 > 0 || bhTime1 > 0) ? 1 : 0) + ((bh2 > 0 || bhTime2 > 0) ? 1 : 0)
                + ((bh3 > 0 || bhTime3 > 0) ? 1 : 0) + ((bh4 > 0 || bhTime4 > 0) ? 1 : 0);

        birdhouses.addProperty("meadowNorth", bh1Ready ? "Done / Ready to harvest"
                : (bhTime1 > nowSec ? "Growing (" + Math.max(1, (bhTime1 - nowSec) / 60) + "m remaining)"
                        : "Empty / not built"));
        birdhouses.addProperty("meadowSouth", bh2Ready ? "Done / Ready to harvest"
                : (bhTime2 > nowSec ? "Growing (" + Math.max(1, (bhTime2 - nowSec) / 60) + "m remaining)"
                        : "Empty / not built"));
        birdhouses.addProperty("valleyNorth", bh3Ready ? "Done / Ready to harvest"
                : (bhTime3 > nowSec ? "Growing (" + Math.max(1, (bhTime3 - nowSec) / 60) + "m remaining)"
                        : "Empty / not built"));
        birdhouses.addProperty("valleySouth", bh4Ready ? "Done / Ready to harvest"
                : (bhTime4 > nowSec ? "Growing (" + Math.max(1, (bhTime4 - nowSec) / 60) + "m remaining)"
                        : "Empty / not built"));
        birdhouses.addProperty("readyCount", readyBhCount);
        birdhouses.addProperty("ready", readyBhCount > 0);
        if (readyBhCount == 4) {
            birdhouses.addProperty("summary",
                    "All 4 birdhouse traps are full and ready to harvest (Mushroom Meadow North/South, Verdant Valley Northeast/Southwest)");
        } else if (readyBhCount > 0) {
            birdhouses.addProperty("summary", readyBhCount + " of 4 birdhouse traps are ready to harvest");
        } else if (activeBhCount > 0) {
            birdhouses.addProperty("summary", activeBhCount + " birdhouses are currently catching birds");
        } else {
            birdhouses.addProperty("summary", "Empty / not built");
        }
        result.add("birdhouses", birdhouses);

        // 2. Hespori Boss Growth
        JsonObject hespori = new JsonObject();
        int hesporiVar = client.getVarbitValue(VARBIT_HESPORI_GROWTH);
        hespori.addProperty("stateVarbit", hesporiVar);
        hespori.addProperty("status", hesporiVar >= HESPORI_STAGE_READY ? "Fully Grown / Ready to fight"
                : (hesporiVar > 0 ? "Growing" : "Empty / Cleared"));
        result.add("hespori", hespori);

        // 3. Tears of Guthix Cooldown
        JsonObject tog = new JsonObject();
        int togCooldown = client.getVarpValue(VARP_TEARS_OF_GUTHIX_COOLDOWN);
        tog.addProperty("cooldownVarp", togCooldown);
        tog.addProperty("ready", togCooldown <= 0);
        result.add("tearsOfGuthix", tog);

        // 4. Kingdom of Miscellania
        JsonObject kingdom = new JsonObject();
        int rawFavour = client.getVarpValue(VARP_KINGDOM_FAVOUR);
        int rawCoffer = client.getVarpValue(VARP_KINGDOM_COFFER);

        Integer cachedFavour = null;
        Integer cachedCoffer = null;
        Instant lastChangedInstant = null;

        if (configManager != null) {
            String profileKey = configManager.getRSProfileKey();
            try {
                Object lastChangedObj = configManager.getRSProfileConfiguration("kingdomofmiscellania", "lastChanged",
                        Instant.class);
                if (lastChangedObj instanceof Instant) {
                    lastChangedInstant = (Instant) lastChangedObj;
                }
            } catch (Throwable ignored) {
            }

            String[] kingdomGroups = new String[] { "kingdomofmiscellania", "kingdom", "miscellania",
                    "dailytaskindicators", "dailytasks" };
            for (String group : kingdomGroups) {
                if (cachedFavour == null) {
                    String val = getConfigValue(group, "approval", "lastApproval", "favor", "favour", "lastFavor",
                            "lastFavour", "approvalPercent");
                    if (val != null) {
                        try {
                            cachedFavour = Integer.parseInt(val.replaceAll("[^0-9]", ""));
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (cachedCoffer == null) {
                    String val = getConfigValue(group, "coffer", "lastCoffer", "coffers", "lastCoffers",
                            "cofferAmount");
                    if (val != null) {
                        try {
                            cachedCoffer = Integer.parseInt(val.replaceAll("[^0-9]", ""));
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (lastChangedInstant == null) {
                    String lastChangedStr = getConfigValue(group, "lastChanged", "lastCheck", "lastVisit",
                            "lastTimestamp");
                    if (lastChangedStr != null && !lastChangedStr.isEmpty()) {
                        try {
                            lastChangedInstant = Instant.parse(lastChangedStr.trim());
                        } catch (Exception e1) {
                            try {
                                long epoch = Long.parseLong(lastChangedStr.replaceAll("[^0-9]", ""));
                                if (epoch > 1_000_000_000_000L) {
                                    lastChangedInstant = Instant.ofEpochMilli(epoch);
                                } else if (epoch > 1_000_000_000L) {
                                    lastChangedInstant = Instant.ofEpochSecond(epoch);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                if (profileKey != null
                        && (cachedFavour == null || cachedCoffer == null || lastChangedInstant == null)) {
                    List<String> keys = configManager.getRSProfileConfigurationKeys(group, profileKey, "");
                    if (keys != null) {
                        for (String key : keys) {
                            String lower = key.toLowerCase();
                            String val = configManager.getRSProfileConfiguration(group, key);
                            if (val != null && !val.isEmpty()) {
                                if ((lower.contains("approval") || lower.contains("favor") || lower.contains("favour"))
                                        && cachedFavour == null) {
                                    try {
                                        cachedFavour = Integer.parseInt(val.replaceAll("[^0-9]", ""));
                                    } catch (Exception ignored) {
                                    }
                                } else if (lower.contains("coffer") && cachedCoffer == null) {
                                    try {
                                        cachedCoffer = Integer.parseInt(val.replaceAll("[^0-9]", ""));
                                    } catch (Exception ignored) {
                                    }
                                } else if (lower.contains("lastchanged") && lastChangedInstant == null) {
                                    try {
                                        lastChangedInstant = Instant.parse(val.trim());
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        boolean royalTroubleCompleted = (getQuestStateSafe(Quest.ROYAL_TROUBLE) == QuestState.FINISHED);
        int daysPassed = 0;
        if (lastChangedInstant != null) {
            try {
                Instant truncatedLast = lastChangedInstant.truncatedTo(ChronoUnit.DAYS);
                Instant truncatedNow = Instant.now().truncatedTo(ChronoUnit.DAYS);
                daysPassed = Math.max(0, (int) ChronoUnit.DAYS.between(truncatedLast, truncatedNow));
            } catch (Exception ignored) {
            }
        }

        int finalFavour;
        int finalCoffer;
        String source;

        if (rawFavour > 0 || rawCoffer > 0) {
            finalFavour = rawFavour;
            finalCoffer = rawCoffer;
            source = "live (in Miscellania)";
        } else if (cachedFavour != null || cachedCoffer != null) {
            int estimatedApproval = cachedFavour != null ? cachedFavour : 0;
            int estimatedCoffer = cachedCoffer != null ? cachedCoffer : 0;

            if (daysPassed > 0) {
                int maxWithdrawal = royalTroubleCompleted ? 75000 : 50000;
                int threshold = maxWithdrawal * 10;
                for (int i = 0; i < daysPassed; i++) {
                    int withdrawal = (estimatedCoffer > threshold) ? maxWithdrawal : (estimatedCoffer / 10);
                    estimatedCoffer = Math.max(0, estimatedCoffer - withdrawal);
                }

                float decrement = royalTroubleCompleted ? 0.01f : 0.025f;
                int reduction = (int) (daysPassed * decrement * 127.0f);
                estimatedApproval = Math.max(0, estimatedApproval - reduction);
            }

            finalFavour = estimatedApproval;
            finalCoffer = estimatedCoffer;
            source = "cached & estimated (RuneLite Kingdom tracker, " + daysPassed + " day"
                    + (daysPassed == 1 ? "" : "s") + " elapsed)";
        } else {
            finalFavour = 0;
            finalCoffer = 0;
            source = "unavailable (not yet recorded by client)";
        }

        int favourPct = 0;
        if (finalFavour > 0) {
            favourPct = Math.min(100, Math.max(0, (finalFavour * 100) / 127));
        }

        kingdom.addProperty("favourPercent", favourPct);
        kingdom.addProperty("cofferGp", finalCoffer);
        kingdom.addProperty("cofferFormatted",
                String.format(Locale.US, "%,d gp (%.2fM)", finalCoffer, finalCoffer / 1_000_000.0));
        kingdom.addProperty("daysSinceLastVisit", daysPassed);
        if (lastChangedInstant != null) {
            kingdom.addProperty("lastRecordedTime", lastChangedInstant.toString());
        }
        kingdom.addProperty("royalTroubleCompleted", royalTroubleCompleted);
        kingdom.addProperty("source", source);
        if (finalFavour == 0 && finalCoffer == 0 && (cachedFavour == null && cachedCoffer == null)) {
            kingdom.addProperty("note",
                    "Kingdom data is recorded by RuneLite when visiting Miscellania or opening the kingdom management interface.");
        } else {
            kingdom.addProperty("summary", String.format(Locale.US, "Approval: %d%%, Coffer: %,d gp (%.2fM) [%s]",
                    favourPct, finalCoffer, finalCoffer / 1_000_000.0, source));
        }
        result.add("kingdomOfMiscellania", kingdom);

        // 5. Daily Task Collectibles (Zaff Battlestaves, etc.)
        JsonObject dailyTasks = new JsonObject();
        int varrockDiaryEasy = client.getVarbitValue(Varbits.DIARY_VARROCK_EASY);
        int varrockDiaryMed = client.getVarbitValue(Varbits.DIARY_VARROCK_MEDIUM);
        int varrockDiaryHard = client.getVarbitValue(Varbits.DIARY_VARROCK_HARD);
        int varrockDiaryElite = client.getVarbitValue(Varbits.DIARY_VARROCK_ELITE);

        int zaffDailyCount = 0;
        if (varrockDiaryElite >= 5) {
            zaffDailyCount = 120;
        } else if (varrockDiaryHard >= 10) {
            zaffDailyCount = 60;
        } else if (varrockDiaryMed >= 13) {
            zaffDailyCount = 30;
        } else if (varrockDiaryEasy >= 14) {
            zaffDailyCount = 15;
        }

        if (zaffDailyCount > 0) {
            JsonObject zaff = new JsonObject();
            zaff.addProperty("discountBattlestavesAvailablePerDay", zaffDailyCount);
            zaff.addProperty("location", "Zaff's staff shop in Varrock center (north-west corner of square)");
            zaff.addProperty("note", "Buy for 7,000 gp each and craft/alch or resell for profit.");
            dailyTasks.add("zaffBattlestaves", zaff);
        }
        result.add("dailyTaskCollectibles", dailyTasks);

        // 6. Active InfoBox Timers & Boosts (Filter out empty/zero template indicators)
        JsonArray infoBoxList = new JsonArray();
        if (infoBoxManager != null && infoBoxManager.getInfoBoxes() != null) {
            for (InfoBox ib : infoBoxManager.getInfoBoxes()) {
                if (ib == null) {
                    continue;
                }
                String text = ib.getText();
                String tooltip = ib.getTooltip();
                if (text == null || text.trim().isEmpty() || "0".equals(text.trim()) || "?".equals(text.trim())
                        || "-1".equals(text.trim()) || "0/0".equals(text.trim())) {
                    continue;
                }
                JsonObject ibObj = new JsonObject();
                if (ib.getName() != null) {
                    ibObj.addProperty("name", ib.getName());
                }
                ibObj.addProperty("text", text.trim());
                if (tooltip != null && !tooltip.trim().isEmpty()) {
                    String cleanTooltip = PATTERN_HTML_TAGS.matcher(tooltip).replaceAll("").trim();
                    if (!cleanTooltip.isEmpty()) {
                        ibObj.addProperty("tooltip", cleanTooltip);
                    }
                }
                infoBoxList.add(ibObj);
            }
        }
        if (infoBoxList.size() > 0) {
            result.add("activeInfoBoxesAndTimers", infoBoxList);
        }

        // 7. RuneLite Time Tracking Plugin Patches (Herbs, Trees, Fruit Trees, Hespori,
        // Hardwood, etc.)
        JsonObject farmingPatches = new JsonObject();
        JsonArray readyHerbs = new JsonArray();
        JsonArray growingHerbs = new JsonArray();
        JsonArray readyHardwoodTrees = new JsonArray();
        JsonArray growingHardwoodTrees = new JsonArray();
        JsonArray readyTrees = new JsonArray();
        JsonArray growingTrees = new JsonArray();
        JsonArray readyFruitTrees = new JsonArray();
        JsonArray growingFruitTrees = new JsonArray();
        JsonArray specialPatches = new JsonArray();
        Boolean cachedHesporiReady = null;

        if (configManager != null) {
            for (FarmingPatchDef patchDef : getKnownFarmingPatches()) {
                String val = getConfigValue("timetracking", patchDef.configKey);
                if (val == null || val.isEmpty()) {
                    val = getConfigValue("farmingtracker", patchDef.configKey);
                }
                if (val == null || val.trim().isEmpty() || "0".equals(val.trim())) {
                    continue;
                }

                JsonObject patchObj = parseFarmingPatchState(patchDef, val, nowSec);
                if (patchObj == null) {
                    continue;
                }

                String patchType = patchDef.patchType;
                boolean isReady = patchObj.has("ready") && patchObj.get("ready").getAsBoolean();
                String produce = patchObj.has("produce") ? patchObj.get("produce").getAsString() : patchType;
                String loc = patchDef.locationName;
                String statusStr = patchObj.has("status") ? patchObj.get("status").getAsString() : "";
                String displaySummary = loc + " (" + produce
                        + (statusStr.contains("Check health") ? " - Check health ready" : "") + ")";

                if ("Hespori".equalsIgnoreCase(patchType)) {
                    cachedHesporiReady = isReady;
                    specialPatches.add(patchObj);
                } else if ("Herb".equalsIgnoreCase(patchType)) {
                    if (isReady) {
                        readyHerbs.add(displaySummary);
                    } else {
                        growingHerbs.add(patchObj);
                    }
                } else if ("Hardwood Tree".equalsIgnoreCase(patchType)) {
                    if (isReady) {
                        readyHardwoodTrees.add(displaySummary);
                    } else {
                        growingHardwoodTrees.add(patchObj);
                    }
                } else if ("Fruit Tree".equalsIgnoreCase(patchType)) {
                    if (isReady) {
                        readyFruitTrees.add(displaySummary);
                    } else {
                        growingFruitTrees.add(patchObj);
                    }
                } else if ("Tree".equalsIgnoreCase(patchType)) {
                    if (isReady) {
                        readyTrees.add(displaySummary);
                    } else {
                        growingTrees.add(patchObj);
                    }
                } else {
                    specialPatches.add(patchObj);
                }
            }
        }

        // Re-evaluate Hespori with cached status if varbit was 0 outside Farming Guild
        if (cachedHesporiReady != null) {
            hespori.addProperty("ready", cachedHesporiReady);
            hespori.addProperty("status", cachedHesporiReady ? "Fully Grown / Ready to fight" : "Growing");
            hespori.addProperty("source", "Time Tracking plugin cache");
        }

        farmingPatches.add("readyHerbPatches", readyHerbs);
        if (growingHerbs.size() > 0) {
            farmingPatches.add("growingHerbPatches", growingHerbs);
        }
        if (readyHardwoodTrees.size() > 0) {
            farmingPatches.add("readyHardwoodTrees", readyHardwoodTrees);
        }
        if (growingHardwoodTrees.size() > 0) {
            farmingPatches.add("growingHardwoodTrees", growingHardwoodTrees);
        }
        if (readyTrees.size() > 0) {
            farmingPatches.add("readyTreePatches", readyTrees);
        }
        if (growingTrees.size() > 0) {
            farmingPatches.add("growingTreePatches", growingTrees);
        }
        if (readyFruitTrees.size() > 0) {
            farmingPatches.add("readyFruitTreePatches", readyFruitTrees);
        }
        if (growingFruitTrees.size() > 0) {
            farmingPatches.add("growingFruitTreePatches", growingFruitTrees);
        }
        if (specialPatches.size() > 0) {
            farmingPatches.add("specialPatches", specialPatches);
        }
        result.add("farmingPatchesAndCrops", farmingPatches);

        return gson.toJson(result);
    }

    private static class FarmingPatchDef {
        final String configKey;
        final String locationName;
        final String patchType;
        final Object farmingPatch;
        final Object patchImplementation;
        final boolean healthCheckRequired;

        FarmingPatchDef(String configKey, String locationName, String patchType, Object farmingPatch,
                Object patchImplementation, boolean healthCheckRequired) {
            this.configKey = configKey;
            this.locationName = locationName;
            this.patchType = patchType;
            this.farmingPatch = farmingPatch;
            this.patchImplementation = patchImplementation;
            this.healthCheckRequired = healthCheckRequired;
        }
    }

    private static List<FarmingPatchDef> cachedFarmingPatches = null;
    private Object farmingTracker = null;
    private Method farmingTrackerPredictMethod = null;

    private synchronized Object getFarmingTracker() {
        if (farmingTracker == null && configManager != null) {
            try {
                Class<?> ftClass = Class.forName("net.runelite.client.plugins.timetracking.farming.FarmingTracker");
                Class<?> fwClass = Class.forName("net.runelite.client.plugins.timetracking.farming.FarmingWorld");
                java.lang.reflect.Constructor<?> fwCtor = fwClass.getDeclaredConstructor();
                fwCtor.setAccessible(true);
                Object fw = fwCtor.newInstance();

                java.lang.reflect.Constructor<?>[] ctors = ftClass.getDeclaredConstructors();
                java.lang.reflect.Constructor<?> ftCtor = ctors[0];
                ftCtor.setAccessible(true);
                Class<?>[] paramTypes = ftCtor.getParameterTypes();
                Object[] args = new Object[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) {
                    if (paramTypes[i].equals(Client.class) || paramTypes[i].getName().endsWith(".Client")) {
                        args[i] = client;
                    } else if (paramTypes[i].equals(net.runelite.client.config.ConfigManager.class)
                            || paramTypes[i].getName().endsWith(".ConfigManager")) {
                        args[i] = configManager;
                    } else if (paramTypes[i].equals(fwClass)) {
                        args[i] = fw;
                    } else {
                        args[i] = null;
                    }
                }
                farmingTracker = ftCtor.newInstance(args);

                farmingTrackerPredictMethod = ftClass.getDeclaredMethod("predictPatch",
                        Class.forName("net.runelite.client.plugins.timetracking.farming.FarmingPatch"), String.class);
                farmingTrackerPredictMethod.setAccessible(true);
            } catch (Throwable t) {
                log.debug("Could not initialize direct FarmingTracker reflection: {}", t.getMessage());
            }
        }
        return farmingTracker;
    }

    private static synchronized List<FarmingPatchDef> getKnownFarmingPatches() {
        if (cachedFarmingPatches != null) {
            return cachedFarmingPatches;
        }
        List<FarmingPatchDef> list = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        try {
            Class<?> fwClass = Class.forName("net.runelite.client.plugins.timetracking.farming.FarmingWorld");
            java.lang.reflect.Constructor<?> ctor = fwClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object fw = ctor.newInstance();
            Field regionsField = fwClass.getDeclaredField("regions");
            regionsField.setAccessible(true);
            com.google.common.collect.Multimap<?, ?> regions = (com.google.common.collect.Multimap<?, ?>) regionsField
                    .get(fw);

            Class<?> regionClass = Class.forName("net.runelite.client.plugins.timetracking.farming.FarmingRegion");
            Method getName = regionClass.getDeclaredMethod("getName");
            getName.setAccessible(true);
            Method getPatches = regionClass.getDeclaredMethod("getPatches");
            getPatches.setAccessible(true);

            Class<?> patchClass = Class.forName("net.runelite.client.plugins.timetracking.farming.FarmingPatch");
            Method getImplementation = patchClass.getDeclaredMethod("getImplementation");
            getImplementation.setAccessible(true);
            Method configKeyMethod = patchClass.getDeclaredMethod("configKey");
            configKeyMethod.setAccessible(true);

            Class<?> piClass = Class.forName("net.runelite.client.plugins.timetracking.farming.PatchImplementation");
            Method isHealthCheckRequiredMethod = piClass.getDeclaredMethod("isHealthCheckRequired");
            isHealthCheckRequiredMethod.setAccessible(true);

            for (Object region : regions.values()) {
                String rName = (String) getName.invoke(region);
                Object[] patches = (Object[]) getPatches.invoke(region);
                for (Object patch : patches) {
                    Enum<?> imp = (Enum<?>) getImplementation.invoke(patch);
                    String cKey = (String) configKeyMethod.invoke(patch);
                    if (cKey == null || seenKeys.contains(cKey)) {
                        continue;
                    }
                    seenKeys.add(cKey);

                    boolean healthCheck = (Boolean) isHealthCheckRequiredMethod.invoke(imp);
                    String pType = formatPatchTypeName(imp.name());
                    list.add(new FarmingPatchDef(cKey, rName, pType, patch, imp, healthCheck));
                }
            }
        } catch (Throwable t) {
            // Fallback list of key patches if reflection fails
        }
        cachedFarmingPatches = list;
        return cachedFarmingPatches;
    }

    private static String formatPatchTypeName(String enumName) {
        if (enumName == null)
            return "General";
        switch (enumName) {
            case "HERB":
                return "Herb";
            case "HARDWOOD_TREE":
                return "Hardwood Tree";
            case "FRUIT_TREE":
                return "Fruit Tree";
            case "TREE":
                return "Tree";
            case "SEAWEED":
                return "Seaweed";
            case "HESPORI":
                return "Hespori";
            case "REDWOOD":
                return "Redwood";
            case "CALQUAT":
                return "Calquat";
            case "CELASTRUS":
                return "Celastrus";
            case "SPIRIT_TREE":
                return "Spirit Tree";
            case "BUSH":
                return "Bush";
            case "CACTUS":
                return "Cactus";
            case "BELLADONNA":
                return "Belladonna";
            case "MUSHROOM":
                return "Mushroom";
            case "ALLOTMENT":
                return "Allotment";
            case "FLOWER":
                return "Flower";
            case "HOPS":
                return "Hops";
            case "GRAPES":
                return "Grapes";
            case "CRYSTAL_TREE":
                return "Crystal Tree";
            case "ANIMA":
                return "Anima";
            default:
                return enumName;
        }
    }

    private JsonObject parseFarmingPatchState(FarmingPatchDef patchDef, String val, long nowSec) {
        if (patchDef == null || val == null || val.trim().isEmpty()) {
            return null;
        }

        // 1. Try RuneLite's native FarmingTracker prediction first
        Object ft = getFarmingTracker();
        if (ft != null && farmingTrackerPredictMethod != null && patchDef.farmingPatch != null) {
            try {
                String profileKey = configManager != null ? configManager.getRSProfileKey() : null;
                Object prediction = farmingTrackerPredictMethod.invoke(ft, patchDef.farmingPatch, profileKey);
                if (prediction != null) {
                    Class<?> predClass = prediction.getClass();
                    Method getProduce = predClass.getDeclaredMethod("getProduce");
                    getProduce.setAccessible(true);
                    Method getCropState = predClass.getDeclaredMethod("getCropState");
                    getCropState.setAccessible(true);
                    Method getDoneEstimate = predClass.getDeclaredMethod("getDoneEstimate");
                    getDoneEstimate.setAccessible(true);
                    Method getStage = predClass.getDeclaredMethod("getStage");
                    getStage.setAccessible(true);
                    Method getStages = predClass.getDeclaredMethod("getStages");
                    getStages.setAccessible(true);

                    Enum<?> cropState = (Enum<?>) getCropState.invoke(prediction);
                    Object produceObj = getProduce.invoke(prediction);

                    if (cropState == null || "EMPTY".equals(cropState.name()) || produceObj == null
                            || "WEEDS".equalsIgnoreCase(((Enum<?>) produceObj).name())) {
                        return null; // Empty patch or weeds, do not report
                    }

                    Method getProduceNameMethod = produceObj.getClass().getDeclaredMethod("getName");
                    getProduceNameMethod.setAccessible(true);
                    String produceName = (String) getProduceNameMethod.invoke(produceObj);

                    long doneEstimate = (Long) getDoneEstimate.invoke(prediction);
                    int stage = (Integer) getStage.invoke(prediction);
                    int stages = (Integer) getStages.invoke(prediction);

                    boolean isReady = false;
                    String status = "Growing";
                    int minutesRemaining = 0;

                    if ("HARVESTABLE".equals(cropState.name())) {
                        status = patchDef.healthCheckRequired ? "Check health ready" : "Ready to harvest";
                        isReady = true;
                    } else if ("GROWING".equals(cropState.name())) {
                        if ((doneEstimate > 0 && doneEstimate <= nowSec) || (stages > 0 && stage >= stages - 1)) {
                            status = patchDef.healthCheckRequired ? "Check health ready" : "Ready to harvest";
                            isReady = true;
                        } else {
                            if (doneEstimate > nowSec) {
                                minutesRemaining = (int) Math.max(1, (doneEstimate - nowSec) / 60);
                                status = "Growing (" + minutesRemaining + " mins remaining)";
                            } else {
                                status = "Growing";
                            }
                            isReady = false;
                        }
                    } else if ("DISEASED".equals(cropState.name())) {
                        status = "Diseased";
                        isReady = false;
                    } else if ("DEAD".equals(cropState.name())) {
                        status = "Dead";
                        isReady = false;
                    } else if ("FILLING".equals(cropState.name())) {
                        status = "Composting / Filling";
                        isReady = false;
                    }

                    JsonObject obj = new JsonObject();
                    obj.addProperty("location", patchDef.locationName);
                    obj.addProperty("type", patchDef.patchType);
                    obj.addProperty("produce", produceName != null ? produceName : patchDef.patchType);
                    obj.addProperty("status", status);
                    obj.addProperty("ready", isReady);
                    if (minutesRemaining > 0) {
                        obj.addProperty("minutesRemaining", minutesRemaining);
                    }
                    return obj;
                }
            } catch (Throwable t) {
                log.debug("FarmingTracker prediction reflection failed for {}: {}", patchDef.configKey, t.getMessage());
            }
        }

        // 2. Fallback to manual varbit + tick computation if FarmingTracker prediction
        // wasn't available
        String[] parts = val.split("[:;,|]");
        int varbitValue = 0;
        try {
            varbitValue = Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException ignored) {
            return null;
        }

        if (varbitValue <= 0) {
            return null; // Empty patch or cleared weeds
        }

        long timestamp = 0;
        if (parts.length > 1) {
            timestamp = parseTimestampOrDuration(parts[1]);
        }

        try {
            Method forVarbitValueMethod = patchDef.patchImplementation.getClass().getDeclaredMethod("forVarbitValue",
                    int.class);
            forVarbitValueMethod.setAccessible(true);
            Object patchState = forVarbitValueMethod.invoke(patchDef.patchImplementation, varbitValue);
            if (patchState == null) {
                return null;
            }

            Class<?> psClass = patchState.getClass();
            Method getProduce = psClass.getDeclaredMethod("getProduce");
            getProduce.setAccessible(true);
            Method getCropState = psClass.getDeclaredMethod("getCropState");
            getCropState.setAccessible(true);
            Method getStage = psClass.getDeclaredMethod("getStage");
            getStage.setAccessible(true);
            Method getStages = psClass.getDeclaredMethod("getStages");
            getStages.setAccessible(true);
            Method getTickRate = psClass.getDeclaredMethod("getTickRate");
            getTickRate.setAccessible(true);

            Enum<?> cropState = (Enum<?>) getCropState.invoke(patchState);
            if (cropState == null || "EMPTY".equals(cropState.name())) {
                return null; // CropState.EMPTY = not planted
            }

            Object produceObj = getProduce.invoke(patchState);
            String produceName = patchDef.patchType;
            if (produceObj != null) {
                Method getNameMethod = produceObj.getClass().getDeclaredMethod("getName");
                getNameMethod.setAccessible(true);
                produceName = (String) getNameMethod.invoke(produceObj);
            }

            boolean isReady = false;
            String status = "Growing";
            int minutesRemaining = 0;

            if ("HARVESTABLE".equals(cropState.name())) {
                status = patchDef.healthCheckRequired ? "Check health ready" : "Ready to harvest";
                isReady = true;
            } else if ("GROWING".equals(cropState.name())) {
                int stage = (Integer) getStage.invoke(patchState);
                int stages = (Integer) getStages.invoke(patchState);
                int tickRate = (Integer) getTickRate.invoke(patchState);
                int remainingStages = Math.max(0, stages - stage);
                long totalGrowSeconds = (long) remainingStages * tickRate * 60L;
                long doneTime = timestamp + totalGrowSeconds;

                if (doneTime <= nowSec || remainingStages == 0) {
                    status = patchDef.healthCheckRequired ? "Check health ready" : "Ready to harvest";
                    isReady = true;
                } else {
                    minutesRemaining = (int) Math.max(1, (doneTime - nowSec) / 60);
                    status = "Growing (" + minutesRemaining + " mins remaining)";
                    isReady = false;
                }
            } else if ("DISEASED".equals(cropState.name())) {
                status = "Diseased";
                isReady = false;
            } else if ("DEAD".equals(cropState.name())) {
                status = "Dead";
                isReady = false;
            } else if ("FILLING".equals(cropState.name())) {
                status = "Composting / Filling";
                isReady = false;
            }

            JsonObject obj = new JsonObject();
            obj.addProperty("location", patchDef.locationName);
            obj.addProperty("type", patchDef.patchType);
            obj.addProperty("produce", produceName);
            obj.addProperty("status", status);
            obj.addProperty("ready", isReady);
            if (minutesRemaining > 0) {
                obj.addProperty("minutesRemaining", minutesRemaining);
            }
            return obj;
        } catch (Throwable t) {
            return null;
        }
    }

    private long parseTimestampOrDuration(String val) {
        if (val == null || val.trim().isEmpty()) {
            return 0;
        }
        String[] parts = val.split("[:;,|]");
        for (String part : parts) {
            try {
                long num = Long.parseLong(part.trim());
                if (num > 1000000000000L) {
                    return num / 1000L;
                } else if (num > 1000000000L) {
                    return num;
                }
            } catch (Exception ignored) {
            }
        }
        return 0;
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
        sb.append("Account Type: ").append(Utilities.describeAccountTypeFromVarbit(accountTypeVarbit)).append("\n");
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
        sb.append("Active Spellbook: ").append(Utilities.describeSpellbook(spellbookVar)).append("\n");
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

    private String getDiaryStatus(int varbitId, int maxTasks) {
        return Utilities.getDiaryStatus(client, varbitId, maxTasks);
    }

    private JsonObject createDiaryProgress(
            int easyVarbit, int easyMax,
            int medVarbit, int medMax,
            int hardVarbit, int hardMax,
            int eliteVarbit, int eliteMax) {
        JsonObject obj = new JsonObject();
        obj.addProperty("Easy", getDiaryStatus(easyVarbit, easyMax));
        obj.addProperty("Medium", getDiaryStatus(medVarbit, medMax));
        obj.addProperty("Hard", getDiaryStatus(hardVarbit, hardMax));
        obj.addProperty("Elite", getDiaryStatus(eliteVarbit, eliteMax));
        return obj;
    }

    @SuppressWarnings("unused")
    private String executeWikiSearch(String query) {
        return WikiSearchUtil.executeWikiSearch(getWikiClient(), gson, query);
    }

    @SuppressWarnings("unused")
    private String describeAccountType(Integer accountTypeVarbit) {
        return Utilities.describeAccountTypeFromVarbit(accountTypeVarbit);
    }

    @SuppressWarnings("unused")
    private String describeSpellbook(int val) {
        return Utilities.describeSpellbook(val);
    }

    @SuppressWarnings("unused")
    private JsonObject aggregateItemsWithPrices(ItemContainer container, String filter, int minValue) {
        return ItemContainerUtils.aggregateItemsWithPrices(client, itemManager, container, filter, minValue);
    }
}
