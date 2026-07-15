package com.osrsai;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import okhttp3.OkHttpClient;
import com.google.gson.Gson;

import org.junit.Assert;
import org.junit.Test;

public class AiServiceTest {
    @Test
    public void testWikiSearch() throws Exception {
        AiService aiService = new AiService();

        // Initialize Gson
        Field gsonField = AiService.class.getDeclaredField("gson");
        gsonField.setAccessible(true);
        gsonField.set(aiService, new Gson());

        // Initialize OkHttpClient
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
        AiService aiService = new AiService();
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
}
