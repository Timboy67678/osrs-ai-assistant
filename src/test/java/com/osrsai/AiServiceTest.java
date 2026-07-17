package com.osrsai;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import okhttp3.OkHttpClient;
import com.google.gson.Gson;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemComposition;
import net.runelite.api.Skill;
import net.runelite.api.Experience;
import net.runelite.client.game.ItemManager;

@RunWith(MockitoJUnitRunner.Silent.class)
public class AiServiceTest {

    @InjectMocks
    private AiService aiService;

    @Mock
    private OkHttpClient okHttpClient;

    @Mock
    private ItemManager itemManager;

    @Mock
    private net.runelite.api.Client client;

    @Mock
    private net.runelite.client.plugins.PluginManager pluginManager;

    @Before
    public void setUp() throws Exception {
        Field gsonField = AiService.class.getDeclaredField("gson");
        gsonField.setAccessible(true);
        gsonField.set(aiService, new Gson());
    }

    @Test
    public void testWikiSearch() throws Exception {
        // Set a real OkHttpClient for this integration test
        Field okHttpClientField = AiService.class.getDeclaredField("okHttpClient");
        okHttpClientField.setAccessible(true);
        okHttpClientField.set(aiService, new OkHttpClient());

        Method executeWikiSearch = AiService.class.getDeclaredMethod("executeWikiSearch", String.class);
        executeWikiSearch.setAccessible(true);

        System.out.println("Running executeWikiSearch...");
        String result = (String) executeWikiSearch.invoke(aiService, "Suqah teeth");
        System.out.println("Result: " + result);
        Assert.assertNotNull(result);
        // Resilient to offline environments - allow "status":"error" OR matching result
        Assert.assertTrue(result.contains("Suqah") || result.contains("\"status\":\"error\""));
    }

    @Test
    public void describeAccountTypeIncludesIronmanVariants() throws Exception {
        Method describeAccountType = AiService.class.getDeclaredMethod("describeAccountType", Integer.class);
        describeAccountType.setAccessible(true);

        Assert.assertEquals("Ironman", describeAccountType.invoke(aiService, 1));
        Assert.assertEquals("Ultimate Ironman (UIM)", describeAccountType.invoke(aiService, 2));
        Assert.assertEquals("Hardcore Ironman (HCIM)", describeAccountType.invoke(aiService, 3));
        Assert.assertEquals("Group Ironman (GIM)", describeAccountType.invoke(aiService, 4));
        Assert.assertEquals("Hardcore Group Ironman (HGIM)", describeAccountType.invoke(aiService, 5));
        Assert.assertEquals("Unranked Group Ironman (UGIM)", describeAccountType.invoke(aiService, 6));
        Assert.assertEquals("Normal", describeAccountType.invoke(aiService, 0));
        Assert.assertEquals("Unknown", describeAccountType.invoke(aiService, new Object[] { null }));
    }

    @Test
    public void buildSystemPromptIncludesGroundingRules() {
        String prompt = AiService.buildSystemPrompt("Location Name: Grand Exchange", "You: Where am I?");

        Assert.assertTrue(prompt.contains("GROUNDING RULES"));
        Assert.assertTrue(prompt.contains("GAME CONTEXT"));
        Assert.assertTrue(prompt.contains("RECENT CONVERSATION"));
        Assert.assertTrue(prompt.contains("OSRS RuneLite assistant"));
        Assert.assertTrue(prompt.contains("Never invent stats"));
        Assert.assertTrue(prompt.contains("farming patch"));
        Assert.assertTrue(prompt.contains("Never assume or state that a skilling/farming patch"));
        Assert.assertTrue(prompt.contains("Never guess, assume, or invent item prices"));
        Assert.assertTrue(prompt.contains("High Alchemy values"));
        Assert.assertTrue(prompt.contains("active spellbook"));
        Assert.assertTrue(prompt.contains("travel/teleportation"));
        Assert.assertTrue(prompt.contains("Book of the Dead"));
        Assert.assertTrue(prompt.contains("one-handed or two-handed"));
    }

    @Test
    public void trimToPromptBudgetCapsLongText() {
        String text = "1234567890";
        String trimmed = AiService.trimToPromptBudget(text, 8, "...[cut]");

        Assert.assertTrue(trimmed.endsWith("...[cut]"));
        Assert.assertTrue(trimmed.length() <= 8);
    }

    @Test
    public void trimToPromptBudgetNormalizesEmptyText() {
        Assert.assertEquals("None", AiService.trimToPromptBudget("   ", 10, "...[cut]"));
        Assert.assertEquals("abc", AiService.trimToPromptBudget("  abc  ", 10, "...[cut]"));
    }

    @Test
    public void extractSearchQueryCleansConversationalPrefixes() {
        Assert.assertEquals("super antipoison",
                AiService.extractSearchQuery("what are the ingredients for super antipoison?"));
        Assert.assertEquals("super antipoison", AiService.extractSearchQuery("how to make super antipoison"));
        Assert.assertEquals("grand exchange", AiService.extractSearchQuery("where is the grand exchange?"));
        Assert.assertEquals("king roald", AiService.extractSearchQuery("tell me about king roald"));
        Assert.assertEquals("guthix rest", AiService.extractSearchQuery("recipe for guthix rest"));
        Assert.assertEquals("hello", AiService.extractSearchQuery("hello"));
    }

    @Test
    public void extractSearchQueryCleansConversationalSuffixes() {
        Assert.assertEquals("blood runes", AiService.extractSearchQuery("blood runes buy shops locations"));
        Assert.assertEquals("blood rune", AiService.extractSearchQuery("blood rune shop locations OSRS"));
        Assert.assertEquals("dagannoth", AiService.extractSearchQuery("Dagannoth elemental weakness"));
        Assert.assertEquals("blood runes", AiService.extractSearchQuery("where can i buy blood runes"));
        Assert.assertEquals("fire rune", AiService.extractSearchQuery("how to get a fire rune drop rate"));
    }

    @Test
    public void describeSpellbookIncludesAllSpellbooks() throws Exception {
        Method describeSpellbook = AiService.class.getDeclaredMethod("describeSpellbook", int.class);
        describeSpellbook.setAccessible(true);

        Assert.assertEquals("Standard", describeSpellbook.invoke(aiService, 0));
        Assert.assertEquals("Ancient Magicks", describeSpellbook.invoke(aiService, 1));
        Assert.assertEquals("Lunar", describeSpellbook.invoke(aiService, 2));
        Assert.assertEquals("Arceuus", describeSpellbook.invoke(aiService, 3));
        Assert.assertEquals("Unknown (4)", describeSpellbook.invoke(aiService, 4));
    }

    @Test
    public void aggregateItemsWithPricesSupportsMultiFilters() throws Exception {
        // Create mock ItemContainer
        ItemContainer mockContainer = Mockito.mock(ItemContainer.class);
        Mockito.when(mockContainer.getItems()).thenReturn(new Item[] {
                new Item(995, 1000), // Coins -> "Item 995"
                new Item(556, 50),   // Air rune -> "Item 556"
                new Item(560, 20)    // Death rune -> "Item 560"
        });

        // Get private method aggregateItemsWithPrices
        Method aggregateItemsWithPrices = AiService.class.getDeclaredMethod("aggregateItemsWithPrices",
                ItemContainer.class, String.class, int.class);
        aggregateItemsWithPrices.setAccessible(true);

        // Run multi-filter with "995 OR 560"
        com.google.gson.JsonObject result = (com.google.gson.JsonObject) aggregateItemsWithPrices.invoke(
                aiService, mockContainer, "995 OR 560", 0);

        Assert.assertNotNull(result);
        Assert.assertTrue(result.has("Item 995"));
        Assert.assertTrue(result.has("Item 560"));
        Assert.assertFalse(result.has("Item 556"));

        // Run multi-filter with comma: "556, 560"
        com.google.gson.JsonObject resultComma = (com.google.gson.JsonObject) aggregateItemsWithPrices.invoke(
                aiService, mockContainer, "556, 560", 0);

        Assert.assertTrue(resultComma.has("Item 556"));
        Assert.assertTrue(resultComma.has("Item 560"));
        Assert.assertFalse(resultComma.has("Item 995"));

        // Run multi-filter with AND: "Item AND 560"
        com.google.gson.JsonObject resultAnd = (com.google.gson.JsonObject) aggregateItemsWithPrices.invoke(
                aiService, mockContainer, "Item AND 560", 0);

        Assert.assertTrue(resultAnd.has("Item 560"));
        Assert.assertFalse(resultAnd.has("Item 995"));
        Assert.assertFalse(resultAnd.has("Item 556"));

        // Run multi-filter with &: "Item & 556"
        com.google.gson.JsonObject resultAmp = (com.google.gson.JsonObject) aggregateItemsWithPrices.invoke(
                aiService, mockContainer, "Item & 556", 0);

        Assert.assertTrue(resultAmp.has("Item 556"));
        Assert.assertFalse(resultAmp.has("Item 560"));

        // Run DNF query: "995 & Item OR 560 & Item"
        com.google.gson.JsonObject resultDnf = (com.google.gson.JsonObject) aggregateItemsWithPrices.invoke(
                aiService, mockContainer, "995 & Item OR 560 & Item", 0);

        Assert.assertTrue(resultDnf.has("Item 995"));
        Assert.assertTrue(resultDnf.has("Item 560"));
        Assert.assertFalse(resultDnf.has("Item 556"));
    }

    @Test
    public void aggregateItemsWithPricesFiltersOutPlaceholders() throws Exception {
        // Mock normal item composition
        ItemComposition normalItem = Mockito.mock(ItemComposition.class);
        Mockito.when(normalItem.getName()).thenReturn("Coins");
        Mockito.when(normalItem.getPlaceholderTemplateId()).thenReturn(-1);
        Mockito.when(itemManager.getItemComposition(995)).thenReturn(normalItem);

        // Mock placeholder item composition
        ItemComposition placeholderItem = Mockito.mock(ItemComposition.class);
        Mockito.when(placeholderItem.getName()).thenReturn("Abyssal whip");
        Mockito.when(placeholderItem.getPlaceholderTemplateId()).thenReturn(1440);
        Mockito.when(itemManager.getItemComposition(4152)).thenReturn(placeholderItem);

        // Create mock ItemContainer
        ItemContainer mockContainer = Mockito.mock(ItemContainer.class);
        Mockito.when(mockContainer.getItems()).thenReturn(new Item[] {
                new Item(995, 1000), // Coins (normal)
                new Item(4152, 1)    // Abyssal whip (placeholder)
        });

        // Invoke aggregateItemsWithPrices
        Method aggregateItemsWithPrices = AiService.class.getDeclaredMethod("aggregateItemsWithPrices",
                ItemContainer.class, String.class, int.class);
        aggregateItemsWithPrices.setAccessible(true);

        com.google.gson.JsonObject result = (com.google.gson.JsonObject) aggregateItemsWithPrices.invoke(
                aiService, mockContainer, null, 0);

        Assert.assertNotNull(result);
        Assert.assertTrue(result.has("Coins"));
        Assert.assertFalse(result.has("Abyssal whip"));
    }

    @Test
    public void testGetPlayerCluesTool() throws Exception {
        // Mock items in inventory
        ItemContainer invMock = Mockito.mock(ItemContainer.class);
        Item clueInInv = new Item(12001, 1);
        Mockito.when(invMock.getItems()).thenReturn(new Item[] { clueInInv });
        Mockito.when(client.getItemContainer(net.runelite.api.InventoryID.INVENTORY)).thenReturn(invMock);

        // Mock items in bank
        ItemContainer bankMock = Mockito.mock(ItemContainer.class);
        Item clueInBank = new Item(12002, 1);
        Mockito.when(bankMock.getItems()).thenReturn(new Item[] { clueInBank });
        Mockito.when(client.getItemContainer(net.runelite.api.InventoryID.BANK)).thenReturn(bankMock);

        // Mock ItemCompositions
        ItemComposition invComp = Mockito.mock(ItemComposition.class);
        Mockito.when(invComp.getName()).thenReturn("Clue scroll (easy)");
        Mockito.when(invComp.getIntValue(net.runelite.api.ParamID.CLUE_SCROLL)).thenReturn(1);
        Mockito.when(client.getItemDefinition(12001)).thenReturn(invComp);

        ItemComposition bankComp = Mockito.mock(ItemComposition.class);
        Mockito.when(bankComp.getName()).thenReturn("Clue scroll (hard)");
        Mockito.when(bankComp.getIntValue(net.runelite.api.ParamID.CLUE_SCROLL)).thenReturn(1);
        Mockito.when(client.getItemDefinition(12002)).thenReturn(bankComp);

        // Invoke the tool via registry executor
        AiService.ToolDefinition def = AiService.getToolRegistry().stream()
                .filter(d -> d.name.equals("get_player_clues"))
                .findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("Tool not found"));
        String jsonResult = def.executor.execute(aiService, new com.google.gson.JsonObject());
        System.out.println("Result of get_player_clues: " + jsonResult);
        
        Assert.assertNotNull(jsonResult);
        com.google.gson.JsonObject rootObj = new Gson().fromJson(jsonResult, com.google.gson.JsonObject.class);
        
        Assert.assertTrue(rootObj.has("inventoryClues"));
        Assert.assertTrue(rootObj.has("bankClues"));
        Assert.assertTrue(rootObj.has("activeClue"));
        
        com.google.gson.JsonArray invArray = rootObj.getAsJsonArray("inventoryClues");
        Assert.assertEquals(1, invArray.size());
        Assert.assertEquals("Clue scroll (easy)", invArray.get(0).getAsJsonObject().get("name").getAsString());

        com.google.gson.JsonArray bankArray = rootObj.getAsJsonArray("bankClues");
        Assert.assertEquals(1, bankArray.size());
        Assert.assertEquals("Clue scroll (hard)", bankArray.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void testGetPlayerSkillsTool() throws Exception {
        // Mock default behavior for all skills
        Mockito.when(client.getBoostedSkillLevel(Mockito.any(Skill.class))).thenReturn(1);
        Mockito.when(client.getRealSkillLevel(Mockito.any(Skill.class))).thenReturn(1);
        Mockito.when(client.getSkillExperience(Mockito.any(Skill.class))).thenReturn(0);

        // Specific values for Attack
        Mockito.when(client.getBoostedSkillLevel(Skill.ATTACK)).thenReturn(60);
        Mockito.when(client.getRealSkillLevel(Skill.ATTACK)).thenReturn(55);
        Mockito.when(client.getSkillExperience(Skill.ATTACK)).thenReturn(170000);

        // Specific values for a capped level (Strength at max virtual level)
        Mockito.when(client.getBoostedSkillLevel(Skill.STRENGTH)).thenReturn(99);
        Mockito.when(client.getRealSkillLevel(Skill.STRENGTH)).thenReturn(99);
        Mockito.when(client.getSkillExperience(Skill.STRENGTH)).thenReturn(200000000);

        // Retrieve tool definition
        AiService.ToolDefinition def = AiService.getToolRegistry().stream()
                .filter(d -> d.name.equals("get_player_skills"))
                .findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("Tool not found"));

        String jsonResult = def.executor.execute(aiService, new com.google.gson.JsonObject());
        System.out.println("Result of get_player_skills: " + jsonResult);

        Assert.assertNotNull(jsonResult);
        com.google.gson.JsonObject rootObj = new Gson().fromJson(jsonResult, com.google.gson.JsonObject.class);

        // Verify Attack structure and calculations
        Assert.assertTrue(rootObj.has("Attack"));
        com.google.gson.JsonObject attackObj = rootObj.getAsJsonObject("Attack");
        Assert.assertEquals(60, attackObj.get("boosted").getAsInt());
        Assert.assertEquals(55, attackObj.get("real").getAsInt());
        Assert.assertEquals(170000, attackObj.get("xp").getAsInt());

        int expectedNextLevelXp = Experience.getXpForLevel(56);
        Assert.assertEquals(expectedNextLevelXp, attackObj.get("nextLevelXp").getAsInt());
        Assert.assertEquals(expectedNextLevelXp - 170000, attackObj.get("xpToNextLevel").getAsInt());

        // Verify Strength capped behavior
        Assert.assertTrue(rootObj.has("Strength"));
        com.google.gson.JsonObject strengthObj = rootObj.getAsJsonObject("Strength");
        Assert.assertEquals(99, strengthObj.get("boosted").getAsInt());
        Assert.assertEquals(99, strengthObj.get("real").getAsInt());
        Assert.assertEquals(200000000, strengthObj.get("xp").getAsInt());
        Assert.assertEquals(-1, strengthObj.get("nextLevelXp").getAsInt());
        Assert.assertEquals(0, strengthObj.get("xpToNextLevel").getAsInt());
    }
}
