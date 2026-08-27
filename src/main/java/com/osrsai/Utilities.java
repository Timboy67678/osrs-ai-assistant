package com.osrsai;

import net.runelite.api.Client;
import net.runelite.api.Varbits;

/**
 * General utility class providing shared OSRS game state decoders, account type
 * checks,
 * equipment slot mappings, achievement diary progression formatters, and string
 * helpers.
 */
public class Utilities {

    private Utilities() {
        // Utility class
    }

    /**
     * Enum representing the player's account type in Old School RuneScape.
     */
    public enum AccountType {
        NORMAL(0, "Normal"),
        IRONMAN(1, "Ironman"),
        ULTIMATE_IRONMAN(2, "Ultimate Ironman (UIM)"),
        HARDCORE_IRONMAN(3, "Hardcore Ironman (HCIM)"),
        GROUP_IRONMAN(4, "Group Ironman (GIM)"),
        HARDCORE_GROUP_IRONMAN(5, "Hardcore Group Ironman (HGIM)"),
        UNRANKED_GROUP_IRONMAN(6, "Unranked Group Ironman (UGIM)"),
        UNKNOWN(-1, "Unknown");

        private final int id;
        private final String name;

        AccountType(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public boolean isIronman() {
            return this != NORMAL && this != UNKNOWN;
        }

        /**
         * Resolves an {@link AccountType} from an integer varbit ID.
         *
         * @param varbit integer varbit value
         * @return matching {@link AccountType}, or {@link #UNKNOWN} if null or unmapped
         */
        public static AccountType fromVarbit(Integer varbit) {
            if (varbit == null) {
                return UNKNOWN;
            }
            for (AccountType type : values()) {
                if (type.id == varbit) {
                    return type;
                }
            }
            return UNKNOWN;
        }
    }

    /**
     * Checks if the currently logged-in player is on an Ironman account mode
     * (Ironman, UIM, HCIM, GIM, HGIM, UGIM).
     *
     * @param client RuneLite {@link Client} instance
     * @return {@code true} if an Ironman mode is active; {@code false} otherwise
     */
    public static boolean isIronman(Client client) {
        if (client == null) {
            return false;
        }
        try {
            int accountType = client.getVarbitValue(Varbits.ACCOUNT_TYPE);
            return AccountType.fromVarbit(accountType).isIronman();
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Converts RuneLite's ACCOUNT_TYPE varbit integer value into a human-readable
     * account type string.
     *
     * @param accountTypeVarbit varbit integer (0=Normal, 1=Ironman, 2=UIM, 3=HCIM,
     *                          4=GIM, 5=HGIM, 6=UGIM)
     * @return account type display name
     */
    public static String describeAccountTypeFromVarbit(Integer accountTypeVarbit) {
        if (accountTypeVarbit == null) {
            return "Unknown";
        }
        return AccountType.fromVarbit(accountTypeVarbit).getName();
    }

    /**
     * Converts RuneLite's active spellbook varbit integer into a human-readable
     * spellbook name.
     *
     * @param val spellbook integer ID (0=Standard, 1=Ancient Magicks, 2=Lunar,
     *            3=Arceuus)
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

    /**
     * Converts an equipment slot index integer into a human-readable equipment slot
     * name.
     *
     * @param index slot index (0=Head, 1=Cape, 2=Amulet, 3=Weapon, 4=Body,
     *              5=Shield, 6=Legs, 7=Gloves, 8=Boots, 9=Ring, 10=Ammo)
     * @return equipment slot display name
     */
    public static String getSlotName(int index) {
        switch (index) {
            case 0:
                return "Head";
            case 1:
                return "Cape";
            case 2:
                return "Amulet";
            case 3:
                return "Weapon";
            case 4:
                return "Body";
            case 5:
                return "Shield";
            case 6:
                return "Legs";
            case 7:
                return "Gloves";
            case 8:
                return "Boots";
            case 9:
                return "Ring";
            case 10:
                return "Ammo";
            default:
                return "Unknown (" + index + ")";
        }
    }

    /**
     * Formats achievement diary task progression into a human-readable status
     * string.
     *
     * @param completedTasks number of completed tasks
     * @param maxTasks       total number of tasks required
     * @return status description ("Not Started", "Completed", or "In Progress (X/Y
     *         tasks)")
     */
    public static String describeDiaryStatus(int completedTasks, int maxTasks) {
        if (completedTasks <= 0) {
            return "Not Started";
        }
        if (completedTasks >= maxTasks) {
            return "Completed";
        }
        return "In Progress (" + completedTasks + "/" + maxTasks + " tasks)";
    }

    /**
     * Resolves an achievement diary tier's status from a client and varbit ID.
     *
     * @param client   RuneLite {@link Client} instance
     * @param varbitId diary varbit identifier
     * @param maxTasks total number of tasks required
     * @return status description
     */
    public static String getDiaryStatus(Client client, int varbitId, int maxTasks) {
        if (client == null) {
            return "Not Started";
        }
        try {
            int val = client.getVarbitValue(varbitId);
            return describeDiaryStatus(val, maxTasks);
        } catch (Exception ignored) {
            return "Not Started";
        }
    }

    /**
     * Truncates text to a maximum character limit with ellipsis.
     *
     * @param text      input text
     * @param maxLength maximum allowed length
     * @return truncated string
     */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (maxLength <= 0) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        if (maxLength <= 3) {
            return text.substring(0, maxLength);
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}
