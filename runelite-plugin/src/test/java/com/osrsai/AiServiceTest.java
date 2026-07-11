package com.osrsai;

import java.lang.reflect.Method;

import org.junit.Assert;
import org.junit.Test;

public class AiServiceTest
{
    @Test
    public void describeAccountTypeIncludesIronmanVariants() throws Exception
    {
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
    public void buildSystemPromptIncludesGroundingRules()
    {
        String prompt = AiService.buildSystemPrompt("Location Name: Grand Exchange", "You: Where am I?");

        Assert.assertTrue(prompt.contains("GROUNDING RULES"));
        Assert.assertTrue(prompt.contains("GAME CONTEXT"));
        Assert.assertTrue(prompt.contains("RECENT CONVERSATION"));
        Assert.assertTrue(prompt.contains("OSRS RuneLite assistant"));
        Assert.assertTrue(prompt.contains("Never invent stats"));
    }

    @Test
    public void trimToPromptBudgetCapsLongText()
    {
        String text = "1234567890";
        String trimmed = AiService.trimToPromptBudget(text, 8, "...[cut]");

        Assert.assertTrue(trimmed.endsWith("...[cut]"));
        Assert.assertTrue(trimmed.length() <= 8);
    }

    @Test
    public void trimToPromptBudgetNormalizesEmptyText()
    {
        Assert.assertEquals("None", AiService.trimToPromptBudget("   ", 10, "...[cut]"));
        Assert.assertEquals("abc", AiService.trimToPromptBudget("  abc  ", 10, "...[cut]"));
    }

    @Test
    public void extractSearchQueryCleansConversationalPrefixes()
    {
        Assert.assertEquals("super antipoison", AiService.extractSearchQuery("what are the ingredients for super antipoison?"));
        Assert.assertEquals("super antipoison", AiService.extractSearchQuery("how to make super antipoison"));
        Assert.assertEquals("grand exchange", AiService.extractSearchQuery("where is the grand exchange?"));
        Assert.assertEquals("king roald", AiService.extractSearchQuery("tell me about king roald"));
        Assert.assertEquals("guthix rest", AiService.extractSearchQuery("recipe for guthix rest"));
        Assert.assertEquals("hello", AiService.extractSearchQuery("hello"));
    }
}
