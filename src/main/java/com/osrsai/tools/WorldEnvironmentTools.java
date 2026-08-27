package com.osrsai.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrsai.util.ItemContainerUtils;
import com.osrsai.util.LocationResolver;
import com.osrsai.util.Utilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.ItemManager;

import java.util.*;
import java.util.function.Supplier;

/**
 * Tool implementations for world environment scanning (NPCs, other players,
 * ground items,
 * interactive objects), location details, and transportation / teleportation
 * networks.
 */
@Slf4j
public class WorldEnvironmentTools {
    private static final int DEFAULT_SURROUNDINGS_SCAN_RADIUS = 15;
    private static final int MAX_SURROUNDINGS_SCAN_RADIUS = 30;
    private static final int MIN_SURROUNDINGS_SCAN_RADIUS = 1;
    private static final int MAX_SURROUNDINGS_NPC_COUNT = 20;
    private static final int MAX_SURROUNDINGS_PLAYER_COUNT = 15;
    private static final int MAX_SURROUNDINGS_GROUND_ITEM_COUNT = 15;
    private static final int OBJECT_SCAN_MAX_RADIUS = 10;

    private static final int VARBIT_SPELLBOOK = 4070;
    private static final int VARBIT_WILDERNESS_LEVEL = 5963;

    private static final int POH_LEVEL_PORTAL_CHAMBER = 50;
    private static final int POH_LEVEL_PORTAL_NEXUS = 72;
    private static final int POH_LEVEL_BASIC_JEWELLERY_BOX = 81;
    private static final int POH_LEVEL_FAIRY_RING = 85;
    private static final int POH_LEVEL_ORNATE_JEWELLERY_BOX = 91;
    private static final int POH_LEVEL_SPIRIT_TREE = 95;

    private final Client client;
    private final ItemManager itemManager;
    private final LocationResolver locationResolver;
    private final Gson gson;
    private final Supplier<List<ItemContainerUtils.SimpleItem>> cachedBankSupplier;

    public WorldEnvironmentTools(Client client, ItemManager itemManager, LocationResolver locationResolver,
            Gson gson, Supplier<List<ItemContainerUtils.SimpleItem>> cachedBankSupplier) {
        this.client = client;
        this.itemManager = itemManager;
        this.locationResolver = locationResolver;
        this.gson = gson;
        this.cachedBankSupplier = cachedBankSupplier;
    }

    private boolean isInInstance(Player localPlayer) {
        if (localPlayer != null) {
            WorldView worldView = localPlayer.getWorldView();
            if (worldView != null) {
                return worldView.isInstance();
            }
        }
        return client != null && client.getTopLevelWorldView() != null && client.getTopLevelWorldView().isInstance();
    }

    private InstanceTemplates getInstanceTemplate(Player localPlayer, WorldPoint worldPoint) {
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

    private QuestState getQuestStateSafe(Quest quest) {
        if (quest == null)
            return QuestState.NOT_STARTED;
        try {
            QuestState state = quest.getState(client);
            return state != null ? state : QuestState.NOT_STARTED;
        } catch (Exception e) {
            return QuestState.NOT_STARTED;
        }
    }

    public String executeGetSurroundingEnvironment(JsonObject args) {
        JsonObject result = new JsonObject();
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            result.addProperty("status", "error");
            result.addProperty("message", "Player is not currently logged in.");
            return gson.toJson(result);
        }

        WorldPoint playerLoc = localPlayer.getWorldLocation();
        int radius = (args != null && args.has("radius") && !args.get("radius").isJsonNull())
                ? Math.min(MAX_SURROUNDINGS_SCAN_RADIUS,
                        Math.max(MIN_SURROUNDINGS_SCAN_RADIUS, args.get("radius").getAsInt()))
                : DEFAULT_SURROUNDINGS_SCAN_RADIUS;

        result.addProperty("status", "success");
        result.addProperty("playerLocation",
                playerLoc.getX() + ", " + playerLoc.getY() + ", Plane " + playerLoc.getPlane());
        result.addProperty("scanRadiusTiles", radius);

        WorldView wv = client.getTopLevelWorldView();

        // 1. Nearby NPCs & Monsters
        JsonArray npcList = new JsonArray();
        Iterable<? extends NPC> npcs = wv != null ? wv.npcs() : client.getNpcs();
        if (npcs != null) {
            List<NPC> sortedNpcs = new ArrayList<>();
            for (NPC npc : npcs) {
                if (npc == null || npc.getName() == null || npc.getName().trim().isEmpty()) {
                    continue;
                }
                WorldPoint npcLoc = npc.getWorldLocation();
                if (npcLoc != null && playerLoc.distanceTo(npcLoc) <= radius) {
                    sortedNpcs.add(npc);
                }
            }
            sortedNpcs.sort(Comparator.comparingInt(n -> playerLoc.distanceTo(n.getWorldLocation())));

            int count = 0;
            for (NPC npc : sortedNpcs) {
                if (count >= MAX_SURROUNDINGS_NPC_COUNT) {
                    break;
                }
                JsonObject obj = new JsonObject();
                obj.addProperty("name", npc.getName());
                obj.addProperty("id", npc.getId());
                obj.addProperty("combatLevel", npc.getCombatLevel());
                obj.addProperty("distance", playerLoc.distanceTo(npc.getWorldLocation()));
                if (npc.getHealthScale() > 0 && npc.getHealthRatio() >= 0) {
                    int healthPct = (int) Math.round(((double) npc.getHealthRatio() / npc.getHealthScale()) * 100.0);
                    obj.addProperty("healthPercent", healthPct);
                }
                if (npc.getInteracting() != null && npc.getInteracting().getName() != null) {
                    obj.addProperty("target", npc.getInteracting().getName());
                }
                if (npc.getAnimation() != -1) {
                    obj.addProperty("animating", true);
                }
                npcList.add(obj);
                count++;
            }
        }
        result.add("nearbyNpcs", npcList);

        // 2. Nearby Players (Wilderness / Threat Awareness)
        JsonArray playerList = new JsonArray();
        Iterable<? extends Player> players = wv != null ? wv.players() : client.getPlayers();
        if (players != null) {
            List<Player> sortedPlayers = new ArrayList<>();
            for (Player p : players) {
                if (p == null || p == localPlayer || p.getName() == null) {
                    continue;
                }
                WorldPoint pLoc = p.getWorldLocation();
                if (pLoc != null && playerLoc.distanceTo(pLoc) <= radius) {
                    sortedPlayers.add(p);
                }
            }
            sortedPlayers.sort(Comparator.comparingInt(p -> playerLoc.distanceTo(p.getWorldLocation())));

            int pCount = 0;
            for (Player p : sortedPlayers) {
                if (pCount >= MAX_SURROUNDINGS_PLAYER_COUNT) {
                    break;
                }
                JsonObject pObj = new JsonObject();
                pObj.addProperty("name", p.getName());
                pObj.addProperty("combatLevel", p.getCombatLevel());
                pObj.addProperty("distance", playerLoc.distanceTo(p.getWorldLocation()));
                pObj.addProperty("skulled", p.getSkullIcon() != -1);
                if (p.getInteracting() != null && p.getInteracting().getName() != null) {
                    pObj.addProperty("interactingWith", p.getInteracting().getName());
                }
                playerList.add(pObj);
                pCount++;
            }
        }
        result.add("nearbyPlayers", playerList);

        // 3. Ground Items in render distance
        JsonArray groundItemList = new JsonArray();
        Scene scene = (wv != null && wv.getScene() != null) ? wv.getScene() : client.getScene();
        if (scene != null && scene.getTiles() != null) {
            int plane = playerLoc.getPlane();
            Tile[][][] tiles = scene.getTiles();
            if (plane >= 0 && plane < tiles.length && tiles[plane] != null) {
                LocalPoint localPoint = localPlayer.getLocalLocation();
                if (localPoint != null) {
                    int centerTileX = localPoint.getSceneX();
                    int centerTileY = localPoint.getSceneY();
                    int minX = Math.max(0, centerTileX - radius);
                    int maxX = Math.min(tiles[plane].length - 1, centerTileX + radius);
                    int minY = Math.max(0, centerTileY - radius);
                    int maxY = Math.min(tiles[plane][0].length - 1, centerTileY + radius);

                    List<JsonObject> groundItemsFound = new ArrayList<>();
                    for (int x = minX; x <= maxX; x++) {
                        for (int y = minY; y <= maxY; y++) {
                            Tile tile = tiles[plane][x][y];
                            if (tile == null) {
                                continue;
                            }
                            List<TileItem> items = tile.getGroundItems();
                            if (items != null) {
                                for (TileItem item : items) {
                                    if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) {
                                        continue;
                                    }
                                    ItemComposition comp = null;
                                    try {
                                        comp = itemManager != null ? itemManager.getItemComposition(item.getId())
                                                : null;
                                    } catch (Exception ignored) {
                                    }
                                    String itemName = (comp != null && comp.getName() != null) ? comp.getName()
                                            : "Item " + item.getId();
                                    int gePrice = itemManager != null ? itemManager.getItemPrice(item.getId()) : 0;
                                    int haPrice = comp != null ? comp.getHaPrice() : 0;
                                    int dist = Math.max(Math.abs(x - centerTileX), Math.abs(y - centerTileY));

                                    JsonObject gObj = new JsonObject();
                                    gObj.addProperty("name", itemName);
                                    gObj.addProperty("id", item.getId());
                                    gObj.addProperty("quantity", item.getQuantity());
                                    gObj.addProperty("gePrice", gePrice);
                                    gObj.addProperty("haPrice", haPrice);
                                    gObj.addProperty("distance", dist);
                                    groundItemsFound.add(gObj);
                                }
                            }
                        }
                    }
                    groundItemsFound.sort((a, b) -> {
                        int valA = Math.max(a.get("gePrice").getAsInt(), a.get("haPrice").getAsInt())
                                * a.get("quantity").getAsInt();
                        int valB = Math.max(b.get("gePrice").getAsInt(), b.get("haPrice").getAsInt())
                                * b.get("quantity").getAsInt();
                        return Integer.compare(valB, valA);
                    });
                    int giCount = 0;
                    for (JsonObject gObj : groundItemsFound) {
                        if (giCount >= MAX_SURROUNDINGS_GROUND_ITEM_COUNT) {
                            break;
                        }
                        groundItemList.add(gObj);
                        giCount++;
                    }
                }
            }
        }
        result.add("nearbyGroundItems", groundItemList);

        // 4. Notable Nearby Game Objects
        JsonArray objectList = new JsonArray();
        if (scene != null && scene.getTiles() != null) {
            int plane = playerLoc.getPlane();
            Tile[][][] tiles = scene.getTiles();
            if (plane >= 0 && plane < tiles.length && tiles[plane] != null) {
                LocalPoint localPoint = localPlayer.getLocalLocation();
                if (localPoint != null) {
                    int centerTileX = localPoint.getSceneX();
                    int centerTileY = localPoint.getSceneY();
                    int minX = Math.max(0, centerTileX - Math.min(OBJECT_SCAN_MAX_RADIUS, radius));
                    int maxX = Math.min(tiles[plane].length - 1,
                            centerTileX + Math.min(OBJECT_SCAN_MAX_RADIUS, radius));
                    int minY = Math.max(0, centerTileY - Math.min(OBJECT_SCAN_MAX_RADIUS, radius));
                    int maxY = Math.min(tiles[plane][0].length - 1,
                            centerTileY + Math.min(OBJECT_SCAN_MAX_RADIUS, radius));

                    Set<String> seenObjects = new HashSet<>();
                    for (int x = minX; x <= maxX; x++) {
                        for (int y = minY; y <= maxY; y++) {
                            Tile tile = tiles[plane][x][y];
                            if (tile == null) {
                                continue;
                            }
                            GameObject[] gameObjs = tile.getGameObjects();
                            if (gameObjs != null) {
                                for (GameObject go : gameObjs) {
                                    if (go == null) {
                                        continue;
                                    }
                                    try {
                                        ObjectComposition oc = client.getObjectDefinition(go.getId());
                                        if (oc != null && oc.getName() != null && !oc.getName().trim().isEmpty()
                                                && !"null".equalsIgnoreCase(oc.getName())) {
                                            String name = oc.getName();
                                            String lower = name.toLowerCase();
                                            if (lower.contains("altar") || lower.contains("bank")
                                                    || lower.contains("booth")
                                                    || lower.contains("chest") || lower.contains("portal")
                                                    || lower.contains("fairy ring")
                                                    || lower.contains("furnace") || lower.contains("anvil")
                                                    || lower.contains("range")
                                                    || lower.contains("ladder") || lower.contains("trapdoor")
                                                    || lower.contains("stairs")
                                                    || lower.contains("shortcut") || lower.contains("tree")
                                                    || lower.contains("crevice")
                                                    || lower.contains("barrier") || lower.contains("entrance")
                                                    || lower.contains("tunnel")) {
                                                if (seenObjects.add(name)) {
                                                    int dist = Math.max(Math.abs(x - centerTileX),
                                                            Math.abs(y - centerTileY));
                                                    JsonObject oObj = new JsonObject();
                                                    oObj.addProperty("name", name);
                                                    oObj.addProperty("distance", dist);
                                                    objectList.add(oObj);
                                                }
                                            }
                                        }
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        result.add("nearbyNotableObjects", objectList);

        return gson.toJson(result);
    }

    public String executeGetPlayerLocationDetails(JsonObject args) {
        JsonObject result = new JsonObject();
        Player localPlayer = client.getLocalPlayer();

        int wildyLevel = 0;
        try {
            wildyLevel = client.getVarbitValue(VARBIT_WILDERNESS_LEVEL);
        } catch (Exception ignored) {
        }
        result.addProperty("wildernessLevel", wildyLevel);
        result.addProperty("inWilderness", wildyLevel > 0);

        boolean isMulti = false;
        try {
            isMulti = client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1;
        } catch (Exception ignored) {
        }
        result.addProperty("multiCombat", isMulti);

        boolean inInstance = isInInstance(localPlayer);
        result.addProperty("instancedArea", inInstance);

        if (localPlayer != null) {
            WorldPoint wp = localPlayer.getWorldLocation();
            if (wp != null) {
                InstanceTemplates instanceTemplate = getInstanceTemplate(localPlayer, wp);
                String locName = locationResolver != null
                        ? locationResolver.describeForAi(wp, inInstance, instanceTemplate)
                        : "Unknown";
                result.addProperty("locationName", locName);
                result.addProperty("coordinates", wp.getX() + ", " + wp.getY() + ", " + wp.getPlane());
                result.addProperty("regionId", wp.getRegionID());
            }
        }

        JsonArray worldTypes = new JsonArray();
        for (WorldType wt : client.getWorldType()) {
            worldTypes.add(wt.name());
        }
        result.add("worldTypes", worldTypes);

        return gson.toJson(result);
    }

    public String executeGetPlayerTransportation(JsonObject args) {
        JsonObject result = new JsonObject();

        // 1. Unlocked Transportation Networks & Quests
        JsonObject networks = new JsonObject();

        QuestState fairytale2 = getQuestStateSafe(Quest.FAIRYTALE_II__CURE_A_QUEEN);
        boolean fairyRingsUnlocked = (fairytale2 == QuestState.IN_PROGRESS || fairytale2 == QuestState.FINISHED);
        networks.addProperty("fairyRings", fairyRingsUnlocked ? "UNLOCKED" : "LOCKED");

        boolean stafflessFairyRings = false;
        try {
            int lumbridgeElite = client.getVarbitValue(Varbits.DIARY_LUMBRIDGE_ELITE);
            stafflessFairyRings = (lumbridgeElite == 1);
        } catch (Exception ignored) {
        }
        networks.addProperty("stafflessFairyRings", stafflessFairyRings);

        QuestState treeGnomeVillage = getQuestStateSafe(Quest.TREE_GNOME_VILLAGE);
        networks.addProperty("spiritTrees", (treeGnomeVillage == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        QuestState grandTree = getQuestStateSafe(Quest.THE_GRAND_TREE);
        networks.addProperty("gnomeGliders", (grandTree == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        QuestState enlightenedJourney = getQuestStateSafe(Quest.ENLIGHTENED_JOURNEY);
        networks.addProperty("hotAirBalloons", (enlightenedJourney == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        QuestState ghostsAhoy = getQuestStateSafe(Quest.GHOSTS_AHOY);
        networks.addProperty("ectophial", (ghostsAhoy == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        QuestState tasteOfHope = getQuestStateSafe(Quest.A_TASTE_OF_HOPE);
        networks.addProperty("drakkansMedallion",
                (tasteOfHope == QuestState.IN_PROGRESS || tasteOfHope == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        QuestState mm2 = getQuestStateSafe(Quest.MONKEY_MADNESS_II);
        networks.addProperty("royalSeedPod", (mm2 == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        QuestState clientOfKourend = getQuestStateSafe(Quest.CLIENT_OF_KOUREND);
        networks.addProperty("kharedstsMemoirs", (clientOfKourend == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        QuestState kingdomDivided = getQuestStateSafe(Quest.A_KINGDOM_DIVIDED);
        networks.addProperty("bookOfTheDead", (kingdomDivided == QuestState.FINISHED) ? "UNLOCKED" : "LOCKED");

        result.add("unlockedNetworks", networks);

        // 2. Magic & Spellbook Teleports
        JsonObject magicObj = new JsonObject();
        int spellbookVal = 0;
        try {
            spellbookVal = client.getVarbitValue(VARBIT_SPELLBOOK);
        } catch (Exception ignored) {
        }
        String spellbookName = Utilities.describeSpellbook(spellbookVal);
        magicObj.addProperty("currentSpellbook", spellbookName);

        int magicLevel = client.getRealSkillLevel(Skill.MAGIC);
        int magicBoosted = client.getBoostedSkillLevel(Skill.MAGIC);
        magicObj.addProperty("magicLevelBase", magicLevel);
        magicObj.addProperty("magicLevelBoosted", magicBoosted);

        JsonArray unlockedTeleports = new JsonArray();
        int effectiveMagic = Math.max(magicLevel, magicBoosted);
        if ("Standard".equals(spellbookName)) {
            unlockedTeleports.add("Home Teleport (Lumbridge)");
            if (effectiveMagic >= 25)
                unlockedTeleports.add("Varrock Teleport (25)");
            if (effectiveMagic >= 31)
                unlockedTeleports.add("Lumbridge Teleport (31)");
            if (effectiveMagic >= 37)
                unlockedTeleports.add("Falador Teleport (37)");
            if (effectiveMagic >= 40)
                unlockedTeleports.add("Teleport to House (40)");
            if (effectiveMagic >= 45)
                unlockedTeleports.add("Camelot Teleport (45)");
            if (effectiveMagic >= 51)
                unlockedTeleports.add("Ardougne Teleport (51)");
            if (effectiveMagic >= 58)
                unlockedTeleports.add("Watchtower Teleport (58)");
            if (effectiveMagic >= 61)
                unlockedTeleports.add("Trollheim Teleport (61)");
            if (effectiveMagic >= 64)
                unlockedTeleports.add("Ape Atoll Teleport (64)");
            if (effectiveMagic >= 69)
                unlockedTeleports.add("Kourend Castle Teleport (69)");
        } else if ("Ancient Magicks".equals(spellbookName)) {
            unlockedTeleports.add("Edgeville Home Teleport");
            if (effectiveMagic >= 54)
                unlockedTeleports.add("Paddewwa Teleport (54)");
            if (effectiveMagic >= 60)
                unlockedTeleports.add("Senntisten Teleport (60)");
            if (effectiveMagic >= 66)
                unlockedTeleports.add("Kharyrll Teleport (66)");
            if (effectiveMagic >= 72)
                unlockedTeleports.add("Lassar Teleport (72)");
            if (effectiveMagic >= 78)
                unlockedTeleports.add("Dareeyak Teleport (78)");
            if (effectiveMagic >= 84)
                unlockedTeleports.add("Carrallangar Teleport (84)");
            if (effectiveMagic >= 90)
                unlockedTeleports.add("Annakarl Teleport (90)");
            if (effectiveMagic >= 96)
                unlockedTeleports.add("Ghorrock Teleport (96)");
        } else if ("Lunar".equals(spellbookName)) {
            unlockedTeleports.add("Lunar Home Teleport");
            if (effectiveMagic >= 69)
                unlockedTeleports.add("Moonclan Teleport (69)");
            if (effectiveMagic >= 71)
                unlockedTeleports.add("Ourania Teleport (71)");
            if (effectiveMagic >= 72)
                unlockedTeleports.add("Waterbirth Teleport (72)");
            if (effectiveMagic >= 75)
                unlockedTeleports.add("Barbarian Teleport (75)");
            if (effectiveMagic >= 78)
                unlockedTeleports.add("Khazard Teleport (78)");
            if (effectiveMagic >= 85)
                unlockedTeleports.add("Fishing Guild Teleport (85)");
            if (effectiveMagic >= 87)
                unlockedTeleports.add("Catherby Teleport (87)");
            if (effectiveMagic >= 89)
                unlockedTeleports.add("Ice Plateau Teleport (89)");
        } else if ("Arceuus".equals(spellbookName)) {
            unlockedTeleports.add("Arceuus Home Teleport");
            if (effectiveMagic >= 38)
                unlockedTeleports.add("Arceuus Library Teleport (38)");
            if (effectiveMagic >= 40)
                unlockedTeleports.add("Draynor Manor Teleport (40)");
            if (effectiveMagic >= 40)
                unlockedTeleports.add("Salve Graveyard Teleport (40)");
            if (effectiveMagic >= 48)
                unlockedTeleports.add("Fenkenstrain's Castle Teleport (48)");
            if (effectiveMagic >= 61)
                unlockedTeleports.add("West Ardougne Teleport (61)");
            if (effectiveMagic >= 65)
                unlockedTeleports.add("Harmony Island Teleport (65)");
            if (effectiveMagic >= 71)
                unlockedTeleports.add("Cemetery Teleport (71)");
            if (effectiveMagic >= 83)
                unlockedTeleports.add("Barrows Teleport (83)");
            if (effectiveMagic >= 90)
                unlockedTeleports.add("Ape Atoll Teleport (90)");
        }
        magicObj.add("unlockedSpellTeleports", unlockedTeleports);
        result.add("magicAndSpellbook", magicObj);

        // 3. Construction & POH Features
        JsonObject pohObj = new JsonObject();
        int conLevel = client.getRealSkillLevel(Skill.CONSTRUCTION);
        pohObj.addProperty("constructionLevel", conLevel);
        pohObj.addProperty("portalChamberUnlocked", conLevel >= POH_LEVEL_PORTAL_CHAMBER);
        pohObj.addProperty("portalNexusUnlocked", conLevel >= POH_LEVEL_PORTAL_NEXUS);
        pohObj.addProperty("basicJewelleryBoxUnlocked", conLevel >= POH_LEVEL_BASIC_JEWELLERY_BOX);
        pohObj.addProperty("ornateJewelleryBoxUnlocked", conLevel >= POH_LEVEL_ORNATE_JEWELLERY_BOX);
        pohObj.addProperty("pohFairyRingUnlocked", conLevel >= POH_LEVEL_FAIRY_RING);
        pohObj.addProperty("pohSpiritTreeUnlocked", conLevel >= POH_LEVEL_SPIRIT_TREE);
        result.add("constructionAndPoh", pohObj);

        // 4. Available Teleport Items in Inventory, Equipment, and Bank
        JsonArray teleportItems = scanTeleportItems();
        result.add("availableTeleportItems", teleportItems);

        return gson.toJson(result);
    }

    private JsonArray scanTeleportItems() {
        JsonArray found = new JsonArray();
        Set<String> uniqueFoundNames = new HashSet<>();

        List<InventoryID> containersToScan = Arrays.asList(
                InventoryID.INVENTORY,
                InventoryID.EQUIPMENT,
                InventoryID.BANK);

        String[] keywords = new String[] {
                "ring of dueling", "games necklace", "combat bracelet", "skills necklace",
                "necklace of passage", "digsite pendant", "xeric's talisman", "slayer ring",
                "rada's blessing", "pharaoh's sceptre", "royal seed pod", "ectophial",
                "drakkan's medallion", "teleport crystal", "ring of the elements",
                "teleport scroll", "master scroll book", "ardougne cloak", "kandarin headgear",
                "explorer's ring", "desert amulet", "morytania legs", "karamja gloves",
                "western banner", "fremennik boots", "dramen staff", "lunar staff",
                "book of the dead", "kharedst's memoirs", "teleport to house", "varrock teleport",
                "lumbridge teleport", "falador teleport", "camelot teleport", "ardougne teleport",
                "mythical cape"
        };

        for (InventoryID invId : containersToScan) {
            ItemContainer container = client.getItemContainer(invId);
            if (container == null)
                continue;

            Item[] items = container.getItems();
            if (items == null)
                continue;

            for (Item item : items) {
                if (item == null || item.getId() <= 0)
                    continue;

                String name = null;
                if (itemManager != null) {
                    try {
                        ItemComposition comp = itemManager.getItemComposition(item.getId());
                        if (comp != null && comp.getName() != null) {
                            name = comp.getName();
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (name == null)
                    continue;

                String lowerName = name.toLowerCase();
                for (String kw : keywords) {
                    if (lowerName.contains(kw) && !uniqueFoundNames.contains(name)) {
                        uniqueFoundNames.add(name);
                        JsonObject itemObj = new JsonObject();
                        itemObj.addProperty("name", name);
                        itemObj.addProperty("location", invId.name().toLowerCase());
                        found.add(itemObj);
                        break;
                    }
                }
            }
        }

        // Also check cached bank items if live bank is closed
        List<ItemContainerUtils.SimpleItem> cachedBank = cachedBankSupplier != null ? cachedBankSupplier.get() : null;
        if (client.getItemContainer(InventoryID.BANK) == null && cachedBank != null && !cachedBank.isEmpty()) {
            for (ItemContainerUtils.SimpleItem item : cachedBank) {
                if (item == null || item.getId() <= 0)
                    continue;

                String name = null;
                if (itemManager != null) {
                    try {
                        ItemComposition comp = itemManager.getItemComposition(item.getId());
                        if (comp != null && comp.getName() != null) {
                            name = comp.getName();
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (name == null)
                    continue;

                String lowerName = name.toLowerCase();
                for (String kw : keywords) {
                    if (lowerName.contains(kw) && !uniqueFoundNames.contains(name)) {
                        uniqueFoundNames.add(name);
                        JsonObject itemObj = new JsonObject();
                        itemObj.addProperty("name", name);
                        itemObj.addProperty("location", "bank (cached)");
                        found.add(itemObj);
                        break;
                    }
                }
            }
        }
        return found;
    }
}
