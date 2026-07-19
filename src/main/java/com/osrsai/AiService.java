package com.osrsai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
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
import net.runelite.api.SoundEffectID;
import net.runelite.api.ParamID;
import net.runelite.client.Notifier;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemStats;
import net.runelite.http.api.item.ItemEquipmentStats;

import javax.swing.SwingUtilities;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.jetbrains.annotations.NotNull;

@Slf4j
public class AiService {
    // User Agent String
    private static final String OSRS_AI_USER_AGENT = "OSRS AI Assistant RuneLite Plugin - https://github.com/Timboy67678/osrs-ai-assistant";

    // Template Removal Constants
    private static final int MAX_TEMPLATE_REMOVALS = 5;

    // Global Constants
    static final int MAX_DEPTH_COUNT = 10;

    // Token Constants
    private static final int MAX_CONTEXT_CHARACTERS = 8000;
    private static final int MAX_RECENT_CONVERSATION_CHARS = 1200;
    private static final int WIKI_EXTRACT_CHARS = 2500;

    // URI Constants
    private static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";
    private static final String DEFAULT_CUSTOM_ENDPOINT = "http://localhost:11434/v1/chat/completions"; // Ollama

    private static final Map<Integer, String> CA_TIER_MAP = Map.of(
            3981, "Easy",
            3982, "Medium",
            3983, "Hard",
            3984, "Elite",
            3985, "Master",
            3986, "Grandmaster");

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
    private ConfigManager configManager;

    @Inject
    private PluginManager pluginManager;

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

    public void sendQuestion(String question, OsrsAiPanel panel) {
        final String apiKey = config.apiKey();
        final AiProvider provider = config.aiProvider();
        if (provider != AiProvider.CUSTOM && (apiKey == null || apiKey.isEmpty())) {
            panel.addMessage("System", "Please set an API key in the plugin config.");
            return;
        }

        panel.setThinking(true);
        try {
            // Retrieve recent conversation context on the EDT (current thread) to avoid
            // thread-safety violations when copying/iterating over panel.recentMessages on
            // the client thread.
            String recentConversation = panel.getRecentConversationContext(question);

            clientThread.invokeLater(() -> {
                try {
                    final String gameContext = buildGameContext();
                    final String clientId = config.clientId();
                    final String customModel = config.customModel();
                    final String modelId = (customModel != null && !customModel.trim().isEmpty())
                            ? customModel.trim()
                            : provider.getModelId();
                    final String customEndpoint = config.customEndpoint();
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
                    }).exceptionally(ex -> {
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
                try {
                    if (!response.isSuccessful()) {
                        String errBody = "";
                        if (response.body() != null) {
                            errBody = response.body().string();
                        }
                        log.error("API returned error (code {}): {}", response.code(), errBody);
                        final String errText = "AI returned an error code " + response.code()
                                + (errBody.isEmpty() ? "" : ": " + errBody);
                        SwingUtilities.invokeLater(() -> {
                            panel.setThinking(false);
                            panel.addMessage("System", errText);
                        });
                        return;
                    }

                    assert response.body() != null;
                    String responseBody = response.body().string();
                    log.info("Received response from AI provider {}: {}", provider, responseBody);
                    JsonObject root = gson.fromJson(responseBody, JsonObject.class);

                    // Check for tool calls
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

                            // Update request body with tool calls and results
                            handler.updateRequestWithToolResults(requestBody, root, results);

                            // If the next request will be the final depth, remove the tools object so the
                            // model must return text
                            if (depth + 1 >= maxDepth) {
                                requestBody.remove("tools");
                            }

                            // Send updated request recursively
                            executeRequestLoop(provider, modelId, endpoint, apiKey, clientId, requestBody, depth + 1,
                                    panel);
                        });
                    } else {
                        // Normal text response
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
                                notifier.notify("AI Assistant: " + truncateForNotification(finalResponse));
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

                    ToolDefinition def = getToolRegistry().stream()
                            .filter(d -> d.name.equals(tc.name))
                            .findFirst()
                            .orElse(null);

                    if (def == null) {
                        throw new IllegalArgumentException("Unknown tool: " + tc.name);
                    }

                    String output;
                    if (def.runOnClientThread) {
                        // Client-thread bound tools
                        final String[] clientThreadResult = new String[1];
                        final Throwable[] clientThreadError = new Throwable[1];
                        CompletableFuture<Void> clientFuture = new CompletableFuture<>();
                        clientThread.invokeLater(() -> {
                            try {
                                clientThreadResult[0] = def.executor.execute(this, tc.args);
                                clientFuture.complete(null);
                            } catch (Throwable t) {
                                clientThreadError[0] = t;
                                clientFuture.completeExceptionally(t);
                            }
                        });
                        clientFuture.join(); // wait for client thread to finish
                        if (clientThreadError[0] != null) {
                            throw new Exception(clientThreadError[0]);
                        }
                        output = clientThreadResult[0];
                    } else {
                        // Background-thread/network tools
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
        });
        return future;
    }

    private JsonObject aggregateItemsWithPrices(ItemContainer container, String filter, int minValue) {
        JsonObject result = new JsonObject();
        Map<String, Long> quantities = new LinkedHashMap<>();
        Map<String, Integer> itemIds = new HashMap<>();
        Map<String, Integer> itemHaPrices = new HashMap<>();

        String search = (filter != null) ? filter.trim().toLowerCase() : null;
        String[] tokens = null;
        if (search != null) {
            tokens = search.split("\\s+or\\s+|\\s*,\\s*|\\s*\\|\\s*");
        }
        boolean isIron = isIronman();

        for (Item item : container.getItems()) {
            if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) {
                continue;
            }
            net.runelite.api.ItemComposition comp = null;
            if (itemManager != null) {
                try {
                    comp = itemManager.getItemComposition(item.getId());
                } catch (Exception ignored) {
                }
            }
            if (comp != null && comp.getPlaceholderTemplateId() != -1) {
                continue;
            }
            String itemName = (comp != null && comp.getName() != null && !comp.getName().trim().isEmpty())
                    ? comp.getName()
                    : "Item " + item.getId();

            // Apply name filter if present
            if (tokens != null && tokens.length > 0) {
                boolean matchesAnyOrGroup = false;
                for (String orGroup : tokens) {
                    String cleanGroup = orGroup.trim();
                    if (cleanGroup.isEmpty()) {
                        continue;
                    }

                    // Split the OR group by " and " or "&" to find all AND tokens
                    String[] andTokens = cleanGroup.split("\\s+and\\s+|\\s*&\\s*");
                    boolean matchesAllAndTokens = true;
                    for (String andToken : andTokens) {
                        String cleanAndToken = andToken.trim();
                        if (!cleanAndToken.isEmpty() && !itemName.toLowerCase().contains(cleanAndToken)) {
                            matchesAllAndTokens = false;
                            break;
                        }
                    }

                    if (matchesAllAndTokens) {
                        matchesAnyOrGroup = true;
                        break;
                    }
                }
                if (!matchesAnyOrGroup) {
                    continue;
                }
            }

            quantities.put(itemName, quantities.getOrDefault(itemName, 0L) + item.getQuantity());
            itemIds.putIfAbsent(itemName, item.getId());
            itemHaPrices.putIfAbsent(itemName, comp != null ? comp.getHaPrice() : 0);
        }

        // Help sort items by total stack value
        class BankItem {
            final String name;
            final long qty;
            final int gePrice;
            final int haPrice;
            final long totalSortVal;

            BankItem(String name, long qty, int gePrice, int haPrice) {
                this.name = name;
                this.qty = qty;
                this.gePrice = gePrice;
                this.haPrice = haPrice;
                long unitPrice = isIron ? haPrice : gePrice;
                this.totalSortVal = unitPrice * qty;
            }
        }

        List<BankItem> list = new ArrayList<>();
        for (Map.Entry<String, Long> entry : quantities.entrySet()) {
            String name = entry.getKey();
            long qty = entry.getValue();
            int itemId = itemIds.get(name);
            int price = 0;
            if (itemManager != null) {
                try {
                    price = itemManager.getItemPrice(itemId);
                } catch (Exception e) {
                }
            }
            if (price <= 0 && "Coins".equals(name)) {
                price = 1;
            }
            int haPrice = itemHaPrices.getOrDefault(name, 0);

            // Apply minimum value filter if present based on account type preference
            int checkVal = isIron ? haPrice : price;
            if (minValue > 0 && checkVal < minValue) {
                continue;
            }

            list.add(new BankItem(name, qty, price, haPrice));
        }

        // Sort by totalSortVal descending
        list.sort((a, b) -> Long.compare(b.totalSortVal, a.totalSortVal));

        // Limit unfiltered container output to top 100 items to conserve tokens
        int limit = (search == null) ? 100 : Integer.MAX_VALUE;
        int count = 0;
        for (BankItem bi : list) {
            if (count >= limit) {
                break;
            }
            JsonObject detail = new JsonObject();
            detail.addProperty("id", itemIds.get(bi.name));
            detail.addProperty("qty", bi.qty);
            detail.addProperty("gePrice", bi.gePrice);
            detail.addProperty("haPrice", bi.haPrice);
            result.add(bi.name, detail);
            count++;
        }

        return result;
    }

    private boolean isIronman() {
        try {
            int accountType = client.getVarbitValue(Varbits.ACCOUNT_TYPE);
            return accountType >= 1 && accountType <= 6;
        } catch (Exception ex) {
            return false;
        }
    }

    static class ToolCall {
        final String id;
        final String name;
        final JsonObject args;

        ToolCall(String id, String name, JsonObject args) {
            this.id = id;
            this.name = name;
            this.args = args;
        }
    }

    static class ToolResult {
        final ToolCall call;
        final String resultJson;

        ToolResult(ToolCall call, String resultJson) {
            this.call = call;
            this.resultJson = resultJson;
        }
    }

    public static class ToolParameter {
        public final String name;
        public final String type; // "string", "integer", "array_string", "array_integer"
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
        List<ToolDefinition> registry = new ArrayList<>();

        registry.add(new ToolDefinition("get_player_skills",
                "Retrieve the player's current levels (both real and boosted), experience (XP), next level threshold, and remaining experience for all skills or a specific filtered skill.",
                true, true, AiService::executeGetPlayerSkills)
                .addParam("skill", "string", "Optional skill name to filter strictly by (case-insensitive, e.g. 'Attack', 'Strength', 'Slayer'). If omitted, retrieves all skills.", false));

        registry.add(new ToolDefinition("get_player_inventory",
                "Retrieve the items, quantities, Grand Exchange prices, and High Alchemy values currently in the player's inventory.",
                true, true, AiService::executeGetPlayerInventory));

        registry.add(new ToolDefinition("get_player_equipment",
                "Retrieve the items, quantities, Grand Exchange prices, and High Alchemy values currently equipped by the player.",
                true, true, AiService::executeGetPlayerEquipment));

        registry.add(new ToolDefinition("get_player_slayer_task",
                "Retrieve the player's current Slayer task, remaining quantity, current Slayer points, and current streak.",
                true, true, AiService::executeGetPlayerSlayerTask));

        registry.add(new ToolDefinition("get_player_quests",
                "Retrieve the player's quest points, completed quest count, and lists of in-progress, not started, or completed quests.",
                true, true, AiService::executeGetPlayerQuests)
                .addParam("status", "string",
                        "Optional quest status filter: 'IN_PROGRESS' (default), 'NOT_STARTED', 'COMPLETED', or 'ALL'.", false));

        registry.add(new ToolDefinition("get_player_status",
                "Retrieve the player's current combat and vital status, including Special Attack energy %, active prayers, poison/venom state, run energy, and HP/Prayer values.",
                true, true, AiService::executeGetPlayerStatus));

        registry.add(new ToolDefinition("get_player_currencies_and_points",
                "Retrieve the player's minigame reward points, currencies, tickets, and tokens (e.g. NMZ points, Pest Control commends, Tithe Farm points, Golden Nuggets, Abyssal Pearls, Marks of Grace, Slayer points, Archery tickets).",
                true, true, AiService::executeGetPlayerCurrenciesAndPoints));

        registry.add(new ToolDefinition("get_player_location_details",
                "Retrieve detailed information about the player's location, including Wilderness level, multi-combat status, instanced area status, world types (PvP, Members, High Risk), and region ID.",
                true, true, AiService::executeGetPlayerLocationDetails));

        registry.add(new ToolDefinition("get_player_achievement_diaries",
                "Retrieve the player's Achievement Diary completion progress for all regions and tiers (Easy, Medium, Hard, Elite).",
                true, true, AiService::executeGetPlayerAchievementDiaries));

        registry.add(new ToolDefinition("get_player_bank",
                "Retrieve the items, quantities, Grand Exchange prices, and High Alchemy values currently in the player's bank. Only works if the bank interface is open.",
                true, true, AiService::executeGetPlayerBank)
                .addParam("filter", "string",
                        "Optional search query to filter bank items strictly by item name substring (case-insensitive, e.g. 'bar' or 'ore'). Do NOT filter by skill or category name (e.g. do NOT use 'crafting' as a filter).",
                        false)
                .addParam("minValue", "integer", "Optional minimum value to filter items.", false));

        registry.add(new ToolDefinition("get_item_stats",
                "Retrieve detailed equipment statistics, combat bonuses, weight, slot, and prices for a list of item IDs or item names.",
                true, true, AiService::executeGetItemStats)
                .addParam("itemIds", "array_integer", "Optional list of OSRS item IDs to retrieve stats for.", false)
                .addParam("itemNames", "array_string",
                        "Optional list of item names to search for in containers and retrieve stats.", false));

        registry.add(new ToolDefinition("get_player_clues",
                "Retrieve details about the player's active clue scroll (current step text, requirements, and solution) if they are in the middle of one, as well as a list of clue scroll items currently in their inventory or bank.",
                true, true, AiService::executeGetPlayerClues));

        registry.add(new ToolDefinition("search_osrs_wiki",
                "Search the Old School RuneScape Wiki for authoritative mechanics, stats, requirements, locations, farming patches, training methods, and information.",
                false, false, AiService::executeSearchOsrsWiki)
                .addParam("query", "string",
                        "The exact entity, location, farming patch, training method, or topic to search for (e.g. 'Sharp Eye', 'Abyssal whip', 'Barrows', 'Farming patches').",
                        true));

        registry.add(new ToolDefinition("get_player_combat_achievements",
                "Retrieve the player's Combat Achievement tier completion status (Easy, Medium, Hard, Elite, Master, Grandmaster) and boss/activity kill counts (KC).",
                true, true, AiService::executeGetPlayerCombatAchievements)
                .addParam("tier", "string",
                        "Optional. Filter individual tasks strictly by tier (case-insensitive: 'Easy', 'Medium', 'Hard', 'Elite', 'Master', 'Grandmaster').",
                        false)
                .addParam("boss", "string",
                        "Optional. Filter individual tasks by boss/monster name substring (case-insensitive, e.g. 'barrows' or 'zulrah').",
                        false)
                .addParam("completed", "boolean", "Optional. Filter individual tasks by completion status.", false)
                .addParam("taskName", "string",
                        "Optional. Filter individual tasks strictly by task name substring (case-insensitive, e.g. 'noxious foe' or 'barrows novice').",
                        false));

        return registry;
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

    private void addMilestoneXp(JsonObject skillData, int currentXp) {
        int[] milestones = {50, 60, 70, 80, 90, 99};
        for (int level : milestones) {
            int targetXp = Experience.getXpForLevel(level);
            if (currentXp < targetXp) {
                skillData.addProperty("xpTo" + level, targetXp - currentXp);
            }
        }
    }

    private String executeGetPlayerSkills(JsonObject args) {
        JsonObject result = new JsonObject();
        String filterSkill = (args != null && args.has("skill")) ? normalizeSkillName(args.get("skill").getAsString()) : null;

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

                addMilestoneXp(skillData, xp);

                result.add(skillName, skillData);
            }
        }
        return gson.toJson(result);
    }

    private String executeGetPlayerInventory(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject invItems = new JsonObject();
        ItemContainer invContainer = client.getItemContainer(InventoryID.INVENTORY);
        if (invContainer != null) {
            invItems = aggregateItemsWithPrices(invContainer, null, 0);
        }
        result.add("items", invItems);
        return gson.toJson(result);
    }

    private String executeGetPlayerEquipment(JsonObject args) {
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

                String slotName = getSlotName(i);
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
                        if (eq.getAstab() != 0) statsObj.addProperty("astab", eq.getAstab());
                        if (eq.getAslash() != 0) statsObj.addProperty("aslash", eq.getAslash());
                        if (eq.getAcrush() != 0) statsObj.addProperty("ascrush", eq.getAcrush());
                        if (eq.getAmagic() != 0) statsObj.addProperty("asmagic", eq.getAmagic());
                        if (eq.getArange() != 0) statsObj.addProperty("asrange", eq.getArange());
                        if (eq.getDstab() != 0) statsObj.addProperty("dstab", eq.getDstab());
                        if (eq.getDslash() != 0) statsObj.addProperty("dslash", eq.getDslash());
                        if (eq.getDcrush() != 0) statsObj.addProperty("dcrush", eq.getDcrush());
                        if (eq.getDmagic() != 0) statsObj.addProperty("dmagic", eq.getDmagic());
                        if (eq.getDrange() != 0) statsObj.addProperty("drange", eq.getDrange());
                        if (eq.getStr() != 0) statsObj.addProperty("str", eq.getStr());
                        if (eq.getRstr() != 0) statsObj.addProperty("rstr", eq.getRstr());
                        if (eq.getMdmg() != 0) statsObj.addProperty("mdmg", eq.getMdmg());
                        if (eq.getPrayer() != 0) statsObj.addProperty("prayer", eq.getPrayer());
                        if (eq.getAspeed() != 0) statsObj.addProperty("aspeed", eq.getAspeed());
                        itemDetail.add("stats", statsObj);
                    }
                }
                eqSlots.add(slotName, itemDetail);
            }
        }
        result.add("slots", eqSlots);
        return gson.toJson(result);
    }

    private String executeGetPlayerSlayerTask(JsonObject args) {
        JsonObject result = new JsonObject();
        String taskName = configManager.getRSProfileConfiguration("slayer", "taskName");
        if (taskName == null || taskName.isEmpty()) {
            taskName = configManager.getConfiguration("slayer", "taskName");
        }
        String amount = configManager.getRSProfileConfiguration("slayer", "amount");
        if (amount == null || amount.isEmpty()) {
            amount = configManager.getConfiguration("slayer", "amount");
        }
        String pointsStr = configManager.getRSProfileConfiguration("slayer", "points");
        if (pointsStr == null || pointsStr.isEmpty()) {
            pointsStr = configManager.getConfiguration("slayer", "points");
        }
        String streakStr = configManager.getRSProfileConfiguration("slayer", "streak");
        if (streakStr == null || streakStr.isEmpty()) {
            streakStr = configManager.getConfiguration("slayer", "streak");
        }
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
        } else {
            result.addProperty("task", "None");
            result.addProperty("quantity", 0);
        }
        return gson.toJson(result);
    }

    private String executeGetPlayerQuests(JsonObject args) {
        JsonObject result = new JsonObject();
        int qp = client.getVarpValue(VarPlayerID.QP);
        result.addProperty("questPoints", qp);

        String statusFilter = (args != null && args.has("status"))
                ? args.get("status").getAsString().trim().toUpperCase()
                : "DEFAULT";

        int completedCount = 0;
        int inProgressCount = 0;
        int notStartedCount = 0;

        JsonArray completed = new JsonArray();
        JsonArray inProgress = new JsonArray();
        JsonArray notStarted = new JsonArray();

        boolean includeCompleted = "COMPLETED".equals(statusFilter) || "ALL".equals(statusFilter);
        boolean includeInProgress = "DEFAULT".equals(statusFilter) || "IN_PROGRESS".equals(statusFilter) || "ALL".equals(statusFilter);
        boolean includeNotStarted = "DEFAULT".equals(statusFilter) || "NOT_STARTED".equals(statusFilter) || "ALL".equals(statusFilter);

        for (Quest quest : Quest.values()) {
            QuestState state = quest.getState(client);
            if (state == QuestState.FINISHED) {
                completedCount++;
                if (includeCompleted) {
                    completed.add(quest.getName());
                }
            } else if (state == QuestState.IN_PROGRESS) {
                inProgressCount++;
                if (includeInProgress) {
                    inProgress.add(quest.getName());
                }
            } else if (state == QuestState.NOT_STARTED) {
                notStartedCount++;
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

    private String executeGetPlayerAchievementDiaries(JsonObject args) {
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

    private String executeGetPlayerCombatAchievements(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject tiers = new JsonObject();
        tiers.addProperty("Easy", getCombatAchievementTierStatus(Varbits.COMBAT_ACHIEVEMENT_TIER_EASY));
        tiers.addProperty("Medium", getCombatAchievementTierStatus(Varbits.COMBAT_ACHIEVEMENT_TIER_MEDIUM));
        tiers.addProperty("Hard", getCombatAchievementTierStatus(Varbits.COMBAT_ACHIEVEMENT_TIER_HARD));
        tiers.addProperty("Elite", getCombatAchievementTierStatus(Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE));
        tiers.addProperty("Master", getCombatAchievementTierStatus(Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER));
        tiers.addProperty("Grandmaster", getCombatAchievementTierStatus(Varbits.COMBAT_ACHIEVEMENT_TIER_GRANDMASTER));
        result.add("tiers", tiers);

        JsonObject killCounts = new JsonObject();
        String profileKey = configManager.getRSProfileKey();
        if (profileKey != null) {
            List<String> keys = configManager.getRSProfileConfigurationKeys("killcount", profileKey, "");
            if (keys != null) {
                List<String> sortedKeys = new ArrayList<>(keys);
                Collections.sort(sortedKeys);
                for (String key : sortedKeys) {
                    String valueStr = configManager.getRSProfileConfiguration("killcount", key);
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

        String filterTier = (args != null && args.has("tier")) ? args.get("tier").getAsString().trim().toLowerCase()
                : null;
        String filterBoss = (args != null && args.has("boss")) ? args.get("boss").getAsString().trim().toLowerCase()
                : null;
        Boolean filterCompleted = (args != null && args.has("completed")) ? args.get("completed").getAsBoolean() : null;
        String filterTaskName = (args != null && args.has("taskName"))
                ? args.get("taskName").getAsString().trim().toLowerCase()
                : null;

        boolean hasFilters = (filterTier != null || filterBoss != null || filterCompleted != null
                || filterTaskName != null);

        if (hasFilters) {
            JsonArray tasks = new JsonArray();
            net.runelite.api.EnumComposition bossEnum = client.getEnum(3971);

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

                    String name = struct.getStringValue(1308);
                    String description = struct.getStringValue(1309);
                    int id = struct.getIntValue(1306);
                    int typeId = struct.getIntValue(1311);
                    String type = CA_TYPE_MAP.get(typeId);
                    int bossId = struct.getIntValue(1312);
                    String bossName = getBossName(bossEnum, bossId);

                    boolean completed = false;
                    if (id >= 0 && id < CA_VARP_IDS.length * 32) {
                        int varpIndex = id / 32;
                        int bitIndex = id % 32;
                        if (varpIndex < CA_VARP_IDS.length) {
                            int varpValue = client.getVarpValue(CA_VARP_IDS[varpIndex]);
                            completed = (varpValue & (1 << bitIndex)) != 0;
                        }
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
                return "In Progress";
            case 2:
                return "Completed";
            default:
                return "Unknown (" + val + ")";
        }
    }

    private String executeGetPlayerBank(JsonObject args) {
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
            result.add("items", aggregateItemsWithPrices(bankContainer, filter, minValue));
        }
        return gson.toJson(result);
    }

    private String executeGetItemStats(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject itemsStats = new JsonObject();
        if (args != null) {
            if (args.has("itemIds")) {
                JsonArray ids = args.getAsJsonArray("itemIds");
                for (int i = 0; i < ids.size(); i++) {
                    int itemId = ids.get(i).getAsInt();
                    itemsStats.add(String.valueOf(itemId), buildItemStatsJson(itemId));
                }
            }
            if (args.has("itemNames")) {
                JsonArray names = args.getAsJsonArray("itemNames");
                for (int i = 0; i < names.size(); i++) {
                    String itemName = names.get(i).getAsString();
                    Integer itemId = findItemIdInContainers(itemName);
                    if (itemId != null) {
                        itemsStats.add(itemName, buildItemStatsJson(itemId));
                    } else {
                        JsonObject errorObj = new JsonObject();
                        errorObj.addProperty("error", "Item not found in equipment, inventory, or bank.");
                        itemsStats.add(itemName, errorObj);
                    }
                }
            }
        }
        result.add("items", itemsStats);
        return gson.toJson(result);
    }

    private String executeGetPlayerClues(JsonObject args) throws Exception {
        JsonObject result = new JsonObject();
        // 1. Scan for clue items in inventory
        JsonArray invClueItems = new JsonArray();
        ItemContainer invCont = client.getItemContainer(InventoryID.INVENTORY);
        if (invCont != null) {
            for (Item item : invCont.getItems()) {
                if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) {
                    continue;
                }
                net.runelite.api.ItemComposition comp = client.getItemDefinition(item.getId());
                if (comp != null && comp.getIntValue(ParamID.CLUE_SCROLL) != -1) {
                    JsonObject clueItem = new JsonObject();
                    clueItem.addProperty("id", item.getId());
                    clueItem.addProperty("name", comp.getName());
                    clueItem.addProperty("qty", item.getQuantity());
                    clueItem.addProperty("location", "Inventory");
                    invClueItems.add(clueItem);
                }
            }
        }
        result.add("inventoryClues", invClueItems);

        // 2. Scan for clue items in bank
        JsonArray bankClueItems = new JsonArray();
        ItemContainer bankCont = client.getItemContainer(InventoryID.BANK);
        if (bankCont != null) {
            for (Item item : bankCont.getItems()) {
                if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) {
                    continue;
                }
                net.runelite.api.ItemComposition comp = client.getItemDefinition(item.getId());
                if (comp != null && comp.getIntValue(ParamID.CLUE_SCROLL) != -1) {
                    JsonObject clueItem = new JsonObject();
                    clueItem.addProperty("id", item.getId());
                    clueItem.addProperty("name", comp.getName());
                    clueItem.addProperty("qty", item.getQuantity());
                    clueItem.addProperty("location", "Bank");
                    bankClueItems.add(clueItem);
                }
            }
        }
        result.add("bankClues", bankClueItems);

        // 3. Locate ClueScrollPlugin via PluginManager
        JsonObject activeClueObj = new JsonObject();
        activeClueObj.addProperty("status", "No active clue scroll detected");
        boolean foundPlugin = false;

        if (pluginManager != null) {
            for (net.runelite.client.plugins.Plugin p : pluginManager.getPlugins()) {
                if (p.getClass().getName().equals("net.runelite.client.plugins.cluescrolls.ClueScrollPlugin")) {
                    foundPlugin = true;
                    if (pluginManager.isPluginEnabled(p)) {
                        if (p instanceof net.runelite.client.plugins.cluescrolls.ClueScrollPlugin) {
                            net.runelite.client.plugins.cluescrolls.ClueScrollPlugin clueScrollPlugin = (net.runelite.client.plugins.cluescrolls.ClueScrollPlugin) p;
                            net.runelite.client.plugins.cluescrolls.clues.ClueScroll clue = clueScrollPlugin
                                    .getClue();
                            if (clue != null) {
                                activeClueObj.addProperty("status", "Active clue scroll detected");
                                activeClueObj.addProperty("type", clue.getClass().getSimpleName());

                                // Render hint using PanelComponent
                                net.runelite.client.ui.overlay.components.PanelComponent panel = new net.runelite.client.ui.overlay.components.PanelComponent();
                                try {
                                    clue.makeOverlayHint(panel, clueScrollPlugin);
                                    JsonArray hintLines = new JsonArray();
                                    for (Object child : panel.getChildren()) {
                                        if (child instanceof net.runelite.client.ui.overlay.components.LineComponent) {
                                            net.runelite.client.ui.overlay.components.LineComponent lc = (net.runelite.client.ui.overlay.components.LineComponent) child;

                                            // Use reflection to read private left/right fields to bypass getter
                                            // compilation issue
                                            String left = "";
                                            String right = "";
                                            try {
                                                java.lang.reflect.Field leftField = lc.getClass()
                                                        .getDeclaredField("left");
                                                leftField.setAccessible(true);
                                                left = (String) leftField.get(lc);
                                            } catch (Exception ignored) {
                                            }

                                            try {
                                                java.lang.reflect.Field rightField = lc.getClass()
                                                        .getDeclaredField("right");
                                                rightField.setAccessible(true);
                                                right = (String) rightField.get(lc);
                                            } catch (Exception ignored) {
                                            }

                                            if (left != null && !left.trim().isEmpty()) {
                                                if (right != null && !right.trim().isEmpty()) {
                                                    hintLines.add(left + ": " + right);
                                                } else {
                                                    hintLines.add(left);
                                                }
                                            }
                                        } else if (child instanceof net.runelite.client.ui.overlay.components.TitleComponent) {
                                            net.runelite.client.ui.overlay.components.TitleComponent tc = (net.runelite.client.ui.overlay.components.TitleComponent) child;

                                            // Use reflection to read private text field
                                            String text = "";
                                            try {
                                                java.lang.reflect.Field textField = tc.getClass()
                                                        .getDeclaredField("text");
                                                textField.setAccessible(true);
                                                text = (String) textField.get(tc);
                                            } catch (Exception ignored) {
                                            }

                                            if (text != null && !text.trim().isEmpty()) {
                                                hintLines.add(text);
                                            }
                                        } else {
                                            hintLines.add(child.toString());
                                        }
                                    }
                                    activeClueObj.add("details", hintLines);
                                } catch (Throwable t) {
                                    activeClueObj.addProperty("error",
                                            "Failed to format clue details: " + t.getMessage());
                                }
                            } else {
                                activeClueObj.addProperty("status",
                                        "No active clue scroll step loaded. Ask the player to read/open their clue scroll once to activate tracking.");
                            }
                        }
                    } else {
                        activeClueObj.addProperty("status",
                                "RuneLite's built-in Clue Scroll plugin is disabled in the client settings. Ask the player to enable it.");
                    }
                    break;
                }
            }
        }

        if (!foundPlugin) {
            activeClueObj.addProperty("status", "RuneLite's built-in Clue Scroll plugin was not found.");
        }

        result.add("activeClue", activeClueObj);
        return gson.toJson(result);
    }

    private String executeSearchOsrsWiki(JsonObject args) {
        String query = args.has("query") ? args.get("query").getAsString() : "";
        return executeWikiSearch(query);
    }

    private String executeGetPlayerStatus(JsonObject args) {
        JsonObject result = new JsonObject();

        int specPercent = client.getVarpValue(300) / 10;
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
                if (client.isPrayerActive(p)) {
                    activePrayers.add(p.name());
                }
            } catch (Exception ignored) {
            }
        }
        result.add("activePrayers", activePrayers);

        int poisonVarp = client.getVarpValue(VarPlayerID.POISON);
        String status = "Healthy";
        if (poisonVarp > 0 && poisonVarp < 1000000) {
            status = "Poisoned (" + poisonVarp + " dmg)";
        } else if (poisonVarp >= 1000000) {
            int venomDmg = (poisonVarp - 1000000) / 5 + 6;
            status = "Venomed (" + venomDmg + " dmg)";
        }
        result.addProperty("poisonState", status);

        JsonObject boostedSkills = new JsonObject();
        for (Skill s : Skill.values()) {
            if ("OVERALL".equals(s.name())) continue;
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

        return gson.toJson(result);
    }

    private String executeGetPlayerCurrenciesAndPoints(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject points = new JsonObject();

        try {
            int nmz = client.getVarpValue(1056);
            points.addProperty("nightmareZonePoints", nmz);
        } catch (Exception ignored) {}

        try {
            int pc = client.getVarpValue(261);
            points.addProperty("pestControlCommendations", pc);
        } catch (Exception ignored) {}

        try {
            int tithe = client.getVarbitValue(4893);
            points.addProperty("titheFarmPoints", tithe);
        } catch (Exception ignored) {}

        try {
            String pts = configManager.getRSProfileConfiguration("slayer", "points");
            if (pts == null || pts.isEmpty()) pts = configManager.getConfiguration("slayer", "points");
            if (pts != null && !pts.isEmpty()) {
                points.addProperty("slayerPoints", Integer.parseInt(pts));
            }
            String strk = configManager.getRSProfileConfiguration("slayer", "streak");
            if (strk == null || strk.isEmpty()) strk = configManager.getConfiguration("slayer", "streak");
            if (strk != null && !strk.isEmpty()) {
                points.addProperty("slayerStreak", Integer.parseInt(strk));
            }
        } catch (Exception ignored) {}

        Map<String, Integer> targetItemNames = Map.of(
            "Mark of grace", 11849,
            "Golden nugget", 12012,
            "Abyssal pearl", 26884,
            "Tokkul", 6529,
            "Stardust", 25527,
            "Archery ticket", 1464,
            "Mermaid's tear", 27433
        );

        JsonObject itemCurrencies = new JsonObject();
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        ItemContainer bank = client.getItemContainer(InventoryID.BANK);

        for (Map.Entry<String, Integer> entry : targetItemNames.entrySet()) {
            String name = entry.getKey();
            int targetId = entry.getValue();
            long total = 0;
            if (inv != null) {
                for (Item item : inv.getItems()) {
                    if (item != null && item.getId() == targetId) total += item.getQuantity();
                }
            }
            if (bank != null) {
                for (Item item : bank.getItems()) {
                    if (item != null && item.getId() == targetId) total += item.getQuantity();
                }
            }
            if (total > 0) {
                itemCurrencies.addProperty(name, total);
            }
        }
        points.add("currencyItems", itemCurrencies);
        result.add("pointsAndCurrencies", points);

        return gson.toJson(result);
    }

    private String executeGetPlayerLocationDetails(JsonObject args) {
        JsonObject result = new JsonObject();
        Player localPlayer = client.getLocalPlayer();

        int wildyLevel = 0;
        try {
            wildyLevel = client.getVarbitValue(5963);
        } catch (Exception ignored) {}
        result.addProperty("wildernessLevel", wildyLevel);
        result.addProperty("inWilderness", wildyLevel > 0);

        boolean isMulti = false;
        try {
            isMulti = client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1;
        } catch (Exception ignored) {}
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
        sb.append("Account Type: ").append(describeAccountType(accountTypeVarbit)).append("\n");
        sb.append("World: ").append(client.getWorld()).append("\n");
        sb.append("Total Level: ").append(client.getTotalLevel()).append("\n");
        int spellbookVar = client.getVarbitValue(4070);
        sb.append("Active Spellbook: ").append(describeSpellbook(spellbookVar)).append("\n");
        sb.append("Hitpoints: ")
                .append(client.getBoostedSkillLevel(Skill.HITPOINTS))
                .append("/")
                .append(client.getRealSkillLevel(Skill.HITPOINTS))
                .append("\n");
        sb.append("Prayer: ")
                .append(client.getBoostedSkillLevel(Skill.PRAYER))
                .append("/")
                .append(client.getRealSkillLevel(Skill.PRAYER))
                .append("\n");
        sb.append("\nCURRENT LOCATION:\n");
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

        return trimToPromptBudget(sb.toString(), MAX_CONTEXT_CHARACTERS,
                "...[game context truncated for prompt budget]");
    }

    private String safeItemName(int itemId) {
        try {
            String name = itemManager.getItemComposition(itemId).getName();
            if (name == null || name.trim().isEmpty()) {
                return "Item " + itemId;
            }
            return name;
        } catch (Exception ex) {
            return "Item " + itemId;
        }
    }

    static String trimToPromptBudget(String text, int maxChars, String truncationLabel) {
        return trimToPromptBudget(text, maxChars, truncationLabel, false);
    }

    static String trimToPromptBudget(String text, int maxChars, String truncationLabel, boolean keepEnd) {
        if (maxChars <= 0) {
            return "";
        }

        if (text == null || text.trim().isEmpty()) {
            return "None";
        }

        String safeLabel = (truncationLabel == null || truncationLabel.isEmpty())
                ? "...[truncated]"
                : truncationLabel;

        String normalized = text.trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }

        if (maxChars <= safeLabel.length()) {
            return safeLabel.substring(0, maxChars);
        }

        int keepLength = maxChars - safeLabel.length() - 1;
        if (keepEnd) {
            return safeLabel + "\n" + normalized.substring(normalized.length() - keepLength);
        } else {
            return normalized.substring(0, keepLength) + "\n" + safeLabel;
        }
    }

    private String executeWikiSearch(String query) {
        String cleanedQuery = extractSearchQuery(query);
        String title = searchWikiTopResult(cleanedQuery);
        if (title != null) {
            String extract = fetchWikiExtract(title);
            if (extract != null && !extract.isEmpty()) {
                JsonObject res = new JsonObject();
                res.addProperty("title", title);
                res.addProperty("extract", extract);
                return gson.toJson(res);
            }
        }
        JsonObject err = new JsonObject();
        err.addProperty("status", "error");
        err.addProperty("message", "No wiki article found for search query: " + query);
        return gson.toJson(err);
    }

    static String extractSearchQuery(String question) {
        if (question == null) {
            return "";
        }
        String q = question.trim().toLowerCase();

        if (q.endsWith("?")) {
            q = q.substring(0, q.length() - 1).trim();
        }

        String[] prefixes = {
                "what are the ingredients for",
                "what is the drop rate of",
                "what is the drop rate for",
                "what is the recipe for",
                "what are the stats for",
                "what are the stats of",
                "what is the stats for",
                "what is the stats of",
                "where can i find",
                "where can i buy",
                "where can i get",
                "where do i find",
                "where do i buy",
                "where do i get",
                "can you search for",
                "can you look up",
                "tell me about",
                "information on",
                "ingredients for",
                "how do i craft",
                "how do i make",
                "how do i brew",
                "how do i get",
                "recipe for",
                "search for",
                "how to craft",
                "how to make",
                "how to brew",
                "how to get",
                "look up",
                "where is",
                "where are",
                "what is",
                "what are",
                "info on",
                "lookup",
                "how to",
                "how do"
        };

        boolean prefixFound;
        do {
            prefixFound = false;
            for (String prefix : prefixes) {
                if (q.startsWith(prefix)) {
                    q = q.substring(prefix.length()).trim();
                    prefixFound = true;
                    break;
                }
            }
        } while (prefixFound);

        if (q.startsWith("the ")) {
            q = q.substring(4).trim();
        } else if (q.startsWith("a ")) {
            q = q.substring(2).trim();
        } else if (q.startsWith("an ")) {
            q = q.substring(3).trim();
        }

        String[] suffixes = {
                " buy shops locations",
                " shop locations osrs",
                " elemental weakness",
                " shops locations",
                " spawn locations",
                " ingredients for",
                " spawn location",
                " shop locations",
                " shop location",
                " requirements",
                " requirement",
                " ingredients",
                " drop rates",
                " drop table",
                " drop rate",
                " locations",
                " location",
                " weakness",
                " recipe",
                " spawns",
                " shops",
                " spawn",
                " drops",
                " stats",
                " guide",
                " drop",
                " shop",
                " wiki",
                " osrs",
                " buy"
        };

        boolean suffixFound;
        do {
            suffixFound = false;
            for (String suffix : suffixes) {
                if (q.endsWith(suffix)) {
                    String trimmed = q.substring(0, q.length() - suffix.length()).trim();
                    if (!trimmed.isEmpty()) {
                        q = trimmed;
                        suffixFound = true;
                        break;
                    }
                }
            }
        } while (suffixFound);

        return q.isEmpty() ? question : q;
    }

    private String resolveTitleDirectly(String query) {
        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(WIKI_API)).newBuilder()
                    .addQueryParameter("action", "query")
                    .addQueryParameter("titles", query)
                    .addQueryParameter("redirects", "1")
                    .addQueryParameter("format", "json")
                    .build();

            OkHttpClient wikiClient = getWikiClient();
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", OSRS_AI_USER_AGENT)
                    .build();

            try (Response response = wikiClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null)
                    return null;
                JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
                JsonObject queryObj = json.getAsJsonObject("query");
                if (queryObj == null)
                    return null;
                JsonObject pages = queryObj.getAsJsonObject("pages");
                if (pages == null)
                    return null;
                for (Map.Entry<String, com.google.gson.JsonElement> entry : pages.entrySet()) {
                    if ("-1".equals(entry.getKey()))
                        continue;
                    JsonObject page = entry.getValue().getAsJsonObject();
                    if (page.has("title")) {
                        return page.get("title").getAsString();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Direct title resolution failed for: {}", query, e);
        }
        return null;
    }

    private String searchWikiTopResult(String query) {
        // Try to resolve directly first to handle exact titles or redirects correctly
        String directTitle = resolveTitleDirectly(query);
        if (directTitle != null) {
            return directTitle;
        }

        try {
            HttpUrl url = HttpUrl.parse(WIKI_API).newBuilder()
                    .addQueryParameter("action", "query")
                    .addQueryParameter("list", "search")
                    .addQueryParameter("srsearch", query)
                    .addQueryParameter("srnamespace", "0")
                    .addQueryParameter("srlimit", "1")
                    .addQueryParameter("format", "json")
                    .build();

            OkHttpClient wikiClient = getWikiClient();
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", OSRS_AI_USER_AGENT)
                    .build();

            try (Response response = wikiClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null)
                    return null;
                JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
                JsonObject queryObj = json.getAsJsonObject("query");
                if (queryObj == null)
                    return null;
                JsonArray results = queryObj.getAsJsonArray("search");
                if (results == null || results.size() == 0)
                    return null;
                return results.get(0).getAsJsonObject().get("title").getAsString();
            }
        } catch (Exception e) {
            log.warn("Wiki search failed for: {}", query, e);
            return null;
        }
    }

    private String fetchWikiExtract(String title) {
        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(WIKI_API)).newBuilder()
                    .addQueryParameter("action", "query")
                    .addQueryParameter("titles", title)
                    .addQueryParameter("prop", "revisions")
                    .addQueryParameter("rvprop", "content")
                    .addQueryParameter("rvlimit", "1")
                    .addQueryParameter("redirects", "1")
                    .addQueryParameter("format", "json")
                    .build();

            OkHttpClient wikiClient = getWikiClient();
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", OSRS_AI_USER_AGENT)
                    .build();

            try (Response response = wikiClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null)
                    return null;
                JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
                JsonObject queryObj = json.getAsJsonObject("query");
                if (queryObj == null)
                    return null;
                JsonObject pages = queryObj.getAsJsonObject("pages");
                if (pages == null)
                    return null;
                for (Map.Entry<String, com.google.gson.JsonElement> entry : pages.entrySet()) {
                    if ("-1".equals(entry.getKey()))
                        continue;
                    JsonObject page = entry.getValue().getAsJsonObject();
                    if (page.has("revisions")) {
                        JsonArray revisions = page.getAsJsonArray("revisions");
                        if (revisions != null && revisions.size() > 0) {
                            JsonObject rev = revisions.get(0).getAsJsonObject();
                            if (rev.has("*")) {
                                String wikitext = rev.get("*").getAsString();
                                String cleaned = cleanWikitext(wikitext);
                                if (cleaned.length() > WIKI_EXTRACT_CHARS) {
                                    cleaned = cleaned.substring(0, WIKI_EXTRACT_CHARS) + "\n...[truncated]";
                                }
                                return cleaned;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Wiki extract fetch failed for: {}", title, e);
        }
        return null;
    }

    static String cleanWikitext(String wikitext) {
        if (wikitext == null) {
            return "";
        }

        String clean = wikitext.replaceAll("(?s)<!--.*?-->", "");
        clean = clean.replaceAll("(?s)\\{\\|.*?\\|\\}", "");
        clean = clean.replaceAll("(?i)\\[\\[(File|Image|Category):.*?\\]\\]", "");
        clean = clean.replaceAll("\\[\\[[^]]*?\\|([^]]+?)\\]\\]", "$1");
        clean = clean.replaceAll("\\[\\[([^]]+?)\\]\\]", "$1");
        clean = clean.replaceAll("'''(.*?)'''", "**$1**");
        clean = clean.replaceAll("''(.*?)''", "*$1*");

        for (int i = 0; i < MAX_TEMPLATE_REMOVALS; i++) {
            String next = clean.replaceAll("\\{\\{[^{}]*?\\}\\}", "");
            if (next.equals(clean)) {
                break;
            }
            clean = next;
        }

        clean = clean.replaceAll("(?m)^[ \t]*\r?\n", "");

        return clean.trim();
    }

    static String buildSystemPrompt(String context, String recentConversation) {
        String compactConversation = trimToPromptBudget(recentConversation, MAX_RECENT_CONVERSATION_CHARS,
                "...[recent conversation truncated]", true);

        return "You are an OSRS RuneLite assistant. Use OSRS knowledge and treat GAME CONTEXT and tools as truth.\n"
                + "\n"
                + "AVAILABLE TOOLS:\n"
                + "- Player state: 'get_player_skills', 'get_player_inventory', 'get_player_equipment', 'get_player_bank' (when open), 'get_player_status', 'get_player_currencies_and_points', 'get_player_location_details'.\n"
                + "- Activities & tasks: 'get_player_slayer_task', 'get_player_quests', 'get_player_achievement_diaries', 'get_player_combat_achievements', 'get_player_clues'.\n"
                + "- Game info: 'get_item_stats', 'search_osrs_wiki'.\n"
                + "- Call tools to inspect player state rather than guessing. Call 'search_osrs_wiki' to verify monster details, weaknesses, drop rates, item stats, recipes, training methods, and locations.\n"
                + "\n"
                + "GROUNDING RULES:\n"
                + "1. Never invent stats, quests, items, locations, or NPCs for the player.\n"
                + "2. Keep answers concise, direct, practical, and conversational. Do not use markdown headings (# or ##).\n"
                + "3. For Ironman/UIM/GIM accounts, value items by High Alchemy value (haPrice) rather than Grand Exchange price (gePrice), and do not suggest invalid GE trading.\n"
                + "4. Base travel recommendations on the player's location, active spellbook, and inventory/equipment/bank teleportation items. Do not assume standard teleports if on Ancients/Lunar/Arceuus.\n"
                + "5. Never assume obscure items are useless; advise checking wiki/clue steps before alching or destroying unique gear.\n"
                + "6. Do not mix up RS3 features or mechanics with OSRS.\n"
                + "\n"
                + "RECENT CONVERSATION:\n"
                + compactConversation
                + "\n\nGAME CONTEXT:\n"
                + trimToPromptBudget(context, MAX_CONTEXT_CHARACTERS, "...[game context truncated for prompt budget]");
    }

    private boolean isInInstance(Player localPlayer) {
        WorldView worldView = localPlayer.getWorldView();
        if (worldView != null) {
            return worldView.isInstance();
        }

        return client.getTopLevelWorldView() != null && client.getTopLevelWorldView().isInstance();
    }

    private InstanceTemplates getInstanceTemplate(Player localPlayer, WorldPoint worldPoint) {
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

    private String describeAccountType(Integer accountTypeVarbit) {
        if (accountTypeVarbit == null) {
            return "Unknown";
        }

        switch (accountTypeVarbit) {
            case 1:
                return "Ironman";
            case 2:
                return "Ultimate Ironman (UIM)";
            case 3:
                return "Hardcore Ironman (HCIM)";
            case 4:
                return "Group Ironman (GIM)";
            case 5:
                return "Hardcore Group Ironman (HGIM)";
            case 6:
                return "Unranked Group Ironman (UGIM)";
            case 0:
            default:
                return "Normal";
        }
    }

    private String describeSpellbook(int val) {
        switch (val) {
            case 0:
                return "Standard";
            case 1:
                return "Ancient Magicks";
            case 2:
                return "Lunar";
            case 3:
                return "Arceuus";
            default:
                return "Unknown (" + val + ")";
        }
    }

    private static String truncateForNotification(String text) {
        if (text.length() <= 80) {
            return text;
        }
        return text.substring(0, 77) + "...";
    }

    private String getDiaryStatus(int varbitId) {
        int val = client.getVarbitValue(varbitId);
        switch (val) {
            case 0:
                return "Not Started";
            case 1:
                return "In Progress";
            case 2:
                return "Completed";
            default:
                return "Unknown (" + val + ")";
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

    private Integer findItemIdInContainers(String name) {
        String search = name.trim().toLowerCase();
        Set<Integer> checkedIds = new HashSet<>();

        ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
        if (eq != null) {
            for (Item item : eq.getItems()) {
                if (item != null && item.getId() > 0 && checkedIds.add(item.getId())) {
                    if (safeItemName(item.getId()).toLowerCase().contains(search)) {
                        return item.getId();
                    }
                }
            }
        }
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv != null) {
            for (Item item : inv.getItems()) {
                if (item != null && item.getId() > 0 && checkedIds.add(item.getId())) {
                    if (safeItemName(item.getId()).toLowerCase().contains(search)) {
                        return item.getId();
                    }
                }
            }
        }
        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        if (bank != null) {
            for (Item item : bank.getItems()) {
                if (item != null && item.getId() > 0 && checkedIds.add(item.getId())) {
                    if (safeItemName(item.getId()).toLowerCase().contains(search)) {
                        return item.getId();
                    }
                }
            }
        }
        return null;
    }

    private JsonObject buildItemStatsJson(int itemId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", itemId);

        net.runelite.api.ItemComposition comp = null;
        if (itemManager != null) {
            try {
                comp = itemManager.getItemComposition(itemId);
            } catch (Exception ignored) {
            }
        }
        String itemName = (comp != null && comp.getName() != null && !comp.getName().trim().isEmpty())
                ? comp.getName()
                : "Item " + itemId;
        obj.addProperty("name", itemName);

        int gePrice = 0;
        if (itemManager != null) {
            try {
                gePrice = itemManager.getItemPrice(itemId);
            } catch (Exception ignored) {
            }
        }
        if (gePrice <= 0 && "Coins".equals(itemName)) {
            gePrice = 1;
        }
        obj.addProperty("gePrice", gePrice);
        obj.addProperty("haPrice", comp != null ? comp.getHaPrice() : 0);

        ItemStats stats = (itemManager != null) ? itemManager.getItemStats(itemId, false) : null;
        if (stats == null) {
            obj.addProperty("equipable", false);
            return obj;
        }

        obj.addProperty("equipable", stats.isEquipable());
        obj.addProperty("weight", stats.getWeight());
        obj.addProperty("geLimit", stats.getGeLimit());

        if (stats.isEquipable() && stats.getEquipment() != null) {
            ItemEquipmentStats eq = stats.getEquipment();
            JsonObject eqObj = new JsonObject();
            eqObj.addProperty("astab", eq.getAstab());
            eqObj.addProperty("aslash", eq.getAslash());
            eqObj.addProperty("ascrush", eq.getAcrush());
            eqObj.addProperty("asmagic", eq.getAmagic());
            eqObj.addProperty("asrange", eq.getArange());
            eqObj.addProperty("dstab", eq.getDstab());
            eqObj.addProperty("dslash", eq.getDslash());
            eqObj.addProperty("dcrush", eq.getDcrush());
            eqObj.addProperty("dmagic", eq.getDmagic());
            eqObj.addProperty("drange", eq.getDrange());
            eqObj.addProperty("str", eq.getStr());
            eqObj.addProperty("rstr", eq.getRstr());
            eqObj.addProperty("mdmg", eq.getMdmg());
            eqObj.addProperty("prayer", eq.getPrayer());
            eqObj.addProperty("aspeed", eq.getAspeed());

            obj.add("equipment", eqObj);
        }
        return obj;
    }

    private String getSlotName(int index) {
        switch (index) {
            case 0:
                return "Head";
            case 1:
                return "Cape";
            case 2:
                return "Amulet";
            case 3:
                return "Weapon";
            case 4:
                return "Body";
            case 5:
                return "Shield";
            case 6:
                return "Legs";
            case 7:
                return "Gloves";
            case 8:
                return "Boots";
            case 9:
                return "Ring";
            case 10:
                return "Ammo";
            default:
                return "Unknown (" + index + ")";
        }
    }
}
