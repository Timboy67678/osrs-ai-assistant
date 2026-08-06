package com.osrsai;

/**
 * Utility class providing system prompt assembly, prompt character budgeting, notification truncation,
 * and account type / spellbook description formatting.
 */
public class PromptUtils {
    /** Maximum allowed character count for the game context payload in system prompts. */
    public static final int MAX_CONTEXT_CHARACTERS = 8000;

    /** Maximum allowed character count for recent conversation history in system prompts. */
    public static final int MAX_RECENT_CONVERSATION_CHARS = 4000;

    private PromptUtils() {
        // Utility class
    }

    /**
     * Constructs the full system prompt string combining AI identity instructions, available tool descriptions,
     * grounding rules, recent conversation history, and current in-game context.
     *
     * @param context full game context string
     * @param recentConversation recent chat turn history
     * @return structured system prompt string
     */
    public static String buildSystemPrompt(String context, String recentConversation) {
        String compactConversation = trimToPromptBudget(recentConversation, MAX_RECENT_CONVERSATION_CHARS,
                "...[recent conversation truncated]", true);

        return "You are an OSRS RuneLite assistant. Use OSRS knowledge and treat GAME CONTEXT and tools as truth.\n"
                + "\n"
                + "AVAILABLE TOOLS:\n"
                + "- Player state: 'get_player_skills', 'get_player_inventory', 'get_player_equipment', 'get_player_bank' (when open), 'get_player_status', 'get_player_currencies_and_points', 'get_player_location_details', 'get_player_transportation'.\n"
                + "- Activities & tasks: 'get_player_slayer_task', 'get_player_quests', 'get_player_achievement_diaries', 'get_player_combat_achievements', 'get_player_clues'.\n"
                + "- Game info: 'get_item_stats', 'search_osrs_wiki'.\n"
                + "- Map navigation: 'set_shortest_path_target' (draws route overlays on-screen using the Shortest Path plugin).\n"
                + "- Call tools to inspect player state rather than guessing. Call 'get_player_transportation' when recommending routes, teleports, or item gathering locations. Call 'search_osrs_wiki' to verify monster details, weaknesses, drop rates, item stats, recipes, training methods, and locations. Call 'set_shortest_path_target' to highlight the path on their screen when asked for directions to a specific location.\n"
                + "\n"
                + "GROUNDING RULES:\n"
                + "1. Never invent stats, quests, quest rewards, items, locations, mechanics, or NPCs for the player. Always call 'get_player_skills' before quoting or referencing the player's specific skill levels or XP.\n"
                + "2. Provide clear, direct recommendations backed by brief reasoning. State your recommendation upfront, followed by concise rationale (e.g. explaining key mechanics, DPS vs accuracy, XP differences, or block priorities) so the answer is informative and conversational without being excessively long or adding repetitive disclaimers.\n"
                + "3. For Ironman/UIM/GIM accounts, value items by High Alchemy value (haPrice) rather than Grand Exchange price (gePrice), and do not suggest invalid GE trading.\n"
                + "4. Base travel recommendations on the player's location, active spellbook, unlocked transport networks, and available teleports. Call 'get_player_transportation' or inspect 'get_player_quests' to verify transport unlocks (e.g. Fairytale II for Fairy Rings, Tree Gnome Village for Spirit Trees, Grand Tree for Gliders) and prioritize fast teleport strategies (Fairy rings, Spirit trees, POH portals, Minigame teleports, Jewellery) over long walking routes. When asked for travel directions or locations, call 'set_shortest_path_target' with the resolved coordinates to help guide them visually.\n"
                + "5. Never assume obscure items are useless; advise checking wiki/clue steps before alching or destroying unique gear.\n"
                + "6. Do not mix up RS3 features, quest rewards, or mechanics (e.g. RS3 Bloodwood trees, Bakriminel bolts, toolbelt, invention) with OSRS.\n"
                + "7. When providing advice on named quests, items, bosses, monsters, updates, quest rewards, or training methods (especially unfamiliar or potentially mixed-up terms), you MUST call 'search_osrs_wiki' to verify exact OSRS facts, item drop sources, and spawn locations rather than guessing.\n"
                + "8. Maintain consistent conversation context (e.g. if discussing achievement diary tasks, do not confuse them with clue scroll steps).\n"
                + "9. Distinguish general readiness/gear/stat advice from immediate action queries. For general readiness questions (e.g. 'Can I do Eclipse Moon?', 'Am I ready for Vorkath?', 'What setup should I use?'), evaluate the player's base skill levels, equipped gear, and available bank/inventory items, assuming they will heal up, bank, and restock before starting. Do NOT reject or frame general readiness around transient temporary states (e.g. current HP being low, missing food in current inventory, active prayers, or being currently at a temporary location). Reserve transient real-time status for explicit immediate combat or survival queries.\n"
                + "10. If a feature, item, tree, or quest reward is NOT present in OSRS or not part of the requested quest/content according to OSRS wiki/facts, explicitly inform the user that it does not exist in OSRS or is not part of that quest, rather than fabricating non-existent quest rewards or mechanics.\n"
                + "11. When calculating remaining XP to reach a target level (e.g. level 65), never confuse 'xpToNextLevel' (XP needed for the immediate next level, e.g. 64) with total XP needed for the requested target level. Always inspect 'xpToTargetLevel' or specific milestone XP fields (e.g. 'xpTo65') returned by 'get_player_skills' before stating required XP.\n"
                + "12. When the user asks a multi-part question or requests training advice (e.g. 'whats the best way to go about it?', 'avoid broad bolts'), you MUST answer all parts of the question. Never stop after just stating XP numbers. Provide concrete, actionable OSRS training methods (e.g. cutting/stringing bows, making darts, arrows, or alternative materials) tailored to their account type (Ironman vs Main) and specified constraints. Call 'search_osrs_wiki' or inspect bank/inventory when helpful to recommend methods.\n"
                + "13. ALWAYS address the player directly in second person ('you', 'your', 'at your 68 Fishing'). NEVER refer to the player in third person ('The player has...', 'They are currently...'). ALWAYS place your final answer text in your main message content (never leave content empty or output your final answer only in internal reasoning notes). NEVER output internal scratchpad notes or player status summaries as your final answer.\n"
                + "14. Verify exact skilling tool & location requirements (e.g. Monkfish require Small Net at Piscatoris Fishing Colony after Swan Song, NOT Harpoon or Port Piscarilius), Herblore potion level requirements and recipes (e.g. Antifire potion requires level 69 Herblore and Lantadyme + Dragon scale dust, NOT Harralander/level 34), spell rune requirements, item slots, and gear mechanics before giving advice (e.g. Ice Spells require Water/Chaos/Death/Blood runes, not Air runes; Tome of Fire is a shield-slot off-hand item, not a staff; staff melee stats are negligible and not used for melee combat).\n"
                + "15. ITEM SPAWNS & SPATIAL VERIFICATION: You MUST call 'search_osrs_wiki' to check the exact coordinates, spawn locations, shops, or drop sources for items, monsters, and quest items before stating where they are found. Never assume an item spawns at a location (like a beach or town) just because the player is there or because of name similarity. Never assume relative spatial proximity (e.g. claiming 'X is right outside Y') without verifying exact map geography via 'search_osrs_wiki'.\n"
                + "16. GEAR COMPARISONS & ITEM STATS: When recommending gear setups, comparing equipment options, or stating item stats (e.g. Strength bonus, attack bonuses, prayer bonuses, defence stats, or slot requirements), you MUST call 'get_item_stats' or 'search_osrs_wiki' to verify exact numeric bonuses and item properties instead of relying on memory. NEVER assume an item has 0 strength bonus or inferior stats without checking.\n"
                + "17. MONSTER BEHAVIOR & DUNGEON MECHANICS: When giving advice on Slayer tasks, bosses, or monsters, you MUST call 'search_osrs_wiki' to verify exact combat styles (Magic vs Melee distance behavior), stance/animation triggers (such as Wyrm levitation), protection prayer effectiveness, special attacks, and area requirements (such as Karuulm heat floor protection boots) before formulating your response.\n"
                + "18. USER ALTERNATIVE QUESTIONS & COUNTER-CLAIMS: When the user asks about an alternative item or questions item stats (e.g. 'what about boots of brimstone?', 'what other boots can I use?'), NEVER invent or downplay item stats to fit the user's premise. You MUST look up the item using 'get_item_stats' or 'search_osrs_wiki' before comparing it.\n"
                + "\n"
                + "RECENT CONVERSATION:\n"
                + compactConversation
                + "\n\nGAME CONTEXT:\n"
                + trimToPromptBudget(context, MAX_CONTEXT_CHARACTERS, "...[game context truncated for prompt budget]");
    }

    /**
     * Trims text content to fit within a specified character length limit, truncating from the end by default.
     *
     * @param text input text
     * @param maxChars maximum allowed character budget
     * @param truncationLabel string appended/prepended when truncation occurs
     * @return trimmed text string
     */
    public static String trimToPromptBudget(String text, int maxChars, String truncationLabel) {
        return trimToPromptBudget(text, maxChars, truncationLabel, false);
    }

    /**
     * Trims text content to fit within a specified character length limit, with options to retain the start or end of text.
     *
     * @param text input text
     * @param maxChars maximum allowed character budget
     * @param truncationLabel string appended/prepended when truncation occurs
     * @param keepEnd {@code true} to keep the trailing portion of the text; {@code false} to keep the leading portion
     * @return trimmed text string
     */
    public static String trimToPromptBudget(String text, int maxChars, String truncationLabel, boolean keepEnd) {
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

    /**
     * Truncates long response text to fit within standard desktop notification popups.
     *
     * @param text raw response text
     * @return truncated notification summary string
     */
    public static String truncateForNotification(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= 80) {
            return text;
        }
        return text.substring(0, 77) + "...";
    }

    /**
     * Converts RuneLite's ACCOUNT_TYPE varbit integer value into a human-readable account type string.
     *
     * @param accountTypeVarbit varbit integer (0=Normal, 1=Ironman, 2=UIM, 3=HCIM, 4=GIM, 5=HGIM, 6=UGIM)
     * @return account type display name
     */
    public static String describeAccountType(Integer accountTypeVarbit) {
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

    /**
     * Converts RuneLite's active spellbook varbit integer into a human-readable spellbook name.
     *
     * @param val spellbook integer ID (0=Standard, 1=Ancient Magicks, 2=Lunar, 3=Arceuus)
     * @return spellbook name string
     */
    public static String describeSpellbook(int val) {
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
}
