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
    private static final Pattern PATTERN_HTML_TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern PATTERN_WHITESPACE = Pattern.compile("\\s+");

    private static final int VARP_SPECIAL_ATTACK_PERCENT = 300;
    private static final int VARP_NMZ_REWARD_POINTS = 1056;
    private static final int VARP_PEST_CONTROL_POINTS = 261;
    private static final int POISON_VENOM_THRESHOLD = 1000000;

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
    private static final int VARBIT_CARPENTERS_POINTS = 10671;
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

    private String getConfigValue(String group, String... keys) {
        if (configManager == null) {
            return null;
        }
        for (String key : keys) {
            String val = configManager.getRSProfileConfiguration(group, key);
            if (val == null || val.isEmpty()) {
                val = configManager.getConfiguration(group, key);
            }
            if (val != null && !val.isEmpty()) {
                return val;
            }
        }
        return null;
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
        if (poisonVarp > 0 && poisonVarp < POISON_VENOM_THRESHOLD) {
            status = "Poisoned (" + poisonVarp + " dmg)";
        } else if (poisonVarp >= POISON_VENOM_THRESHOLD) {
            int venomDmg = (poisonVarp - POISON_VENOM_THRESHOLD) / 5 + 6;
            status = "Venomed (" + venomDmg + " dmg)";
        }
        result.addProperty("poisonState", status);

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
                        String noHtml = PATTERN_HTML_TAGS.matcher(tooltip).replaceAll(" ");
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
            int carp = client.getVarbitValue(VARBIT_CARPENTERS_POINTS);
            points.addProperty("carpentersPoints", carp);
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
            String pts = getConfigValue("slayer", "points");
            if (pts != null && !pts.isEmpty()) {
                points.addProperty("slayerPoints", Integer.parseInt(pts));
            }
            String strk = getConfigValue("slayer", "streak");
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
        String taskName = getConfigValue("slayer", "taskName");
        String amount = getConfigValue("slayer", "amount");
        String taskLocation = getConfigValue("slayer", "taskLocation", "location");
        String slayerMaster = getConfigValue("slayer", "slayerMaster", "masterName", "master", "taskMaster");
        String pointsStr = getConfigValue("slayer", "points");
        String streakStr = getConfigValue("slayer", "streak");

        int pointsVal = 0;
        if (pointsStr != null && !pointsStr.isEmpty()) {
            try {
                pointsVal = Integer.parseInt(pointsStr);
            } catch (NumberFormatException ignored) {
            }
        }
        if (pointsVal != 0) {
            result.addProperty("points", pointsVal);
        }
        if (streakStr != null && !streakStr.isEmpty()) {
            try {
                result.addProperty("streak", Integer.parseInt(streakStr));
            } catch (NumberFormatException ignored) {
            }
        }
        if (taskName != null && !taskName.isEmpty() && amount != null) {
            result.addProperty("task", taskName);
            result.addProperty("quantity", Integer.parseInt(amount));
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
        return gson.toJson(result);
    }
}
