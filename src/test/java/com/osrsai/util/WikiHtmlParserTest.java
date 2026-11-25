package com.osrsai.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.osrsai.util.WikiHtmlParser.WikiPage;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Tests for the wiki HTML parser and lookup flow, using HTML shaped like the OSRS Wiki's
 * {@code action=parse} output and a fake HTTP layer (no network needed).
 */
public class WikiHtmlParserTest {

    private final Gson gson = new Gson();

    @Before
    public void clearCache() {
        WikiSearchUtil.clearCache();
    }

    // =========================================================================
    // Parser
    // =========================================================================

    @Test
    public void infoboxIconOnlyStatHeadersAreLabelledFromAltAndTitle() {
        String html = "<table class=\"infobox infobox-switch infobox-monster\">"
                + "<tr><th class=\"infobox-header\">Abyssal demon</th></tr>"
                + "<tr><th>Combat level</th><td>124</td></tr>"
                + "<tr><td class=\"infobox-padding\"></td></tr>"
                + "<tr><th class=\"infobox-subheader\">Combat stats</th></tr>"
                + "<tr><th class=\"infobox-nested\"><a title=\"Hitpoints\"><img alt=\"Hitpoints icon.png\"></a></th>"
                + "<th class=\"infobox-nested\"><a title=\"Attack\"><img alt=\"Attack icon.png\"></a></th></tr>"
                + "<tr><td class=\"infobox-nested\">150</td><td class=\"infobox-nested\">97</td></tr>"
                + "<tr><th class=\"infobox-subheader\">Melee defence</th></tr>"
                + "<tr><th class=\"infobox-nested\"><img alt=\"Stab\"></th><th class=\"infobox-nested\"><img alt=\"Slash\"></th></tr>"
                + "<tr><td class=\"infobox-nested\">+20</td><td class=\"infobox-nested\">+20</td></tr>"
                + "</table>";
        String md = WikiHtmlParser.parse("Abyssal demon", html).renderAll();

        Assert.assertTrue(md, md.contains("- **Combat level**: 124"));
        Assert.assertTrue(md, md.contains("**Combat stats**"));
        Assert.assertTrue(md, md.contains("- Hitpoints: 150, Attack: 97"));
        Assert.assertTrue(md, md.contains("- Stab: +20, Slash: +20"));
        Assert.assertFalse("page title row should not repeat", md.contains("**Abyssal demon**"));
    }

    @Test
    public void hiddenSwitchDataAndWikiUiAreDropped() {
        String html = "<table class=\"infobox infobox-switch\"><caption class=\"infobox-switch-buttons-caption\">"
                + "<div class=\"infobox-buttons\"><span data-switch-index=\"1\">Standard</span>"
                + "<span data-switch-index=\"2\">Wilderness Slayer Cave</span></div></caption>"
                + "<tr><th>Max hit</th><td>8</td></tr></table>"
                + "<div class=\"infobox-switch-resources hidden\"><span data-attr-param=\"att\"><span>97&amp;&amp;SPLITPOINT&amp;&amp;98</span></span></div>"
                + "<div class=\"dps-calc\">Open in DPS calculator</div>"
                + "<span style=\"display: none\">secret</span>"
                + "<p>Visible text<sup class=\"reference\">[1]</sup>.</p>";
        String md = WikiHtmlParser.parse("Abyssal demon", html).renderAll();

        Assert.assertTrue(md, md.contains("- **Versions**: Standard, Wilderness Slayer Cave (values below are for Standard)"));
        Assert.assertTrue(md, md.contains("- **Max hit**: 8"));
        Assert.assertTrue(md, md.contains("Visible text."));
        Assert.assertFalse(md, md.contains("SPLITPOINT"));
        Assert.assertFalse(md, md.contains("DPS calculator"));
        Assert.assertFalse(md, md.contains("secret"));
        Assert.assertFalse(md, md.contains("[1]"));
    }

    @Test
    public void dropTableDropsImageColumnAndKeepsRates() {
        String html = "<table class=\"wikitable sortable item-drops\">"
                + "<tr><th class=\"drops-img-header\"></th><th>Item</th><th>Quantity</th><th>Rarity</th></tr>"
                + "<tr><td class=\"inventory-image\"><img alt=\"Abyssal whip.png: Abyssal demon drops Abyssal whip\"></td>"
                + "<td>Abyssal whip</td><td>1</td><td>1/512</td></tr>"
                + "<tr><td class=\"inventory-image\"><img alt=\"Abyssal dagger.png: drops\"></td>"
                + "<td>Abyssal dagger</td><td>1</td><td>1/32,000</td></tr>"
                + "</table>";
        String md = WikiHtmlParser.parse("Abyssal demon", html).renderAll();

        Assert.assertTrue(md, md.contains("| Item | Quantity | Rarity |\n| --- | --- | --- |\n| Abyssal whip | 1 | 1/512 |\n"
                + "| Abyssal dagger | 1 | 1/32,000 |"));
        Assert.assertFalse(md, md.contains(".png"));
    }

    @Test
    public void rowAndColumnSpansAreExpanded() {
        String html = "<table class=\"wikitable\">"
                + "<tr><th colspan=\"2\">Combat style</th><th>Attack speed</th></tr>"
                + "<tr><td>Flick</td><td>Slash</td><td rowspan=\"2\">4 ticks</td></tr>"
                + "<tr><td>Lash</td><td>Slash</td></tr>"
                + "</table>";
        String md = WikiHtmlParser.parse("Abyssal whip", html).renderAll();

        Assert.assertTrue(md, md.contains("| Flick | Slash | 4 ticks |"));
        Assert.assertTrue(md, md.contains("| Lash | Slash | 4 ticks |"));
    }

    @Test
    public void questRequirementListsKeepTheirNesting() {
        String html = "<table class=\"questdetails\">"
                + "<tr><th>Start point</th><td>Talk to Alec Kincade.</td></tr>"
                + "<tr><th>Requirements</th><td><ul><li>75 Magic</li><li>Completion of:"
                + "<ul><li>Legends' Quest<ul><li>Family Crest</li></ul></li></ul></li></ul></td></tr>"
                + "</table>";
        String md = WikiHtmlParser.parse("Dragon Slayer II", html).renderAll();

        Assert.assertTrue(md, md.contains("- **Start point**: Talk to Alec Kincade."));
        Assert.assertTrue(md, md.contains("- **Requirements**:\n  - 75 Magic\n  - Completion of:\n    - Legends' Quest\n"
                + "      - Family Crest"));
    }

    @Test
    public void pagesSplitIntoSectionsAndUselessSectionsAreDropped() {
        String html = "<p>Lead text.</p>"
                + "<div class=\"mw-heading mw-heading2\"><h2 id=\"Drops\">Drops</h2></div><p>Drop intro.</p>"
                + "<div class=\"mw-heading mw-heading3\"><h3>Standard</h3></div>"
                + "<div class=\"mw-heading mw-heading4\"><h4>Weapons and armour</h4></div><p>Standard weapons.</p>"
                + "<div class=\"mw-heading mw-heading3\"><h3>Wilderness Slayer Cave</h3></div>"
                + "<div class=\"mw-heading mw-heading4\"><h4>Weapons and armour</h4></div><p>Wilderness weapons.</p>"
                + "<div class=\"mw-heading mw-heading4\"><h4>Shark drop table</h4></div><p>Sharks.</p>"
                + "<h2>Changes</h2><p>Changed in 2005.</p><h3>Older</h3><p>Even older.</p>"
                + "<h2>Strategy</h2><p>Pray melee.</p>";
        WikiPage page = WikiHtmlParser.parse("Abyssal demon", html);

        Assert.assertEquals("# Abyssal demon\n\nLead text.", page.renderLead());
        String all = page.renderAll();
        Assert.assertFalse(all, all.contains("Changed in 2005"));
        Assert.assertFalse(all, all.contains("Even older"));
        Assert.assertTrue(all, all.contains("## Strategy\n\nPray melee."));

        Assert.assertTrue(page.outline().contains("Wilderness Slayer Cave > Weapons and armour"));
        Assert.assertEquals("Drops", page.sections.get(page.findSection("drop table")).name);
        Assert.assertEquals("Drops", page.sections.get(page.findSection("drops")).name);
        int wild = page.findSection("Wilderness Slayer Cave > Weapons and armour");
        Assert.assertTrue(page.renderSection(wild).contains("Wilderness weapons."));
        Assert.assertTrue(page.renderSection(page.findSection("Drops")).contains("Sharks."));
        Assert.assertEquals(-1, page.findSection("Rewards"));
    }

    @Test
    public void entitiesAndMessageBoxesRenderAsPlainText() {
        String html = "<table class=\"messagebox\"><tr><td>This article has a <a>strategy guide</a>.</td></tr></table>"
                + "<p>Slayer Tower (2<sup>nd</sup> floor&#93;) &ndash; A&nbsp;B &amp; C&#8203;</p>";
        String md = WikiHtmlParser.parse("Abyssal demon", html).renderAll();

        Assert.assertTrue(md, md.contains("This article has a strategy guide."));
        Assert.assertFalse(md, md.contains("| --- |"));
        Assert.assertTrue(md, md.contains("Slayer Tower (2nd floor]) – A B & C"));
    }

    // =========================================================================
    // Lookup flow (fake HTTP)
    // =========================================================================

    private static OkHttpClient fakeWiki(Function<Request, String> responder) {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            String body = responder.apply(chain.request());
            if (body == null) {
                throw new IOException("timeout");
            }
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(MediaType.parse("application/json"), body))
                    .build();
        }).build();
    }

    private static String parseResponse(String title, String html) {
        JsonObject parse = new JsonObject();
        parse.addProperty("title", title);
        parse.addProperty("text", html);
        JsonObject root = new JsonObject();
        root.add("parse", parse);
        return root.toString();
    }

    private static final String MISSING = "{\"error\":{\"code\":\"missingtitle\"}}";

    @Test
    public void connectionFailuresAreErrorsNotMissingPages() {
        OkHttpClient client = fakeWiki(r -> null);
        JsonObject result = gson.fromJson(WikiSearchUtil.executeWikiSearch(client, gson, "Abyssal whip"),
                JsonObject.class);

        Assert.assertEquals("error", result.get("status").getAsString());
        Assert.assertTrue(result.get("message").getAsString().contains("not evidence that the topic doesn't exist"));
    }

    @Test
    public void irrelevantSearchResultsAreOfferedNotReturned() {
        OkHttpClient client = fakeWiki(r -> "parse".equals(r.url().queryParameter("action")) ? MISSING
                : "{\"query\":{\"search\":[{\"title\":\"Lumbridge\"},{\"title\":\"Varrock\"}]}}");
        JsonObject result = gson.fromJson(WikiSearchUtil.executeWikiSearch(client, gson, "Bloodwood axe"),
                JsonObject.class);

        Assert.assertEquals("no_match", result.get("status").getAsString());
        Assert.assertEquals(2, result.getAsJsonArray("searchResults").size());
        Assert.assertFalse(result.has("extract"));
    }

    @Test
    public void longPagesReturnSummaryAndSectionsServeFromCache() {
        StringBuilder drops = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            drops.append("<p>Drop line ").append(i).append(" with padding text.</p>");
        }
        String html = "<table class=\"infobox\"><tr><th>Combat level</th><td>732</td></tr></table><p>Lead.</p>"
                + "<h2>Drops</h2>" + drops + "<h2>Strategy</h2><p>Walk.</p>";
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient client = fakeWiki(r -> {
            requests.incrementAndGet();
            return parseResponse("Vorkath", html);
        });

        JsonObject summary = gson.fromJson(WikiSearchUtil.executeWikiSearch(client, gson, "vorkath"), JsonObject.class);
        Assert.assertEquals("success", summary.get("status").getAsString());
        Assert.assertEquals("(summary and infobox)", summary.get("section").getAsString());
        Assert.assertTrue(summary.get("extract").getAsString().contains("- **Combat level**: 732"));
        Assert.assertTrue(summary.getAsJsonArray("availableSections").toString().contains("Drops"));
        Assert.assertEquals("https://oldschool.runescape.wiki/w/Vorkath", summary.get("url").getAsString());

        JsonObject section = gson.fromJson(WikiSearchUtil.executeWikiSearch(client, gson, "vorkath", "strategy"),
                JsonObject.class);
        Assert.assertEquals("Strategy", section.get("section").getAsString());
        Assert.assertTrue(section.get("extract").getAsString().contains("Walk."));

        JsonObject longSection = gson.fromJson(WikiSearchUtil.executeWikiSearch(client, gson, "vorkath", "Drops"),
                JsonObject.class);
        Assert.assertTrue(longSection.get("truncated").getAsBoolean());
        Assert.assertTrue(longSection.get("extract").getAsString().length() <= WikiSearchUtil.WIKI_SECTION_CHARS + 20);

        JsonObject missing = gson.fromJson(WikiSearchUtil.executeWikiSearch(client, gson, "vorkath", "Rewards"),
                JsonObject.class);
        Assert.assertEquals("section_not_found", missing.get("status").getAsString());

        Assert.assertEquals("one HTTP request for all four lookups", 1, requests.get());
    }

    @Test
    public void longSectionsAreCutBetweenSubsectionsAndNameWhatWasLeftOut() {
        StringBuilder html = new StringBuilder("<p>Lead.</p><h2>Drops</h2><p>Two rolls.</p>");
        for (String sub : new String[] { "Weapons", "Runes", "Seeds", "Tertiary" }) {
            html.append("<h3>").append(sub).append("</h3>");
            for (int i = 0; i < 100; i++) {
                html.append("<p>").append(sub).append(" drop ").append(i).append(" at 1/100.</p>");
            }
        }
        OkHttpClient client = fakeWiki(r -> parseResponse("Vorkath", html.toString()));
        JsonObject result = gson.fromJson(WikiSearchUtil.executeWikiSearch(client, gson, "Vorkath", "Drops"),
                JsonObject.class);

        String extract = result.get("extract").getAsString();
        Assert.assertTrue(extract.contains("Two rolls."));
        Assert.assertTrue(extract.contains("### Weapons"));
        Assert.assertTrue(extract.length() <= WikiSearchUtil.WIKI_SECTION_CHARS);
        Assert.assertFalse("no subsection is cut in half", extract.contains("...[cut off]"));
        String notIncluded = result.getAsJsonArray("notIncluded").toString();
        Assert.assertTrue(notIncluded, notIncluded.contains("Drops > Tertiary"));
        Assert.assertTrue(result.get("hint").getAsString().contains("section='"));
    }

    @Test
    public void dropTablesLeaveOutPriceColumns() {
        String html = "<table class=\"wikitable item-drops\"><tr><th></th><th>Item</th><th>Quantity</th><th>Rarity</th>"
                + "<th>Price</th><th>High Alch</th></tr>"
                + "<tr><td></td><td>Vorkath's head</td><td>1</td><td>1/50</td><td>4,000</td><td>100</td></tr></table>";
        String md = WikiHtmlParser.parse("Vorkath", html).renderAll();

        Assert.assertTrue(md, md.contains("| Item | Quantity | Rarity |"));
        Assert.assertTrue(md, md.contains("| Vorkath's head | 1 | 1/50 |"));
        Assert.assertFalse(md, md.contains("4,000"));
    }

    @Test
    public void redirectsAreFlagged() {
        OkHttpClient client = fakeWiki(r -> parseResponse("Dragon bolts", "<p>Dragon bolts are bolts.</p>"));
        JsonObject result = gson.fromJson(WikiSearchUtil.executeWikiSearch(client, gson, "Bakriminel bolts"),
                JsonObject.class);

        Assert.assertEquals("Dragon bolts", result.get("title").getAsString());
        Assert.assertTrue(result.get("redirectNote").getAsString().contains("redirects 'Bakriminel bolts' to 'Dragon bolts'"));
    }

    @Test
    public void titleRelevanceHandlesPluralsAndJoinedWords() {
        Assert.assertTrue(WikiSearchUtil.isTitleRelevantToQuery("Superantipoison", "super antipoison"));
        Assert.assertTrue(WikiSearchUtil.isTitleRelevantToQuery("Abyssal demon", "abyssal demons"));
        Assert.assertFalse(WikiSearchUtil.isTitleRelevantToQuery("Lumbridge", "bloodwood axe"));
    }
}
