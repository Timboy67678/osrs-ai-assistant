package com.osrsai;

public class PromptUtils {
    public static final int MAX_CONTEXT_CHARACTERS = 8000;
    public static final int MAX_RECENT_CONVERSATION_CHARS = 1200;

    private PromptUtils() {
        // Utility class
    }

    public static String buildSystemPrompt(String context, String recentConversation) {
        String compactConversation = trimToPromptBudget(recentConversation, MAX_RECENT_CONVERSATION_CHARS,
                "...[recent conversation truncated]", true);

        return "You are an OSRS RuneLite assistant. Use OSRS knowledge and treat GAME CONTEXT and tools as truth.\n"
                + "\n"
                + "AVAILABLE TOOLS:\n"
                + "- Player state: 'get_player_skills', 'get_player_inventory', 'get_player_equipment', 'get_player_bank' (when open), 'get_player_status', 'get_player_currencies_and_points', 'get_player_location_details'.\n"
                + "- Activities & tasks: 'get_player_slayer_task', 'get_player_quests', 'get_player_achievement_diaries', 'get_player_combat_achievements', 'get_player_clues'.\n"
                + "- Game info: 'get_item_stats', 'search_osrs_wiki'.\n"
                + "- Call tools to inspect player state rather than guessing. Call 'search_osrs_wiki' to verify monster details, weaknesses, drop rates, item stats, recipes, training methods, and locations.\n"
                + "\n"
                + "GROUNDING RULES:\n"
                + "1. Never invent stats, quests, quest rewards, items, locations, mechanics, or NPCs for the player. Always call 'get_player_skills' before quoting or referencing the player's specific skill levels or XP.\n"
                + "2. Keep answers concise, direct, practical, and conversational. Do not use markdown headings (# or ##).\n"
                + "3. For Ironman/UIM/GIM accounts, value items by High Alchemy value (haPrice) rather than Grand Exchange price (gePrice), and do not suggest invalid GE trading.\n"
                + "4. Base travel recommendations on the player's location, active spellbook, and inventory/equipment/bank teleportation items. Do not assume standard teleports if on Ancients/Lunar/Arceuus.\n"
                + "5. Never assume obscure items are useless; advise checking wiki/clue steps before alching or destroying unique gear.\n"
                + "6. Do not mix up RS3 features, quest rewards, or mechanics (e.g. RS3 Bloodwood trees, Bakriminel bolts, toolbelt, invention) with OSRS.\n"
                + "7. When providing advice on named quests, items, bosses, monsters, updates, quest rewards, or training methods (especially unfamiliar or potentially mixed-up terms), call 'search_osrs_wiki' to verify exact OSRS facts and mechanics rather than guessing.\n"
                + "8. Maintain consistent conversation context (e.g. if discussing achievement diary tasks, do not confuse them with clue scroll steps).\n"
                + "9. Distinguish general readiness/gear/stat advice from immediate action queries. For general readiness questions (e.g. 'Can I do Eclipse Moon?', 'Am I ready for Vorkath?', 'What setup should I use?'), evaluate the player's base skill levels, equipped gear, and available bank/inventory items, assuming they will heal up, bank, and restock before starting. Do NOT reject or frame general readiness around transient temporary states (e.g. current HP being low, missing food in current inventory, active prayers, or being currently at a temporary location). Reserve transient real-time status for explicit immediate combat or survival queries.\n"
                + "10. If a feature, item, tree, or quest reward is NOT present in OSRS or not part of the requested quest/content according to OSRS wiki/facts, explicitly inform the user that it does not exist in OSRS or is not part of that quest, rather than fabricating non-existent quest rewards or mechanics.\n"
                + "11. When calculating remaining XP to reach a target level (e.g. level 65), never confuse 'xpToNextLevel' (XP needed for the immediate next level, e.g. 64) with total XP needed for the requested target level. Always inspect 'xpToTargetLevel' or specific milestone XP fields (e.g. 'xpTo65') returned by 'get_player_skills' before stating required XP.\n"
                + "12. When the user asks a multi-part question or requests training advice (e.g. 'whats the best way to go about it?', 'avoid broad bolts'), you MUST answer all parts of the question. Never stop after just stating XP numbers. Provide concrete, actionable OSRS training methods (e.g. cutting/stringing bows, making darts, arrows, or alternative materials) tailored to their account type (Ironman vs Main) and specified constraints. Call 'search_osrs_wiki' or inspect bank/inventory when helpful to recommend methods.\n"
                + "13. Address the player's actual skill levels directly (e.g. 'At your 80 Magic...') instead of using generic hypothetical conditionals (e.g. 'If your Magic is 70+', 'If you have 40+ Magic'). Always refer to the player's active skill levels in GAME CONTEXT or call 'get_player_skills'.\n"
                + "14. Verify exact spell rune requirements, item slots, and gear mechanics before giving advice (e.g. Ice Spells require Water/Chaos/Death/Blood runes, not Air runes; Tome of Fire is a shield-slot off-hand item, not a staff; staff melee stats are negligible and not used for melee combat).\n"
                + "\n"
                + "RECENT CONVERSATION:\n"
                + compactConversation
                + "\n\nGAME CONTEXT:\n"
                + trimToPromptBudget(context, MAX_CONTEXT_CHARACTERS, "...[game context truncated for prompt budget]");
    }

    public static String trimToPromptBudget(String text, int maxChars, String truncationLabel) {
        return trimToPromptBudget(text, maxChars, truncationLabel, false);
    }

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

    public static String truncateForNotification(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= 80) {
            return text;
        }
        return text.substring(0, 77) + "...";
    }

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
