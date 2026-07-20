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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.jetbrains.annotations.NotNull;

@Slf4j
public class AiService {
    // URI Constants
    private static final String DEFAULT_CUSTOM_ENDPOINT = "http://localhost:11434/v1/chat/completions"; // Ollama

    // Global Constants
    static final int MAX_DEPTH_COUNT = 10;

    // Game Varbits & Varplayer (Varp) IDs
    private static final int VARBIT_SPELLBOOK = 4070;
    private static final int VARBIT_TITHE_FARM_POINTS = 4893;
    private static final int VARBIT_WILDERNESS_LEVEL = 5963;
    private static final int VARP_SPECIAL_ATTACK_PERCENT = 300;
    private static final int VARP_NMZ_REWARD_POINTS = 1056;
    private static final int VARP_PEST_CONTROL_POINTS = 261;
    private static final int POISON_VENOM_THRESHOLD = 1000000;

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

    // Currency & Activity Item IDs
    private static final int ITEM_ID_MARK_OF_GRACE = 11849;
    private static final int ITEM_ID_GOLDEN_NUGGET = 12012;
    private static final int ITEM_ID_ABYSSAL_PEARL = 26884;
    private static final int ITEM_ID_TOKKUL = 6529;
    private static final int ITEM_ID_STARDUST = 25527;
    private static final int ITEM_ID_ARCHERY_TICKET = 1464;
    private static final int ITEM_ID_MERMAIDS_TEAR = 27433;

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

                    ToolDefinition def = getToolRegistry().stream()
                            .filter(d -> d.name.equals(tc.name))
                            .findFirst()
                            .orElse(null);

                    if (def == null) {
                        throw new IllegalArgumentException("Unknown tool: " + tc.name);
                    }

                    String output;
                    if (def.runOnClientThread) {
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
                        clientFuture.join();
                        if (clientThreadError[0] != null) {
                            throw new Exception(clientThreadError[0]);
                        }
                        output = clientThreadResult[0];
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
        });
        return future;
    }

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
        int[] milestones = { 50, 60, 70, 80, 90, 99 };
        for (int level : milestones) {
            int targetXp = Experience.getXpForLevel(level);
            if (currentXp < targetXp) {
                skillData.addProperty("xpTo" + level, targetXp - currentXp);
            }
        }
    }

    String executeGetPlayerSkills(JsonObject args) {
        JsonObject result = new JsonObject();
        String filterSkill = (args != null && args.has("skill")) ? normalizeSkillName(args.get("skill").getAsString())
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

                addMilestoneXp(skillData, xp);

                result.add(skillName, skillData);
            }
        }
        return gson.toJson(result);
    }

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

    String executeGetPlayerSlayerTask(JsonObject args) {
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

    String executeGetPlayerQuests(JsonObject args) {
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
        boolean includeInProgress = "DEFAULT".equals(statusFilter) || "IN_PROGRESS".equals(statusFilter)
                || "ALL".equals(statusFilter);
        boolean includeNotStarted = "DEFAULT".equals(statusFilter) || "NOT_STARTED".equals(statusFilter)
                || "ALL".equals(statusFilter);

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
                    String description = struct.getStringValue(CA_STRUCT_PARAM_DESCRIPTION);
                    int id = struct.getIntValue(CA_STRUCT_PARAM_TASK_ID);
                    int typeId = struct.getIntValue(CA_STRUCT_PARAM_TYPE);
                    String type = CA_TYPE_MAP.get(typeId);
                    int bossId = struct.getIntValue(CA_STRUCT_PARAM_BOSS_ID);
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
                        errorObj.addProperty("error", "Item not found in equipment, inventory, or bank.");
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

                                net.runelite.client.ui.overlay.components.PanelComponent panel = new net.runelite.client.ui.overlay.components.PanelComponent();
                                try {
                                    clue.makeOverlayHint(panel, clueScrollPlugin);
                                    JsonArray hintLines = new JsonArray();
                                    for (Object child : panel.getChildren()) {
                                        if (child instanceof net.runelite.client.ui.overlay.components.LineComponent) {
                                            net.runelite.client.ui.overlay.components.LineComponent lc = (net.runelite.client.ui.overlay.components.LineComponent) child;

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
                if (client.isPrayerActive(p)) {
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
            String pts = configManager.getRSProfileConfiguration("slayer", "points");
            if (pts == null || pts.isEmpty())
                pts = configManager.getConfiguration("slayer", "points");
            if (pts != null && !pts.isEmpty()) {
                points.addProperty("slayerPoints", Integer.parseInt(pts));
            }
            String strk = configManager.getRSProfileConfiguration("slayer", "streak");
            if (strk == null || strk.isEmpty())
                strk = configManager.getConfiguration("slayer", "streak");
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
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        ItemContainer bank = client.getItemContainer(InventoryID.BANK);

        for (Map.Entry<String, Integer> entry : targetItemNames.entrySet()) {
            String name = entry.getKey();
            int targetId = entry.getValue();
            long total = 0;
            if (inv != null) {
                for (Item item : inv.getItems()) {
                    if (item != null && item.getId() == targetId)
                        total += item.getQuantity();
                }
            }
            if (bank != null) {
                for (Item item : bank.getItems()) {
                    if (item != null && item.getId() == targetId)
                        total += item.getQuantity();
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
        int spellbookVar = client.getVarbitValue(VARBIT_SPELLBOOK);
        sb.append("Active Spellbook: ").append(PromptUtils.describeSpellbook(spellbookVar)).append("\n");
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

}
