package com.osrsai.tools;

import com.osrsai.AiService;
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
                                "Get the player's base and boosted levels and XP for each skill, and XP remaining to a target level.",
                                true, true, AiService::executeGetPlayerSkills)
                                .addParam("skill", "string",
                                                "Optional skill to return (e.g. 'Slayer'). Omit for all skills.",
                                                false)
                                .addParam("targetLevel", "integer",
                                                "Optional target level to calculate remaining XP for.",
                                                false));

                registry.add(new AiService.ToolDefinition("get_player_inventory",
                                "Get the items in the player's inventory with quantities, GE prices and High Alchemy values.",
                                true, true, AiService::executeGetPlayerInventory));

                registry.add(new AiService.ToolDefinition("get_player_equipment",
                                "Get the player's equipped items with GE prices and High Alchemy values.",
                                true, true, AiService::executeGetPlayerEquipment));

                registry.add(new AiService.ToolDefinition("get_player_slayer_task",
                                "Get the player's current Slayer task: monster, amount left, assigned location, Slayer master, Slayer points and task streaks.",
                                true, true, AiService::executeGetPlayerSlayerTask)
                                .addParam("includeUnlocks", "boolean",
                                                "Set true to also return purchased Slayer unlocks and the block list (needed for questions about task variants, unlocks or blocking).",
                                                false));

                registry.add(new AiService.ToolDefinition("get_player_quests",
                                "Get the player's quest points and which quests are completed, in progress or not started.",
                                true, true, AiService::executeGetPlayerQuests)
                                .addParam("status", "string",
                                                "Optional quest status filter: 'IN_PROGRESS' (default), 'NOT_STARTED', 'COMPLETED', or 'ALL'.",
                                                false)
                                .addParam("quest", "string",
                                                "Optional quest name to filter by (e.g. 'Desert Treasure').",
                                                false));

                registry.add(new AiService.ToolDefinition("get_player_status",
                                "Get the player's current HP, prayer points, active prayers, poison/venom, run energy, special attack and active timers. For questions about right now, not general readiness.",
                                true, true, AiService::executeGetPlayerStatus));

                registry.add(new AiService.ToolDefinition("get_player_currencies_and_points",
                                "Get the player's minigame points, currencies, tickets and tokens (e.g. Slayer points, Pest Control points, NMZ points).",
                                true, true, AiService::executeGetPlayerCurrenciesAndPoints));

                registry.add(new AiService.ToolDefinition("get_player_location_details",
                                "Get the player's location details: Wilderness level, multi-combat, instance, world type and region ID.",
                                true, true, AiService::executeGetPlayerLocationDetails));

                registry.add(new AiService.ToolDefinition("get_player_achievement_diaries",
                                "Get the player's Achievement Diary completion for every region and tier.",
                                true, true, AiService::executeGetPlayerAchievementDiaries));

                registry.add(new AiService.ToolDefinition("get_player_bank",
                                "Get items in the player's bank (the last-seen contents if the bank is closed). Omit 'filter' for gear and valuable items.",
                                true, true, AiService::executeGetPlayerBank)
                                .addParam("filter", "string",
                                                "Optional item name or category ('gear', 'weapons', 'armour', 'potions', 'food').",
                                                false)
                                .addParam("minValue", "integer", "Optional minimum value to filter items.", false));

                registry.add(new AiService.ToolDefinition("get_item_stats",
                                "Get items' equipment bonuses, slot, weight and prices from the game's own data. Use when comparing or recommending gear.",
                                true, true, AiService::executeGetItemStats)
                                .addParam("itemIds", "array_integer",
                                                "Optional list of OSRS item IDs to retrieve stats for.", false)
                                .addParam("itemNames", "array_string",
                                                "Optional list of item names.",
                                                false));

                registry.add(new AiService.ToolDefinition("get_player_clues",
                                "Get the player's active clue step (text, solution, location) from RuneLite's Clue Scroll plugin, and clue scrolls in their inventory or bank.",
                                true, true, AiService::executeGetPlayerClues));

                registry.add(new AiService.ToolDefinition("search_osrs_wiki",
                                "Look up a page on the Old School RuneScape Wiki, the authoritative source for items, monsters, bosses, quests, skills, locations and mechanics. Short pages are returned whole. Long pages return the summary and infobox (stats, requirements) plus 'availableSections'; call again with 'section' to read one, e.g. 'Drops' for drop rates or 'Rewards' for quest rewards. Check the returned 'title' is what you asked for, and only state facts that appear in the result.",
                                false, false, AiService::executeSearchOsrsWiki)
                                .addParam("query", "string",
                                                "The page to look up, ideally its exact name (e.g. 'Abyssal whip', 'Vorkath', 'Dragon Slayer II', 'Slayer task/Basilisks').",
                                                true)
                                .addParam("section", "string",
                                                "Optional section of the page to read, from 'availableSections' of a previous result (e.g. 'Drops', 'Locations', 'Rewards', 'Strategy').",
                                                false));

                registry.add(new AiService.ToolDefinition("get_player_combat_achievements",
                                "Get the player's Combat Achievement progress by tier and boss kill counts. Filters return individual tasks.",
                                true, true, AiService::executeGetPlayerCombatAchievements)
                                .addParam("tier", "string",
                                                "Optional tier filter ('Easy' to 'Grandmaster').",
                                                false)
                                .addParam("boss", "string",
                                                "Optional boss name filter (e.g. 'zulrah').",
                                                false)
                                .addParam("completed", "boolean",
                                                "Optional completion filter.", false)
                                .addParam("taskName", "string",
                                                "Optional task name filter.",
                                                false));

                registry.add(new AiService.ToolDefinition("get_player_transportation",
                                "Get the travel options the player has unlocked: transport networks, spellbook teleports, POH portals and teleport items they own. Use when suggesting routes.",
                                true, true, AiService::executeGetPlayerTransportation));

                registry.add(new AiService.ToolDefinition("set_shortest_path_target",
                                "Draw a route to a destination on the player's screen using the Shortest Path plugin (must be installed). Prefer 'poiName' for known places; for dungeons, target the surface entrance (Y below 5000).",
                                true, true, AiService::executeSetShortestPathTarget)
                                .addParam("poiName", "string",
                                                "Optional known place name (e.g. 'Grand Exchange', 'Farming Guild', 'Barrows'); coordinates are looked up automatically.",
                                                false)
                                .addParam("x", "integer",
                                                "Target world X coordinate, if not using poiName.",
                                                false)
                                .addParam("y", "integer",
                                                "Target world Y coordinate, if not using poiName.",
                                                false)
                                .addParam("plane", "integer",
                                                "Target plane (0 is ground level).",
                                                false)
                                .addParam("locationName", "string",
                                                "Name of the destination to show the player.",
                                                false)
                                .addParam("startX", "integer",
                                                "Optional start X; omit to start from the player.",
                                                false)
                                .addParam("startY", "integer", "Optional custom starting Y coordinate.", false)
                                .addParam("startPlane", "integer", "Optional custom starting plane.", false)
                                .addParam("avoidWilderness", "boolean",
                                                "Set true to avoid the Wilderness.",
                                                false));

                registry.add(new AiService.ToolDefinition("clear_shortest_path_target",
                                "Clear the currently displayed route overlay and destination target in the Shortest Path plugin.",
                                true, true, AiService::executeClearShortestPathTarget));

                registry.add(new AiService.ToolDefinition("get_player_sailing_status",
                                "Get the player's Sailing level and, when aboard, their ship's name, hull HP, current activity, facilities, cargo and location.",
                                true, true, AiService::executeGetPlayerSailingStatus)
                                .addParam("includeCargo", "boolean",
                                                "Set false to leave out the cargo list.",
                                                false));

                registry.add(new AiService.ToolDefinition("get_surrounding_environment",
                                "Scan what is near the player: NPCs and monsters, other players (with Wilderness threat info), valuable ground items and notable objects such as banks, altars and portals.",
                                true, true, AiService::executeGetSurroundingEnvironment)
                                .addParam("radius", "integer",
                                                "Optional scan radius in tiles (default 15, max 30).",
                                                false));

                registry.add(new AiService.ToolDefinition("get_player_ge_offers",
                                "Get the player's Grand Exchange offers: item, buy or sell, price, quantity filled and GP spent or received.",
                                true, true, AiService::executeGetPlayerGeOffers));

                registry.add(new AiService.ToolDefinition("get_market_prices",
                                "Get live GE prices and High Alchemy values for items, including High Alchemy profit after the nature rune cost.",
                                true, true, AiService::executeGetMarketPrices)
                                .addParam("itemNames", "array_string",
                                                "Optional list of item names.",
                                                false)
                                .addParam("itemIds", "array_integer",
                                                "Optional list of item IDs.",
                                                false));

                registry.add(new AiService.ToolDefinition("get_player_farming_and_timers",
                                "Get birdhouse timers, Hespori growth, Tears of Guthix cooldown, Kingdom of Miscellania status and active timers. Crop and herb patch states are not available; point the player to RuneLite's Time Tracking panel for those.",
                                true, true, AiService::executeGetPlayerFarmingAndTimers));

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
