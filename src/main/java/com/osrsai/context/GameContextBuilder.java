package com.osrsai.context;

import com.osrsai.OsrsAiConfig;
import com.osrsai.util.LocationResolver;
import com.osrsai.util.PromptUtils;
import com.osrsai.util.Utilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;



/**
 * Builds the initial real-time player and game context snapshot for AI prompts.
 */
@Slf4j
public class GameContextBuilder {

    private final Client client;
    private final OsrsAiConfig config;
    private final LocationResolver locationResolver;

    public GameContextBuilder(Client client, OsrsAiConfig config, LocationResolver locationResolver) {
        this.client = client;
        this.config = config;
        this.locationResolver = locationResolver;
    }

    /**
     * Telemetry data structure extracted from active vessel sailing widgets.
     */
    public static class VesselWidgetData {
        public boolean foundVesselUi = false;
        public String shipName = null;
        public int currentHp = -1;
        public int maxHp = -1;
        public String sailingActivity = null;
        public List<String> facilities = new ArrayList<>();
    }

    /**
     * Builds the formatted player context snapshot string.
     *
     * @return prompt-budgeted context string
     */
    public String buildGameContext() {
        if (config != null && !config.shareCharacterInfo()) {
            return "Player is not sharing character details with the AI (this option is disabled in the settings).";
        }

        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return "Player is not logged in.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("PLAYER PROFILE:\n");

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer != null) {
            sb.append("Name: ").append(localPlayer.getName()).append("\n");
            sb.append("Combat Level: ").append(localPlayer.getCombatLevel()).append("\n");
        }

        Integer accountTypeVarbit = null;
        if (client.getLocalPlayer() != null) {
            accountTypeVarbit = client.getVarbitValue(Varbits.ACCOUNT_TYPE);
        }
        sb.append("Account Type: ").append(Utilities.describeAccountTypeFromVarbit(accountTypeVarbit)).append("\n");
        sb.append("World: ").append(client.getWorld()).append("\n");
        sb.append("Total Level: ").append(client.getTotalLevel()).append("\n");
        sb.append("Combat & Key Skills: ")
                .append("Attack ").append(client.getRealSkillLevel(Skill.ATTACK)).append(", ")
                .append("Strength ").append(client.getRealSkillLevel(Skill.STRENGTH)).append(", ")
                .append("Defence ").append(client.getRealSkillLevel(Skill.DEFENCE)).append(", ")
                .append("Ranged ").append(client.getRealSkillLevel(Skill.RANGED)).append(", ")
                .append("Prayer ").append(client.getRealSkillLevel(Skill.PRAYER)).append(", ")
                .append("Magic ").append(client.getRealSkillLevel(Skill.MAGIC)).append(", ")
                .append("Hitpoints ").append(client.getRealSkillLevel(Skill.HITPOINTS)).append(", ")
                .append("Slayer ").append(client.getRealSkillLevel(Skill.SLAYER)).append("\n");
        int spellbookVar = client.getVarbitValue(Varbits.SPELLBOOK);
        sb.append("Active Spellbook: ").append(Utilities.describeSpellbook(spellbookVar)).append("\n");
        sb.append("Hitpoints: Current ")
                .append(client.getBoostedSkillLevel(Skill.HITPOINTS))
                .append(" (Base Level ")
                .append(client.getRealSkillLevel(Skill.HITPOINTS))
                .append(")\n");
        sb.append("Prayer Points: Current ")
                .append(client.getBoostedSkillLevel(Skill.PRAYER))
                .append(" (Base Level ")
                .append(client.getRealSkillLevel(Skill.PRAYER))
                .append(")\n");

        for (Skill s : Skill.values()) {
            if ("SAILING".equalsIgnoreCase(s.name())) {
                try {
                    sb.append("Sailing Skill: Base Level ")
                            .append(client.getRealSkillLevel(s))
                            .append(" (Boosted ")
                            .append(client.getBoostedSkillLevel(s))
                            .append(")\n");
                } catch (Exception ignored) {
                }
                break;
            }
        }

        VesselWidgetData vData = scanVesselWidgets();
        if (vData.foundVesselUi) {
            sb.append("\nCURRENTLY ABOARD VESSEL:\n");
            sb.append("Vessel Status: ABOARD VESSEL\n");
            sb.append("Vessel Name: ").append(vData.shipName != null ? vData.shipName : "Sailing Vessel").append("\n");
            if (vData.currentHp > 0 && vData.maxHp > 0) {
                int hpPct = (int) Math.round(((double) vData.currentHp / vData.maxHp) * 100.0);
                sb.append("Hull Health: ").append(vData.currentHp).append("/").append(vData.maxHp)
                        .append(" (").append(hpPct).append("%)\n");
            }
            if (vData.sailingActivity != null) {
                sb.append("Current Activity: ").append(vData.sailingActivity).append("\n");
            }
            if (!vData.facilities.isEmpty()) {
                sb.append("Active Facilities: ").append(String.join(", ", vData.facilities)).append("\n");
            }
        }
        sb.append("\nTEMPORARY CURRENT LOCATION (where player is standing right now):\n");
        if (localPlayer != null) {
            WorldPoint wp = localPlayer.getWorldLocation();
            if (wp != null) {
                InstanceTemplates instanceTemplate = getInstanceTemplate(localPlayer, wp);
                boolean inInstance = isInInstance(localPlayer);
                String locName = locationResolver != null
                        ? locationResolver.describeForAi(wp, inInstance, instanceTemplate)
                        : "Unknown";
                sb.append("Location Name: ").append(locName).append("\n");
                sb.append("Coordinates: ").append(wp.getX()).append(", ").append(wp.getY()).append(", Plane ")
                        .append(wp.getPlane()).append("\n");
                sb.append("Region ID: ").append(wp.getRegionID()).append("\n");
                sb.append("Instanced Area: ").append(inInstance ? "Yes" : "No").append("\n");
            }
        }
        sb.append("\n");

        return PromptUtils.trimToPromptBudget(sb.toString(), PromptUtils.MAX_CONTEXT_CHARACTERS,
                "...[game context truncated for prompt budget]");
    }

    private static final int VARBIT_SAILING_STATE = 15200;
    private static final Pattern PATTERN_TIMESTAMP = Pattern
            .compile("^\\[\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM|am|pm)?\\]");
    private static final Pattern PATTERN_SHIP_TYPE = Pattern.compile(
            "\\b(clipper|sloop|skiff|brig|frigate|galleon|raft|caravel|dhow|catamaran)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Set<Integer> IGNORED_WIDGET_GROUPS = Set.of(
            162, // Chatbox
            163, // Private Chat
            164, // Chat channel
            149, // Inventory
            387, // Equipment
            84, // Equipment screen
            320, // Skills
            399, // Quest list
            629, // Achievement Diary
            712, // Combat Achievements
            541, // Prayer
            218, // Spellbook
            429, // Friends
            432, // Ignore
            7, // Clan
            693, // Friends Chat
            239, // Music
            116, // Settings
            261, // Options
            216, // Emotes
            182, // Logout
            69, // World switcher
            160, // Minimap / orbs
            12, // Bank
            213, // Bank pin
            192, // Deposit box
            465, // Grand Exchange
            217, // NPC dialogue
            231, // Player dialogue
            193, // Options dialogue
            229, // Message dialogue
            219 // Dialogues
    );

    /**
     * Scans active widgets across all roots to extract vessel sailing data.
     *
     * @return extracted {@link VesselWidgetData}
     */
    public VesselWidgetData scanVesselWidgets() {
        VesselWidgetData data = new VesselWidgetData();
        if (client == null) {
            return data;
        }

        try {
            int sailingStateVar = client.getVarbitValue(VARBIT_SAILING_STATE);
            if (sailingStateVar > 0) {
                data.foundVesselUi = true;
            }
        } catch (Exception ignored) {
        }

        Player localPlayer = client.getLocalPlayer();
        String localPlayerName = (localPlayer != null && localPlayer.getName() != null)
                ? localPlayer.getName().trim()
                : "";

        try {
            Widget[] roots = client.getWidgetRoots();
            if (roots != null) {
                for (Widget root : roots) {
                    scanWidgetNode(root, data, localPlayerName);
                }
            }
        } catch (Exception e) {
            log.debug("Error scanning widgets for vessel telemetry", e);
        }

        return data;
    }

    private void scanWidgetNode(Widget widget, VesselWidgetData data, String localPlayerName) {
        if (widget == null || widget.isSelfHidden()) {
            return;
        }

        int groupId = widget.getId() >> 16;
        if (IGNORED_WIDGET_GROUPS.contains(groupId)) {
            return;
        }

        String text = widget.getText();
        if (text != null && !text.isEmpty()) {
            String cleanText = Utilities.PATTERN_HTML_TAGS.matcher(text).replaceAll("").trim();
            if (!cleanText.isEmpty() && !isIgnoredText(cleanText, localPlayerName)) {
                if (cleanText.matches("^\\d{1,4}\\s*/\\s*\\d{1,4}$")) {
                    String[] parts = cleanText.split("/");
                    try {
                        data.currentHp = Integer.parseInt(parts[0].trim());
                        data.maxHp = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException ignored) {
                    }
                }

                String lower = cleanText.toLowerCase();
                if (lower.equals("facilities") || lower.equals("steering") || lower.startsWith("repairs")
                        || lower.equals("sail trim") || lower.equals("wind vector") || lower.equals("anchor")) {
                    data.foundVesselUi = true;
                    if (!data.facilities.contains(cleanText)) {
                        data.facilities.add(cleanText);
                    }
                }

                if (lower.contains("charting") || lower.contains("weather pattern")) {
                    data.sailingActivity = cleanText;
                    data.foundVesselUi = true;
                }

                if (cleanText.length() >= 3 && cleanText.length() <= 35) {
                    if (PATTERN_SHIP_TYPE.matcher(cleanText).find()) {
                        data.shipName = cleanText;
                        data.foundVesselUi = true;
                    }
                }
            }
        }

        Widget[] children = widget.getChildren();
        if (children != null) {
            for (Widget child : children) {
                scanWidgetNode(child, data, localPlayerName);
            }
        }
        Widget[] nested = widget.getNestedChildren();
        if (nested != null) {
            for (Widget child : nested) {
                scanWidgetNode(child, data, localPlayerName);
            }
        }
        Widget[] dynamic = widget.getDynamicChildren();
        if (dynamic != null) {
            for (Widget child : dynamic) {
                scanWidgetNode(child, data, localPlayerName);
            }
        }
        Widget[] staticChildren = widget.getStaticChildren();
        if (staticChildren != null) {
            for (Widget child : staticChildren) {
                scanWidgetNode(child, data, localPlayerName);
            }
        }
    }

    private boolean isIgnoredText(String text, String localPlayerName) {
        if (PATTERN_TIMESTAMP.matcher(text).find()) {
            return true;
        }
        if (!localPlayerName.isEmpty() && text.toLowerCase().contains(localPlayerName.toLowerCase())) {
            return true;
        }
        if (text.endsWith(":") || text.contains("]:")) {
            return true;
        }
        return false;
    }

    /**
     * Checks if the player is currently inside an instanced area.
     *
     * @param localPlayer player reference
     * @return {@code true} if in an instance; {@code false} otherwise
     */
    public boolean isInInstance(Player localPlayer) {
        if (localPlayer != null) {
            WorldView worldView = localPlayer.getWorldView();
            if (worldView != null) {
                return worldView.isInstance();
            }
        }

        return client != null && client.getTopLevelWorldView() != null && client.getTopLevelWorldView().isInstance();
    }

    /**
     * Resolves the instance template chunk match for an instanced coordinate.
     *
     * @param localPlayer player reference
     * @param worldPoint  current world coordinate
     * @return matching {@link InstanceTemplates}, or {@code null} if not in an
     *         instance
     */
    public InstanceTemplates getInstanceTemplate(Player localPlayer, WorldPoint worldPoint) {
        if (localPlayer == null || worldPoint == null) {
            return null;
        }
        WorldView worldView = localPlayer.getWorldView();
        if (worldView == null || !worldView.isInstance()) {
            return null;
        }

        LocalPoint localPoint = LocalPoint.fromWorld(worldView, worldPoint);
        if (localPoint == null) {
            localPoint = localPlayer.getLocalLocation();
        }

        if (localPoint == null) {
            return null;
        }

        int[][][] chunks = worldView.getInstanceTemplateChunks();
        if (chunks == null) {
            return null;
        }

        int plane = worldPoint.getPlane();
        int chunkX = localPoint.getSceneX() / 8;
        int chunkY = localPoint.getSceneY() / 8;
        if (plane < 0 || plane >= chunks.length
                || chunkX < 0 || chunkX >= chunks[plane].length
                || chunkY < 0 || chunkY >= chunks[plane][chunkX].length) {
            return null;
        }

        return InstanceTemplates.findMatch(chunks[plane][chunkX][chunkY]);
    }
}
