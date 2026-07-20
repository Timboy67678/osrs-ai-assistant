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
        Assert.assertFalse(cleaned.contains("Infobox Item"));
        Assert.assertFalse(cleaned.contains("comment"));
    }

    @Test
    public void testWikiSearchIntegrationFallback() {
        OkHttpClient client = new OkHttpClient();
        Gson gson = new Gson();
        String result = WikiSearchUtil.executeWikiSearch(client, gson, "Suqah teeth");
        Assert.assertNotNull(result);
        Assert.assertTrue(result.contains("Suqah") || result.contains("\"status\":\"error\""));
    }
}
