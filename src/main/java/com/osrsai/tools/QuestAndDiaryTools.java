package com.osrsai.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrsai.util.Utilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.gameval.VarbitID;

import java.util.*;

/**
 * Tool implementations for Quest progression, Achievement Diaries, and Combat
 * Achievements.
 */
@Slf4j
public class QuestAndDiaryTools {
    public static final int QUEST_STRUCT_PARAM_VARBIT = 299;
    public static final int QUEST_STRUCT_PARAM_VARP = 300;

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

    private final Client client;
    private final ConfigManager configManager;
    private final Gson gson;

    public QuestAndDiaryTools(Client client, ConfigManager configManager, Gson gson) {
        this.client = client;
        this.configManager = configManager;
        this.gson = gson;
    }


    public int getQuestStageValue(Quest quest) {
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

    public QuestState getQuestStateSafe(Quest quest) {
        if (quest == null)
            return QuestState.NOT_STARTED;
        try {
            QuestState state = quest.getState(client);
            return state != null ? state : QuestState.NOT_STARTED;
        } catch (Exception e) {
            return QuestState.NOT_STARTED;
        }
    }

    public String executeGetPlayerQuests(JsonObject args) {
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

    private JsonObject createDiaryProgress(int easyVarbit, int medVarbit, int hardVarbit, int eliteVarbit) {
        JsonObject obj = new JsonObject();
        obj.addProperty("Easy", Utilities.getDiaryStatus(client, easyVarbit));
        obj.addProperty("Medium", Utilities.getDiaryStatus(client, medVarbit));
        obj.addProperty("Hard", Utilities.getDiaryStatus(client, hardVarbit));
        obj.addProperty("Elite", Utilities.getDiaryStatus(client, eliteVarbit));
        return obj;
    }

    public String executeGetPlayerAchievementDiaries(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject diaries = new JsonObject();
        diaries.add("Ardougne", createDiaryProgress(
                VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE,
                VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE,
                VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE,
                VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE));
        diaries.add("Desert", createDiaryProgress(
                VarbitID.DESERT_DIARY_EASY_COMPLETE,
                VarbitID.DESERT_DIARY_MEDIUM_COMPLETE,
                VarbitID.DESERT_DIARY_HARD_COMPLETE,
                VarbitID.DESERT_DIARY_ELITE_COMPLETE));
        diaries.add("Falador", createDiaryProgress(
                VarbitID.FALADOR_DIARY_EASY_COMPLETE,
                VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE,
                VarbitID.FALADOR_DIARY_HARD_COMPLETE,
                VarbitID.FALADOR_DIARY_ELITE_COMPLETE));
        diaries.add("Fremennik", createDiaryProgress(
                VarbitID.FREMENNIK_DIARY_EASY_COMPLETE,
                VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE,
                VarbitID.FREMENNIK_DIARY_HARD_COMPLETE,
                VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE));
        diaries.add("Kandarin", createDiaryProgress(
                VarbitID.KANDARIN_DIARY_EASY_COMPLETE,
                VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE,
                VarbitID.KANDARIN_DIARY_HARD_COMPLETE,
                VarbitID.KANDARIN_DIARY_ELITE_COMPLETE));
        diaries.add("Karamja", createDiaryProgress(
                VarbitID.ATJUN_EASY_DONE,
                VarbitID.ATJUN_MED_DONE,
                VarbitID.ATJUN_HARD_DONE,
                VarbitID.KARAMJA_DIARY_ELITE_COMPLETE));
        diaries.add("Kourend", createDiaryProgress(
                VarbitID.KOUREND_DIARY_EASY_COMPLETE,
                VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE,
                VarbitID.KOUREND_DIARY_HARD_COMPLETE,
                VarbitID.KOUREND_DIARY_ELITE_COMPLETE));
        diaries.add("Lumbridge", createDiaryProgress(
                VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE,
                VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
                VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE,
                VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE));
        diaries.add("Morytania", createDiaryProgress(
                VarbitID.MORYTANIA_DIARY_EASY_COMPLETE,
                VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
                VarbitID.MORYTANIA_DIARY_HARD_COMPLETE,
                VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE));
        diaries.add("Varrock", createDiaryProgress(
                VarbitID.VARROCK_DIARY_EASY_COMPLETE,
                VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE,
                VarbitID.VARROCK_DIARY_HARD_COMPLETE,
                VarbitID.VARROCK_DIARY_ELITE_COMPLETE));
        diaries.add("Western", createDiaryProgress(
                VarbitID.WESTERN_DIARY_EASY_COMPLETE,
                VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE,
                VarbitID.WESTERN_DIARY_HARD_COMPLETE,
                VarbitID.WESTERN_DIARY_ELITE_COMPLETE));
        diaries.add("Wilderness", createDiaryProgress(
                VarbitID.WILDERNESS_DIARY_EASY_COMPLETE,
                VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
                VarbitID.WILDERNESS_DIARY_HARD_COMPLETE,
                VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE));
        result.add("diaries", diaries);
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

    public String executeGetPlayerCombatAchievements(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject tiers = new JsonObject();
        tiers.addProperty("Easy", getCombatAchievementTierStatus(VarbitID.CA_TIER_STATUS_EASY));
        tiers.addProperty("Medium", getCombatAchievementTierStatus(VarbitID.CA_TIER_STATUS_MEDIUM));
        tiers.addProperty("Hard", getCombatAchievementTierStatus(VarbitID.CA_TIER_STATUS_HARD));
        tiers.addProperty("Elite", getCombatAchievementTierStatus(VarbitID.CA_TIER_STATUS_ELITE));
        tiers.addProperty("Master", getCombatAchievementTierStatus(VarbitID.CA_TIER_STATUS_MASTER));
        tiers.addProperty("Grandmaster", getCombatAchievementTierStatus(VarbitID.CA_TIER_STATUS_GRANDMASTER));
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
        String profileKey = configManager != null ? configManager.getRSProfileKey() : null;
        if (profileKey != null) {
            List<String> keys = configManager.getRSProfileConfigurationKeys("killcount", profileKey, "");
            if (keys != null) {
                List<String> sortedKeys = new ArrayList<>(keys);
                Collections.sort(sortedKeys);
                for (String key : sortedKeys) {
                    if (filterBoss != null && !key.toLowerCase().contains(filterBoss)) {
                        continue;
                    }
                    String valueStr = Utilities.getConfigValue(configManager, "killcount", key);
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
}
