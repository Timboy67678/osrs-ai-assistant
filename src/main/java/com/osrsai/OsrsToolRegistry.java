package com.osrsai;

import java.util.ArrayList;
import java.util.List;

public class OsrsToolRegistry {
    private OsrsToolRegistry() {
        // Utility class
    }

    public static List<AiService.ToolDefinition> getToolRegistry() {
        List<AiService.ToolDefinition> registry = new ArrayList<>();

        registry.add(new AiService.ToolDefinition("get_player_skills",
                "Retrieve the player's base levels, boosted levels, experience (XP), and thresholds. MUST be called before quoting or relying on any of the player's specific skill levels or XP progress.",
                true, true, AiService::executeGetPlayerSkills)
                .addParam("skill", "string",
                        "Optional skill name to filter strictly by (case-insensitive, e.g. 'Attack', 'Strength', 'Slayer'). If omitted, retrieves all skills.",
                        false));

        registry.add(new AiService.ToolDefinition("get_player_inventory",
                "Retrieve the items, quantities, Grand Exchange prices, and High Alchemy values currently in the player's inventory. Note: Inventory contents are temporary snapshot state and can be restocked at a bank before starting new activities.",
                true, true, AiService::executeGetPlayerInventory));

        registry.add(new AiService.ToolDefinition("get_player_equipment",
                "Retrieve the items, quantities, Grand Exchange prices, and High Alchemy values currently equipped by the player.",
                true, true, AiService::executeGetPlayerEquipment));

        registry.add(new AiService.ToolDefinition("get_player_slayer_task",
                "Retrieve the player's current Slayer task, remaining quantity, current Slayer points, and current streak.",
                true, true, AiService::executeGetPlayerSlayerTask));

        registry.add(new AiService.ToolDefinition("get_player_quests",
                "Retrieve the player's quest points, completed quest count, and lists of in-progress, not started, or completed quests.",
                true, true, AiService::executeGetPlayerQuests)
                .addParam("status", "string",
                        "Optional quest status filter: 'IN_PROGRESS' (default), 'NOT_STARTED', 'COMPLETED', or 'ALL'.",
                        false));

        registry.add(new AiService.ToolDefinition("get_player_status",
                "Retrieve the player's real-time combat and vital status (current HP, prayer points, active prayers, poison/venom state, run energy, special attack %). Use for immediate action/survival questions, NOT as primary criteria for general boss readiness.",
                true, true, AiService::executeGetPlayerStatus));

        registry.add(new AiService.ToolDefinition("get_player_currencies_and_points",
                "Retrieve the player's minigame reward points, currencies, tickets, and tokens (e.g. NMZ points, Pest Control commends, Tithe Farm points, Golden Nuggets, Abyssal Pearls, Marks of Grace, Slayer points, Archery tickets).",
                true, true, AiService::executeGetPlayerCurrenciesAndPoints));

        registry.add(new AiService.ToolDefinition("get_player_location_details",
                "Retrieve detailed information about the player's location, including Wilderness level, multi-combat status, instanced area status, world types (PvP, Members, High Risk), and region ID.",
                true, true, AiService::executeGetPlayerLocationDetails));

        registry.add(new AiService.ToolDefinition("get_player_achievement_diaries",
                "Retrieve the player's Achievement Diary completion progress for all regions and tiers (Easy, Medium, Hard, Elite).",
                true, true, AiService::executeGetPlayerAchievementDiaries));

        registry.add(new AiService.ToolDefinition("get_player_bank",
                "Retrieve the items, quantities, Grand Exchange prices, and High Alchemy values currently in the player's bank. Only works if the bank interface is open.",
                true, true, AiService::executeGetPlayerBank)
                .addParam("filter", "string",
                        "Optional search query to filter bank items strictly by item name substring (case-insensitive, e.g. 'bar' or 'ore'). Do NOT filter by skill or category name (e.g. do NOT use 'crafting' as a filter).",
                        false)
                .addParam("minValue", "integer", "Optional minimum value to filter items.", false));

        registry.add(new AiService.ToolDefinition("get_item_stats",
                "Retrieve detailed equipment statistics, combat bonuses, weight, slot, and prices for a list of item IDs or item names.",
                true, true, AiService::executeGetItemStats)
                .addParam("itemIds", "array_integer", "Optional list of OSRS item IDs to retrieve stats for.", false)
                .addParam("itemNames", "array_string",
                        "Optional list of item names to search for in containers and retrieve stats.", false));

        registry.add(new AiService.ToolDefinition("get_player_clues",
                "Retrieve details about the player's active clue scroll (current step text, requirements, and solution) if they are in the middle of one, as well as a list of clue scroll items currently in their inventory or bank.",
                true, true, AiService::executeGetPlayerClues));

        registry.add(new AiService.ToolDefinition("search_osrs_wiki",
                "Search the Old School RuneScape Wiki for authoritative mechanics, stats, requirements, locations, quest rewards, training methods, and information. MUST be called whenever answering questions about named quests, items, bosses, monsters, updates, quest rewards, or training methods to verify exact OSRS mechanics and rewards before formulating your response.",
                false, false, AiService::executeSearchOsrsWiki)
                .addParam("query", "string",
                        "The exact entity, location, farming patch, training method, or topic to search for (e.g. 'Sharp Eye', 'Abyssal whip', 'Barrows', 'Farming patches').",
                        true));

        registry.add(new AiService.ToolDefinition("get_player_combat_achievements",
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
}
