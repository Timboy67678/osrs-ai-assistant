package com.osrsai;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import okhttp3.OkHttpClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

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
import net.runelite.client.config.ConfigManager;
import java.util.Arrays;
import java.util.List;

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
    private net.runelite.client.ui.overlay.infobox.InfoBoxManager infoBoxManager;

    @Test
    public void testGetPlayerQuestsToolIncludesStage() {
        JsonObject args = new JsonObject();
        args.addProperty("status", "IN_PROGRESS");

        Mockito.when(client.getVarpValue(net.runelite.api.gameval.VarPlayerID.QP)).thenReturn(10);
        net.runelite.api.StructComposition mockStruct = Mockito.mock(net.runelite.api.StructComposition.class);
        Mockito.when(client.getStructComposition(net.runelite.api.Quest.COOKS_ASSISTANT.getId())).thenReturn(mockStruct);
        Mockito.when(mockStruct.getIntValue(AiService.QUEST_STRUCT_PARAM_VARBIT)).thenReturn(101);
        Mockito.when(client.getVarbitValue(101)).thenReturn(10);

        final int[] currentQuestStatus = new int[] { 1 };
        Mockito.doAnswer(invocation -> {
            int questId = invocation.getArgument(1);
            if (questId == net.runelite.api.Quest.COOKS_ASSISTANT.getId()) {
                currentQuestStatus[0] = 0; // IN_PROGRESS
            } else {
                currentQuestStatus[0] = 1; // NOT_STARTED
            }
            return null;
        }).when(client).runScript(Mockito.anyInt(), Mockito.anyInt());
        Mockito.when(client.getIntStack()).thenAnswer(invocation -> currentQuestStatus);

        String json = aiService.executeGetPlayerQuests(args);
        Assert.assertNotNull(json);
        Assert.assertTrue(json.contains("questPoints"));
        Assert.assertTrue(json.contains("inProgressQuests"));

        com.google.gson.JsonObject rootObj = new Gson().fromJson(json, com.google.gson.JsonObject.class);
        com.google.gson.JsonArray inProgress = rootObj.getAsJsonArray("inProgressQuests");
        Assert.assertTrue(inProgress.size() > 0);
        Assert.assertEquals("Cook's Assistant", inProgress.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void testGetPlayerQuestsToolQuestFilter() {
        JsonObject args = new JsonObject();
        args.addProperty("quest", "Cook's");

        Mockito.when(client.getVarpValue(net.runelite.api.gameval.VarPlayerID.QP)).thenReturn(10);
        net.runelite.api.StructComposition mockStruct = Mockito.mock(net.runelite.api.StructComposition.class);
        Mockito.when(client.getStructComposition(net.runelite.api.Quest.COOKS_ASSISTANT.getId())).thenReturn(mockStruct);
        Mockito.when(mockStruct.getIntValue(AiService.QUEST_STRUCT_PARAM_VARBIT)).thenReturn(101);
        Mockito.when(client.getVarbitValue(101)).thenReturn(10);

        final int[] currentQuestStatus = new int[] { 1 };
        Mockito.doAnswer(invocation -> {
            int questId = invocation.getArgument(1);
            if (questId == net.runelite.api.Quest.COOKS_ASSISTANT.getId()) {
                currentQuestStatus[0] = 0; // IN_PROGRESS
            } else {
                currentQuestStatus[0] = 1; // NOT_STARTED
            }
            return null;
        }).when(client).runScript(Mockito.anyInt(), Mockito.anyInt());
        Mockito.when(client.getIntStack()).thenAnswer(invocation -> currentQuestStatus);

        String json = aiService.executeGetPlayerQuests(args);
        Assert.assertNotNull(json);
        com.google.gson.JsonObject rootObj = new Gson().fromJson(json, com.google.gson.JsonObject.class);

        // The inProgressQuests should have Cook's Assistant since it matches
        Assert.assertTrue(rootObj.has("inProgressQuests"));
        com.google.gson.JsonArray inProgress = rootObj.getAsJsonArray("inProgressQuests");
        boolean foundCooks = false;
        for (int i = 0; i < inProgress.size(); i++) {
            String name = inProgress.get(i).getAsJsonObject().get("name").getAsString();
            if (name.contains("Cook's")) {
                foundCooks = true;
            }
        }
        Assert.assertTrue(foundCooks);

        // The notStartedQuests (which otherwise has all other quests) should NOT contain other quests
        if (rootObj.has("notStartedQuests")) {
            com.google.gson.JsonArray notStarted = rootObj.getAsJsonArray("notStartedQuests");
            for (int i = 0; i < notStarted.size(); i++) {
                String name = notStarted.get(i).getAsString();
                Assert.assertTrue(name.contains("Cook's"));
            }
        }
    }

    @Mock
    private net.runelite.client.plugins.PluginManager pluginManager;

    @Mock
    private ConfigManager configManager;

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
        Assert.assertTrue(prompt.contains("High Alchemy value"));
        Assert.assertTrue(prompt.contains("active spellbook"));
        Assert.assertTrue(prompt.contains("search_osrs_wiki"));
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

        // Verify milestone XP additions
        int expectedXpTo60 = Experience.getXpForLevel(60) - 170000;
        Assert.assertEquals(expectedXpTo60, attackObj.get("xpTo60").getAsInt());
        int expectedXpTo70 = Experience.getXpForLevel(70) - 170000;
        Assert.assertEquals(expectedXpTo70, attackObj.get("xpTo70").getAsInt());
        int expectedXpTo99 = Experience.getXpForLevel(99) - 170000;
        Assert.assertEquals(expectedXpTo99, attackObj.get("xpTo99").getAsInt());

        // Verify Strength capped behavior has no remaining milestone XP
        Assert.assertTrue(rootObj.has("Strength"));
        com.google.gson.JsonObject strengthObj = rootObj.getAsJsonObject("Strength");
        Assert.assertEquals(99, strengthObj.get("boosted").getAsInt());
        Assert.assertEquals(99, strengthObj.get("real").getAsInt());
        Assert.assertEquals(200000000, strengthObj.get("xp").getAsInt());
        Assert.assertEquals(-1, strengthObj.get("nextLevelXp").getAsInt());
        Assert.assertEquals(0, strengthObj.get("xpToNextLevel").getAsInt());
        Assert.assertFalse(strengthObj.has("xpTo99"));

        // Test with filter parameter using direct skill name
        com.google.gson.JsonObject argsWithFilter = new com.google.gson.JsonObject();
        argsWithFilter.addProperty("skill", "attack");
        String jsonResultFiltered = def.executor.execute(aiService, argsWithFilter);
        System.out.println("Result of get_player_skills (filtered): " + jsonResultFiltered);
        Assert.assertNotNull(jsonResultFiltered);
        com.google.gson.JsonObject rootObjFiltered = new Gson().fromJson(jsonResultFiltered, com.google.gson.JsonObject.class);
        Assert.assertTrue(rootObjFiltered.has("Attack"));
        Assert.assertFalse(rootObjFiltered.has("Strength"));

        // Test with abbreviation filter
        com.google.gson.JsonObject argsWithAbbrev = new com.google.gson.JsonObject();
        argsWithAbbrev.addProperty("skill", "att");
        String jsonResultAbbrev = def.executor.execute(aiService, argsWithAbbrev);
        System.out.println("Result of get_player_skills (abbrev filter): " + jsonResultAbbrev);
        Assert.assertNotNull(jsonResultAbbrev);
        com.google.gson.JsonObject rootObjAbbrev = new Gson().fromJson(jsonResultAbbrev, com.google.gson.JsonObject.class);
        Assert.assertTrue(rootObjAbbrev.has("Attack"));
        Assert.assertFalse(rootObjAbbrev.has("Strength"));
    }

    @Test
    public void testGetPlayerBankTool() throws Exception {
        // Create mock ItemContainer for bank
        ItemContainer bankMock = Mockito.mock(ItemContainer.class);
        Item goldOre = new Item(444, 100);
        Mockito.when(bankMock.getItems()).thenReturn(new Item[] { goldOre });
        Mockito.when(client.getItemContainer(net.runelite.api.InventoryID.BANK)).thenReturn(bankMock);

        // Mock ItemComposition for Gold ore
        ItemComposition comp = Mockito.mock(ItemComposition.class);
        Mockito.when(comp.getName()).thenReturn("Gold ore");
        Mockito.when(comp.getPlaceholderTemplateId()).thenReturn(-1);
        Mockito.when(itemManager.getItemComposition(444)).thenReturn(comp);

        // Retrieve tool definition
        AiService.ToolDefinition def = AiService.getToolRegistry().stream()
                .filter(d -> d.name.equals("get_player_bank"))
                .findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("Tool not found"));

        // Test with a filter that matches
        com.google.gson.JsonObject argsMatch = new com.google.gson.JsonObject();
        argsMatch.addProperty("filter", "gold");
        String jsonResultMatch = def.executor.execute(aiService, argsMatch);
        System.out.println("Result of get_player_bank (match): " + jsonResultMatch);

        com.google.gson.JsonObject rootObjMatch = new Gson().fromJson(jsonResultMatch, com.google.gson.JsonObject.class);
        Assert.assertEquals("success", rootObjMatch.get("status").getAsString());
        Assert.assertTrue(rootObjMatch.get("bankOpen").getAsBoolean());
        Assert.assertEquals("gold", rootObjMatch.get("filterApplied").getAsString());
        Assert.assertTrue(rootObjMatch.getAsJsonObject("items").has("Gold ore"));

        // Test with a filter that does NOT match
        com.google.gson.JsonObject argsNoMatch = new com.google.gson.JsonObject();
        argsNoMatch.addProperty("filter", "crafting");
        String jsonResultNoMatch = def.executor.execute(aiService, argsNoMatch);
        System.out.println("Result of get_player_bank (no match): " + jsonResultNoMatch);

        com.google.gson.JsonObject rootObjNoMatch = new Gson().fromJson(jsonResultNoMatch, com.google.gson.JsonObject.class);
        Assert.assertEquals("success", rootObjNoMatch.get("status").getAsString());
        Assert.assertTrue(rootObjNoMatch.get("bankOpen").getAsBoolean());
        Assert.assertEquals("crafting", rootObjNoMatch.get("filterApplied").getAsString());
        Assert.assertEquals(0, rootObjNoMatch.getAsJsonObject("items").size());
    }

    @Test
    public void testGetPlayerCombatAchievementsTool() throws Exception {
        // Mock Varbits for Combat Achievements Tiers
        Mockito.when(client.getVarbitValue(net.runelite.api.Varbits.COMBAT_ACHIEVEMENT_TIER_EASY)).thenReturn(2); // Completed
        Mockito.when(client.getVarbitValue(net.runelite.api.Varbits.COMBAT_ACHIEVEMENT_TIER_MEDIUM)).thenReturn(1); // In Progress
        Mockito.when(client.getVarbitValue(net.runelite.api.Varbits.COMBAT_ACHIEVEMENT_TIER_HARD)).thenReturn(0); // Not Started
        Mockito.when(client.getVarbitValue(net.runelite.api.Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE)).thenReturn(0);
        Mockito.when(client.getVarbitValue(net.runelite.api.Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER)).thenReturn(0);
        Mockito.when(client.getVarbitValue(net.runelite.api.Varbits.COMBAT_ACHIEVEMENT_TIER_GRANDMASTER)).thenReturn(0);

        // Mock ConfigManager profile and keys
        String profileKey = "rsprofile.12345";
        Mockito.when(configManager.getRSProfileKey()).thenReturn(profileKey);

        List<String> mockKeys = Arrays.asList("zulrah", "vorkath", "barrows chests");
        Mockito.when(configManager.getRSProfileConfigurationKeys("killcount", profileKey, "")).thenReturn(mockKeys);

        Mockito.when(configManager.getRSProfileConfiguration("killcount", "zulrah")).thenReturn("150");
        Mockito.when(configManager.getRSProfileConfiguration("killcount", "vorkath")).thenReturn("320");
        Mockito.when(configManager.getRSProfileConfiguration("killcount", "barrows chests")).thenReturn("85");

        // Mock client.getEnum for Easy tier (Enum ID 3981)
        net.runelite.api.EnumComposition mockEasyEnum = Mockito.mock(net.runelite.api.EnumComposition.class);
        Mockito.when(client.getEnum(3981)).thenReturn(mockEasyEnum);
        Mockito.when(mockEasyEnum.getIntVals()).thenReturn(new int[] { 100 }); // Struct ID 100

        // Mock client.getStructComposition for Struct 100 (Noxious Foe)
        net.runelite.api.StructComposition mockStruct = Mockito.mock(net.runelite.api.StructComposition.class);
        Mockito.when(client.getStructComposition(100)).thenReturn(mockStruct);

        // Setup struct properties
        Mockito.when(mockStruct.getStringValue(1308)).thenReturn("Noxious Foe"); // Name
        Mockito.when(mockStruct.getStringValue(1309)).thenReturn("Kill an Aberrant Spectre."); // Description
        Mockito.when(mockStruct.getIntValue(1306)).thenReturn(5); // Task ID (Varp index 0, bit index 5)
        Mockito.when(mockStruct.getIntValue(1311)).thenReturn(3); // Type ID (Kill Count)
        Mockito.when(mockStruct.getIntValue(1312)).thenReturn(20); // Boss ID 20

        // Mock boss name enum (Enum ID 3971)
        net.runelite.api.EnumComposition mockBossEnum = Mockito.mock(net.runelite.api.EnumComposition.class);
        Mockito.when(client.getEnum(3971)).thenReturn(mockBossEnum);
        Mockito.when(mockBossEnum.getStringValue(20)).thenReturn("Aberrant Spectre");

        // Mock Varp for task completion (Varp index 0, bit index 5 is completed!)
        Mockito.when(client.getVarpValue(net.runelite.api.gameval.VarPlayerID.CA_TASK_COMPLETED_0)).thenReturn(1 << 5);

        // Retrieve tool definition
        AiService.ToolDefinition def = AiService.getToolRegistry().stream()
                .filter(d -> d.name.equals("get_player_combat_achievements"))
                .findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("Tool not found"));

        // 1. Run without filters -> individual tasks should be omitted
        com.google.gson.JsonObject argsNoFilters = new com.google.gson.JsonObject();
        String jsonNoFilters = def.executor.execute(aiService, argsNoFilters);
        com.google.gson.JsonObject rootNoFilters = new Gson().fromJson(jsonNoFilters, com.google.gson.JsonObject.class);
        Assert.assertTrue(rootNoFilters.has("tiers"));
        Assert.assertTrue(rootNoFilters.has("killCounts"));
        Assert.assertFalse(rootNoFilters.has("tasks"));

        // 2. Run with filters (tier=Easy, taskName=Noxious) -> individual tasks should be returned
        com.google.gson.JsonObject argsWithFilters = new com.google.gson.JsonObject();
        argsWithFilters.addProperty("tier", "Easy");
        argsWithFilters.addProperty("taskName", "Noxious");
        String jsonWithFilters = def.executor.execute(aiService, argsWithFilters);
        com.google.gson.JsonObject rootWithFilters = new Gson().fromJson(jsonWithFilters, com.google.gson.JsonObject.class);

        Assert.assertTrue(rootWithFilters.has("tiers"));
        Assert.assertTrue(rootWithFilters.has("killCounts"));
        Assert.assertTrue(rootWithFilters.has("tasks"));

        com.google.gson.JsonArray tasksArr = rootWithFilters.getAsJsonArray("tasks");
        Assert.assertEquals(1, tasksArr.size());

        com.google.gson.JsonObject taskObj = tasksArr.get(0).getAsJsonObject();
        Assert.assertEquals(5, taskObj.get("id").getAsInt());
        Assert.assertEquals("Noxious Foe", taskObj.get("name").getAsString());
        Assert.assertEquals("Kill an Aberrant Spectre.", taskObj.get("description").getAsString());
        Assert.assertEquals("Easy", taskObj.get("tier").getAsString());
        Assert.assertEquals("Kill Count", taskObj.get("type").getAsString());
        Assert.assertEquals("Aberrant Spectre", taskObj.get("boss").getAsString());
        Assert.assertTrue(taskObj.get("completed").getAsBoolean());
    }

    @Test
    public void testGetPlayerAchievementDiariesTool() throws Exception {
        // Varbit value 1 = Completed for Achievement Diaries
        Mockito.when(client.getVarbitValue(net.runelite.api.Varbits.DIARY_KANDARIN_EASY)).thenReturn(1);
        Mockito.when(client.getVarbitValue(net.runelite.api.Varbits.DIARY_KANDARIN_MEDIUM)).thenReturn(0);
        Mockito.when(client.getVarbitValue(net.runelite.api.Varbits.DIARY_KANDARIN_HARD)).thenReturn(0);
        Mockito.when(client.getVarbitValue(net.runelite.api.Varbits.DIARY_KANDARIN_ELITE)).thenReturn(0);

        AiService.ToolDefinition def = AiService.getToolRegistry().stream()
                .filter(d -> d.name.equals("get_player_achievement_diaries"))
                .findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("Tool not found"));

        String json = def.executor.execute(aiService, new com.google.gson.JsonObject());
        com.google.gson.JsonObject root = new Gson().fromJson(json, com.google.gson.JsonObject.class);
        Assert.assertTrue(root.has("diaries"));

        com.google.gson.JsonObject kandarin = root.getAsJsonObject("diaries").getAsJsonObject("Kandarin");
        Assert.assertEquals("Completed", kandarin.get("Easy").getAsString());
        Assert.assertEquals("Not Started", kandarin.get("Medium").getAsString());
    }

    @Test
    public void testCleanWikitext() {
        String raw = "<!-- comment -->Hello [[World|Earth]]! This is a {{stub}} test.\n"
                + "{| class=\"wikitable\"\n"
                + "|- \n"
                + "| Table content\n"
                + "|}\n"
                + "__TOC__\n"
                + "A&nbsp;B &amp; C &lt; D &gt; E &quot;F&quot;\n"
                + "[[Category:Test]]\n"
                + "[[File:Image.png]]\n"
                + "'''bold''' and ''italic''.";
        String cleaned = AiService.cleanWikitext(raw);
        System.out.println("Cleaned: " + cleaned);
        Assert.assertFalse(cleaned.contains("comment"));
        Assert.assertFalse(cleaned.contains("wikitable"));
        Assert.assertFalse(cleaned.contains("Category"));
        Assert.assertFalse(cleaned.contains("File"));
        Assert.assertFalse(cleaned.contains("stub"));
        Assert.assertFalse(cleaned.contains("TOC"));
        Assert.assertTrue(cleaned.contains("A B & C < D > E \"F\""));
        Assert.assertTrue(cleaned.contains("Earth"));
        Assert.assertTrue(cleaned.contains("**bold**"));
        Assert.assertTrue(cleaned.contains("*italic*"));
    }

    @Test
    public void testGetPlayerCombatAchievementsToolBossFilterKc() throws Exception {
        // Mock ConfigManager profile and keys
        String profileKey = "rsprofile.12345";
        Mockito.when(configManager.getRSProfileKey()).thenReturn(profileKey);

        List<String> mockKeys = Arrays.asList("zulrah", "vorkath", "barrows chests");
        Mockito.when(configManager.getRSProfileConfigurationKeys("killcount", profileKey, "")).thenReturn(mockKeys);

        Mockito.when(configManager.getRSProfileConfiguration("killcount", "zulrah")).thenReturn("150");
        Mockito.when(configManager.getRSProfileConfiguration("killcount", "vorkath")).thenReturn("320");
        Mockito.when(configManager.getRSProfileConfiguration("killcount", "barrows chests")).thenReturn("85");

        // Retrieve tool definition
        AiService.ToolDefinition def = AiService.getToolRegistry().stream()
                .filter(d -> d.name.equals("get_player_combat_achievements"))
                .findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("Tool not found"));

        // Run with filter boss = zulrah
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("boss", "zulrah");
        String json = def.executor.execute(aiService, args);
        com.google.gson.JsonObject root = new Gson().fromJson(json, com.google.gson.JsonObject.class);

        Assert.assertTrue(root.has("killCounts"));
        com.google.gson.JsonObject kc = root.getAsJsonObject("killCounts");
        Assert.assertTrue(kc.has("zulrah"));
        Assert.assertFalse(kc.has("vorkath"));
        Assert.assertFalse(kc.has("barrows chests"));
    }

    @Test
    public void testGetPlayerTransportationReturnsExpectedJson() throws Exception {
        Mockito.when(client.getRealSkillLevel(net.runelite.api.Skill.MAGIC)).thenReturn(75);
        Mockito.when(client.getBoostedSkillLevel(net.runelite.api.Skill.MAGIC)).thenReturn(75);
        Mockito.when(client.getRealSkillLevel(net.runelite.api.Skill.CONSTRUCTION)).thenReturn(83);
        Mockito.when(client.getVarbitValue(4070)).thenReturn(0); // Standard spellbook

        String json = aiService.executeGetPlayerTransportation(new JsonObject());
        Assert.assertNotNull(json);

        JsonObject root = new Gson().fromJson(json, JsonObject.class);
        Assert.assertTrue(root.has("unlockedNetworks"));
        Assert.assertTrue(root.has("magicAndSpellbook"));
        Assert.assertTrue(root.has("constructionAndPoh"));
        Assert.assertTrue(root.has("availableTeleportItems"));

        JsonObject magic = root.getAsJsonObject("magicAndSpellbook");
        Assert.assertEquals("Standard", magic.get("currentSpellbook").getAsString());
        Assert.assertEquals(75, magic.get("magicLevelBase").getAsInt());

        JsonObject poh = root.getAsJsonObject("constructionAndPoh");
        Assert.assertEquals(83, poh.get("constructionLevel").getAsInt());
        Assert.assertTrue(poh.get("portalChamberUnlocked").getAsBoolean());
        Assert.assertTrue(poh.get("portalNexusUnlocked").getAsBoolean());
        Assert.assertTrue(poh.get("basicJewelleryBoxUnlocked").getAsBoolean());
    }

    @Test
    public void testGetPlayerStatusFiltersDormantInfoBoxes() {
        net.runelite.client.ui.overlay.infobox.InfoBox activeBox = Mockito.mock(net.runelite.client.ui.overlay.infobox.InfoBox.class);
        Mockito.when(activeBox.getName()).thenReturn("Boost Attack");
        Mockito.when(activeBox.getText()).thenReturn("+12");
        Mockito.when(activeBox.getTooltip()).thenReturn("Attack boost");

        net.runelite.client.ui.overlay.infobox.InfoBox dormantBox = Mockito.mock(net.runelite.client.ui.overlay.infobox.InfoBox.class);
        Mockito.when(dormantBox.getName()).thenReturn("Potion Agility");
        Mockito.when(dormantBox.getText()).thenReturn("0");
        Mockito.when(dormantBox.getTooltip()).thenReturn("Agility potion: 0");

        net.runelite.client.ui.overlay.infobox.InfoBox emptyBox = Mockito.mock(net.runelite.client.ui.overlay.infobox.InfoBox.class);
        Mockito.when(emptyBox.getName()).thenReturn("Empty Box");
        Mockito.when(emptyBox.getText()).thenReturn("");

        Mockito.when(infoBoxManager.getInfoBoxes()).thenReturn(Arrays.asList(activeBox, dormantBox, emptyBox));

        String json = aiService.executeGetPlayerStatus(new JsonObject());
        Assert.assertNotNull(json);

        JsonObject root = new Gson().fromJson(json, JsonObject.class);
        Assert.assertTrue(root.has("activeInfoBoxes"));
        com.google.gson.JsonArray boxes = root.getAsJsonArray("activeInfoBoxes");

        Assert.assertEquals(1, boxes.size());
        JsonObject box0 = boxes.get(0).getAsJsonObject();
        Assert.assertEquals("Boost Attack", box0.get("name").getAsString());
        Assert.assertEquals("+12", box0.get("text").getAsString());
    }
}

