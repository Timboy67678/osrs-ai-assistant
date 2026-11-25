package com.osrsai;

import com.osrsai.util.PromptUtils;
import org.junit.Assert;
import org.junit.Test;

public class PromptUtilsTest {

    @Test
    public void testBuildSystemPromptIncludesGroundingRules() {
        String prompt = PromptUtils.buildSystemPrompt("Location Name: Grand Exchange", "You: Where am I?");

        Assert.assertTrue(prompt.contains("GROUNDING RULES"));
        Assert.assertTrue(prompt.contains("GAME CONTEXT:\nLocation Name: Grand Exchange"));
        Assert.assertTrue(prompt.contains("RECENT CONVERSATION:\nYou: Where am I?"));
        Assert.assertTrue(prompt.contains("search_osrs_wiki"));
        Assert.assertTrue(prompt.contains("availableSections"));
        Assert.assertTrue(prompt.contains("say you couldn't confirm it"));
        Assert.assertTrue(prompt.contains("status 'error' means the wiki couldn't be reached"));
        Assert.assertTrue(prompt.contains("'Source:' line"));
        Assert.assertTrue(prompt.contains("High Alchemy"));
        Assert.assertTrue(prompt.contains("xpToTargetLevel"));
        Assert.assertTrue(prompt.contains("poiName"));
    }

    @Test
    public void testInstructionsStayFreeOfHardcodedGameFacts() {
        // Hard-coded facts go stale and override the wiki (the old prompt called Bloodwood trees
        // RS3-only after they were added to OSRS). Keep game facts in the wiki, not the prompt.
        Assert.assertFalse(PromptUtils.INSTRUCTIONS.contains("Bloodwood"));
        Assert.assertFalse(PromptUtils.INSTRUCTIONS.contains("Slayer helmet"));
        Assert.assertFalse(PromptUtils.INSTRUCTIONS.contains("Anti-dragon shield"));
    }

    @Test
    public void testInstructionsStaySmallEnoughForLocalModels() {
        Assert.assertTrue("instructions are " + PromptUtils.INSTRUCTIONS.length() + " chars",
                PromptUtils.INSTRUCTIONS.length() < 7000);
    }

    @Test
    public void testTrimToPromptBudgetCapsLongText() {
        String text = "1234567890";
        String trimmed = PromptUtils.trimToPromptBudget(text, 8, "...[cut]");

        Assert.assertTrue(trimmed.endsWith("...[cut]"));
        Assert.assertTrue(trimmed.length() <= 8);
    }

    @Test
    public void testTrimToPromptBudgetNormalizesEmptyText() {
        Assert.assertEquals("None", PromptUtils.trimToPromptBudget("   ", 10, "...[cut]"));
        Assert.assertEquals("abc", PromptUtils.trimToPromptBudget("  abc  ", 10, "...[cut]"));
    }

    @Test
    public void testTruncateForNotification() {
        Assert.assertEquals("Short message", PromptUtils.truncateForNotification("Short message"));
        String longText = "This is a very long notification message that exceeds eighty characters and needs truncation.";
        String truncated = PromptUtils.truncateForNotification(longText);
        Assert.assertTrue(truncated.endsWith("..."));
        Assert.assertEquals(80, truncated.length());
    }
}
