package com.osrsai.util;

import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemEquipmentStats;
import net.runelite.http.api.item.ItemPrice;
import net.runelite.http.api.item.ItemStats;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Utility class for inspecting, aggregating, filtering, and retrieving item
 * stats and details
 * from RuneLite item containers (inventory, equipment, bank).
 */
public class ItemContainerUtils {
    /**
     * Maximum number of items returned for unfiltered bank/container queries to
     * conserve token budget.
     */
    public static final int UNFILTERED_BANK_LIMIT = 50;

    private static final Pattern OR_SPLIT_PATTERN = Pattern.compile("\\s+or\\s+|\\s*,\\s*|\\s*\\|\\s*");
    private static final Pattern AND_SPLIT_PATTERN = Pattern.compile("\\s+and\\s+|\\s*&\\s*");

    private ItemContainerUtils() {
        // Utility class
    }

    /**
     * Data structure representing a lightweight item identifier and quantity.
     */
    public static class SimpleItem {
        private final int id;
        private final int quantity;

        public SimpleItem(int id, int quantity) {
            this.id = id;
            this.quantity = quantity;
        }

        public int getId() {
            return id;
        }

        public int getQuantity() {
            return quantity;
        }
    }

    /**
     * Converts an array of RuneLite {@link Item}s into a list of
     * {@link SimpleItem}s.
     *
     * @param items array of items
     * @return list of non-empty {@link SimpleItem} objects
     */
    public static List<SimpleItem> toSimpleItemList(Item[] items) {
        List<SimpleItem> list = new ArrayList<>();
        if (items != null) {
            for (Item item : items) {
                if (item != null && item.getId() > 0 && item.getQuantity() > 0) {
                    list.add(new SimpleItem(item.getId(), item.getQuantity()));
                }
            }
        }
        return list;
    }

    /**
     * Aggregates items in an {@link ItemContainer}, resolving item names, stack
     * quantities, Grand Exchange prices,
     * and High Alchemy values. Applies optional name filters and minimum stack
     * value filters.
     *
     * @param client      RuneLite {@link Client} instance
     * @param itemManager RuneLite {@link ItemManager} instance
     * @param container   the target {@link ItemContainer} (inventory, equipment, or
     *                    bank)
     * @param filter      optional search filter expression (supports logical 'or',
     *                    'and', ',', '|')
     * @param minValue    optional minimum total stack value filter
     * @return {@link JsonObject} mapping item names to item detail objects (id,
     *         qty, gePrice, haPrice)
     */
    public static JsonObject aggregateItemsWithPrices(Client client, ItemManager itemManager, ItemContainer container,
            String filter, int minValue) {
        if (container == null) {
            return new JsonObject();
        }
        return aggregateItemsWithPrices(client, itemManager, toSimpleItemList(container.getItems()), filter, minValue);
    }

    /**
     * Aggregates items from a list of {@link SimpleItem}s, resolving item names,
     * stack
     * quantities, Grand Exchange prices, and High Alchemy values.
     *
     * @param client      RuneLite {@link Client} instance
     * @param itemManager RuneLite {@link ItemManager} instance
     * @param items       the list of {@link SimpleItem}s
     * @param filter      optional search filter expression
     * @param minValue    optional minimum total stack value filter
     * @return {@link JsonObject} mapping item names to item detail objects
     */
    public static JsonObject aggregateItemsWithPrices(Client client, ItemManager itemManager, List<SimpleItem> items,
            String filter, int minValue) {
        JsonObject result = new JsonObject();
        if (items == null || items.isEmpty()) {
            return result;
        }

        Map<String, Long> quantities = new LinkedHashMap<>();
        Map<String, Integer> itemIds = new HashMap<>();
        Map<String, Integer> itemHaPrices = new HashMap<>();

        String search = (filter != null) ? filter.trim().toLowerCase() : null;
        String[] tokens = null;
        if (search != null) {
            tokens = OR_SPLIT_PATTERN.split(search);
        }
        boolean isIron = Utilities.isIronman(client);

        for (SimpleItem item : items) {
            if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) {
                continue;
            }
            ItemComposition comp = null;
            if (itemManager != null) {
                try {
                    comp = itemManager.getItemComposition(item.getId());
                } catch (Exception ignored) {
                }
            }
            if (comp != null && comp.getPlaceholderTemplateId() != -1) {
                continue;
            }
            String itemName = (comp != null && comp.getName() != null && !comp.getName().trim().isEmpty())
                    ? comp.getName()
                    : "Item " + item.getId();

            // Apply name filter or category filter if present
            if (search != null && !matchesCategoryOrFilter(itemName, item.getId(), itemManager, search, tokens)) {
                continue;
            }

            quantities.put(itemName, quantities.getOrDefault(itemName, 0L) + item.getQuantity());
            itemIds.putIfAbsent(itemName, item.getId());
            itemHaPrices.putIfAbsent(itemName, comp != null ? comp.getHaPrice() : 0);
        }

        // Help sort items by total stack value with equipment priority
        class BankItem {
            final String name;
            final long qty;
            final int gePrice;
            final int haPrice;
            final boolean equipable;
            final long totalSortVal;

            BankItem(String name, long qty, int gePrice, int haPrice, boolean equipable) {
                this.name = name;
                this.qty = qty;
                this.gePrice = gePrice;
                this.haPrice = haPrice;
                this.equipable = equipable;
                long unitPrice = isIron ? haPrice : gePrice;
                if (unitPrice <= 0) {
                    unitPrice = Math.max(gePrice, haPrice);
                }
                if (unitPrice <= 0 && equipable) {
                    unitPrice = 1000;
                }
                this.totalSortVal = unitPrice * qty;
            }
        }

        List<BankItem> list = new ArrayList<>();
        for (Map.Entry<String, Long> entry : quantities.entrySet()) {
            String name = entry.getKey();
            long qty = entry.getValue();
            int itemId = itemIds.get(name);
            int price = 0;
            if (itemManager != null) {
                try {
                    price = itemManager.getItemPrice(itemId);
                } catch (Exception e) {
                }
            }
            if (price <= 0 && "Coins".equals(name)) {
                price = 1;
            }
            int haPrice = itemHaPrices.getOrDefault(name, 0);

            // Apply minimum value filter if present based on account type preference
            int checkVal = isIron ? haPrice : price;
            if (minValue > 0 && checkVal < minValue) {
                continue;
            }

            boolean equipable = isEquipableItem(itemManager, itemId, name);
            list.add(new BankItem(name, qty, price, haPrice, equipable));
        }

        // Sort equipable items first, then by totalSortVal descending
        list.sort((a, b) -> {
            if (a.equipable != b.equipable) {
                return a.equipable ? -1 : 1;
            }
            return Long.compare(b.totalSortVal, a.totalSortVal);
        });

        // Limit unfiltered container output to top items to conserve tokens
        int limit = (search == null) ? UNFILTERED_BANK_LIMIT : Integer.MAX_VALUE;
        int count = 0;
        for (BankItem bi : list) {
            if (count >= limit) {
                break;
            }
            JsonObject detail = new JsonObject();
            detail.addProperty("id", itemIds.get(bi.name));
            detail.addProperty("qty", bi.qty);
            detail.addProperty("gePrice", bi.gePrice);
            detail.addProperty("haPrice", bi.haPrice);
            result.add(bi.name, detail);
            count++;
        }

        return result;
    }

    private static boolean matchesCategoryOrFilter(String itemName, int itemId, ItemManager itemManager, String search,
            String[] tokens) {
        if (search == null || search.trim().isEmpty()) {
            return true;
        }
        String lowerName = itemName.toLowerCase();

        // Category keyword checks
        if (search.equals("gear") || search.equals("equipment") || search.contains("gear")
                || search.contains("equipment")) {
            if (isEquipableItem(itemManager, itemId, itemName)) {
                return true;
            }
        }
        if (search.contains("weapon")) {
            if (isEquipableItem(itemManager, itemId, itemName) && (lowerName.contains("whip")
                    || lowerName.contains("scimitar")
                    || lowerName.contains("sword") || lowerName.contains("bow") || lowerName.contains("staff")
                    || lowerName.contains("wand")
                    || lowerName.contains("dagger") || lowerName.contains("spear") || lowerName.contains("mace")
                    || lowerName.contains("axe")
                    || lowerName.contains("crossbow") || lowerName.contains("blowpipe") || lowerName.contains("trident")
                    || lowerName.contains("scepter")
                    || lowerName.contains("halberd") || lowerName.contains("dart") || lowerName.contains("knife"))) {
                return true;
            }
        }
        if (search.contains("armour") || search.contains("armor")) {
            if (isEquipableItem(itemManager, itemId, itemName)
                    && (lowerName.contains("helm") || lowerName.contains("mask")
                            || lowerName.contains("torso") || lowerName.contains("body")
                            || lowerName.contains("platebody") || lowerName.contains("legs")
                            || lowerName.contains("platelegs") || lowerName.contains("chaps")
                            || lowerName.contains("robe") || lowerName.contains("boots")
                            || lowerName.contains("gloves") || lowerName.contains("shield")
                            || lowerName.contains("defender") || lowerName.contains("cape")
                            || lowerName.contains("cuirass") || lowerName.contains("brassard")
                            || lowerName.contains("skirt") || lowerName.contains("coif")
                            || lowerName.contains("void") || lowerName.contains("graceful")
                            || lowerName.contains("barrows") || lowerName.contains("armour")
                            || lowerName.contains("armor"))) {
                return true;
            }
        }
        if (search.contains("potion")) {
            if (lowerName.contains("potion") || lowerName.contains("brew") || lowerName.contains("super ")
                    || lowerName.contains("stamina")
                    || lowerName.contains("antifire") || lowerName.contains("(4)") || lowerName.contains("(3)")
                    || lowerName.contains("(2)")
                    || lowerName.contains("(1)")) {
                return true;
            }
        }
        if (search.contains("food")) {
            if (lowerName.contains("shark") || lowerName.contains("monkfish") || lowerName.contains("karambwan")
                    || lowerName.contains("lobster")
                    || lowerName.contains("swordfish") || lowerName.contains("manta") || lowerName.contains("angler")
                    || lowerName.contains("tuna")
                    || lowerName.contains("cooked") || lowerName.contains("cake") || lowerName.contains("pie")
                    || lowerName.contains("pizza")) {
                return true;
            }
        }

        if (tokens != null && tokens.length > 0) {
            for (String orGroup : tokens) {
                String cleanGroup = orGroup.trim();
                if (cleanGroup.isEmpty()) {
                    continue;
                }
                String[] andTokens = AND_SPLIT_PATTERN.split(cleanGroup);
                boolean matchesAllAndTokens = true;
                for (String andToken : andTokens) {
                    String cleanAndToken = andToken.trim();
                    if (!cleanAndToken.isEmpty() && !lowerName.contains(cleanAndToken)) {
                        matchesAllAndTokens = false;
                        break;
                    }
                }
                if (matchesAllAndTokens) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isEquipableItem(ItemManager itemManager, int itemId, String itemName) {
        if (itemManager != null) {
            try {
                ItemStats stats = itemManager.getItemStats(itemId, false);
                if (stats != null && stats.isEquipable()) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        if (itemName == null) {
            return false;
        }
        String lower = itemName.toLowerCase();
        return lower.contains("helm") || lower.contains("mask") || lower.contains("torso") || lower.contains("body")
                || lower.contains("platebody") || lower.contains("legs") || lower.contains("platelegs")
                || lower.contains("chaps")
                || lower.contains("robe") || lower.contains("boots") || lower.contains("gloves")
                || lower.contains("shield")
                || lower.contains("defender") || lower.contains("cape") || lower.contains("accumulator")
                || lower.contains("assembler")
                || lower.contains("whip") || lower.contains("sword") || lower.contains("scimitar")
                || lower.contains("bow")
                || lower.contains("staff") || lower.contains("wand") || lower.contains("dagger")
                || lower.contains("spear")
                || lower.contains("mace") || lower.contains("axe") || lower.contains("pickaxe")
                || lower.contains("ring")
                || lower.contains("amulet") || lower.contains("necklace") || lower.contains("blessing")
                || lower.contains("coif")
                || lower.contains("brassard") || lower.contains("skirt") || lower.contains("vamb")
                || lower.contains("bracer")
                || lower.contains("gauntlets") || lower.contains("tome") || lower.contains("book")
                || lower.contains("quiver")
                || lower.contains("void") || lower.contains("graceful") || lower.contains("fighter")
                || lower.contains("barrows");
    }

    /**
     * Builds a detailed JSON representation of an item's equipment statistics,
     * prices, weight, GE limits, and slot bonuses.
     *
     * @param itemManager RuneLite {@link ItemManager} instance
     * @param itemId      OSRS item ID
     * @return {@link JsonObject} containing detailed item statistics
     */
    public static JsonObject buildItemStatsJson(ItemManager itemManager, int itemId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", itemId);

        ItemComposition comp = null;
        if (itemManager != null) {
            try {
                comp = itemManager.getItemComposition(itemId);
            } catch (Exception ignored) {
            }
        }
        String itemName = (comp != null && comp.getName() != null && !comp.getName().trim().isEmpty())
                ? comp.getName()
                : "Item " + itemId;
        obj.addProperty("name", itemName);

        int gePrice = 0;
        if (itemManager != null) {
            try {
                gePrice = itemManager.getItemPrice(itemId);
            } catch (Exception ignored) {
            }
        }
        if (gePrice <= 0 && "Coins".equals(itemName)) {
            gePrice = 1;
        }
        obj.addProperty("gePrice", gePrice);
        obj.addProperty("haPrice", comp != null ? comp.getHaPrice() : 0);

        ItemStats stats = (itemManager != null) ? itemManager.getItemStats(itemId, false) : null;
        if (stats == null) {
            obj.addProperty("equipable", false);
            return obj;
        }

        obj.addProperty("equipable", stats.isEquipable());
        obj.addProperty("weight", stats.getWeight());
        obj.addProperty("geLimit", stats.getGeLimit());

        if (stats.isEquipable() && stats.getEquipment() != null) {
            ItemEquipmentStats eq = stats.getEquipment();
            JsonObject eqObj = new JsonObject();
            eqObj.addProperty("astab", eq.getAstab());
            eqObj.addProperty("aslash", eq.getAslash());
            eqObj.addProperty("ascrush", eq.getAcrush());
            eqObj.addProperty("asmagic", eq.getAmagic());
            eqObj.addProperty("asrange", eq.getArange());
            eqObj.addProperty("dstab", eq.getDstab());
            eqObj.addProperty("dslash", eq.getDslash());
            eqObj.addProperty("dcrush", eq.getDcrush());
            eqObj.addProperty("dmagic", eq.getDmagic());
            eqObj.addProperty("drange", eq.getDrange());
            eqObj.addProperty("str", eq.getStr());
            eqObj.addProperty("rstr", eq.getRstr());
            eqObj.addProperty("mdmg", eq.getMdmg());
            eqObj.addProperty("prayer", eq.getPrayer());
            eqObj.addProperty("aspeed", eq.getAspeed());

            obj.add("equipment", eqObj);
        }
        return obj;
    }

    /**
     * Searches player equipment, inventory, and bank containers for an item
     * matching a target name substring.
     *
     * @param client      RuneLite {@link Client} instance
     * @param itemManager RuneLite {@link ItemManager} instance
     * @param name        target item name substring
     * @return matching OSRS item ID, or {@code null} if not found in any container
     */
    public static Integer findItemIdInContainers(Client client, ItemManager itemManager, String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String search = name.trim().toLowerCase();
        Set<Integer> checkedIds = new HashSet<>();

        if (client != null) {
            ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
            if (eq != null) {
                for (Item item : eq.getItems()) {
                    if (item != null && item.getId() > 0 && checkedIds.add(item.getId())) {
                        if (safeItemName(itemManager, item.getId()).toLowerCase().contains(search)) {
                            return item.getId();
                        }
                    }
                }
            }
            ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
            if (inv != null) {
                for (Item item : inv.getItems()) {
                    if (item != null && item.getId() > 0 && checkedIds.add(item.getId())) {
                        if (safeItemName(itemManager, item.getId()).toLowerCase().contains(search)) {
                            return item.getId();
                        }
                    }
                }
            }
            ItemContainer bank = client.getItemContainer(InventoryID.BANK);
            if (bank != null) {
                for (Item item : bank.getItems()) {
                    if (item != null && item.getId() > 0 && checkedIds.add(item.getId())) {
                        if (safeItemName(itemManager, item.getId()).toLowerCase().contains(search)) {
                            return item.getId();
                        }
                    }
                }
            }
        }

        // Global item database search fallback via RuneLite ItemManager
        if (itemManager != null) {
            try {
                List<ItemPrice> searchResults = itemManager.search(name);
                if (searchResults != null) {
                    for (ItemPrice ip : searchResults) {
                        if (ip != null && ip.getId() > 0) {
                            ItemComposition comp = itemManager.getItemComposition(ip.getId());
                            if (comp != null && comp.getPlaceholderTemplateId() == -1) {
                                return ip.getId();
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    /**
     * Safely resolves an item's display name without throwing exceptions if item
     * data is missing.
     *
     * @param itemManager RuneLite {@link ItemManager} instance
     * @param itemId      OSRS item ID
     * @return resolved item name, or fallback string (e.g. "Item 1234")
     */
    public static String safeItemName(ItemManager itemManager, int itemId) {
        if (itemManager == null) {
            return "Item " + itemId;
        }
        try {
            ItemComposition comp = itemManager.getItemComposition(itemId);
            String name = (comp != null) ? comp.getName() : null;
            if (name == null || name.trim().isEmpty()) {
                return "Item " + itemId;
            }
            return name;
        } catch (Exception ex) {
            return "Item " + itemId;
        }
    }

}
