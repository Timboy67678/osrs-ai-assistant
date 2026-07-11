package com.osrsai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import javax.swing.SwingUtilities;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class AiService {
    private static final double LOW_TEMPERATURE = 0.2d;
    private static final int MAX_CONTEXT_CHARACTERS = 8000;
    private static final int MAX_RECENT_CONVERSATION_CHARS = 1200;
    private static final int MAX_WIKI_CHARS = 1400;
    private static final int WIKI_EXTRACT_CHARS = 650;
    private static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";
    private static final Pattern SLAYER_TASK_PATTERN = Pattern.compile("Slayer Task: \\d+ (.+)", Pattern.MULTILINE);


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

    private final LocationResolver locationResolver = new LocationResolver();

    public void sendQuestion(String question, OsrsAiPanel panel) {
        String apiKey = config.apiKey();
        if (apiKey == null || apiKey.isEmpty()) {
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
                    final String gameContext = buildContext();
                    final AiProvider provider = config.aiProvider();
                    final String clientId = config.clientId();

                    CompletableFuture.supplyAsync(() -> {
                        try {
                            String wikiContext = buildWikiContext(question, gameContext);
                            return appendWikiToContext(gameContext, wikiContext);
                        } catch (Exception e) {
                            log.error("Error building wiki context", e);
                            return gameContext;
                        }
                    }).thenAccept(promptContext -> {
                        try {
                            ProviderHandler handler = provider.getHandler();
                            JsonObject requestBody = handler.buildRequestBody(
                                    provider.getModelId(),
                                    promptContext,
                                    recentConversation,
                                    question,
                                    config.shareCharacterInfo()
                            );

                            executeRequestLoop(provider, apiKey, clientId, requestBody, 0, panel);
                        } catch (Throwable t) {
                            log.error("Error executing API request in supplyAsync callback", t);
                            SwingUtilities.invokeLater(() -> {
                                panel.setThinking(false);
                                panel.addMessage("System", "Error preparing request: " + t.getMessage());
                            });
                        }
                    }).exceptionally(ex -> {
                        log.error("Error in supplyAsync pipeline", ex);
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



    private void executeRequestLoop(AiProvider provider, String apiKey, String clientId, JsonObject requestBody,
            int depth, OsrsAiPanel panel) {
        ProviderHandler handler = provider.getHandler();
        log.info("Sending request to AI provider {}. Depth: {}. Has tools: {}", provider, depth,
                requestBody.has("tools"));
        log.debug("Request body: {}", gson.toJson(requestBody));

        String jsonBody = gson.toJson(requestBody);
        Request request = handler.buildHttpRequest(provider.getModelId(), apiKey, clientId, jsonBody);

        OkHttpClient aiClient = okHttpClient.newBuilder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

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

                    if (depth < 3 && handler.hasToolCalls(root)) {
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

                            // Send updated request recursively
                            executeRequestLoop(provider, apiKey, clientId, requestBody, depth + 1, panel);
                        });
                    } else {
                        // Normal text response
                        String aiResponseText = handler.extractResponseText(root);
                        String cleanResponse = aiResponseText.trim();

                        SwingUtilities.invokeLater(() -> {
                            panel.setThinking(false);
                            panel.addMessage("AI", cleanResponse);
                            if (config.notifyOnResponse()) {
                                notifier.notify("AI Assistant: " + truncateForNotification(cleanResponse));
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

    private String executeWikiSearch(String query) {
        String title = searchWikiTopResult(query);
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
                                clientThreadResult[0] = executeToolOnClientThread(tc.name);
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
                } catch (Exception ex) {
                    log.error("Error executing tool: " + tc.name, ex);
                    JsonObject err = new JsonObject();
                    err.addProperty("status", "error");
                    err.addProperty("message", ex.getMessage());
                    results.add(new ToolResult(tc, gson.toJson(err)));
                }
            }
            future.complete(results);
        });
        return future;
    }

    private String executeToolOnClientThread(String name) {
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
                    Map<String, Long> aggregated = aggregateItems(invContainer);
                    for (Map.Entry<String, Long> entry : aggregated.entrySet()) {
                        invItems.addProperty(entry.getKey(), entry.getValue());
                    }
                }
                result.add("items", invItems);
                break;

            case "get_player_equipment":
                JsonObject eqItems = new JsonObject();
                ItemContainer eqContainer = client.getItemContainer(InventoryID.EQUIPMENT);
                if (eqContainer != null) {
                    Map<String, Long> aggregated = aggregateItems(eqContainer);
                    for (Map.Entry<String, Long> entry : aggregated.entrySet()) {
                        eqItems.addProperty(entry.getKey(), entry.getValue());
                    }
                }
                result.add("items", eqItems);
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
                JsonArray inProgress = new JsonArray();
                for (Quest quest : Quest.values()) {
                    if (quest.getState(client) == QuestState.IN_PROGRESS) {
                        inProgress.add(quest.getName());
                    }
                }
                result.add("inProgressQuests", inProgress);
                break;

            case "get_player_bank":
                ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
                if (bankContainer == null || bankContainer.getItems().length == 0) {
                    result.addProperty("status", "error");
                    result.addProperty("message",
                            "The bank is not currently open. Ask the player to open their bank if they want you to check bank items.");
                } else {
                    JsonObject bankItems = new JsonObject();
                    Map<String, Long> aggregated = aggregateItems(bankContainer);
                    for (Map.Entry<String, Long> entry : aggregated.entrySet()) {
                        bankItems.addProperty(entry.getKey(), entry.getValue());
                    }
                    result.add("items", bankItems);
                }
                break;

            default:
                result.addProperty("status", "error");
                result.addProperty("message", "Unknown tool: " + name);
                break;
        }
        return gson.toJson(result);
    }

    private Map<String, Long> aggregateItems(ItemContainer container) {
        Map<String, Long> aggregatedItems = new LinkedHashMap<>();
        for (Item item : container.getItems()) {
            if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) {
                continue;
            }
            String itemName = safeItemName(item.getId());
            aggregatedItems.put(itemName, aggregatedItems.getOrDefault(itemName, 0L) + item.getQuantity());
        }
        return aggregatedItems;
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

    @SuppressWarnings("deprecation")
    private String buildContext() {
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
        return normalized.substring(0, keepLength) + "\n" + safeLabel;
    }

    private boolean isSlayerRelated(String question, String monster) {
        if (question == null)
            return false;
        String q = question.toLowerCase();
        return q.contains("slayer") || q.contains("task") || q.contains("monster") || q.contains("kill")
                || q.contains("fight") || q.contains("weakness") || q.contains("combat") || q.contains("gear")
                || (monster != null && q.contains(monster.toLowerCase()));
    }

    private static final Set<String> GREETINGS = new HashSet<>(Arrays.asList(
            "hi", "hello", "hey", "yo", "sup", "thanks", "thank you", "bye", "goodbye"));

    private static boolean isGreeting(String query) {
        if (query == null) {
            return false;
        }
        return GREETINGS.contains(query.toLowerCase().trim());
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
                "what is the recipe for",
                "what is the drop rate of",
                "what is the drop rate for",
                "where can i find",
                "where do i find",
                "how do i make",
                "how to make",
                "how do i get",
                "how to get",
                "how do i craft",
                "how to craft",
                "how do i brew",
                "how to brew",
                "what are the stats for",
                "what are the stats of",
                "what is the stats of",
                "what is the stats for",
                "can you search for",
                "can you look up",
                "tell me about",
                "information on",
                "ingredients for",
                "recipe for",
                "search for",
                "lookup",
                "look up",
                "where is",
                "where are",
                "what is",
                "what are",
                "how to",
                "how do i",
                "info on"
        };

        for (String prefix : prefixes) {
            if (q.startsWith(prefix)) {
                q = q.substring(prefix.length()).trim();
                break;
            }
        }

        if (q.startsWith("the ")) {
            q = q.substring(4).trim();
        }

        return q.isEmpty() ? question : q;
    }

    private String buildWikiContext(String question, String gameContext) {
        List<String> sections = new ArrayList<>();
        List<String> fetchedTitles = new ArrayList<>();

        // Search wiki based on the user's question — handles "what spell for X", "how
        // to unlock Y", etc.
        String cleanQuery = extractSearchQuery(question);
        if (!cleanQuery.isEmpty() && !isGreeting(cleanQuery)) {
            String questionTitle = searchWikiTopResult(cleanQuery);
            if (questionTitle != null) {
                String extract = fetchWikiExtract(questionTitle);
                if (extract != null && !extract.isEmpty()) {
                    sections.add(questionTitle + ":\n" + extract);
                    fetchedTitles.add(questionTitle.toLowerCase());
                }
            }
        }

        // Include the slayer task monster only if active and the question is
        // slayer/combat related.
        String slayerMonster = extractSlayerMonster(gameContext);
        if (slayerMonster != null && !slayerMonster.isEmpty() && isSlayerRelated(question, slayerMonster)) {
            boolean alreadyCovered = fetchedTitles.stream()
                    .anyMatch(t -> t.contains(slayerMonster.toLowerCase()) || slayerMonster.toLowerCase().contains(t));
            if (!alreadyCovered) {
                String extract = fetchWikiExtract(slayerMonster);
                if (extract != null && !extract.isEmpty()) {
                    sections.add(slayerMonster + ":\n" + extract);
                }
            }
        }

        return String.join("\n\n", sections);
    }

    private String searchWikiTopResult(String query) {
        try {
            HttpUrl url = HttpUrl.parse(WIKI_API).newBuilder()
                    .addQueryParameter("action", "query")
                    .addQueryParameter("list", "search")
                    .addQueryParameter("srsearch", query)
                    .addQueryParameter("srnamespace", "0")
                    .addQueryParameter("srlimit", "1")
                    .addQueryParameter("format", "json")
                    .build();

            OkHttpClient wikiClient = wikiHttpClient();
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
                if (results == null || results.isEmpty())
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
                    .addQueryParameter("prop", "extracts")
                    .addQueryParameter("exintro", "true")
                    .addQueryParameter("exchars", String.valueOf(WIKI_EXTRACT_CHARS))
                    .addQueryParameter("explaintext", "true")
                    .addQueryParameter("redirects", "1")
                    .addQueryParameter("format", "json")
                    .build();

            OkHttpClient wikiClient = wikiHttpClient();
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
                    if (page.has("extract")) {
                        String extract = page.get("extract").getAsString().trim();
                        if (!extract.isEmpty())
                            return extract;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Wiki extract fetch failed for: {}", title, e);
        }
        return null;
    }

    private OkHttpClient wikiHttpClient() {
        return okHttpClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    private static String appendWikiToContext(String gameContext, String wikiContext) {
        if (wikiContext == null || wikiContext.isEmpty()) {
            return gameContext;
        }
        return gameContext
                + "\n\n--- OSRS WIKI REFERENCE (authoritative for mechanics, weaknesses, requirements) ---\n"
                + trimToPromptBudget(wikiContext, MAX_WIKI_CHARS, "...[wiki truncated]");
    }

    private static String extractSlayerMonster(String context) {
        if (context == null)
            return null;
        Matcher matcher = SLAYER_TASK_PATTERN.matcher(context);
        if (matcher.find()) {
            String monster = matcher.group(1).trim();
            return monster.equalsIgnoreCase("none") ? null : monster;
        }
        return null;
    }

    static String buildSystemPrompt(String context, String recentConversation) {
        String compactConversation = trimToPromptBudget(recentConversation, MAX_RECENT_CONVERSATION_CHARS,
                "...[recent conversation truncated]");

        return "You are an OSRS RuneLite assistant. Use OSRS knowledge, and treat the GAME CONTEXT and tools as truth for the player.\n"
                + "\n"
                + "INTEGRATION TOOLS:\n"
                + "- You have tools to query skills, inventory, equipment, slayer tasks, quest progress, and bank (when open).\n"
                + "- Call them when the player asks about stats, items, progress, or general goals/progression advice (query skills/quests first for tailored advice).\n"
                + "- Do not guess player details; call the relevant tools to check.\n"
                + "\n"
                + "GROUNDING RULES:\n"
                + "1. Never invent stats, quests, items, locations, or NPCs for the player's character.\n"
                + "2. If user state details (stats, inventory, etc.) are missing from GAME CONTEXT, call the correct tool. If tools return an error/disabled, explain that sharing is disabled or the player is logged out, but still provide general advice.\n"
                + "3. Base player-specific advice on retrieved details; clarify when advice is a general recommendation.\n"
                + "4. If location name is approximate, say so.\n"
                + "5. For Ironman/UIM/GIM accounts, do not recommend invalid trading, Grand Exchange, or banking options.\n"
                + "6. Respect disabled tools/errors and keep answers practical and concise. Avoid markdown headings.\n"
                + "7. Treat OSRS WIKI REFERENCE as authoritative for mechanics, weaknesses, NPC details, and requirements.\n"
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

    private static String truncateForNotification(String text) {
        if (text.length() <= 80) {
            return text;
        }
        return text.substring(0, 77) + "...";
    }
}
