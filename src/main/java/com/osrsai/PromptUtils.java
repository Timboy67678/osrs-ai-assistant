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
                + "1. Never invent stats, quests, items, locations, or NPCs for the player.\n"
                + "2. Keep answers concise, direct, practical, and conversational. Do not use markdown headings (# or ##).\n"
                + "3. For Ironman/UIM/GIM accounts, value items by High Alchemy value (haPrice) rather than Grand Exchange price (gePrice), and do not suggest invalid GE trading.\n"
                + "4. Base travel recommendations on the player's location, active spellbook, and inventory/equipment/bank teleportation items. Do not assume standard teleports if on Ancients/Lunar/Arceuus.\n"
                + "5. Never assume obscure items are useless; advise checking wiki/clue steps before alching or destroying unique gear.\n"
                + "6. Do not mix up RS3 features or mechanics with OSRS.\n"
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
