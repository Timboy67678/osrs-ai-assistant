package com.osrsai;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import org.junit.Assert;
import org.junit.Test;

public class WikiSearchUtilTest {

    @Test
    public void testExtractSearchQueryCleansConversationalPrefixes() {
        Assert.assertEquals("super antipoison",
                WikiSearchUtil.extractSearchQuery("what are the ingredients for super antipoison?"));
        Assert.assertEquals("super antipoison", WikiSearchUtil.extractSearchQuery("how to make super antipoison"));
        Assert.assertEquals("grand exchange", WikiSearchUtil.extractSearchQuery("where is the grand exchange?"));
        Assert.assertEquals("king roald", WikiSearchUtil.extractSearchQuery("tell me about king roald"));
        Assert.assertEquals("guthix rest", WikiSearchUtil.extractSearchQuery("recipe for guthix rest"));
        Assert.assertEquals("hello", WikiSearchUtil.extractSearchQuery("hello"));
    }

    @Test
    public void testExtractSearchQueryCleansConversationalSuffixes() {
        Assert.assertEquals("blood runes", WikiSearchUtil.extractSearchQuery("blood runes buy shops locations"));
        Assert.assertEquals("blood rune", WikiSearchUtil.extractSearchQuery("blood rune shop locations OSRS"));
        Assert.assertEquals("dagannoth", WikiSearchUtil.extractSearchQuery("Dagannoth elemental weakness"));
        Assert.assertEquals("blood runes", WikiSearchUtil.extractSearchQuery("where can i buy blood runes"));
        Assert.assertEquals("fire rune", WikiSearchUtil.extractSearchQuery("how to get a fire rune drop rate"));
        Assert.assertEquals("myths' guild", WikiSearchUtil.extractSearchQuery("Myths' Guild location coordinates"));
        Assert.assertEquals("dragon slayer ii", WikiSearchUtil.extractSearchQuery("Dragon Slayer II quest requirements"));
    }

    @Test
    public void testCleanWikitextStripsFormatting() {
        String input = "'''Dragon dagger''' is a [[weapon]]. {{Infobox Item|id=1234}} <!-- comment -->";
        String cleaned = WikiSearchUtil.cleanWikitext(input);
        Assert.assertTrue(cleaned.contains("**Dragon dagger**"));
        Assert.assertTrue(cleaned.contains("weapon"));
        Assert.assertTrue(cleaned.contains("Infobox Item"));
        Assert.assertTrue(cleaned.contains("id=1234"));
        Assert.assertFalse(cleaned.contains("comment"));
    }

    @Test
    public void testCleanWikitextStripsUselessSections() {
        String input = "A rune pouch stores runes.\n==Obtaining==\nBuy from Slayer master.\n==Changes==\nDate: 15 July 2026\n[ div col|colwidth=20em ]\nAdded Maggot King.\n[ div col end ]";
        String cleaned = WikiSearchUtil.cleanWikitext(input);
        Assert.assertTrue(cleaned.contains("A rune pouch stores runes"));
        Assert.assertTrue(cleaned.contains("Buy from Slayer master"));
        Assert.assertFalse(cleaned.contains("==Changes=="));
        Assert.assertFalse(cleaned.contains("Added Maggot King"));
        Assert.assertFalse(cleaned.contains("div col"));
    }

    @Test
    public void testParseWikiHtmlToMarkdownInfobox() {
        String html = "<div class=\"mw-parser-output\">"
                + "<table class=\"infobox\"><tr><th>Attack speed</th><td>2.4s (4 ticks)</td></tr><tr><th>High alch</th><td>72,000 coins</td></tr></table>"
                + "<p>The abyssal whip is a weapon.</p>"
                + "</div>";
        String markdown = WikiSearchUtil.parseWikiHtmlToMarkdown("Abyssal whip", html);
        Assert.assertTrue(markdown.contains("# Abyssal whip"));
        Assert.assertTrue(markdown.contains("- **Attack speed**: 2.4s (4 ticks)"));
        Assert.assertTrue(markdown.contains("- **High alch**: 72,000 coins"));
        Assert.assertTrue(markdown.contains("The abyssal whip is a weapon."));
    }

    @Test
    public void testParseWikiHtmlToMarkdownQuestDetails() {
        String html = "<div class=\"mw-parser-output\">"
                + "<table class=\"questdetails\"><tr><th>Start point</th><td>Alec Kincade outside Myths' Guild</td></tr>"
                + "<tr><th>Requirements</th><td>200 Quest Points</td></tr></table>"
                + "<p>Dragon Slayer II is a grandmaster quest.</p>"
                + "</div>";
        String markdown = WikiSearchUtil.parseWikiHtmlToMarkdown("Dragon Slayer II", html);
        Assert.assertTrue(markdown.contains("# Dragon Slayer II"));
        Assert.assertTrue(markdown.contains("- **Start point**: Alec Kincade outside Myths' Guild"));
        Assert.assertTrue(markdown.contains("- **Requirements**: 200 Quest Points"));
    }

    @Test
    public void testParseWikiHtmlToMarkdownDropTable() {
        String html = "<div class=\"mw-parser-output\">"
                + "<p>Abyssal demons drop rare items.</p>"
                + "<table class=\"wikitable\"><tr><th>Item</th><th>Quantity</th><th>Rarity</th></tr><tr><td>Abyssal whip</td><td>1</td><td>1/512</td></tr></table>"
                + "</div>";
        String markdown = WikiSearchUtil.parseWikiHtmlToMarkdown("Abyssal demon", html);
        Assert.assertTrue(markdown.contains("# Abyssal demon"));
        Assert.assertTrue(markdown.contains("| Item | Quantity | Rarity |"));
        Assert.assertTrue(markdown.contains("| Abyssal whip | 1 | 1/512 |"));
    }

    @Test
    public void testParseWikiHtmlToMarkdownStripsUselessSections() {
        String html = "<div class=\"mw-parser-output\">"
                + "<h2>Overview</h2><p>Abyssal demon details.</p>"
                + "<h2>Changes</h2><p>Added drop on 2005.</p>"
                + "</div>";
        String markdown = WikiSearchUtil.parseWikiHtmlToMarkdown("Abyssal demon", html);
        Assert.assertTrue(markdown.contains("Abyssal demon details."));
        Assert.assertFalse(markdown.contains("Added drop on 2005."));
    }

    @Test
    public void testWikiSearchIntegrationFallback() {
        WikiSearchUtil.clearCache();
        OkHttpClient client = new OkHttpClient();
        Gson gson = new Gson();
        String result = WikiSearchUtil.executeWikiSearch(client, gson, "Suqah teeth");
        Assert.assertNotNull(result);
        Assert.assertTrue(result.contains("Suqah") || result.contains("\"status\":\"not_found\"") || result.contains("\"status\":\"error\""));
    }

    @Test
    public void testWikiSearchCacheHits() {
        WikiSearchUtil.clearCache();
        OkHttpClient client = new OkHttpClient();
        Gson gson = new Gson();
        
        long start1 = System.currentTimeMillis();
        String res1 = WikiSearchUtil.executeWikiSearch(client, gson, "Abyssal whip");
        long duration1 = System.currentTimeMillis() - start1;

        long start2 = System.currentTimeMillis();
        String res2 = WikiSearchUtil.executeWikiSearch(client, gson, "Abyssal whip");
        long duration2 = System.currentTimeMillis() - start2;

        Assert.assertEquals(res1, res2);
        Assert.assertTrue("Cached search should be under 50ms (uncached: " + duration1 + "ms, cached: " + duration2 + "ms)", duration2 < 50);
    }
}
