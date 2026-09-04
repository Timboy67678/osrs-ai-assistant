package com.osrsai.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrsai.util.ItemContainerUtils;
import com.osrsai.util.Utilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.gameval.VarbitID;
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

    private static final int VARP_NMZ_REWARD_POINTS = 1056;
    private static final int VARP_PEST_CONTROL_POINTS = 261;
    private static final int POISON_VENOM_THRESHOLD = 1_000_000; // start of venom

    private static final int MAX_REASONABLE_STREAK = 5000;

    /**
     * Exact Varbit IDs for permanent and toggleable Slayer Unlocks.
     */
    private static final Map<Integer, String> SLAYER_UNLOCK_VARBITS = new LinkedHashMap<>();
    static {
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_AUTOKILL_GARGOYLES, "Gargoyle Smasher");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_AUTOKILL_ROCKSLUGS, "Slug Salter");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_AUTOKILL_DESERTLIZARDS, "Reptile Freezer");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_AUTOKILL_ZYGOMITES, "'Shroom Sprayer");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_HELM_UNLOCKED, "Malevolent Masquerade (Slayer Helmet Crafting)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_RING_UNLOCKED, "Ring Bling (Slayer Ring Crafting)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_AMMO_UNLOCKED, "Broader Fletching");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_REDDRAGONS, "Seeing Red (Red Dragons)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_AVIANSIES, "Watch the Birdie (Aviansies)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_TZHAAR, "Hot Stuff (TzHaar / TzTok-Jad)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_BOSSES, "Like a Boss");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_LIZARDMEN, "Reptile Got Ripped (Lizardmen)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_SUPERIORMOBS, "Bigger and Badder (Superior Slayer Monsters)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_NOTEDMITHRILBARS, "Duly Noted (Mithril Dragons)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_FOSSILWYVERNBLOCK, "Stop the Wyvern (Fossil Island Wyverns)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_GROTESQUEKILLS, "Double Trouble (Grotesque Guardians)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_BASILISK, "Basilocked (Basilisks)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_VAMPYRES, "Actual Vampyre Slayer (Vampyres)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_WILDY_EXTRATASKS,
                "I Wildy More Slayer (Krystilia Wilderness Tasks)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_WARPED_CREATURES, "Warped Reality (Warped Creatures)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_GRYPHONS, "Wings Spread (Gryphons)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_AQUANITES, "Lured In (Aquanites)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_LONGER_FROST_DRAGONS, "Chance of Heavy Frost (Frost Dragons)");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_UNLOCK_LONGER_GRYPHON, "Longer Gryphons");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_LONGER_CUSTODIANS, "Longer Custodians");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_LONGER_WYRMS, "Longer Wyrms");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_LONGER_AQUANITES, "Longer Aquanites");
        SLAYER_UNLOCK_VARBITS.put(VarbitID.SLAYER_LONGER_REVENANTS, "Longer Revenants");
    }

    /**
     * Exact Varbit IDs for Slayer Task Extensions.
     */
    private static final Map<Integer, String> SLAYER_EXTENSION_VARBITS = new LinkedHashMap<>();
    static {
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_ABERRANTSPECTRES, "Aberrant Spectres (Smell ya later)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_ABYSSALDEMONS, "Abyssal Demons (Augment my abbies)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_ANKOU, "Ankou (Ankou very much)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_SUQAH, "Suqahs (Suq-a-nother one)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_BLACKDRAGONS, "Fire Giants (Fire & Darkness)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_METALDRAGONS, "Metal Dragons (Pedal to the metals)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_BLACKDEMONS, "Dark Beasts (It's dark in here)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_GREATERDEMONS, "Greater Demons (Greater challenge)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_UNLOCK_MITHRILDRAGONS, "Mithril Dragons (I hope you mith me)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_BLOODVELD, "Bloodvelds (Bleed me dry)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_AVIANSIES, "Birds of a feather (Aviansies)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_CAVEHORRORS, "Cave Horrors (Horrorific)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_DUSTDEVILS, "Dust Devils (To dust you shall return)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_SKELETALWYVERNS, "Skeletal Wyverns (Wyver-nother one)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_GARGOYLES, "Gargoyles (Get smashed)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_NECHRYAEL, "Nechryael (Nechs please)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_CAVEKRAKEN, "Cave Kraken (Krack on)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_SPIRITUALGWD, "Spiritual Creatures (Spiritual fervour)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_SCABARITES, "Scabarites (Get scabaright on it)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_FOSSILWYVERNS, "Fossil Island Wyverns (Wyver-nother two)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_ADAMANTDRAGONS, "Adamant Dragons (Ada'mind some more)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_RUNEDRAGONS, "Rune Dragons (RUUUUUNE)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_BASILISK, "Basilisks (Basilonger)");
        SLAYER_EXTENSION_VARBITS.put(VarbitID.SLAYER_LONGER_VAMPYRES, "Vampyres (More at stake)");
    }

    /**
     * Slayer Master block slot varbits mapping: Master Name -> array of slot Varbit
     * IDs.
     */
    private static final Map<String, int[]> SLAYER_MASTER_BLOCK_VARBITS = new LinkedHashMap<>();
    static {
        SLAYER_MASTER_BLOCK_VARBITS.put("Duradel", new int[] {
                VarbitID.SLAYER_BLOCKED_DURADEL_1,
                VarbitID.SLAYER_BLOCKED_DURADEL_2,
                VarbitID.SLAYER_BLOCKED_DURADEL_3,
                VarbitID.SLAYER_BLOCKED_DURADEL_4,
                VarbitID.SLAYER_BLOCKED_DURADEL_5,
                VarbitID.SLAYER_BLOCKED_DURADEL_6,
                VarbitID.SLAYER_BLOCKED_DURADEL_DIARY
        });
        SLAYER_MASTER_BLOCK_VARBITS.put("Nieve", new int[] {
                VarbitID.SLAYER_BLOCKED_NIEVE_1,
                VarbitID.SLAYER_BLOCKED_NIEVE_2,
                VarbitID.SLAYER_BLOCKED_NIEVE_3,
                VarbitID.SLAYER_BLOCKED_NIEVE_4,
                VarbitID.SLAYER_BLOCKED_NIEVE_5,
                VarbitID.SLAYER_BLOCKED_NIEVE_6,
                VarbitID.SLAYER_BLOCKED_NIEVE_DIARY
        });
        SLAYER_MASTER_BLOCK_VARBITS.put("Konar", new int[] {
                VarbitID.SLAYER_BLOCKED_KONAR_1,
                VarbitID.SLAYER_BLOCKED_KONAR_2,
                VarbitID.SLAYER_BLOCKED_KONAR_3,
                VarbitID.SLAYER_BLOCKED_KONAR_4,
                VarbitID.SLAYER_BLOCKED_KONAR_5,
                VarbitID.SLAYER_BLOCKED_KONAR_6,
                VarbitID.SLAYER_BLOCKED_KONAR_DIARY
        });
        SLAYER_MASTER_BLOCK_VARBITS.put("Chaeldar", new int[] {
                VarbitID.SLAYER_BLOCKED_CHAELDAR_1,
                VarbitID.SLAYER_BLOCKED_CHAELDAR_2,
                VarbitID.SLAYER_BLOCKED_CHAELDAR_3,
                VarbitID.SLAYER_BLOCKED_CHAELDAR_4,
                VarbitID.SLAYER_BLOCKED_CHAELDAR_5,
                VarbitID.SLAYER_BLOCKED_CHAELDAR_6,
                VarbitID.SLAYER_BLOCKED_CHAELDAR_DIARY
        });
        SLAYER_MASTER_BLOCK_VARBITS.put("Vannaka", new int[] {
                VarbitID.SLAYER_BLOCKED_VANNAKA_1,
                VarbitID.SLAYER_BLOCKED_VANNAKA_2,
                VarbitID.SLAYER_BLOCKED_VANNAKA_3,
                VarbitID.SLAYER_BLOCKED_VANNAKA_4,
                VarbitID.SLAYER_BLOCKED_VANNAKA_5,
                VarbitID.SLAYER_BLOCKED_VANNAKA_6,
                VarbitID.SLAYER_BLOCKED_VANNAKA_DIARY
        });
        SLAYER_MASTER_BLOCK_VARBITS.put("Mazchna", new int[] {
                VarbitID.SLAYER_BLOCKED_MAZCHNA_1,
                VarbitID.SLAYER_BLOCKED_MAZCHNA_2,
                VarbitID.SLAYER_BLOCKED_MAZCHNA_3,
                VarbitID.SLAYER_BLOCKED_MAZCHNA_4,
                VarbitID.SLAYER_BLOCKED_MAZCHNA_5,
                VarbitID.SLAYER_BLOCKED_MAZCHNA_6,
                VarbitID.SLAYER_BLOCKED_MAZCHNA_DIARY
        });
        SLAYER_MASTER_BLOCK_VARBITS.put("Turael", new int[] {
                VarbitID.SLAYER_BLOCKED_TURAEL_1,
                VarbitID.SLAYER_BLOCKED_TURAEL_2,
                VarbitID.SLAYER_BLOCKED_TURAEL_3,
                VarbitID.SLAYER_BLOCKED_TURAEL_4,
                VarbitID.SLAYER_BLOCKED_TURAEL_5,
                VarbitID.SLAYER_BLOCKED_TURAEL_6,
                VarbitID.SLAYER_BLOCKED_TURAEL_DIARY
        });
        SLAYER_MASTER_BLOCK_VARBITS.put("Krystilia", new int[] {
                VarbitID.SLAYER_BLOCKED_KRYSTILIA_1,
                VarbitID.SLAYER_BLOCKED_KRYSTILIA_2,
                VarbitID.SLAYER_BLOCKED_KRYSTILIA_3,
                VarbitID.SLAYER_BLOCKED_KRYSTILIA_4,
                VarbitID.SLAYER_BLOCKED_KRYSTILIA_5,
                VarbitID.SLAYER_BLOCKED_KRYSTILIA_6,
                VarbitID.SLAYER_BLOCKED_KRYSTILIA_DIARY
        });
        SLAYER_MASTER_BLOCK_VARBITS.put("Mortimer", new int[] {
                VarbitID.SLAYER_BLOCKED_MORTIMER_1,
                VarbitID.SLAYER_BLOCKED_MORTIMER_2
        });
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

        int specPercent = client.getVarpValue(VarPlayerID.SA_ENERGY) / 10;
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
            int tithe = client.getVarbitValue(VarbitID.HOSIDIUS_TITHE_REWARDPOINTS);
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
            int pointsVal = 0;
            int standardStreakVal = 0;
            try {
                pointsVal = client.getVarbitValue(VarbitID.SLAYER_POINTS);
                standardStreakVal = client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED);
            } catch (Exception ignored) {
            }
            if (pointsVal <= 0) {
                String pts = Utilities.getConfigValue(configManager, "slayer", "points");
                if (pts != null && !pts.isEmpty()) {
                    try {
                        pointsVal = Integer.parseInt(pts);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (standardStreakVal <= 0) {
                String strk = Utilities.getConfigValue(configManager, "slayer", "streak");
                if (strk != null && !strk.isEmpty()) {
                    try {
                        standardStreakVal = Integer.parseInt(strk);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (pointsVal > 0) {
                points.addProperty("slayerPoints", pointsVal);
            }
            if (standardStreakVal > 0) {
                points.addProperty("slayerStreak", standardStreakVal);
                points.addProperty("standardSlayerStreak", standardStreakVal);
            }

            // Mortimer Streak & Tasks Completed Resolution
            String configStreakStr = Utilities.getConfigValue(configManager, "slayer", "streak");
            String configMaster = Utilities.getConfigValue(configManager, "slayer", "slayerMaster", "masterName",
                    "master", "taskMaster");
            int mortimerStreakVal = resolveMortimerStreak(configStreakStr, standardStreakVal, configMaster);
            if (mortimerStreakVal > 0) {
                points.addProperty("mortimerSlayerStreak", mortimerStreakVal);
                points.addProperty("mortimerTasksCompleted", mortimerStreakVal);
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

    /**
     * Resolves the player's Mortimer Slayer streak / completed tasks count by
     * checking
     * dedicated Mortimer config keys, comparing active Slayer plugin streak against
     * standard
     * Varbit 4069, and falling back to Mortimer varbit range inspection.
     *
     * @param streakStr         raw "streak" string from the Slayer plugin config
     * @param standardStreakVal standard Slayer streak from Varbit 4069
     * @param slayerMaster      name of the current Slayer master (e.g. "Mortimer")
     * @return resolved Mortimer streak / completed task count, or 0 if unrecorded
     */
    private int resolveMortimerStreak(String streakStr, int standardStreakVal, String slayerMaster) {
        String mStrkStr = Utilities.getConfigValue(configManager, "slayer",
                "mortimerStreak", "mortimer.streak", "mortimer_streak", "streakMortimer", "streak_mortimer",
                "mortimerTasksCompleted", "mortimer.tasksCompleted", "mortimer_tasks_completed",
                "mortimer_tasks_completed",
                "mortimerCompletedTasks", "mortimerCompleted", "mortimerTasks", "mortimerCount", "mortimerTaskCount",
                "tasksCompleted_mortimer", "mortimer_tasks_completed", "mortimer_completed", "mortimer_tasks",
                "mortimer_task_count");
        if (mStrkStr == null || mStrkStr.isEmpty()) {
            mStrkStr = Utilities.getConfigValue(configManager, "mortimer", "streak", "mortimerStreak", "tasksCompleted",
                    "completed", "count");
        }
        if (mStrkStr == null || mStrkStr.isEmpty()) {
            mStrkStr = Utilities.getConfigValue(configManager, "slayer.mortimer", "streak", "mortimerStreak",
                    "tasksCompleted", "completed", "count");
        }

        int mortimerStreakVal = 0;
        if (mStrkStr != null && !mStrkStr.isEmpty()) {
            try {
                mortimerStreakVal = Integer.parseInt(mStrkStr);
            } catch (NumberFormatException ignored) {
            }
        }

        boolean isMortimerMaster = slayerMaster != null && "Mortimer".equalsIgnoreCase(slayerMaster.trim());
        // If active Slayer master is Mortimer, RuneLite's Slayer plugin stores
        // Mortimer's streak in config "streak"
        if (isMortimerMaster && mortimerStreakVal <= 0 && streakStr != null && !streakStr.isEmpty()) {
            try {
                int cStreak = Integer.parseInt(streakStr);
                if (cStreak > 0) {
                    mortimerStreakVal = cStreak;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        // If config "streak" differs from Varbit 4069 (standard streak), the config
        // streak is Mortimer's streak
        if (mortimerStreakVal <= 0 && streakStr != null && !streakStr.isEmpty()) {
            try {
                int cStreak = Integer.parseInt(streakStr);
                if (cStreak > 0 && cStreak != standardStreakVal) {
                    mortimerStreakVal = cStreak;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        // Fallback: check varbits for values > 1 in Mortimer range
        if (mortimerStreakVal <= 0 && client != null) {
            for (int v = VarbitID.SLAYER_MODIFIER_ID_STORED; v <= VarbitID.SLAYER_CHOOSE_TASK_2_MODIFIER_NEGATIVE; v++) {
                if (v != VarbitID.SLAYER_BLOCKED_MORTIMER_1 && v != VarbitID.SLAYER_BLOCKED_MORTIMER_2) {
                    try {
                        int val = client.getVarbitValue(v);
                        if (val > 1 && val < MAX_REASONABLE_STREAK) {
                            mortimerStreakVal = val;
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        return mortimerStreakVal;
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

        // 1. Live Points & Standard Streak from game engine varbits
        int pointsVal = 0;
        int standardStreakVal = 0;
        try {
            pointsVal = client.getVarbitValue(VarbitID.SLAYER_POINTS);
            standardStreakVal = client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED);
        } catch (Exception ignored) {
        }
        if (pointsVal <= 0 && pointsStr != null && !pointsStr.isEmpty()) {
            try {
                pointsVal = Integer.parseInt(pointsStr);
            } catch (NumberFormatException ignored) {
            }
        }
        if (standardStreakVal <= 0 && streakStr != null && !streakStr.isEmpty()) {
            try {
                standardStreakVal = Integer.parseInt(streakStr);
            } catch (NumberFormatException ignored) {
            }
        }

        // 2. Mortimer Streak & Tasks Completed Resolution
        int mortimerStreakVal = resolveMortimerStreak(streakStr, standardStreakVal, slayerMaster);
        boolean isMortimerMaster = slayerMaster != null && "Mortimer".equalsIgnoreCase(slayerMaster.trim());

        result.addProperty("points", pointsVal);
        result.addProperty("streak", standardStreakVal);
        result.addProperty("standardStreak", standardStreakVal);
        result.addProperty("mortimerStreak", mortimerStreakVal);
        result.addProperty("mortimerTasksCompleted", mortimerStreakVal);

        // 3. Active Task Info
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
            result.addProperty("isMortimerTask", isMortimerMaster);
        } else {
            result.addProperty("task", "None");
            result.addProperty("quantity", 0);
            result.addProperty("isMortimerTask", false);
            if (slayerMaster != null && !slayerMaster.isEmpty() && !"None".equalsIgnoreCase(slayerMaster.trim())) {
                result.addProperty("lastSlayerMaster", slayerMaster.trim());
            }
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
