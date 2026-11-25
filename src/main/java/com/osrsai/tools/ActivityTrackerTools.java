package com.osrsai.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrsai.context.GameContextBuilder;
import com.osrsai.context.SailingState;
import com.osrsai.util.LocationResolver;
import com.osrsai.util.Utilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.AnagramClue;
import net.runelite.client.plugins.cluescrolls.clues.CipherClue;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.CrypticClue;
import net.runelite.client.plugins.cluescrolls.clues.EmoteClue;
import net.runelite.client.plugins.cluescrolls.clues.FairyRingClue;
import net.runelite.client.plugins.cluescrolls.clues.FaloTheBardClue;
import net.runelite.client.plugins.cluescrolls.clues.HotColdClue;
import net.runelite.client.plugins.cluescrolls.clues.LocationClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.MapClue;
import net.runelite.client.plugins.cluescrolls.clues.MusicClue;
import net.runelite.client.plugins.cluescrolls.clues.NpcClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.SkillChallengeClue;
import net.runelite.client.plugins.cluescrolls.clues.ThreeStepCrypticClue;
import net.runelite.api.gameval.InventoryID;

/**
 * Tool implementations for active clue scrolls and sailing telemetry / status.
 */
@Slf4j
public class ActivityTrackerTools {

    private final Client client;
    private final ItemManager itemManager;
    private final PluginManager pluginManager;
    private final LocationResolver locationResolver;
    private final GameContextBuilder gameContextBuilder;
    private final Gson gson;

    public ActivityTrackerTools(Client client, ItemManager itemManager, PluginManager pluginManager,
            LocationResolver locationResolver, GameContextBuilder gameContextBuilder, Gson gson) {
        this.client = client;
        this.itemManager = itemManager;
        this.pluginManager = pluginManager;
        this.locationResolver = locationResolver;
        this.gameContextBuilder = gameContextBuilder;
        this.gson = gson;
    }

    public String executeGetPlayerClues(JsonObject args) {
        JsonObject result = new JsonObject();
        result.add("inventoryClues", extractClueItems(InventoryID.INV, "Inventory"));
        result.add("bankClues", extractClueItems(InventoryID.BANK, "Bank"));
        result.add("activeClue", extractActiveClueDetails());
        return gson.toJson(result);
    }

    private JsonArray extractClueItems(int inventoryId, String location) {
        JsonArray clueItems = new JsonArray();
        ItemContainer container = client.getItemContainer(inventoryId);
        if (container != null) {
            for (Item item : container.getItems()) {
                if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) {
                    continue;
                }
                ItemComposition comp = client.getItemDefinition(item.getId());
                if (comp != null && comp.getIntValue(ParamID.CLUE_SCROLL) != -1) {
                    JsonObject clueItem = new JsonObject();
                    clueItem.addProperty("id", item.getId());
                    clueItem.addProperty("name", comp.getName());
                    clueItem.addProperty("qty", item.getQuantity());
                    clueItem.addProperty("location", location);
                    clueItems.add(clueItem);
                }
            }
        }
        return clueItems;
    }

    private JsonObject extractActiveClueDetails() {
        JsonObject activeClueObj = new JsonObject();
        if (pluginManager == null) {
            activeClueObj.addProperty("status", "RuneLite's built-in Clue Scroll plugin was not found.");
            return activeClueObj;
        }

        net.runelite.client.plugins.cluescrolls.ClueScrollPlugin cluePlugin = null;
        for (Plugin p : pluginManager.getPlugins()) {
            if (p instanceof net.runelite.client.plugins.cluescrolls.ClueScrollPlugin) {
                cluePlugin = (net.runelite.client.plugins.cluescrolls.ClueScrollPlugin) p;
                break;
            }
        }

        if (cluePlugin == null) {
            activeClueObj.addProperty("status", "RuneLite's built-in Clue Scroll plugin was not found.");
            return activeClueObj;
        }

        if (!pluginManager.isPluginEnabled(cluePlugin)) {
            activeClueObj.addProperty("status",
                    "RuneLite's built-in Clue Scroll plugin is disabled in the client settings. Ask the player to enable it.");
            return activeClueObj;
        }

        net.runelite.client.plugins.cluescrolls.clues.ClueScroll clue = cluePlugin.getClue();
        if (clue == null) {
            activeClueObj.addProperty("status",
                    "No active clue scroll step loaded. Ask the player to read/open their clue scroll once to activate tracking.");
            return activeClueObj;
        }

        activeClueObj.addProperty("status", "Active clue scroll detected");
        activeClueObj.addProperty("type", clue.getClass().getSimpleName());

        try {
            activeClueObj.add("details", formatClueDetails(clue, cluePlugin));
        } catch (Throwable t) {
            activeClueObj.addProperty("error", "Failed to format clue details: " + t.getMessage());
        }

        return activeClueObj;
    }

    /**
     * Describes the active clue using the Clue Scroll plugin's public getters. Each lookup is
     * guarded separately so one failing getter doesn't drop the rest of the details.
     */
    private JsonArray formatClueDetails(ClueScroll clue, ClueScrollPlugin cluePlugin) {
        JsonArray lines = new JsonArray();

        if (clue instanceof CrypticClue) {
            CrypticClue c = (CrypticClue) clue;
            addLine(lines, "Clue", () -> c.getText());
            addLine(lines, "Solution", () -> c.getSolution(cluePlugin));
            addLine(lines, "Question", () -> c.getQuestionText());
            addLine(lines, "Answer", () -> c.getAnswer());
        } else if (clue instanceof AnagramClue) {
            AnagramClue c = (AnagramClue) clue;
            addLine(lines, "Anagram", () -> c.getTextProvider().apply(cluePlugin));
            addLine(lines, "Solution NPC", () -> c.getNpcProvider().apply(cluePlugin));
            addLine(lines, "Area", () -> c.getArea());
            addLine(lines, "Question", () -> c.getQuestion());
            addLine(lines, "Answer", () -> c.getAnswerProvider().apply(cluePlugin));
        } else if (clue instanceof CipherClue) {
            CipherClue c = (CipherClue) clue;
            addLine(lines, "Cipher", () -> c.getText());
            addLine(lines, "Area", () -> c.getAreaProvider().apply(cluePlugin));
            addLine(lines, "Question", () -> c.getQuestion());
            addLine(lines, "Answer", () -> c.getAnswer());
        } else if (clue instanceof EmoteClue) {
            EmoteClue c = (EmoteClue) clue;
            addLine(lines, "Clue", () -> c.getText());
            addLine(lines, "Location", () -> c.getLocationName());
            addLine(lines, "First emote", () -> c.getFirstEmote() != null ? c.getFirstEmote().getName() : null);
            addLine(lines, "Second emote", () -> c.getSecondEmote() != null ? c.getSecondEmote().getName() : null);
            addLine(lines, "STASH unit", () -> c.getStashUnit() != null ? c.getStashUnit().name() : null);
        } else if (clue instanceof HotColdClue) {
            HotColdClue c = (HotColdClue) clue;
            addLine(lines, "Clue", () -> c.getText());
            addLine(lines, "NPC", () -> c.getNpc());
            addLine(lines, "Solution", () -> c.getSolution());
        } else if (clue instanceof SkillChallengeClue) {
            SkillChallengeClue c = (SkillChallengeClue) clue;
            addLine(lines, "Challenge", () -> c.getChallenge());
            addLine(lines, "Return to", () -> c.getReturnText());
            addLine(lines, "Challenge completed", () -> String.valueOf(c.isChallengeCompleted()));
        } else if (clue instanceof MapClue) {
            addLine(lines, "Map", () -> ((MapClue) clue).getDescription());
        } else if (clue instanceof MusicClue) {
            addLine(lines, "Song to play", () -> ((MusicClue) clue).getSong());
        } else if (clue instanceof FaloTheBardClue) {
            addLine(lines, "Clue", () -> ((FaloTheBardClue) clue).getText());
        } else if (clue instanceof FairyRingClue) {
            addLine(lines, "Clue", () -> ((FairyRingClue) clue).getText());
        } else if (clue instanceof ThreeStepCrypticClue) {
            addLine(lines, "Clue", () -> ((ThreeStepCrypticClue) clue).getText());
        }

        if (clue instanceof NpcClueScroll) {
            addLine(lines, "NPCs", () -> {
                String[] npcs = ((NpcClueScroll) clue).getNpcs(cluePlugin);
                return npcs != null && npcs.length > 0 ? String.join(", ", npcs) : null;
            });
        }
        if (clue instanceof LocationClueScroll) {
            addLine(lines, "Dig/search location (world point)", () -> {
                WorldPoint wp = ((LocationClueScroll) clue).getLocation(cluePlugin);
                return wp != null ? wp.getX() + ", " + wp.getY() + ", " + wp.getPlane() : null;
            });
        }
        addLine(lines, "Requires spade", () -> clue.isRequiresSpade() ? "yes" : null);
        addLine(lines, "Requires light source", () -> clue.isRequiresLight() ? "yes" : null);
        addLine(lines, "Enemy that may appear", () -> clue.getEnemy() != null ? clue.getEnemy().getText() : null);
        return lines;
    }

    private static void addLine(JsonArray lines, String label, java.util.function.Supplier<String> value) {
        try {
            String v = value.get();
            if (v != null && !v.trim().isEmpty()) {
                lines.add(label + ": " + Utilities.PATTERN_HTML_TAGS.matcher(v).replaceAll(" ").trim());
            }
        } catch (Throwable t) {
            log.debug("Could not read clue detail '{}': {}", label, t.getMessage());
        }
    }

    /**
     * Sailing level, boat state from the game's Sailing varbits, location, and the contents of
     * any boat cargo holds the client has loaded. Values the game doesn't expose (speed, wind,
     * sail trim, boat name and type) are deliberately not reported.
     */
    public String executeGetPlayerSailingStatus(JsonObject args) {
        JsonObject result = new JsonObject();
        boolean includeCargo = true;
        if (args != null && args.has("includeCargo") && !args.get("includeCargo").isJsonNull()) {
            includeCargo = args.get("includeCargo").getAsBoolean();
        }

        JsonObject skillData = new JsonObject();
        try {
            skillData.addProperty("realLevel", client.getRealSkillLevel(Skill.SAILING));
            skillData.addProperty("boostedLevel", client.getBoostedSkillLevel(Skill.SAILING));
            skillData.addProperty("experience", client.getSkillExperience(Skill.SAILING));
        } catch (RuntimeException e) {
            skillData.addProperty("status", "Sailing level unavailable");
        }
        result.add("sailingSkill", skillData);

        SailingState sailing = SailingState.read(client);
        JsonObject boat = new JsonObject();
        boat.addProperty("onBoat", sailing.onBoat);
        if (sailing.onBoat) {
            if (sailing.hasHullHp()) {
                boat.addProperty("hullHp", sailing.hullHp);
                boat.addProperty("maxHullHp", sailing.maxHullHp);
                boat.addProperty("hullHealthPercent",
                        (int) Math.round(100.0 * sailing.hullHp / sailing.maxHullHp));
            }
            if (sailing.repairKits >= 0) {
                boat.addProperty("repairKits", sailing.repairKits);
            }
            if (sailing.maxWindCharges > 0 && sailing.windCharges >= 0) {
                boat.addProperty("windCharges", sailing.windCharges);
                boat.addProperty("maxWindCharges", sailing.maxWindCharges);
            }
            boat.addProperty("playerAtHelm", sailing.atHelm);
            boat.addProperty("playerAtSails", sailing.atSails);
        }
        boat.addProperty("note", "Boat speed, wind direction, sail trim and the boat's name and type are not"
                + " available to this plugin. Don't state them.");
        result.add("boat", boat);

        JsonObject locObj = new JsonObject();
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer != null) {
            WorldPoint wp = localPlayer.getWorldLocation();
            if (wp != null) {
                boolean inInstance = gameContextBuilder != null && gameContextBuilder.isInInstance(localPlayer);
                InstanceTemplates template = gameContextBuilder != null
                        ? gameContextBuilder.getInstanceTemplate(localPlayer, wp)
                        : null;
                String locName = locationResolver != null ? locationResolver.describeForAi(wp, inInstance, template)
                        : "Unknown";
                locObj.addProperty("locationName", locName);
                locObj.addProperty("regionId", wp.getRegionID());
                locObj.addProperty("coordinates", wp.getX() + ", " + wp.getY() + ", " + wp.getPlane());
                locObj.addProperty("inInstance", inInstance);
            }
        }
        result.add("location", locObj);

        if (includeCargo) {
            JsonArray holds = new JsonArray();
            for (int i = 0; i < SailingState.CARGO_HOLDS.length; i++) {
                ItemContainer hold = client.getItemContainer(SailingState.CARGO_HOLDS[i]);
                if (hold == null) {
                    continue;
                }
                JsonArray items = new JsonArray();
                for (Item item : hold.getItems()) {
                    if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) {
                        continue;
                    }
                    ItemComposition comp = itemManager != null ? itemManager.getItemComposition(item.getId()) : null;
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", item.getId());
                    entry.addProperty("name", comp != null ? comp.getName() : "Item " + item.getId());
                    entry.addProperty("quantity", item.getQuantity());
                    items.add(entry);
                }
                JsonObject holdObj = new JsonObject();
                holdObj.addProperty("boat", i + 1);
                holdObj.add("items", items);
                holds.add(holdObj);
            }
            result.add("cargoHolds", holds);
            result.addProperty("cargoNote", holds.size() == 0
                    ? "No cargo hold contents are loaded. The game only sends a cargo hold's contents once the"
                            + " player has opened it this session."
                    : "Cargo hold contents as last seen this session; they may have changed since.");
        }

        return gson.toJson(result);
    }
}
