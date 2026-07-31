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
        // The second call must return almost instantaneously from cache (< 50ms)
        Assert.assertTrue("Cached search should be under 50ms (uncached: " + duration1 + "ms, cached: " + duration2 + "ms)", duration2 < 50);
    }
}
