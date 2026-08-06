package com.osrsai;

import org.junit.Assert;
import org.junit.Test;

public class PromptUtilsTest {

    @Test
    public void testDescribeAccountTypeIncludesIronmanVariants() {
        Assert.assertEquals("Ironman", PromptUtils.describeAccountType(1));
        Assert.assertEquals("Ultimate Ironman (UIM)", PromptUtils.describeAccountType(2));
        Assert.assertEquals("Hardcore Ironman (HCIM)", PromptUtils.describeAccountType(3));
        Assert.assertEquals("Group Ironman (GIM)", PromptUtils.describeAccountType(4));
        Assert.assertEquals("Hardcore Group Ironman (HGIM)", PromptUtils.describeAccountType(5));
        Assert.assertEquals("Unranked Group Ironman (UGIM)", PromptUtils.describeAccountType(6));
        Assert.assertEquals("Normal", PromptUtils.describeAccountType(0));
        Assert.assertEquals("Unknown", PromptUtils.describeAccountType(null));
    }

    @Test
    public void testBuildSystemPromptIncludesGroundingRules() {
        String prompt = PromptUtils.buildSystemPrompt("Location Name: Grand Exchange", "You: Where am I?");

        Assert.assertTrue(prompt.contains("GROUNDING RULES"));
        Assert.assertTrue(prompt.contains("GAME CONTEXT"));
        Assert.assertTrue(prompt.contains("RECENT CONVERSATION"));
        Assert.assertTrue(prompt.contains("OSRS RuneLite assistant"));
        Assert.assertTrue(prompt.contains("Never invent stats"));
        Assert.assertTrue(prompt.contains("High Alchemy value"));
        Assert.assertTrue(prompt.contains("active spellbook"));
        Assert.assertTrue(prompt.contains("search_osrs_wiki"));
        Assert.assertTrue(prompt.contains("Distinguish general readiness"));
        Assert.assertTrue(prompt.contains("get_player_skills"));
        Assert.assertTrue(prompt.contains("RS3 Bloodwood trees"));
        Assert.assertTrue(prompt.contains("NOT present in OSRS"));
        Assert.assertTrue(prompt.contains("xpToTargetLevel"));
        Assert.assertTrue(prompt.contains("get_player_transportation"));
        Assert.assertTrue(prompt.contains("ITEM SPAWNS & SPATIAL VERIFICATION"));
        Assert.assertTrue(prompt.contains("GEAR COMPARISONS & ITEM STATS"));
        Assert.assertTrue(prompt.contains("MONSTER BEHAVIOR & DUNGEON MECHANICS"));
        Assert.assertTrue(prompt.contains("USER ALTERNATIVE QUESTIONS & COUNTER-CLAIMS"));
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
    public void testDescribeSpellbookIncludesAllSpellbooks() {
        Assert.assertEquals("Standard", PromptUtils.describeSpellbook(0));
        Assert.assertEquals("Ancient Magicks", PromptUtils.describeSpellbook(1));
        Assert.assertEquals("Lunar", PromptUtils.describeSpellbook(2));
        Assert.assertEquals("Arceuus", PromptUtils.describeSpellbook(3));
        Assert.assertEquals("Unknown (4)", PromptUtils.describeSpellbook(4));
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
