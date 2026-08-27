package com.osrsai.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Varbits;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Tool implementations for time tracking, birdhouse traps, Hespori growth,
 * Tears of Guthix cooldown, Kingdom of Miscellania calculation, daily task
 * collectibles,
 * and RuneLite FarmingTracker patch prediction reflection.
 */
@Slf4j
public class FarmingAndTimerTools {
    private static final Pattern PATTERN_HTML_TAGS = Pattern.compile("<[^>]*>");

    private static final int VARBIT_BIRDHOUSE_MEADOW_NORTH = 6521;
    private static final int VARBIT_BIRDHOUSE_MEADOW_SOUTH = 6522;
    private static final int VARBIT_BIRDHOUSE_VALLEY_NORTH = 6523;
    private static final int VARBIT_BIRDHOUSE_VALLEY_SOUTH = 6524;

    private static final int VARBIT_HESPORI_GROWTH = 7908;
    private static final int HESPORI_STAGE_READY = 7;

    private static final int VARP_TEARS_OF_GUTHIX_COOLDOWN = 452;
    private static final int VARP_KINGDOM_FAVOUR = 73;
    private static final int VARP_KINGDOM_COFFER = 74;

    private final Client client;
    private final ConfigManager configManager;
    private final InfoBoxManager infoBoxManager;
    private final Gson gson;

    private static List<FarmingPatchDef> cachedFarmingPatches = null;
    private Object farmingTracker = null;
    private Method farmingTrackerPredictMethod = null;

    public FarmingAndTimerTools(Client client, ConfigManager configManager,
            InfoBoxManager infoBoxManager, Gson gson) {
        this.client = client;
        this.configManager = configManager;
        this.infoBoxManager = infoBoxManager;
        this.gson = gson;
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

    private QuestState getQuestStateSafe(Quest quest) {
        if (quest == null || client == null)
            return QuestState.NOT_STARTED;
        try {
            QuestState state = quest.getState(client);
            return state != null ? state : QuestState.NOT_STARTED;
        } catch (Exception e) {
            return QuestState.NOT_STARTED;
        }
    }

    public String executeGetPlayerFarmingAndTimers(JsonObject args) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "success");

        long nowSec = System.currentTimeMillis() / 1000L;

        // 1. Birdhouse run states (Fossil Island - live varbits + Time Tracking cache)
        JsonObject birdhouses = new JsonObject();
        int bh1 = client != null ? client.getVarbitValue(VARBIT_BIRDHOUSE_MEADOW_NORTH) : 0;
        int bh2 = client != null ? client.getVarbitValue(VARBIT_BIRDHOUSE_MEADOW_SOUTH) : 0;
        int bh3 = client != null ? client.getVarbitValue(VARBIT_BIRDHOUSE_VALLEY_NORTH) : 0;
        int bh4 = client != null ? client.getVarbitValue(VARBIT_BIRDHOUSE_VALLEY_SOUTH) : 0;

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
        int hesporiVar = client != null ? client.getVarbitValue(VARBIT_HESPORI_GROWTH) : 0;
        hespori.addProperty("stateVarbit", hesporiVar);
        hespori.addProperty("status", hesporiVar >= HESPORI_STAGE_READY ? "Fully Grown / Ready to fight"
                : (hesporiVar > 0 ? "Growing" : "Empty / Cleared"));
        result.add("hespori", hespori);

        // 3. Tears of Guthix Cooldown
        JsonObject tog = new JsonObject();
        int togCooldown = client != null ? client.getVarpValue(VARP_TEARS_OF_GUTHIX_COOLDOWN) : 0;
        tog.addProperty("cooldownVarp", togCooldown);
        tog.addProperty("ready", togCooldown <= 0);
        result.add("tearsOfGuthix", tog);

        // 4. Kingdom of Miscellania
        JsonObject kingdom = new JsonObject();
        int rawFavour = client != null ? client.getVarpValue(VARP_KINGDOM_FAVOUR) : 0;
        int rawCoffer = client != null ? client.getVarpValue(VARP_KINGDOM_COFFER) : 0;

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
        int varrockDiaryEasy = client != null ? client.getVarbitValue(Varbits.DIARY_VARROCK_EASY) : 0;
        int varrockDiaryMed = client != null ? client.getVarbitValue(Varbits.DIARY_VARROCK_MEDIUM) : 0;
        int varrockDiaryHard = client != null ? client.getVarbitValue(Varbits.DIARY_VARROCK_HARD) : 0;
        int varrockDiaryElite = client != null ? client.getVarbitValue(Varbits.DIARY_VARROCK_ELITE) : 0;

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

        // 6. Active InfoBox Timers & Boosts
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

        // 7. RuneLite Time Tracking Plugin Patches
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

    public static class FarmingPatchDef {
        public final String configKey;
        public final String locationName;
        public final String patchType;
        public final Object farmingPatch;
        public final Object patchImplementation;
        public final boolean healthCheckRequired;

        public FarmingPatchDef(String configKey, String locationName, String patchType, Object farmingPatch,
                Object patchImplementation, boolean healthCheckRequired) {
            this.configKey = configKey;
            this.locationName = locationName;
            this.patchType = patchType;
            this.farmingPatch = farmingPatch;
            this.patchImplementation = patchImplementation;
            this.healthCheckRequired = healthCheckRequired;
        }
    }

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

    public static synchronized List<FarmingPatchDef> getKnownFarmingPatches() {
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
                        return null;
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

        // 2. Fallback to manual varbit + tick computation
        String[] parts = val.split("[:;,|]");
        int varbitValue = 0;
        try {
            varbitValue = Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException ignored) {
            return null;
        }

        if (varbitValue <= 0) {
            return null;
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
                return null;
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
}
