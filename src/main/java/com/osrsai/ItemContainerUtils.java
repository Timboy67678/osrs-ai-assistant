package com.osrsai;

import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Varbits;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemEquipmentStats;
import net.runelite.http.api.item.ItemPrice;
import net.runelite.http.api.item.ItemStats;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Utility class for inspecting, aggregating, filtering, and retrieving item stats and details
 * from RuneLite item containers (inventory, equipment, bank).
 */
public class ItemContainerUtils {
    /** Maximum number of items returned for unfiltered bank/container queries to conserve token budget. */
    public static final int UNFILTERED_BANK_LIMIT = 50;

    private static final Pattern OR_SPLIT_PATTERN = Pattern.compile("\\s+or\\s+|\\s*,\\s*|\\s*\\|\\s*");
    private static final Pattern AND_SPLIT_PATTERN = Pattern.compile("\\s+and\\s+|\\s*&\\s*");

    private ItemContainerUtils() {
        // Utility class
    }

    /**
     * Aggregates items in an {@link ItemContainer}, resolving item names, stack quantities, Grand Exchange prices,
     * and High Alchemy values. Applies optional name filters and minimum stack value filters.
     *
     * @param client RuneLite {@link Client} instance
     * @param itemManager RuneLite {@link ItemManager} instance
     * @param container the target {@link ItemContainer} (inventory, equipment, or bank)
     * @param filter optional search filter expression (supports logical 'or', 'and', ',', '|')
     * @param minValue optional minimum total stack value filter
     * @return {@link JsonObject} mapping item names to item detail objects (id, qty, gePrice, haPrice)
     */
    public static JsonObject aggregateItemsWithPrices(Client client, ItemManager itemManager, ItemContainer container,
            String filter, int minValue) {
        JsonObject result = new JsonObject();
        Map<String, Long> quantities = new LinkedHashMap<>();
        Map<String, Integer> itemIds = new HashMap<>();
        Map<String, Integer> itemHaPrices = new HashMap<>();

        String search = (filter != null) ? filter.trim().toLowerCase() : null;
        String[] tokens = null;
        if (search != null) {
            tokens = OR_SPLIT_PATTERN.split(search);
        }
        boolean isIron = isIronman(client);

        for (Item item : container.getItems()) {
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

            // Apply name filter if present
            if (tokens != null && tokens.length > 0) {
                boolean matchesAnyOrGroup = false;
                for (String orGroup : tokens) {
                    String cleanGroup = orGroup.trim();
                    if (cleanGroup.isEmpty()) {
                        continue;
                    }

                    // Split the OR group by " and " or "&" to find all AND tokens
                    String[] andTokens = AND_SPLIT_PATTERN.split(cleanGroup);
                    boolean matchesAllAndTokens = true;
                    for (String andToken : andTokens) {
                        String cleanAndToken = andToken.trim();
                        if (!cleanAndToken.isEmpty() && !itemName.toLowerCase().contains(cleanAndToken)) {
                            matchesAllAndTokens = false;
                            break;
                        }
                    }

                    if (matchesAllAndTokens) {
                        matchesAnyOrGroup = true;
                        break;
                    }
                }
                if (!matchesAnyOrGroup) {
                    continue;
                }
            }

            quantities.put(itemName, quantities.getOrDefault(itemName, 0L) + item.getQuantity());
            itemIds.putIfAbsent(itemName, item.getId());
            itemHaPrices.putIfAbsent(itemName, comp != null ? comp.getHaPrice() : 0);
        }

        // Help sort items by total stack value
        class BankItem {
            final String name;
            final long qty;
            final int gePrice;
            final int haPrice;
            final long totalSortVal;

            BankItem(String name, long qty, int gePrice, int haPrice) {
                this.name = name;
                this.qty = qty;
                this.gePrice = gePrice;
                this.haPrice = haPrice;
                long unitPrice = isIron ? haPrice : gePrice;
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

            list.add(new BankItem(name, qty, price, haPrice));
        }

        // Sort by totalSortVal descending
        list.sort((a, b) -> Long.compare(b.totalSortVal, a.totalSortVal));

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

    /**
     * Checks if the currently logged-in player is on an Ironman account mode (Ironman, UIM, HCIM, GIM, HGIM, UGIM).
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
            return accountType >= 1 && accountType <= 6;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Builds a detailed JSON representation of an item's equipment statistics, prices, weight, GE limits, and slot bonuses.
     *
     * @param itemManager RuneLite {@link ItemManager} instance
     * @param itemId OSRS item ID
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
     * Searches player equipment, inventory, and bank containers for an item matching a target name substring.
     *
     * @param client RuneLite {@link Client} instance
     * @param itemManager RuneLite {@link ItemManager} instance
     * @param name target item name substring
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
     * Safely resolves an item's display name without throwing exceptions if item data is missing.
     *
     * @param itemManager RuneLite {@link ItemManager} instance
     * @param itemId OSRS item ID
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

    /**
     * Converts an equipment slot index integer into a human-readable equipment slot name.
     *
     * @param index slot index (0=Head, 1=Cape, 2=Amulet, 3=Weapon, 4=Body, 5=Shield, 6=Legs, 7=Gloves, 8=Boots, 9=Ring, 10=Ammo)
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
}
