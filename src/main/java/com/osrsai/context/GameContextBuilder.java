package com.osrsai.context;

import com.osrsai.OsrsAiConfig;
import com.osrsai.util.LocationResolver;
import com.osrsai.util.PromptUtils;
import com.osrsai.util.Utilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;

import java.util.ArrayList;
import java.util.List;



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
            accountTypeVarbit = client.getVarbitValue(VarbitID.IRONMAN);
        }
        sb.append("Account Type: ").append(Utilities.describeAccountTypeFromVarbit(accountTypeVarbit)).append("\n");
        sb.append("World: ").append(client.getWorld()).append("\n");
        sb.append("Total Level: ").append(client.getTotalLevel()).append("\n");
        sb.append("Player Skill Levels (Base): ");
        List<String> skillList = new ArrayList<>();
        for (Skill s : Skill.values()) {
            if (isOverall(s)) {
                continue;
            }
            try {
                int lvl = client.getRealSkillLevel(s);
                if (lvl > 0) {
                    skillList.add(s.getName() + " " + lvl);
                }
            } catch (Exception ignored) {
            }
        }
        sb.append(String.join(", ", skillList)).append("\n");
        int spellbookVar = client.getVarbitValue(VarbitID.SPELLBOOK);
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

        SailingState sailing = SailingState.read(client);
        if (sailing.onBoat) {
            sb.append("\nON A BOAT (Sailing):\n");
            if (sailing.hasHullHp()) {
                sb.append("Hull HP: ").append(sailing.hullHp).append("/").append(sailing.maxHullHp).append("\n");
            }
            if (sailing.repairKits >= 0) {
                sb.append("Repair kits: ").append(sailing.repairKits).append("\n");
            }
            sb.append("Call get_player_sailing_status for cargo and more boat details.\n");
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
                if (client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1) {
                    sb.append("In the Wilderness: Yes (PvP risk; call get_player_location_details for the level)\n");
                }
            }
        }
        sb.append("\n");

        return PromptUtils.trimToPromptBudget(sb.toString(), PromptUtils.MAX_CONTEXT_CHARACTERS,
                "...[game context truncated for prompt budget]");
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

    // Skill.OVERALL is deprecated but still returned by Skill.values(), so it must be filtered out.
    @SuppressWarnings("deprecation")
    private static boolean isOverall(Skill s) {
        return s == Skill.OVERALL;
    }
}
