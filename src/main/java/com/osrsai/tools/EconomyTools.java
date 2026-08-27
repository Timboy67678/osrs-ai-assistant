package com.osrsai.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrsai.util.ItemContainerUtils;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.game.ItemManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Tool implementations for player economy: bank querying (live & offline
 * cache),
 * item stats lookup, Grand Exchange offers, and market prices / High Alchemy
 * profits.
 */
@Slf4j
public class EconomyTools {
    private static final int ITEM_ID_NATURE_RUNE = 561;
    private static final int DEFAULT_NATURE_RUNE_PRICE = 90;
    private static final double LOW_ALCH_MULTIPLIER = 0.6;

    private final Client client;
    private final ItemManager itemManager;
    private final Gson gson;
    private final Supplier<List<ItemContainerUtils.SimpleItem>> cachedBankSupplier;
    private final LongSupplier cachedBankTimestampSupplier;

    public EconomyTools(Client client, ItemManager itemManager, Gson gson,
            Supplier<List<ItemContainerUtils.SimpleItem>> cachedBankSupplier,
            LongSupplier cachedBankTimestampSupplier) {
        this.client = client;
        this.itemManager = itemManager;
        this.gson = gson;
        this.cachedBankSupplier = cachedBankSupplier;
        this.cachedBankTimestampSupplier = cachedBankTimestampSupplier;
    }

    public String executeGetPlayerBank(JsonObject args) {
        JsonObject result = new JsonObject();
        ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
        String filter = (args != null && args.has("filter")) ? args.get("filter").getAsString() : null;
        int minValue = (args != null && args.has("minValue")) ? args.get("minValue").getAsInt() : 0;

        if (bankContainer != null && bankContainer.getItems().length > 0) {
            result.addProperty("status", "success");
            result.addProperty("bankOpen", true);
            result.addProperty("cached", false);
            if (filter != null) {
                result.addProperty("filterApplied", filter);
            }
            result.add("items",
                    ItemContainerUtils.aggregateItemsWithPrices(client, itemManager, bankContainer, filter, minValue));
        } else {
            List<ItemContainerUtils.SimpleItem> cachedBank = cachedBankSupplier != null ? cachedBankSupplier.get()
                    : null;
            long cachedTimestamp = cachedBankTimestampSupplier != null ? cachedBankTimestampSupplier.getAsLong() : 0;

            if (cachedBank != null && !cachedBank.isEmpty()) {
                result.addProperty("status", "success");
                result.addProperty("bankOpen", false);
                result.addProperty("cached", true);
                result.addProperty("cachedItemCount", cachedBank.size());
                long ageSeconds = cachedTimestamp > 0
                        ? Math.max(0, (System.currentTimeMillis() - cachedTimestamp) / 1000)
                        : 0;
                result.addProperty("cachedAgeSeconds", ageSeconds);
                if (filter != null) {
                    result.addProperty("filterApplied", filter);
                }
                result.add("items",
                        ItemContainerUtils.aggregateItemsWithPrices(client, itemManager, cachedBank, filter,
                                minValue));
            } else {
                result.addProperty("status", "error");
                result.addProperty("message",
                        "The bank is not currently open and no bank cache is available. Ask the player to open their bank if they want you to check bank items.");
            }
        }
        return gson.toJson(result);
    }

    public String executeGetItemStats(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject itemsStats = new JsonObject();
        if (args != null) {
            if (args.has("itemIds")) {
                JsonArray ids = args.getAsJsonArray("itemIds");
                for (int i = 0; i < ids.size(); i++) {
                    int itemId = ids.get(i).getAsInt();
                    itemsStats.add(String.valueOf(itemId), ItemContainerUtils.buildItemStatsJson(itemManager, itemId));
                }
            }
            if (args.has("itemNames")) {
                JsonArray names = args.getAsJsonArray("itemNames");
                for (int i = 0; i < names.size(); i++) {
                    String itemName = names.get(i).getAsString();
                    Integer itemId = ItemContainerUtils.findItemIdInContainers(client, itemManager, itemName);
                    if (itemId != null) {
                        itemsStats.add(itemName, ItemContainerUtils.buildItemStatsJson(itemManager, itemId));
                    } else {
                        JsonObject errorObj = new JsonObject();
                        errorObj.addProperty("error", "Item '" + itemName
                                + "' not found in game containers or item database. You MUST call 'search_osrs_wiki' with query '"
                                + itemName + "' to look up its stats on the OSRS Wiki before making claims.");
                        itemsStats.add(itemName, errorObj);
                    }
                }
            }
        }
        result.add("items", itemsStats);
        return gson.toJson(result);
    }

    public String executeGetPlayerGeOffers(JsonObject args) {
        JsonObject result = new JsonObject();
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null || offers.length == 0) {
            result.addProperty("status", "empty");
            result.addProperty("message", "No Grand Exchange offer data available.");
            return gson.toJson(result);
        }

        result.addProperty("status", "success");
        JsonArray offerList = new JsonArray();
        int activeCount = 0;

        for (int i = 0; i < offers.length; i++) {
            GrandExchangeOffer offer = offers[i];
            if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("slot", i + 1);
            obj.addProperty("state", offer.getState().name());

            int itemId = offer.getItemId();
            obj.addProperty("itemId", itemId);
            String itemName = "Item " + itemId;
            if (itemManager != null) {
                try {
                    ItemComposition comp = itemManager.getItemComposition(itemId);
                    if (comp != null && comp.getName() != null) {
                        itemName = comp.getName();
                    }
                } catch (Exception ignored) {
                }
            }
            obj.addProperty("itemName", itemName);
            obj.addProperty("offerPrice", offer.getPrice());
            obj.addProperty("totalQuantity", offer.getTotalQuantity());
            obj.addProperty("transferredQuantity", offer.getQuantitySold());
            obj.addProperty("spentOrReceivedGp", offer.getSpent());

            int total = offer.getTotalQuantity();
            int transferred = offer.getQuantitySold();
            int pct = total > 0 ? (int) Math.round(((double) transferred / total) * 100.0) : 0;
            obj.addProperty("progressPercent", pct);

            offerList.add(obj);
            activeCount++;
        }

        result.addProperty("activeOrCompletedOffersCount", activeCount);
        result.add("offers", offerList);
        return gson.toJson(result);
    }

    public String executeGetMarketPrices(JsonObject args) {
        JsonObject result = new JsonObject();
        JsonObject itemsData = new JsonObject();

        int natureRunePrice = 0;
        try {
            natureRunePrice = itemManager != null ? itemManager.getItemPrice(ITEM_ID_NATURE_RUNE)
                    : DEFAULT_NATURE_RUNE_PRICE;
        } catch (Exception ignored) {
        }
        if (natureRunePrice <= 0) {
            natureRunePrice = DEFAULT_NATURE_RUNE_PRICE;
        }
        result.addProperty("natureRuneCost", natureRunePrice);

        List<Integer> targetItemIds = new ArrayList<>();
        if (args != null) {
            if (args.has("itemIds")) {
                JsonArray ids = args.getAsJsonArray("itemIds");
                for (int i = 0; i < ids.size(); i++) {
                    targetItemIds.add(ids.get(i).getAsInt());
                }
            }
            if (args.has("itemNames")) {
                JsonArray names = args.getAsJsonArray("itemNames");
                for (int i = 0; i < names.size(); i++) {
                    String name = names.get(i).getAsString();
                    Integer foundId = ItemContainerUtils.findItemIdInContainers(client, itemManager, name);
                    if (foundId != null) {
                        targetItemIds.add(foundId);
                    } else {
                        JsonObject notFound = new JsonObject();
                        notFound.addProperty("error", "Item '" + name + "' not found in game database.");
                        itemsData.add(name, notFound);
                    }
                }
            }
        }

        for (int itemId : targetItemIds) {
            ItemComposition comp = null;
            try {
                comp = itemManager != null ? itemManager.getItemComposition(itemId) : null;
            } catch (Exception ignored) {
            }
            String itemName = (comp != null && comp.getName() != null) ? comp.getName() : "Item " + itemId;
            int gePrice = itemManager != null ? itemManager.getItemPrice(itemId) : 0;
            int haPrice = comp != null ? comp.getHaPrice() : 0;
            int lowAlchPrice = comp != null ? (int) Math.floor(haPrice * LOW_ALCH_MULTIPLIER) : 0;
            int alchProfit = (haPrice > 0 && gePrice > 0) ? (haPrice - (gePrice + natureRunePrice)) : 0;

            JsonObject itemObj = new JsonObject();
            itemObj.addProperty("itemId", itemId);
            itemObj.addProperty("itemName", itemName);
            itemObj.addProperty("gePrice", gePrice);
            itemObj.addProperty("highAlchValue", haPrice);
            itemObj.addProperty("lowAlchValue", lowAlchPrice);
            itemObj.addProperty("highAlchProfitPerItem", alchProfit);
            itemObj.addProperty("isAlchProfitable", alchProfit > 0);

            itemsData.add(itemName, itemObj);
        }

        result.add("items", itemsData);
        return gson.toJson(result);
    }
}
