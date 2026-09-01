package com.osrsai.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrsai.util.ItemContainerUtils;
import com.osrsai.util.Utilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.http.api.item.ItemEquipmentStats;
import net.runelite.http.api.item.ItemStats;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Tool implementations for player character state: skills, inventory, worn
 * equipment,
 * combat status/vitals, currencies/points, and Slayer tasks.
 */
@Slf4j
public class PlayerStateTools {
    private static final int MAX_TOOLTIP_LENGTH = 150;

    private static final Pattern PATTERN_WHITESPACE = Pattern.compile("\\s+");

    private static final int VARP_SPECIAL_ATTACK_PERCENT = 300;
    private static final int VARP_NMZ_REWARD_POINTS = 1056;
    private static final int VARP_PEST_CONTROL_POINTS = 261;
    private static final int POISON_VENOM_THRESHOLD = 1_000_000; // start of venom

    // ==========================================
    // Slayer Varbits: Unlocks, Extensions, and Per-Master Block Lists
    // ==========================================
    private static final int VARBIT_SLAYER_POINTS = 4068;
    private static final int VARBIT_SLAYER_TASK_STREAK = 4069;

    /**
     * Exact Varbit IDs for permanent and toggleable Slayer Unlocks.
     */
    private static final Map<Integer, String> SLAYER_UNLOCK_VARBITS = new LinkedHashMap<>();
    static {
        SLAYER_UNLOCK_VARBITS.put(4027, "Gargoyle Smasher");
        SLAYER_UNLOCK_VARBITS.put(4028, "Slug Salter");
        SLAYER_UNLOCK_VARBITS.put(4029, "Reptile Freezer");
        SLAYER_UNLOCK_VARBITS.put(4030, "'Shroom Sprayer");
        SLAYER_UNLOCK_VARBITS.put(3202, "Malevolent Masquerade (Slayer Helmet Crafting)");
        SLAYER_UNLOCK_VARBITS.put(3207, "Ring Bling (Slayer Ring Crafting)");
        SLAYER_UNLOCK_VARBITS.put(3208, "Broader Fletching");
        SLAYER_UNLOCK_VARBITS.put(2462, "Seeing Red (Red Dragons)");
        SLAYER_UNLOCK_VARBITS.put(4095, "Watch the Birdie (Aviansies)");
        SLAYER_UNLOCK_VARBITS.put(4691, "Hot Stuff (TzHaar / TzTok-Jad)");
        SLAYER_UNLOCK_VARBITS.put(4724, "Like a Boss");
        SLAYER_UNLOCK_VARBITS.put(4996, "Reptile Got Ripped (Lizardmen)");
        SLAYER_UNLOCK_VARBITS.put(5358, "Bigger and Badder (Superior Slayer Monsters)");
        SLAYER_UNLOCK_VARBITS.put(4589, "Duly Noted (Mithril Dragons)");
        SLAYER_UNLOCK_VARBITS.put(240, "Stop the Wyvern (Fossil Island Wyverns)");
        SLAYER_UNLOCK_VARBITS.put(6485, "Double Trouble (Grotesque Guardians)");
        SLAYER_UNLOCK_VARBITS.put(9456, "Basilocked (Basilisks)");
        SLAYER_UNLOCK_VARBITS.put(10388, "Actual Vampyre Slayer (Vampyres)");
        SLAYER_UNLOCK_VARBITS.put(13636, "I Wildy More Slayer (Krystilia Wilderness Tasks)");
        SLAYER_UNLOCK_VARBITS.put(15286, "Warped Reality (Warped Creatures)");
        SLAYER_UNLOCK_VARBITS.put(19604, "Wings Spread (Gryphons)");
        SLAYER_UNLOCK_VARBITS.put(19605, "Lured In (Aquanites)");
        SLAYER_UNLOCK_VARBITS.put(15399, "Chance of Heavy Frost (Frost Dragons)");
        SLAYER_UNLOCK_VARBITS.put(15398, "Longer Gryphons");
        SLAYER_UNLOCK_VARBITS.put(17219, "Longer Custodians");
        SLAYER_UNLOCK_VARBITS.put(19602, "Longer Wyrms");
        SLAYER_UNLOCK_VARBITS.put(19603, "Longer Aquanites");
        SLAYER_UNLOCK_VARBITS.put(14822, "Longer Revenants");
    }

    /**
     * Exact Varbit IDs for Slayer Task Extensions.
     */
    private static final Map<Integer, String> SLAYER_EXTENSION_VARBITS = new LinkedHashMap<>();
    static {
        SLAYER_EXTENSION_VARBITS.put(4747, "Aberrant Spectres (Smell ya later)");
        SLAYER_EXTENSION_VARBITS.put(4090, "Abyssal Demons (Augment my abbies)");
        SLAYER_EXTENSION_VARBITS.put(4085, "Ankou (Ankou very much)");
        SLAYER_EXTENSION_VARBITS.put(4086, "Suqahs (Suq-a-nother one)");
        SLAYER_EXTENSION_VARBITS.put(4087, "Fire Giants (Fire & Darkness)");
        SLAYER_EXTENSION_VARBITS.put(4088, "Metal Dragons (Pedal to the metals)");
        SLAYER_EXTENSION_VARBITS.put(4091, "Dark Beasts (It's dark in here)");
        SLAYER_EXTENSION_VARBITS.put(4092, "Greater Demons (Greater challenge)");
        SLAYER_EXTENSION_VARBITS.put(4094, "Mithril Dragons (I hope you mith me)");
        SLAYER_EXTENSION_VARBITS.put(4746, "Bloodvelds (Bleed me dry)");
        SLAYER_EXTENSION_VARBITS.put(4748, "Aviansies (Birds of a feather)");
        SLAYER_EXTENSION_VARBITS.put(4750, "Cave Horrors (Horrorific)");
        SLAYER_EXTENSION_VARBITS.put(4751, "Dust Devils (To dust you shall return)");
        SLAYER_EXTENSION_VARBITS.put(4752, "Skeletal Wyverns (Wyver-nother one)");
        SLAYER_EXTENSION_VARBITS.put(4753, "Gargoyles (Get smashed)");
        SLAYER_EXTENSION_VARBITS.put(4754, "Nechryael (Nechs please)");
        SLAYER_EXTENSION_VARBITS.put(4755, "Cave Kraken (Krack on)");
        SLAYER_EXTENSION_VARBITS.put(4757, "Spiritual Creatures (Spiritual fervour)");
        SLAYER_EXTENSION_VARBITS.put(5359, "Scabarites (Get scabaright on it)");
        SLAYER_EXTENSION_VARBITS.put(5733, "Fossil Island Wyverns (Wyver-nother two)");
        SLAYER_EXTENSION_VARBITS.put(6094, "Adamant Dragons (Ada'mind some more)");
        SLAYER_EXTENSION_VARBITS.put(6095, "Rune Dragons (RUUUUUNE)");
        SLAYER_EXTENSION_VARBITS.put(9455, "Basilisks (Basilonger)");
        SLAYER_EXTENSION_VARBITS.put(10389, "Vampyres (More at stake)");
    }

    /**
     * Slayer Master block slot varbits mapping: Master Name -> array of slot Varbit
     * IDs.
     */
    private static final Map<String, int[]> SLAYER_MASTER_BLOCK_VARBITS = new LinkedHashMap<>();
    static {
        SLAYER_MASTER_BLOCK_VARBITS.put("Duradel", new int[] { 17848, 17849, 17850, 17851, 17852, 17853, 17866 });
        SLAYER_MASTER_BLOCK_VARBITS.put("Nieve", new int[] { 17842, 17843, 17844, 17845, 17846, 17847, 17865 });
        SLAYER_MASTER_BLOCK_VARBITS.put("Konar", new int[] { 17836, 17837, 17838, 17839, 17840, 17841, 17864 });
        SLAYER_MASTER_BLOCK_VARBITS.put("Chaeldar", new int[] { 17830, 17831, 17832, 17833, 17834, 17835, 17863 });
        SLAYER_MASTER_BLOCK_VARBITS.put("Vannaka", new int[] { 17824, 17825, 17826, 17827, 17828, 17829, 17862 });
        SLAYER_MASTER_BLOCK_VARBITS.put("Mazchna", new int[] { 17818, 17819, 17820, 17821, 17822, 17823, 17861 });
        SLAYER_MASTER_BLOCK_VARBITS.put("Turael", new int[] { 17812, 17813, 17814, 17815, 17816, 17817, 17860 });
        SLAYER_MASTER_BLOCK_VARBITS.put("Krystilia", new int[] { 17854, 17855, 17856, 17857, 17858, 17859, 17867 });
        SLAYER_MASTER_BLOCK_VARBITS.put("Mortimer", new int[] { 15783, 15784 });
    }

    /**
     * Known Slayer task ID to name mapping for blocked slots and active tasks.
     */
    private static final Map<Integer, String> SLAYER_TASK_ID_NAMES = new HashMap<>();
    static {
        SLAYER_TASK_ID_NAMES.put(1, "Abyssal Sire");
        SLAYER_TASK_ID_NAMES.put(2, "Alchemical Hydra");
        SLAYER_TASK_ID_NAMES.put(3, "Amoxliatl");
        SLAYER_TASK_ID_NAMES.put(4, "Araxxor");
        SLAYER_TASK_ID_NAMES.put(6, "Brutus");
        SLAYER_TASK_ID_NAMES.put(7, "Bryophyta");
        SLAYER_TASK_ID_NAMES.put(8, "Callisto");
        SLAYER_TASK_ID_NAMES.put(9, "Cerberus");
        SLAYER_TASK_ID_NAMES.put(10, "Chaos Elemental");
        SLAYER_TASK_ID_NAMES.put(11, "Chaos Fanatic");
        SLAYER_TASK_ID_NAMES.put(12, "Crazy Archaeologist");
        SLAYER_TASK_ID_NAMES.put(15, "Corporeal Beast");
        SLAYER_TASK_ID_NAMES.put(16, "Commander Zilyana");
        SLAYER_TASK_ID_NAMES.put(17, "Crystalline Hunllef");
        SLAYER_TASK_ID_NAMES.put(18, "Corrupted Hunllef");
        SLAYER_TASK_ID_NAMES.put(19, "Dagannoth Prime");
        SLAYER_TASK_ID_NAMES.put(20, "Dagannoth Rex");
        SLAYER_TASK_ID_NAMES.put(21, "Dagannoth Supreme");
        SLAYER_TASK_ID_NAMES.put(22, "Deranged Archaeologist");
        SLAYER_TASK_ID_NAMES.put(23, "Doom of Mokhaiotl");
        SLAYER_TASK_ID_NAMES.put(24, "Duke Sucellus");
        SLAYER_TASK_ID_NAMES.put(25, "Fortis Colosseum");
        SLAYER_TASK_ID_NAMES.put(26, "General Graardor");
        SLAYER_TASK_ID_NAMES.put(27, "Giant Mole");
        SLAYER_TASK_ID_NAMES.put(28, "Grotesque Guardians");
        SLAYER_TASK_ID_NAMES.put(29, "Hespori");
        SLAYER_TASK_ID_NAMES.put(30, "Black Demons");
        SLAYER_TASK_ID_NAMES.put(31, "Kalphite Queen");
        SLAYER_TASK_ID_NAMES.put(32, "King Black Dragon");
        SLAYER_TASK_ID_NAMES.put(33, "Kraken");
        SLAYER_TASK_ID_NAMES.put(34, "Kree'arra");
        SLAYER_TASK_ID_NAMES.put(35, "K'ril Tsutsaroth");
        SLAYER_TASK_ID_NAMES.put(36, "The Leviathan");
        SLAYER_TASK_ID_NAMES.put(37, "The Mad Angel");
        SLAYER_TASK_ID_NAMES.put(38, "Banshees");
        SLAYER_TASK_ID_NAMES.put(39, "The Mimic");
        SLAYER_TASK_ID_NAMES.put(40, "Moons of Peril");
        SLAYER_TASK_ID_NAMES.put(41, "Nex");
        SLAYER_TASK_ID_NAMES.put(42, "The Nightmare");
        SLAYER_TASK_ID_NAMES.put(43, "Phosani's Nightmare");
        SLAYER_TASK_ID_NAMES.put(44, "Obor");
        SLAYER_TASK_ID_NAMES.put(45, "Phantom Muspah");
        SLAYER_TASK_ID_NAMES.put(46, "Royal Titans");
        SLAYER_TASK_ID_NAMES.put(47, "Scurrius");
        SLAYER_TASK_ID_NAMES.put(48, "Sarachnis");
        SLAYER_TASK_ID_NAMES.put(49, "Scorpia");
        SLAYER_TASK_ID_NAMES.put(50, "Shellbane Gryphon");
        SLAYER_TASK_ID_NAMES.put(51, "Skotizo");
        SLAYER_TASK_ID_NAMES.put(52, "Tempoross");
        SLAYER_TASK_ID_NAMES.put(53, "Kalphite");
        SLAYER_TASK_ID_NAMES.put(56, "Thermonuclear Smoke Devil");
        SLAYER_TASK_ID_NAMES.put(57, "Tombs of Amascut: Entry Mode");
        SLAYER_TASK_ID_NAMES.put(58, "Tombs of Amascut");
        SLAYER_TASK_ID_NAMES.put(59, "Tombs of Amascut: Expert Mode");
        SLAYER_TASK_ID_NAMES.put(60, "TzHaar-Ket-Rak's Challenges");
        SLAYER_TASK_ID_NAMES.put(61, "TzKal-Zuk");
        SLAYER_TASK_ID_NAMES.put(62, "TzTok-Jad");
        SLAYER_TASK_ID_NAMES.put(63, "Vardorvis");
        SLAYER_TASK_ID_NAMES.put(64, "Venenatis");
        SLAYER_TASK_ID_NAMES.put(65, "Vet'ion");
        SLAYER_TASK_ID_NAMES.put(66, "Vorkath");
        SLAYER_TASK_ID_NAMES.put(67, "The Whisperer");
        SLAYER_TASK_ID_NAMES.put(68, "Wintertodt");
        SLAYER_TASK_ID_NAMES.put(69, "Yama");
        SLAYER_TASK_ID_NAMES.put(70, "Zalcano");
        SLAYER_TASK_ID_NAMES.put(71, "Zulrah");
        SLAYER_TASK_ID_NAMES.put(72, "Fragment of Seren");
        SLAYER_TASK_ID_NAMES.put(73, "Glough");
        SLAYER_TASK_ID_NAMES.put(74, "Galvek");
        SLAYER_TASK_ID_NAMES.put(75, "Other");
        SLAYER_TASK_ID_NAMES.put(76, "Greater Demons");
        SLAYER_TASK_ID_NAMES.put(77, "Lizardman Shamans");
        SLAYER_TASK_ID_NAMES.put(78, "Wyrms");
        SLAYER_TASK_ID_NAMES.put(79, "Black Dragons");
        SLAYER_TASK_ID_NAMES.put(80, "Cave Horrors");
        SLAYER_TASK_ID_NAMES.put(81, "Hellhounds");
        SLAYER_TASK_ID_NAMES.put(82, "Bloodvelds");
        SLAYER_TASK_ID_NAMES.put(83, "Suqahs");
        SLAYER_TASK_ID_NAMES.put(84, "Demonic Gorillas");
        SLAYER_TASK_ID_NAMES.put(85, "Basilisk Knights");
        SLAYER_TASK_ID_NAMES.put(86, "Gargoyles");
        SLAYER_TASK_ID_NAMES.put(87, "Skeletal Wyverns");
        SLAYER_TASK_ID_NAMES.put(88, "Kurasks");
        SLAYER_TASK_ID_NAMES.put(89, "Brutal Black Dragons");
        SLAYER_TASK_ID_NAMES.put(90, "Giants");
        SLAYER_TASK_ID_NAMES.put(91, "Tormented Demons");
    }

    /**
     * Helper to resolve a Slayer task ID to its human-readable monster name.
     */
    private String resolveSlayerTaskName(int taskId) {
        String name = SLAYER_TASK_ID_NAMES.get(taskId);
        return name != null ? name : "Task ID " + taskId;
    }

    private static final int VARBIT_TITHE_FARM_POINTS = 4893;
    private static final int VARBIT_VALE_RESEARCH_POINTS = 16301;
    private static final int VARBIT_MTA_TELEKINETIC_PIZZAZZ = 287;
    private static final int VARBIT_MTA_ALCHEMIST_PIZZAZZ = 288;
    private static final int VARBIT_MTA_ENCHANTING_PIZZAZZ = 289;
    private static final int VARBIT_MTA_GRAVEYARD_PIZZAZZ = 290;
    private static final int VARBIT_BA_ATTACKER_POINTS = 4761;
    private static final int VARBIT_BA_DEFENDER_POINTS = 4762;
    private static final int VARBIT_BA_COLLECTOR_POINTS = 4763;
    private static final int VARBIT_BA_HEALER_POINTS = 4764;
    private static final int VARBIT_GIANTS_FOUNDRY_REPUTATION = 13919;
    private static final int VARBIT_VOLCANIC_MINE_POINTS = 5934;
    private static final int VARBIT_LMS_POINTS = 9304;
    private static final int VARBIT_BOUNTY_HUNTER_POINTS = 10079;

    private static final Set<String> TARGET_CURRENCY_NAMES = Set.of(
            "mark of grace",
            "golden nugget",
            "abyssal pearl",
            "tokkul",
            "stardust",
            "archery ticket",
            "mermaid's tear",
            "hallowed mark",
            "molch pearl",
            "castle wars ticket",
            "agility arena ticket",
            "warrior guild token",
            "zeal token",
            "blessed bone shard",
            "sunfire splinter",
            "trading stick",
            "piece of eight",
            "spirit flakes");

    private final Client client;
    private final ItemManager itemManager;
    private final ConfigManager configManager;
    private final InfoBoxManager infoBoxManager;
    private final Gson gson;

    public PlayerStateTools(Client client, ItemManager itemManager, ConfigManager configManager,
            InfoBoxManager infoBoxManager, Gson gson) {
        this.client = client;
        this.itemManager = itemManager;
        this.configManager = configManager;
        this.infoBoxManager = infoBoxManager;
        this.gson = gson;
    }

    public String normalizeSkillName(String input) {
        if (input == null) {
            return null;
        }
        String clean = input.trim().toLowerCase();
        switch (clean) {
            case "wc":
            case "woodcut":
                return "woodcutting";
            case "rc":
            case "runecrafting":
                return "runecraft";
            case "hp":
            case "hitpoint":
                return "hitpoints";
            case "range":
                return "ranged";
            case "fm":
                return "firemaking";
            case "fletch":
                return "fletching";
            case "con":
                return "construction";
            case "cook":
                return "cooking";
            case "craft":
                return "crafting";
            case "slay":
                return "slayer";
            case "str":
                return "strength";
            case "def":
            case "defense":
                return "defence";
            case "att":
                return "attack";
            case "agil":
                return "agility";
            case "thiev":
            case "thief":
                return "thieving";
            case "herb":
                return "herblore";
            case "farm":
                return "farming";
            case "hunt":
                return "hunter";
            default:
                return clean;
        }
    }

    public void addMilestoneXp(JsonObject skillData, int currentXp, Integer targetLevel) {
        int[] milestones = { 60, 65, 70, 75, 80, 85, 90, 92, 95, 99 };
        for (int level : milestones) {
            int targetXp = Experience.getXpForLevel(level);
            if (currentXp < targetXp) {
                skillData.addProperty("xpTo" + level, targetXp - currentXp);
            }
        }
        if (targetLevel != null && targetLevel >= 1 && targetLevel <= Experience.MAX_VIRT_LEVEL) {
            int targetXp = Experience.getXpForLevel(targetLevel);
            skillData.addProperty("requestedTargetLevel", targetLevel);
            skillData.addProperty("targetLevelXp", targetXp);
            skillData.addProperty("xpToTargetLevel", Math.max(0, targetXp - currentXp));
        }
    }

    public String executeGetPlayerSkills(JsonObject args) {
        JsonObject result = new JsonObject();
        String filterSkill = (args != null && args.has("skill") && !args.get("skill").isJsonNull())
                ? normalizeSkillName(args.get("skill").getAsString())
                : null;
        Integer targetLevel = (args != null && args.has("targetLevel") && !args.get("targetLevel").isJsonNull())
                ? args.get("targetLevel").getAsInt()
                : null;

        for (Skill skill : Skill.values()) {
            if (!"OVERALL".equals(skill.name())) {
                String skillName = skill.getName();
                if (filterSkill != null && !skillName.toLowerCase().equals(filterSkill)) {
                    continue;
                }

                JsonObject skillData = new JsonObject();
                skillData.addProperty("boosted", client.getBoostedSkillLevel(skill));
                skillData.addProperty("real", client.getRealSkillLevel(skill));
                int xp = client.getSkillExperience(skill);
                skillData.addProperty("xp", xp);

                int nextLevel = Experience.getLevelForXp(xp) + 1;
                if (nextLevel <= Experience.MAX_VIRT_LEVEL) {
                    int nextXp = Experience.getXpForLevel(nextLevel);
                    skillData.addProperty("nextLevelXp", nextXp);
                    skillData.addProperty("xpToNextLevel", Math.max(0, nextXp - xp));
                } else {
                    skillData.addProperty("nextLevelXp", -1);
                    skillData.addProperty("xpToNextLevel", 0);
                }

                addMilestoneXp(skillData, xp, targetLevel);

                result.add(skillName, skillData);
            }
        }
        return gson.toJson(result);
    }

    public String executeGetPlayerInventory(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject invItems = new JsonObject();
        ItemContainer invContainer = client.getItemContainer(InventoryID.INVENTORY);
        if (invContainer != null) {
            invItems = ItemContainerUtils.aggregateItemsWithPrices(client, itemManager, invContainer, null, 0);
        }
        result.add("items", invItems);
        return gson.toJson(result);
    }

    public String executeGetPlayerEquipment(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject eqSlots = new JsonObject();
        ItemContainer eqContainer = client.getItemContainer(InventoryID.EQUIPMENT);
        if (eqContainer != null) {
            Item[] items = eqContainer.getItems();
            for (int i = 0; i < items.length; i++) {
                Item item = items[i];
                if (item == null || item.getId() <= 0) {
                    continue;
                }
                ItemComposition comp = null;
                if (itemManager != null) {
                    try {
                        comp = itemManager.getItemComposition(item.getId());
                    } catch (Exception ignored) {
                    }
                }
                String itemName = (comp != null && comp.getName() != null && !comp.getName().trim().isEmpty())
                        ? comp.getName()
                        : "Item " + item.getId();

                String slotName = Utilities.getSlotName(i);
                JsonObject itemDetail = new JsonObject();
                itemDetail.addProperty("id", item.getId());
                itemDetail.addProperty("name", itemName);
                itemDetail.addProperty("qty", item.getQuantity());

                int gePrice = 0;
                if (itemManager != null) {
                    try {
                        gePrice = itemManager.getItemPrice(item.getId());
                    } catch (Exception ignored) {
                    }
                }
                if (gePrice <= 0 && "Coins".equals(itemName)) {
                    gePrice = 1;
                }
                itemDetail.addProperty("gePrice", gePrice);
                itemDetail.addProperty("haPrice", comp != null ? comp.getHaPrice() : 0);

                ItemStats stats = (itemManager != null) ? itemManager.getItemStats(item.getId(), false) : null;
                if (stats != null && stats.getEquipment() != null) {
                    ItemEquipmentStats eq = stats.getEquipment();
                    boolean hasCombatStats = eq.getAstab() != 0 || eq.getAslash() != 0 || eq.getAcrush() != 0
                            || eq.getAmagic() != 0 || eq.getArange() != 0 || eq.getDstab() != 0
                            || eq.getDslash() != 0 || eq.getDcrush() != 0 || eq.getDmagic() != 0
                            || eq.getDrange() != 0 || eq.getStr() != 0 || eq.getRstr() != 0
                            || eq.getMdmg() != 0 || eq.getPrayer() != 0;
                    if (hasCombatStats) {
                        JsonObject statsObj = new JsonObject();
                        if (eq.getAstab() != 0)
                            statsObj.addProperty("astab", eq.getAstab());
                        if (eq.getAslash() != 0)
                            statsObj.addProperty("aslash", eq.getAslash());
                        if (eq.getAcrush() != 0)
                            statsObj.addProperty("ascrush", eq.getAcrush());
                        if (eq.getAmagic() != 0)
                            statsObj.addProperty("asmagic", eq.getAmagic());
                        if (eq.getArange() != 0)
                            statsObj.addProperty("asrange", eq.getArange());
                        if (eq.getDstab() != 0)
                            statsObj.addProperty("dstab", eq.getDstab());
                        if (eq.getDslash() != 0)
                            statsObj.addProperty("dslash", eq.getDslash());
                        if (eq.getDcrush() != 0)
                            statsObj.addProperty("dcrush", eq.getDcrush());
                        if (eq.getDmagic() != 0)
                            statsObj.addProperty("dmagic", eq.getDmagic());
                        if (eq.getDrange() != 0)
                            statsObj.addProperty("drange", eq.getDrange());
                        if (eq.getStr() != 0)
                            statsObj.addProperty("str", eq.getStr());
                        if (eq.getRstr() != 0)
                            statsObj.addProperty("rstr", eq.getRstr());
                        if (eq.getMdmg() != 0)
                            statsObj.addProperty("mdmg", eq.getMdmg());
                        if (eq.getPrayer() != 0)
                            statsObj.addProperty("prayer", eq.getPrayer());
                        if (eq.getAspeed() != 0)
                            statsObj.addProperty("aspeed", eq.getAspeed());
                        itemDetail.add("stats", statsObj);
                    }
                }
                eqSlots.add(slotName, itemDetail);
            }
        }
        result.add("slots", eqSlots);
        return gson.toJson(result);
    }

    public String executeGetPlayerStatus(JsonObject args) {
        JsonObject result = new JsonObject();

        int specPercent = client.getVarpValue(VARP_SPECIAL_ATTACK_PERCENT) / 10;
        result.addProperty("specialAttackPercent", specPercent);

        result.addProperty("runEnergy", client.getEnergy() / 100);
        result.addProperty("weightKg", client.getWeight());

        JsonObject hp = new JsonObject();
        hp.addProperty("current", client.getBoostedSkillLevel(Skill.HITPOINTS));
        hp.addProperty("max", client.getRealSkillLevel(Skill.HITPOINTS));
        result.add("hitpoints", hp);

        JsonObject prayer = new JsonObject();
        prayer.addProperty("current", client.getBoostedSkillLevel(Skill.PRAYER));
        prayer.addProperty("max", client.getRealSkillLevel(Skill.PRAYER));
        result.add("prayer", prayer);

        JsonArray activePrayers = new JsonArray();
        for (Prayer p : Prayer.values()) {
            try {
                if (client.getVarbitValue(p.getVarbit()) == 1) {
                    activePrayers.add(p.name());
                }
            } catch (Exception ignored) {
            }
        }
        result.add("activePrayers", activePrayers);

        int poisonVarp = client.getVarpValue(VarPlayerID.POISON);

        String status = "Healthy";
        boolean isPoisoned = false;
        boolean isVenomed = false;
        boolean isImmune = false;
        int nextDamage = 0;

        if (poisonVarp >= POISON_VENOM_THRESHOLD) {
            status = "Venomed";
            isVenomed = true;
            nextDamage = Math.min(20, 6 + ((poisonVarp - POISON_VENOM_THRESHOLD) * 2));
        } else if (poisonVarp > 0) {
            status = "Poisoned";
            isPoisoned = true;
            nextDamage = (int) Math.ceil(poisonVarp / 5.0f);
        } else if (poisonVarp < -38) {
            status = "Venom Immune";
            isImmune = true;
        } else if (poisonVarp < 0) {
            status = "Poison Immune";
            isImmune = true;
        }

        result.addProperty("statusString", status);
        result.addProperty("isPoisoned", isPoisoned);
        result.addProperty("isVenomed", isVenomed);
        result.addProperty("isImmune", isImmune);
        result.addProperty("nextDamageAmount", nextDamage);

        JsonObject boostedSkills = new JsonObject();
        for (Skill s : Skill.values()) {
            if ("OVERALL".equals(s.name()))
                continue;
            int real = client.getRealSkillLevel(s);
            int boosted = client.getBoostedSkillLevel(s);
            if (real != boosted) {
                JsonObject bData = new JsonObject();
                bData.addProperty("boosted", boosted);
                bData.addProperty("real", real);
                boostedSkills.add(s.getName(), bData);
            }
        }
        result.add("boostedSkills", boostedSkills);

        JsonArray activeInfoBoxes = new JsonArray();
        if (infoBoxManager != null) {
            int count = 0;
            for (InfoBox box : infoBoxManager.getInfoBoxes()) {
                if (count >= 30) {
                    break;
                }
                try {
                    String text = box.getText();
                    if (text == null) {
                        text = "";
                    } else {
                        text = text.trim();
                    }

                    if (text.isEmpty() || text.equals("0") || text.equals("0/0") || text.equals("0%")) {
                        continue;
                    }

                    JsonObject boxObj = new JsonObject();
                    String name = box.getName();
                    if (name == null || name.trim().isEmpty()) {
                        name = box.getClass().getSimpleName();
                    }
                    boxObj.addProperty("name", name);
                    boxObj.addProperty("text", text);

                    String tooltip = box.getTooltip();
                    if (tooltip != null && !tooltip.trim().isEmpty()) {
                        String noHtml = Utilities.PATTERN_HTML_TAGS.matcher(tooltip).replaceAll(" ");
                        String cleanTooltip = PATTERN_WHITESPACE.matcher(noHtml).replaceAll(" ").trim();
                        if (cleanTooltip.length() > MAX_TOOLTIP_LENGTH) {
                            cleanTooltip = cleanTooltip.substring(0, MAX_TOOLTIP_LENGTH - 3) + "...";
                        }
                        boxObj.addProperty("tooltip", cleanTooltip);
                    }

                    activeInfoBoxes.add(boxObj);
                    count++;
                } catch (Exception ignored) {
                }
            }
        }
        result.add("activeInfoBoxes", activeInfoBoxes);

        return gson.toJson(result);
    }

    public String executeGetPlayerCurrenciesAndPoints(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject points = new JsonObject();

        try {
            int nmz = client.getVarpValue(VARP_NMZ_REWARD_POINTS);
            points.addProperty("nightmareZonePoints", nmz);
        } catch (Exception ignored) {
        }

        try {
            int pc = client.getVarpValue(VARP_PEST_CONTROL_POINTS);
            points.addProperty("pestControlCommendations", pc);
        } catch (Exception ignored) {
        }

        try {
            int tithe = client.getVarbitValue(VARBIT_TITHE_FARM_POINTS);
            points.addProperty("titheFarmPoints", tithe);
        } catch (Exception ignored) {
        }

        try {
            int vale = client.getVarbitValue(VARBIT_VALE_RESEARCH_POINTS);
            points.addProperty("valeResearchPoints", vale);
        } catch (Exception ignored) {
        }

        try {
            JsonObject mta = new JsonObject();
            mta.addProperty("telekineticPizzazz", client.getVarbitValue(VARBIT_MTA_TELEKINETIC_PIZZAZZ));
            mta.addProperty("alchemistPizzazz", client.getVarbitValue(VARBIT_MTA_ALCHEMIST_PIZZAZZ));
            mta.addProperty("enchantingPizzazz", client.getVarbitValue(VARBIT_MTA_ENCHANTING_PIZZAZZ));
            mta.addProperty("graveyardPizzazz", client.getVarbitValue(VARBIT_MTA_GRAVEYARD_PIZZAZZ));
            points.add("mageTrainingArenaPizzazzPoints", mta);
        } catch (Exception ignored) {
        }

        try {
            JsonObject ba = new JsonObject();
            ba.addProperty("attackerPoints", client.getVarbitValue(VARBIT_BA_ATTACKER_POINTS));
            ba.addProperty("defenderPoints", client.getVarbitValue(VARBIT_BA_DEFENDER_POINTS));
            ba.addProperty("collectorPoints", client.getVarbitValue(VARBIT_BA_COLLECTOR_POINTS));
            ba.addProperty("healerPoints", client.getVarbitValue(VARBIT_BA_HEALER_POINTS));
            points.add("barbarianAssaultHonorPoints", ba);
        } catch (Exception ignored) {
        }

        try {
            int gf = client.getVarbitValue(VARBIT_GIANTS_FOUNDRY_REPUTATION);
            points.addProperty("giantsFoundryReputation", gf);
        } catch (Exception ignored) {
        }

        try {
            int vm = client.getVarbitValue(VARBIT_VOLCANIC_MINE_POINTS);
            points.addProperty("volcanicMinePoints", vm);
        } catch (Exception ignored) {
        }

        try {
            int lms = client.getVarbitValue(VARBIT_LMS_POINTS);
            points.addProperty("lmsPoints", lms);
        } catch (Exception ignored) {
        }

        try {
            int bh = client.getVarbitValue(VARBIT_BOUNTY_HUNTER_POINTS);
            points.addProperty("bountyHunterPoints", bh);
        } catch (Exception ignored) {
        }

        try {
            String pts = Utilities.getConfigValue(configManager, "slayer", "points");
            if (pts != null && !pts.isEmpty()) {
                points.addProperty("slayerPoints", Integer.parseInt(pts));
            }
            String strk = Utilities.getConfigValue(configManager, "slayer", "streak");
            if (strk != null && !strk.isEmpty()) {
                points.addProperty("slayerStreak", Integer.parseInt(strk));
            }
        } catch (Exception ignored) {
        }

        JsonObject itemCurrencies = new JsonObject();
        Map<String, Long> nameCounts = new LinkedHashMap<>();

        List<ItemContainer> containers = new ArrayList<>();
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv != null) {
            containers.add(inv);
        }
        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        if (bank != null) {
            containers.add(bank);
        }

        for (ItemContainer container : containers) {
            for (Item item : container.getItems()) {
                if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) {
                    continue;
                }
                ItemComposition rawComp = (itemManager != null) ? itemManager.getItemComposition(item.getId()) : null;
                if (rawComp != null && rawComp.getPlaceholderTemplateId() != -1) {
                    continue;
                }
                int canonicalId = (itemManager != null) ? itemManager.canonicalize(item.getId()) : item.getId();
                ItemComposition comp = (itemManager != null) ? itemManager.getItemComposition(canonicalId) : rawComp;
                String name = (comp != null && comp.getName() != null) ? comp.getName().trim() : "";
                if (name.isEmpty()) {
                    continue;
                }

                String lowerName = name.toLowerCase();
                for (String target : TARGET_CURRENCY_NAMES) {
                    if (lowerName.contains(target)) {
                        nameCounts.put(name, nameCounts.getOrDefault(name, 0L) + item.getQuantity());
                        break;
                    }
                }
            }
        }

        for (Map.Entry<String, Long> entry : nameCounts.entrySet()) {
            if (entry.getValue() > 0) {
                itemCurrencies.addProperty(entry.getKey(), entry.getValue());
            }
        }
        points.add("currencyItems", itemCurrencies);
        result.add("pointsAndCurrencies", points);

        return gson.toJson(result);
    }

    public String executeGetPlayerSlayerTask(JsonObject args) {
        JsonObject result = new JsonObject();
        String taskName = Utilities.getConfigValue(configManager, "slayer", "taskName");
        String amount = Utilities.getConfigValue(configManager, "slayer", "amount");
        String taskLocation = Utilities.getConfigValue(configManager, "slayer", "taskLocation", "location");
        String slayerMaster = Utilities.getConfigValue(configManager, "slayer", "slayerMaster", "masterName", "master",
                "taskMaster");
        String pointsStr = Utilities.getConfigValue(configManager, "slayer", "points");
        String streakStr = Utilities.getConfigValue(configManager, "slayer", "streak");

        boolean includeUnlocks = (args != null && args.has("includeUnlocks")
                && !args.get("includeUnlocks").isJsonNull())
                && args.get("includeUnlocks").getAsBoolean();

        // 1. Live Points & Streak (prefer live game varbits, fallback to config)
        int pointsVal = 0;
        int streakVal = 0;
        try {
            pointsVal = client.getVarbitValue(VARBIT_SLAYER_POINTS);
            streakVal = client.getVarbitValue(VARBIT_SLAYER_TASK_STREAK);
        } catch (Exception ignored) {
        }
        if (pointsVal <= 0 && pointsStr != null && !pointsStr.isEmpty()) {
            try {
                pointsVal = Integer.parseInt(pointsStr);
            } catch (NumberFormatException ignored) {
            }
        }
        if (streakVal <= 0 && streakStr != null && !streakStr.isEmpty()) {
            try {
                streakVal = Integer.parseInt(streakStr);
            } catch (NumberFormatException ignored) {
            }
        }

        result.addProperty("points", pointsVal);
        result.addProperty("streak", streakVal);

        // 2. Active Task Info
        if (taskName != null && !taskName.isEmpty() && amount != null) {
            result.addProperty("task", taskName);
            try {
                result.addProperty("quantity", Integer.parseInt(amount));
            } catch (NumberFormatException e) {
                result.addProperty("quantity", 0);
            }
            if (taskLocation != null && !taskLocation.isEmpty() && !"None".equalsIgnoreCase(taskLocation.trim())) {
                result.addProperty("location", taskLocation.trim());
            }
            if (slayerMaster != null && !slayerMaster.isEmpty() && !"None".equalsIgnoreCase(slayerMaster.trim())) {
                result.addProperty("slayerMaster", slayerMaster.trim());
            }
        } else {
            result.addProperty("task", "None");
            result.addProperty("quantity", 0);
        }

        // 3. Optional: Slayer Unlocks, Extensions, and Per-Master Block Lists
        if (includeUnlocks) {
            // Unlocks
            JsonArray unlockedArray = new JsonArray();
            for (Map.Entry<Integer, String> entry : SLAYER_UNLOCK_VARBITS.entrySet()) {
                try {
                    if (client.getVarbitValue(entry.getKey()) == 1) {
                        unlockedArray.add(entry.getValue());
                    }
                } catch (Exception ignored) {
                }
            }
            result.add("purchasedUnlocks", unlockedArray);

            // Extensions
            JsonArray extensionsArray = new JsonArray();
            for (Map.Entry<Integer, String> entry : SLAYER_EXTENSION_VARBITS.entrySet()) {
                try {
                    if (client.getVarbitValue(entry.getKey()) == 1) {
                        extensionsArray.add(entry.getValue());
                    }
                } catch (Exception ignored) {
                }
            }
            result.add("activeExtensions", extensionsArray);

            // Block Lists grouped by Slayer Master
            JsonObject blockListsByMaster = new JsonObject();
            for (Map.Entry<String, int[]> entry : SLAYER_MASTER_BLOCK_VARBITS.entrySet()) {
                String master = entry.getKey();
                int[] slotVarbits = entry.getValue();
                JsonArray blockedTasks = new JsonArray();

                for (int slotVarbit : slotVarbits) {
                    try {
                        int taskId = client.getVarbitValue(slotVarbit);
                        if (taskId > 0) {
                            String taskLabel = resolveSlayerTaskName(taskId);
                            blockedTasks.add(taskLabel);
                        }
                    } catch (Exception ignored) {
                    }
                }

                if (blockedTasks.size() > 0) {
                    blockListsByMaster.add(master, blockedTasks);
                }
            }
            result.add("blockedTasksByMaster", blockListsByMaster);
        }

        return gson.toJson(result);
    }
}
