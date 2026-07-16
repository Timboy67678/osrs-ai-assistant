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
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.InstanceTemplates;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
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
    // Global Constants
    static final int MAX_DEPTH_COUNT = 10;

    // Token Constants
    private static final int MAX_CONTEXT_CHARACTERS = 8000;
    private static final int MAX_RECENT_CONVERSATION_CHARS = 1200;
    private static final int WIKI_EXTRACT_CHARS = 2500;

    // URI Constants
    private static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";

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
                            : "http://localhost:11434/v1/chat/completions";

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
                    String output;
                    if ("search_osrs_wiki".equals(tc.name)) {
                        String query = tc.args.has("query") ? tc.args.get("query").getAsString() : "";
                        output = executeWikiSearch(query);
                    } else {
                        // Client-thread bound tools
                        final String[] clientThreadResult = new String[1];
                        final Throwable[] clientThreadError = new Throwable[1];
                        CompletableFuture<Void> clientFuture = new CompletableFuture<>();
                        clientThread.invokeLater(() -> {
                            try {
                                clientThreadResult[0] = executeToolOnClientThread(tc.name, tc.args);
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

    private String executeToolOnClientThread(String name, JsonObject args) {
        JsonObject result = new JsonObject();
        switch (name) {
            case "get_player_skills":
                for (Skill skill : Skill.values()) {
                    if (!"OVERALL".equals(skill.name())) {
                        result.addProperty(skill.getName(),
                                client.getBoostedSkillLevel(skill) + "/" + client.getRealSkillLevel(skill));
                    }
                }
                break;

            case "get_player_inventory":
                JsonObject invItems = new JsonObject();
                ItemContainer invContainer = client.getItemContainer(InventoryID.INVENTORY);
                if (invContainer != null) {
                    invItems = aggregateItemsWithPrices(invContainer, null, 0);
                }
                result.add("items", invItems);
                break;

            case "get_player_equipment":
                JsonObject eqSlots = new JsonObject();
                ItemContainer eqContainer = client.getItemContainer(InventoryID.EQUIPMENT);
                if (eqContainer != null) {
                    Item[] items = eqContainer.getItems();
                    for (int i = 0; i < items.length; i++) {
                        Item item = items[i];
                        if (item == null || item.getId() <= 0) {
                            continue;
                        }
                        String slotName = getSlotName(i);
                        JsonObject itemDetail = new JsonObject();
                        itemDetail.addProperty("id", item.getId());
                        itemDetail.addProperty("name", safeItemName(item.getId()));
                        itemDetail.addProperty("qty", item.getQuantity());

                        int gePrice = 0;
                        if (itemManager != null) {
                            try {
                                gePrice = itemManager.getItemPrice(item.getId());
                            } catch (Exception ignored) {}
                        }
                        if (gePrice <= 0 && "Coins".equals(safeItemName(item.getId()))) {
                            gePrice = 1;
                        }
                        itemDetail.addProperty("gePrice", gePrice);
                        itemDetail.addProperty("haPrice", safeHighAlchPrice(item.getId()));

                        ItemStats stats = itemManager.getItemStats(item.getId(), false);
                        if (stats != null && stats.getEquipment() != null) {
                            ItemEquipmentStats eq = stats.getEquipment();
                            JsonObject statsObj = new JsonObject();
                            statsObj.addProperty("astab", eq.getAstab());
                            statsObj.addProperty("aslash", eq.getAslash());
                            statsObj.addProperty("ascrush", eq.getAcrush());
                            statsObj.addProperty("asmagic", eq.getAmagic());
                            statsObj.addProperty("asrange", eq.getArange());
                            statsObj.addProperty("dstab", eq.getDstab());
                            statsObj.addProperty("dslash", eq.getDslash());
                            statsObj.addProperty("dcrush", eq.getDcrush());
                            statsObj.addProperty("dmagic", eq.getDmagic());
                            statsObj.addProperty("drange", eq.getDrange());
                            statsObj.addProperty("str", eq.getStr());
                            statsObj.addProperty("rstr", eq.getRstr());
                            statsObj.addProperty("mdmg", eq.getMdmg());
                            statsObj.addProperty("prayer", eq.getPrayer());
                            statsObj.addProperty("aspeed", eq.getAspeed());
                            itemDetail.add("stats", statsObj);
                        }
                        eqSlots.add(slotName, itemDetail);
                    }
                }
                result.add("slots", eqSlots);
                break;

            case "get_player_slayer_task":
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
                break;

            case "get_player_quests":
                int qp = client.getVarpValue(VarPlayerID.QP);
                result.addProperty("questPoints", qp);
                JsonArray completed = new JsonArray();
                JsonArray inProgress = new JsonArray();
                for (Quest quest : Quest.values()) {
                    QuestState state = quest.getState(client);
                    if (state == QuestState.FINISHED) {
                        completed.add(quest.getName());
                    } else if (state == QuestState.IN_PROGRESS) {
                        inProgress.add(quest.getName());
                    }
                }
                result.add("completedQuests", completed);
                result.add("inProgressQuests", inProgress);
                break;

            case "get_player_achievement_diaries":
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
                break;

            case "get_player_bank":
                ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
                if (bankContainer == null || bankContainer.getItems().length == 0) {
                    result.addProperty("status", "error");
                    result.addProperty("message",
                            "The bank is not currently open. Ask the player to open their bank if they want you to check bank items.");
                } else {
                    String filter = (args != null && args.has("filter")) ? args.get("filter").getAsString() : null;
                    int minValue = (args != null && args.has("minValue")) ? args.get("minValue").getAsInt() : 0;
                    result.add("items", aggregateItemsWithPrices(bankContainer, filter, minValue));
                }
                break;

            case "get_item_stats":
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
                break;

            case "get_player_clues":
            {
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
                                    net.runelite.client.plugins.cluescrolls.ClueScrollPlugin clueScrollPlugin = 
                                        (net.runelite.client.plugins.cluescrolls.ClueScrollPlugin) p;
                                    net.runelite.client.plugins.cluescrolls.clues.ClueScroll clue = clueScrollPlugin.getClue();
                                    if (clue != null) {
                                        activeClueObj.addProperty("status", "Active clue scroll detected");
                                        activeClueObj.addProperty("type", clue.getClass().getSimpleName());

                                        // Render hint using PanelComponent
                                        net.runelite.client.ui.overlay.components.PanelComponent panel = 
                                            new net.runelite.client.ui.overlay.components.PanelComponent();
                                        try {
                                            clue.makeOverlayHint(panel, clueScrollPlugin);
                                            JsonArray hintLines = new JsonArray();
                                            for (Object child : panel.getChildren()) {
                                                if (child instanceof net.runelite.client.ui.overlay.components.LineComponent) {
                                                    net.runelite.client.ui.overlay.components.LineComponent lc = 
                                                        (net.runelite.client.ui.overlay.components.LineComponent) child;
                                                    
                                                    // Use reflection to read private left/right fields to bypass getter compilation issue
                                                    String left = "";
                                                    String right = "";
                                                    try {
                                                        java.lang.reflect.Field leftField = lc.getClass().getDeclaredField("left");
                                                        leftField.setAccessible(true);
                                                        left = (String) leftField.get(lc);
                                                    } catch (Exception ignored) {}
                                                    
                                                    try {
                                                        java.lang.reflect.Field rightField = lc.getClass().getDeclaredField("right");
                                                        rightField.setAccessible(true);
                                                        right = (String) rightField.get(lc);
                                                    } catch (Exception ignored) {}

                                                    if (left != null && !left.trim().isEmpty()) {
                                                        if (right != null && !right.trim().isEmpty()) {
                                                            hintLines.add(left + ": " + right);
                                                        } else {
                                                            hintLines.add(left);
                                                        }
                                                    }
                                                } else if (child instanceof net.runelite.client.ui.overlay.components.TitleComponent) {
                                                    net.runelite.client.ui.overlay.components.TitleComponent tc = 
                                                        (net.runelite.client.ui.overlay.components.TitleComponent) child;
                                                    
                                                    // Use reflection to read private text field
                                                    String text = "";
                                                    try {
                                                        java.lang.reflect.Field textField = tc.getClass().getDeclaredField("text");
                                                        textField.setAccessible(true);
                                                        text = (String) textField.get(tc);
                                                    } catch (Exception ignored) {}

                                                    if (text != null && !text.trim().isEmpty()) {
                                                        hintLines.add(text);
                                                    }
                                                } else {
                                                    hintLines.add(child.toString());
                                                }
                                            }
                                            activeClueObj.add("details", hintLines);
                                        } catch (Throwable t) {
                                            activeClueObj.addProperty("error", "Failed to format clue details: " + t.getMessage());
                                        }
                                    } else {
                                        activeClueObj.addProperty("status", "No active clue scroll step loaded. Ask the player to read/open their clue scroll once to activate tracking.");
                                    }
                                }
                            } else {
                                activeClueObj.addProperty("status", "RuneLite's built-in Clue Scroll plugin is disabled in the client settings. Ask the player to enable it.");
                            }
                            break;
                        }
                    }
                }

                if (!foundPlugin) {
                    activeClueObj.addProperty("status", "RuneLite's built-in Clue Scroll plugin was not found.");
                }

                result.add("activeClue", activeClueObj);
                break;
            }

            default:
                result.addProperty("status", "error");
                result.addProperty("message", "Unknown tool: " + name);
                break;
        }
        return gson.toJson(result);
    }

    private JsonObject aggregateItemsWithPrices(ItemContainer container, String filter, int minValue) {
        JsonObject result = new JsonObject();
        Map<String, Long> quantities = new LinkedHashMap<>();
        Map<String, Integer> itemIds = new HashMap<>();

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
            if (itemManager != null) {
                try {
                    net.runelite.api.ItemComposition comp = itemManager.getItemComposition(item.getId());
                    if (comp != null && comp.getPlaceholderTemplateId() != -1) {
                        continue;
                    }
                } catch (Exception e) {
                    // Ignore composition errors, default to not ignoring
                }
            }
            String itemName = safeItemName(item.getId());

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
        }

        // Help sort items by value
        class BankItem {
            final String name;
            final long qty;
            final int gePrice;
            final int haPrice;
            final int sortVal;

            BankItem(String name, long qty, int gePrice, int haPrice) {
                this.name = name;
                this.qty = qty;
                this.gePrice = gePrice;
                this.haPrice = haPrice;
                this.sortVal = isIron ? haPrice : gePrice;
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
            int haPrice = safeHighAlchPrice(itemId);

            // Apply minimum value filter if present based on account type preference
            int checkVal = isIron ? haPrice : price;
            if (minValue > 0 && checkVal < minValue) {
                continue;
            }

            list.add(new BankItem(name, qty, price, haPrice));
        }

        // Sort by sortVal descending
        list.sort((a, b) -> Integer.compare(b.sortVal, a.sortVal));

        // If there is no name filter, let's limit the bank output to a reasonable size
        // to prevent timeout and context token bloat. If there is a filter, we return
        // all matches.
        int limit = (search == null) ? 200 : Integer.MAX_VALUE;
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

    private int safeHighAlchPrice(int itemId) {
        try {
            return itemManager.getItemComposition(itemId).getHaPrice();
        } catch (Exception ex) {
            return 0;
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

    public static class ToolDefinition {
        public final String name;
        public final String description;
        public final List<ToolParameter> parameters = new ArrayList<>();
        public final boolean requiresCharacterInfo;

        public ToolDefinition(String name, String description, boolean requiresCharacterInfo) {
            this.name = name;
            this.description = description;
            this.requiresCharacterInfo = requiresCharacterInfo;
        }

        public ToolDefinition addParam(String name, String type, String description, boolean required) {
            this.parameters.add(new ToolParameter(name, type, description, required));
            return this;
        }
    }

    public static List<ToolDefinition> getToolRegistry() {
        List<ToolDefinition> registry = new ArrayList<>();

        registry.add(new ToolDefinition("get_player_skills",
            "Retrieve the player's current levels (both real and boosted) for all skills.", true));

        registry.add(new ToolDefinition("get_player_inventory",
            "Retrieve the items, quantities, Grand Exchange prices, and High Alchemy values currently in the player's inventory.", true));

        registry.add(new ToolDefinition("get_player_equipment",
            "Retrieve the items, quantities, Grand Exchange prices, and High Alchemy values currently equipped by the player.", true));

        registry.add(new ToolDefinition("get_player_slayer_task",
            "Retrieve the player's current Slayer task, remaining quantity, current Slayer points, and current streak.", true));

        registry.add(new ToolDefinition("get_player_quests",
            "Retrieve the player's quest points, and lists of completed and in-progress quests.", true));

        registry.add(new ToolDefinition("get_player_achievement_diaries",
            "Retrieve the player's Achievement Diary completion progress for all regions and tiers (Easy, Medium, Hard, Elite).", true));

        registry.add(new ToolDefinition("get_player_bank",
            "Retrieve the items, quantities, Grand Exchange prices, and High Alchemy values currently in the player's bank. Only works if the bank interface is open.", true)
                .addParam("filter", "string", "Optional search query to filter bank items by name (case-insensitive).", false)
                .addParam("minValue", "integer", "Optional minimum value to filter items.", false));

        registry.add(new ToolDefinition("get_item_stats",
            "Retrieve detailed equipment statistics, combat bonuses, weight, slot, and prices for a list of item IDs or item names.", true)
                .addParam("itemIds", "array_integer", "Optional list of OSRS item IDs to retrieve stats for.", false)
                .addParam("itemNames", "array_string", "Optional list of item names to search for in containers and retrieve stats.", false));

        registry.add(new ToolDefinition("get_player_clues",
            "Retrieve details about the player's active clue scroll (current step text, requirements, and solution) if they are in the middle of one, as well as a list of clue scroll items currently in their inventory or bank.", true));

        registry.add(new ToolDefinition("search_osrs_wiki",
            "Search the Old School RuneScape Wiki for authoritative mechanics, stats, requirements, locations, farming patches, training methods, and information.", false)
                .addParam("query", "string", "The exact entity, location, farming patch, training method, or topic to search for (e.g. 'Sharp Eye', 'Abyssal whip', 'Barrows', 'Farming patches').", true));

        return registry;
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
        sb.append("Run Energy: ").append(client.getEnergy()).append("%\n");
        sb.append("Weight: ").append(client.getWeight()).append(" kg\n");
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
                    .header("User-Agent", "OSRS AI Assistant RuneLite Plugin")
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
                    .header("User-Agent", "OSRS AI Assistant RuneLite Plugin")
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
                    .header("User-Agent", "OSRS AI Assistant RuneLite Plugin")
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
                                if (wikitext.length() > WIKI_EXTRACT_CHARS) {
                                    wikitext = wikitext.substring(0, WIKI_EXTRACT_CHARS) + "\n...[truncated]";
                                }
                                return wikitext;
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

    static String buildSystemPrompt(String context, String recentConversation) {
        String compactConversation = trimToPromptBudget(recentConversation, MAX_RECENT_CONVERSATION_CHARS,
                "...[recent conversation truncated]", true);

        return "You are an OSRS RuneLite assistant. Use OSRS knowledge, and treat the GAME CONTEXT and tools as truth for the player.\n"
                + "\n"
                + "INTEGRATION TOOLS:\n"
                + "- You have tools to query skills, inventory, equipment, slayer tasks, quest progress, achievement diaries, and bank (when open).\n"
                + "- Call them when the player asks about stats, items, progress, or general goals/progression advice (query skills/quests/diaries first for tailored advice).\n"
                + "- When asked about travel, reaching a destination, or teleportation, you MUST call the relevant inventory/equipment/bank tools to check if the player has teleportation items (such as Book of the Dead, Chronicle, teleport tablets, jewelry, runes) equipped, in inventory, or in their bank (if open) to tailor the travel route. Do not guess or assume their items.\n"
                + "- Do not guess player details; call the relevant tools to check.\n"
                + "- Always call the 'search_osrs_wiki' tool when asked about monster details (locations, weaknesses, drop rates), item details (recipes, uses, equipment slots/hands, stats), slayer/quest requirements, farming patch locations/types/mechanics, skilling training methods, shop locations, shop stock, or travel/teleportation options. Do not guess these facts.\n"
                + "\n"
                + "GROUNDING RULES:\n"
                + "1. Never invent stats, quests, items, locations, or NPCs for the player's character.\n"
                + "2. If user state details (stats, inventory, etc.) are missing from GAME CONTEXT, call the correct tool. If tools return an error/disabled, explain that sharing is disabled or the player is logged out, but still provide general advice.\n"
                + "3. Base player-specific advice on retrieved details; clarify when advice is a general recommendation.\n"
                + "4. If location name is approximate, say so.\n"
                + "5. For Ironman/UIM/GIM accounts, do not recommend invalid trading, Grand Exchange, or banking options.\n"
                + "6. Respect disabled tools/errors and keep answers practical and concise. Avoid markdown headings.\n"
                + "7. Treat OSRS WIKI REFERENCE as authoritative for mechanics, weaknesses, NPC details, requirements, item stats/slots/hands, farming patch locations/types, skilling training methods, shop locations, shop stock, and travel/teleportation options. You MUST call the 'search_osrs_wiki' tool to verify these details rather than relying on your pre-trained memory, which may be outdated or incorrect.\n"
                + "8. Use tool result data to answer the user's original question. Do not change the conversation topic to unrelated tool outputs if they do not address the user's query.\n"
                + "9. Never assume or state that a skilling/farming patch, dungeon, monster, NPC, or shop exists in a specific location unless you have verified it using the 'search_osrs_wiki' tool or it is explicitly mentioned in the GAME CONTEXT.\n"
                + "10. Never guess, assume, or invent item prices or High Alchemy values (especially holiday items like partyhats or Santa hats, which are inexpensive/common in OSRS unlike RS3). Trust the prices and High Alchemy values (haPrice) provided in the tool outputs (such as bank/inventory tools), or call 'search_osrs_wiki' to find or verify the price of an item. For Ironman/UIM/GIM accounts, define the 'value' or 'expense' of items using their High Alchemy value (haPrice) rather than their Grand Exchange price (gePrice) because they cannot trade; prioritize and quote High Alchemy values for them when asked about value or the most expensive items (though you can mention the GE price as secondary info).\n"
                + "11. When recommending travel routes, check the player's active spellbook in GAME CONTEXT. If they are on Ancients, Lunar, or Arceuus, remember they do NOT have access to standard spellbook teleports (e.g. Varrock, Falador, Lumbridge, Camelot, Ardougne teleports) unless they use teleport tablets, a portal chamber, or specific teleportation items (like Chronicle, Ring of wealth, Book of the dead). Do not recommend running massive distances across Gielinor when much closer vendors or options exist.\n"
                + "12. Never assume or guess whether an item is one-handed or two-handed, or what equipment slot/stats it has. Always call the 'search_osrs_wiki' tool to verify an item's hands or slot details if not explicitly clear from the current game context.\n"
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
        ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
        if (eq != null) {
            for (Item item : eq.getItems()) {
                if (item != null && item.getId() > 0 && safeItemName(item.getId()).toLowerCase().contains(search)) {
                    return item.getId();
                }
            }
        }
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv != null) {
            for (Item item : inv.getItems()) {
                if (item != null && item.getId() > 0 && safeItemName(item.getId()).toLowerCase().contains(search)) {
                    return item.getId();
                }
            }
        }
        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        if (bank != null) {
            for (Item item : bank.getItems()) {
                if (item != null && item.getId() > 0 && safeItemName(item.getId()).toLowerCase().contains(search)) {
                    return item.getId();
                }
            }
        }
        return null;
    }

    private JsonObject buildItemStatsJson(int itemId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", itemId);
        obj.addProperty("name", safeItemName(itemId));

        int gePrice = 0;
        if (itemManager != null) {
            try {
                gePrice = itemManager.getItemPrice(itemId);
            } catch (Exception ignored) {}
        }
        obj.addProperty("gePrice", gePrice);
        obj.addProperty("haPrice", safeHighAlchPrice(itemId));

        ItemStats stats = itemManager.getItemStats(itemId, false);
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
            case 0: return "Head";
            case 1: return "Cape";
            case 2: return "Amulet";
            case 3: return "Weapon";
            case 4: return "Body";
            case 5: return "Shield";
            case 6: return "Legs";
            case 7: return "Gloves";
            case 8: return "Boots";
            case 9: return "Ring";
            case 10: return "Ammo";
            default: return "Unknown (" + index + ")";
        }
    }
}
