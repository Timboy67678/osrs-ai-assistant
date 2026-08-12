package com.osrsai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry class that defines and holds all available function/tool definitions
 * accessible by the AI assistant.
 * <p>
 * Tools enable the AI to query in-game player state (skills, inventory,
 * equipment, quests, diary/combat achievements, bank, sailing),
 * search the OSRS Wiki, or interact with RuneLite plugin features like Shortest
 * Path target markers.
 */
public class OsrsToolRegistry {
        private static final List<AiService.ToolDefinition> TOOL_REGISTRY;
        private static final Map<String, AiService.ToolDefinition> TOOL_MAP;

        static {
                List<AiService.ToolDefinition> registry = new ArrayList<>();
                Map<String, AiService.ToolDefinition> map = new HashMap<>();

                registry.add(new AiService.ToolDefinition("get_player_skills",
                                "Retrieve the player's base levels, boosted levels, experience (XP), and thresholds. MUST be called before quoting or relying on any of the player's specific skill levels or XP progress.",
                                true, true, AiService::executeGetPlayerSkills)
                                .addParam("skill", "string",
                                                "Optional skill name to filter strictly by (case-insensitive, e.g. 'Attack', 'Strength', 'Slayer'). If omitted, retrieves all skills.",
                                                false)
                                .addParam("targetLevel", "integer",
                                                "Optional target level to calculate exact remaining XP for (e.g. 65, 75, 85, 92).",
                                                false));

                registry.add(new AiService.ToolDefinition("get_player_inventory",
                                "Retrieve the items, quantities, Grand Exchange prices, and High Alchemy values currently in the player's inventory. Note: Inventory contents are temporary snapshot state and can be restocked at a bank before starting new activities.",
                                true, true, AiService::executeGetPlayerInventory));

                registry.add(new AiService.ToolDefinition("get_player_equipment",
                                "Retrieve the items, quantities, Grand Exchange prices, and High Alchemy values currently equipped by the player.",
                                true, true, AiService::executeGetPlayerEquipment));

                registry.add(new AiService.ToolDefinition("get_player_slayer_task",
                                "Retrieve the player's current Slayer task, remaining quantity, assigned location (if location-specific, e.g. Konar), current Slayer points, and current streak.",
                                true, true, AiService::executeGetPlayerSlayerTask));

                registry.add(new AiService.ToolDefinition("get_player_quests",
                                "Retrieve the player's quest points, completed quest count, and lists of in-progress (including stage/varbit value), not started, or completed quests.",
                                true, true, AiService::executeGetPlayerQuests)
                                .addParam("status", "string",
                                                "Optional quest status filter: 'IN_PROGRESS' (default), 'NOT_STARTED', 'COMPLETED', or 'ALL'.",
                                                false)
                                .addParam("quest", "string",
                                                "Optional quest name search term (case-insensitive substring, e.g. 'Desert Treasure') to filter the lists of quests.",
                                                false));

                registry.add(new AiService.ToolDefinition("get_player_status",
                                "Retrieve the player's real-time combat and vital status (current HP, prayer points, active prayers, poison/venom state, run energy, special attack %, active RuneLite infobox timers & status effects). Use for immediate action/survival questions, NOT as primary criteria for general boss readiness.",
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
                                "Retrieve items in the player's bank when open. Omit 'filter' to get top gear and valuable items, or pass category keywords ('gear', 'weapons', 'armour', 'potions', 'food') or item name search queries.",
                                true, true, AiService::executeGetPlayerBank)
                                .addParam("filter", "string",
                                                "Optional search query or category keyword (e.g. 'gear', 'weapons', 'armour', 'potions', 'food', 'dragon', 'rune').",
                                                false)
                                .addParam("minValue", "integer", "Optional minimum value to filter items.", false));

                registry.add(new AiService.ToolDefinition("get_item_stats",
                                "Retrieve detailed equipment statistics, combat bonuses (Strength, Attack, Defence, Prayer), weight, slot, and prices for a list of item IDs or item names. MUST be called whenever recommending gear setups, comparing equipment options, or answering questions about item stats.",
                                true, true, AiService::executeGetItemStats)
                                .addParam("itemIds", "array_integer",
                                                "Optional list of OSRS item IDs to retrieve stats for.", false)
                                .addParam("itemNames", "array_string",
                                                "Optional list of item names to search for in game database or containers and retrieve stats.",
                                                false));

                registry.add(new AiService.ToolDefinition("get_player_clues",
                                "Retrieve details about the player's active clue scroll (current step text, requirements, and solution) if they are in the middle of one, as well as a list of clue scroll items currently in their inventory or bank.",
                                true, true, AiService::executeGetPlayerClues));

                registry.add(new AiService.ToolDefinition("search_osrs_wiki",
                                "Search the Old School RuneScape Wiki for authoritative mechanics, stats, requirements, locations, quest rewards, training methods, and information. MUST be called whenever answering questions about named quests, items, gear choices, bosses, monsters (including combat distance styles, levitation/animation stances, and dungeon floor rules), updates, quest rewards, or training methods to verify exact OSRS mechanics and rewards before formulating your response.",
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
                                .addParam("completed", "boolean",
                                                "Optional. Filter individual tasks by completion status.", false)
                                .addParam("taskName", "string",
                                                "Optional. Filter individual tasks strictly by task name substring (case-insensitive, e.g. 'noxious foe' or 'barrows novice').",
                                                false));

                registry.add(new AiService.ToolDefinition("get_player_transportation",
                                "Retrieve the player's unlocked travel networks, teleportation unlocks (e.g. Fairy Rings, Spirit Trees, Gnome Gliders, Balloons, Ectophial, Drakkan's Medallion, Royal Seed Pod), current spellbook teleports, Construction POH portal access, and teleportation items in inventory/equipment/bank. Call whenever formulating travel routes, teleport suggestions, or item gathering directions.",
                                true, true, AiService::executeGetPlayerTransportation));

                registry.add(new AiService.ToolDefinition("set_shortest_path_target",
                                "Set a destination coordinate (X, Y, Plane) in the player's Shortest Path plugin to draw a route overlay on their game screen via cross-plugin communication. IMPORTANT: Always specify surface entrance coordinates (Y < 5000) for dungeons, caves, or underground locations (e.g. Chasm of Fire entrance at X=1435, Y=3671 instead of internal underground offset Y=10077) so the pathfinder draws a valid route on the world map. Supports optional custom start coordinates and pathfinding config overrides (e.g. avoidWilderness). Requires the Shortest Path plugin to be installed and enabled in RuneLite.",
                                true, true, AiService::executeSetShortestPathTarget)
                                .addParam("x", "integer", "The target X coordinate (WorldPoint x, e.g. 3200).", true)
                                .addParam("y", "integer", "The target Y coordinate (WorldPoint y, e.g. 3400).", true)
                                .addParam("plane", "integer",
                                                "The target plane (0 for ground level, 1 for first floor, etc. Default is 0).",
                                                false)
                                .addParam("locationName", "string",
                                                "A human-readable name of the location being targeted (e.g. 'Varrock West Bank' or 'Grand Exchange') to show the player in the chat response.",
                                                false)
                                .addParam("startX", "integer",
                                                "Optional custom starting X coordinate. Omit to start path calculation from player's current location.",
                                                false)
                                .addParam("startY", "integer", "Optional custom starting Y coordinate.", false)
                                .addParam("startPlane", "integer", "Optional custom starting plane.", false)
                                .addParam("avoidWilderness", "boolean",
                                                "Optional. Set to true to force pathfinding to avoid the Wilderness.",
                                                false));

                registry.add(new AiService.ToolDefinition("clear_shortest_path_target",
                                "Clear the currently displayed route overlay and destination target in the Shortest Path plugin.",
                                true, true, AiService::executeClearShortestPathTarget));

                registry.add(new AiService.ToolDefinition("get_player_sailing_status",
                                "Retrieve details about the player's active vessel and sailing status (ship tier/type, hull HP/condition, sail trim, knot speed, wind direction, anchor status, cargo hold items, crew members, sea location, and Sailing skill stats).",
                                true, true, AiService::executeGetPlayerSailingStatus)
                                .addParam("includeCargo", "boolean",
                                                "Optional. Set to false to exclude ship cargo hold item listing (default is true).",
                                                false));

                for (AiService.ToolDefinition def : registry) {
                        map.put(def.name, def);
                }

                TOOL_REGISTRY = Collections.unmodifiableList(registry);
                TOOL_MAP = Collections.unmodifiableMap(map);
        }

        private OsrsToolRegistry() {
                // Utility class
        }

        /**
         * Gets the unmodifiable list of registered tool definitions available to the AI
         * assistant.
         *
         * @return unmodifiable list of {@link AiService.ToolDefinition} objects
         */
        public static List<AiService.ToolDefinition> getToolRegistry() {
                return TOOL_REGISTRY;
        }

        /**
         * Looks up a registered tool definition by its unique tool name in O(1) time.
         *
         * @param name tool name
         * @return target {@link AiService.ToolDefinition}, or {@code null} if not
         *         registered
         */
        public static AiService.ToolDefinition getTool(String name) {
                return (name != null) ? TOOL_MAP.get(name) : null;
        }
}
