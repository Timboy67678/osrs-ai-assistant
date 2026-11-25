package com.osrsai.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrsai.util.Utilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Tool implementations for time tracking, birdhouse traps, Hespori growth,
 * Kingdom of Miscellania calculation, daily task
 * collectibles,
 * and active InfoBox timers.
 */
@Slf4j
public class FarmingAndTimerTools {

    /** Birdhouse space varps, in the order Meadow North, Meadow South, Valley North, Valley South. */
    private static final int[] BIRDHOUSE_VARPS = {
            VarPlayerID.BIRDHOUSE_TRANSMIT_A,
            VarPlayerID.BIRDHOUSE_TRANSMIT_B,
            VarPlayerID.BIRDHOUSE_TRANSMIT_C,
            VarPlayerID.BIRDHOUSE_TRANSMIT_D };
    private static final String[] BIRDHOUSE_KEYS = { "meadowNorth", "meadowSouth", "valleyNorth", "valleySouth" };
    /** Same duration RuneLite's Time Tracking plugin uses for a seeded birdhouse. */
    private static final long BIRDHOUSE_DURATION_SEC = 50 * 60;

    private static final int HESPORI_STAGE_READY = 7;


    private final Client client;
    private final ConfigManager configManager;
    private final InfoBoxManager infoBoxManager;
    private final Gson gson;

    public FarmingAndTimerTools(Client client, ConfigManager configManager,
            InfoBoxManager infoBoxManager, Gson gson) {
        this.client = client;
        this.configManager = configManager;
        this.infoBoxManager = infoBoxManager;
        this.gson = gson;
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

        // 1. Birdhouses, decoded the same way as RuneLite's Time Tracking plugin: the varp is 0 when
        // empty, a multiple of 3 when seeded, otherwise built but not seeded. Time Tracking stores
        // "varp:timestamp" with the time the space last changed; a seeded house is full 50 minutes later.
        JsonObject birdhouses = new JsonObject();
        int ready = 0;
        int catching = 0;
        int seededUnknownTime = 0;
        for (int i = 0; i < BIRDHOUSE_VARPS.length; i++) {
            String stored = Utilities.getConfigValue(configManager, "timetracking", "birdhouse." + BIRDHOUSE_VARPS[i]);
            int storedVarp = -1;
            long storedTime = 0;
            if (stored != null) {
                String[] parts = stored.split(":");
                try {
                    storedVarp = Integer.parseInt(parts[0].trim());
                    storedTime = parts.length > 1 ? Long.parseLong(parts[1].trim()) : 0;
                } catch (NumberFormatException ignored) {
                }
            }
            int varp = storedVarp;
            if (client != null && client.getLocalPlayer() != null) {
                int live = client.getVarpValue(BIRDHOUSE_VARPS[i]);
                if (live != storedVarp) {
                    // Changed since Time Tracking last saved it, so the stored time doesn't apply.
                    storedTime = 0;
                }
                varp = live;
            }

            String state;
            if (varp < 0) {
                state = "Unknown (not seen yet; log in or visit Fossil Island)";
            } else if (varp == 0) {
                state = "Empty";
            } else if (varp % 3 != 0) {
                state = "Built, not seeded";
            } else if (storedTime <= 0) {
                state = "Seeded (time placed unknown)";
                seededUnknownTime++;
            } else if (nowSec >= storedTime + BIRDHOUSE_DURATION_SEC) {
                state = "Full, ready to collect";
                ready++;
            } else {
                long minutes = Math.max(1, (storedTime + BIRDHOUSE_DURATION_SEC - nowSec + 59) / 60);
                state = "Catching birds (" + minutes + "m left)";
                catching++;
            }
            birdhouses.addProperty(BIRDHOUSE_KEYS[i], state);
        }
        birdhouses.addProperty("readyCount", ready);
        birdhouses.addProperty("summary", ready + " ready, " + catching + " catching birds"
                + (seededUnknownTime > 0 ? ", " + seededUnknownTime + " seeded with unknown time" : ""));
        result.add("birdhouses", birdhouses);

        // 2. Hespori Boss Growth
        JsonObject hespori = new JsonObject();
        int hesporiVar = client != null ? client.getVarbitValue(VarbitID.FARMING_TRANSMIT_J) : 0;
        hespori.addProperty("stateVarbit", hesporiVar);
        hespori.addProperty("status", hesporiVar >= HESPORI_STAGE_READY ? "Fully Grown / Ready to fight"
                : (hesporiVar > 0 ? "Growing" : "Empty / Cleared"));
        result.add("hespori", hespori);

        // 4. Kingdom of Miscellania
        JsonObject kingdom = new JsonObject();
        int rawFavour = client != null ? client.getVarbitValue(VarbitID.MISC_APPROVAL) : 0;
        int rawCoffer = client != null ? client.getVarbitValue(VarbitID.MISC_COFFERS) : 0;

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
                    String val = Utilities.getConfigValue(configManager, group, "approval", "lastApproval", "favor",
                            "favour", "lastFavor",
                            "lastFavour", "approvalPercent");
                    if (val != null) {
                        try {
                            cachedFavour = Integer.parseInt(val.replaceAll("[^0-9]", ""));
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (cachedCoffer == null) {
                    String val = Utilities.getConfigValue(configManager, group, "coffer", "lastCoffer", "coffers",
                            "lastCoffers",
                            "cofferAmount");
                    if (val != null) {
                        try {
                            cachedCoffer = Integer.parseInt(val.replaceAll("[^0-9]", ""));
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (lastChangedInstant == null) {
                    String lastChangedStr = Utilities.getConfigValue(configManager, group, "lastChanged", "lastCheck",
                            "lastVisit",
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
            source = "live";
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
        int varrockDiaryEasy = client != null ? client.getVarbitValue(VarbitID.VARROCK_DIARY_EASY_COMPLETE) : 0;
        int varrockDiaryMed = client != null ? client.getVarbitValue(VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE) : 0;
        int varrockDiaryHard = client != null ? client.getVarbitValue(VarbitID.VARROCK_DIARY_HARD_COMPLETE) : 0;
        int varrockDiaryElite = client != null ? client.getVarbitValue(VarbitID.VARROCK_DIARY_ELITE_COMPLETE) : 0;

        int zaffDailyCount = 0;
        if (varrockDiaryElite > 0) {
            zaffDailyCount = 120;
        } else if (varrockDiaryHard > 0) {
            zaffDailyCount = 60;
        } else if (varrockDiaryMed > 0) {
            zaffDailyCount = 30;
        } else if (varrockDiaryEasy > 0) {
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
                    String cleanTooltip = Utilities.PATTERN_HTML_TAGS.matcher(tooltip).replaceAll("").trim();
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

        // 7. Farming patches. RuneLite's patch growth tables are internal to the Time Tracking
        // plugin and Plugin Hub forbids reflection, so patch states are not decoded here.
        JsonObject farmingPatches = new JsonObject();
        farmingPatches.addProperty("status", "unavailable");
        farmingPatches.addProperty("note",
                "Herb, tree, fruit tree and other farming patch growth states are not available to this plugin. "
                        + "Ask the player to check RuneLite's Time Tracking panel (clock icon in the sidebar) for patch timers.");
        result.add("farmingPatchesAndCrops", farmingPatches);

        return gson.toJson(result);
    }

}
