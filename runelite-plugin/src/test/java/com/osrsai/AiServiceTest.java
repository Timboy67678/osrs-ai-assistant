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
        Assert.assertTrue(prompt.contains("do not know"));
        Assert.assertTrue(prompt.contains("GAME CONTEXT"));
        Assert.assertTrue(prompt.contains("RECENT CONVERSATION"));
        Assert.assertTrue(prompt.contains("Account Type and Account Guidance"));
        Assert.assertTrue(prompt.contains("Not shared"));
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
    public void describeAccountGuidanceIncludesIronmanRestrictions() throws Exception
    {
        AiService aiService = new AiService();
        Method describeAccountGuidance = AiService.class.getDeclaredMethod("describeAccountGuidance", Integer.class);
        describeAccountGuidance.setAccessible(true);

        Assert.assertTrue(((String) describeAccountGuidance.invoke(aiService, 1)).contains("No trading"));
        Assert.assertTrue(((String) describeAccountGuidance.invoke(aiService, 2)).contains("no bank storage"));
        Assert.assertTrue(((String) describeAccountGuidance.invoke(aiService, 4)).contains("within the group"));
        Assert.assertTrue(((String) describeAccountGuidance.invoke(aiService, 5)).contains("hardcore status"));
    }
}
