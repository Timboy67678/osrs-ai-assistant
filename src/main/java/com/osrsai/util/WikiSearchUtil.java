package com.osrsai.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrsai.util.WikiHtmlParser.WikiPage;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Looks up pages on the Old School RuneScape Wiki for the {@code search_osrs_wiki} tool.
 * <p>
 * A lookup resolves the query to a page (exact title first, then the wiki's search), parses it
 * with {@link WikiHtmlParser}, and returns either the whole page or, for long pages, the summary
 * plus a list of sections the model can request one at a time. Results always say how the page
 * was matched, and connection failures are reported as errors rather than as "not found", so the
 * model is never told that something doesn't exist just because the wiki was slow.
 */
@Slf4j
public final class WikiSearchUtil {

    /** Descriptive User-Agent, as the OSRS Wiki asks of automated clients. */
    public static final String OSRS_AI_USER_AGENT = "OSRS AI Assistant RuneLite Plugin - https://github.com/Timboy67678/osrs-ai-assistant";

    /** Endpoint URL for the Old School RuneScape Wiki MediaWiki API. */
    public static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";

    /** Base URL for linking to wiki articles. */
    private static final String WIKI_PAGE_BASE = "https://oldschool.runescape.wiki/w/";

    /** Licence of OSRS Wiki content, which requires attribution when reused. */
    public static final String WIKI_LICENSE = "CC BY-NC-SA 3.0 (OSRS Wiki)";

    /**
     * Most characters of page text returned by one lookup. Longer pages are returned a section at
     * a time, which keeps each tool result small enough for local models.
     */
    public static final int WIKI_EXTRACT_CHARS = 6000;

    /** Search results requested when no page has the exact title. */
    public static final int WIKI_SEARCH_SRLIMIT = 5;

    /** Sections added to a long page's summary when present and small enough. */
    private static final List<String> CORE_SECTIONS = List.of("Details", "Requirements", "Combat stats");

    /** Section names listed for a long page. */
    private static final int MAX_OUTLINE_ENTRIES = 60;

    private static final long CACHE_TTL_MILLIS = 30 * 60 * 1000L;
    private static final int PAGE_CACHE_MAX_ENTRIES = 64;
    private static final int RESOLVE_CACHE_MAX_ENTRIES = 500;

    private static final Pattern PATTERN_WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PATTERN_NON_ALPHA = Pattern.compile("[^a-z]+");

    // =========================================================================
    // Search Query Cleaning Constants
    // =========================================================================

    private static final String[] SEARCH_PREFIXES = {
            "what are the ingredients for",
            "what is the drop rate of",
            "what is the drop rate for",
            "what is the recipe for",
            "what are the stats for",
            "what are the stats of",
            "what is the stats for",
            "what is the stats of",
            "where can i find",
            "where can i buy",
            "where can i get",
            "where do i find",
            "where do i buy",
            "where do i get",
            "can you search for",
            "can you look up",
            "tell me about",
            "information on",
            "ingredients for",
            "how do i craft",
            "how do i make",
            "how do i brew",
            "how do i get",
            "recipe for",
            "search for",
            "how to craft",
            "how to make",
            "how to brew",
            "how to get",
            "look up",
            "where is",
            "where are",
            "what is",
            "what are",
            "info on",
            "lookup",
            "how to",
            "how do"
    };

    private static final String[] SEARCH_SUFFIXES = {
            " location coordinates",
            " buy shops locations",
            " shop locations osrs",
            " slayer points cost",
            " quest requirements",
            " skill requirements",
            " elemental weakness",
            " quest requirement",
            " skill requirement",
            " map coordinates",
            " shops locations",
            " spawn locations",
            " ingredients for",
            " spawn location",
            " shop locations",
            " shop location",
            " drop sources",
            " drop source",
            " map location",
            " slayer points",
            " requirements",
            " requirement",
            " coordinates",
            " ingredients",
            " points cost",
            " slayer point",
            " point cost",
            " drop rates",
            " drop table",
            " drop rate",
            " locations",
            " location",
            " weakness",
            " sources",
            " source",
            " recipe",
            " spawns",
            " coords",
            " shops",
            " spawn",
            " drops",
            " stats",
            " guide",
            " points",
            " costs",
            " price",
            " prices",
            " cost",
            " drop",
            " shop",
            " wiki",
            " osrs",
            " buy"
    };

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "from", "with", "that", "this", "how", "what",
            "where", "when", "can", "you", "are", "not", "osrs", "wiki",
            "get", "has", "its", "your", "their", "they", "have", "been");

    // =========================================================================
    // Caches
    // =========================================================================

    private static final class Timed<V> {
        final V value;
        final long at = System.currentTimeMillis();

        Timed(V value) {
            this.value = value;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - at > CACHE_TTL_MILLIS;
        }
    }

    private static <V> Map<String, Timed<V>> lruCache(int maxEntries) {
        return Collections.synchronizedMap(new LinkedHashMap<String, Timed<V>>(maxEntries, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Timed<V>> eldest) {
                return size() > maxEntries;
            }
        });
    }

    /** Parsed pages by lower-case title, so section requests for a page need no new request. */
    private static final Map<String, Timed<WikiPage>> PAGE_CACHE = lruCache(PAGE_CACHE_MAX_ENTRIES);

    /** How each query resolved: a page plus match details, or a no-match result. */
    private static final Map<String, Timed<Resolution>> RESOLVE_CACHE = lruCache(RESOLVE_CACHE_MAX_ENTRIES);

    private static final class Resolution {
        final WikiPage page;
        final String query;
        final boolean exactTitle;
        final List<String> otherMatches;
        final JsonObject failure;

        private Resolution(WikiPage page, String query, boolean exactTitle, List<String> otherMatches,
                JsonObject failure) {
            this.page = page;
            this.query = query;
            this.exactTitle = exactTitle;
            this.otherMatches = otherMatches;
            this.failure = failure;
        }

        static Resolution found(WikiPage page, String query, boolean exactTitle, List<String> others) {
            return new Resolution(page, query, exactTitle, others, null);
        }

        static Resolution failed(JsonObject failure) {
            return new Resolution(null, null, false, Collections.emptyList(), failure);
        }
    }

    private WikiSearchUtil() {
        // Utility class
    }

    /**
     * Clears all cached wiki pages and query resolutions.
     */
    public static void clearCache() {
        PAGE_CACHE.clear();
        RESOLVE_CACHE.clear();
    }

    // =========================================================================
    // Tool entry point
    // =========================================================================

    /**
     * Looks up a wiki page and returns its summary, or the whole page when it is short.
     *
     * @see #executeWikiSearch(OkHttpClient, Gson, String, String)
     */
    public static String executeWikiSearch(OkHttpClient wikiClient, Gson gson, String query) {
        return executeWikiSearch(wikiClient, gson, query, null);
    }

    /**
     * Looks up a wiki page for the {@code search_osrs_wiki} tool.
     *
     * @param wikiClient HTTP client for wiki requests
     * @param gson       JSON serialiser
     * @param query      page name or search terms
     * @param section    optional section to return (e.g. "Drops"); {@code null} for the summary
     * @return JSON with {@code status} {@code success}, {@code section_not_found}, {@code no_match},
     *         {@code not_found} or {@code error}
     */
    public static String executeWikiSearch(OkHttpClient wikiClient, Gson gson, String query, String section) {
        String q = query == null ? "" : PATTERN_WHITESPACE.matcher(query).replaceAll(" ").trim();
        if (q.endsWith("?")) {
            q = q.substring(0, q.length() - 1).trim();
        }
        if (q.isEmpty()) {
            return gson.toJson(status("error", "No search query was given. Pass the name of a page to look up."));
        }
        try {
            Resolution resolution = resolve(wikiClient, gson, q);
            if (resolution.page == null) {
                return gson.toJson(resolution.failure);
            }
            return gson.toJson(buildResult(resolution, section));
        } catch (IOException | RuntimeException e) {
            log.warn("OSRS Wiki lookup failed for '{}': {}", q, e.toString());
            return gson.toJson(status("error", "The OSRS Wiki could not be reached (" + e.getMessage()
                    + "). This is a connection problem, not evidence that the topic doesn't exist. Tell the player"
                    + " you couldn't check the wiki instead of guessing, or try the lookup again."));
        }
    }

    private static JsonObject status(String status, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("status", status);
        o.addProperty("message", message);
        return o;
    }

    private static Resolution resolve(OkHttpClient client, Gson gson, String query) throws IOException {
        String key = query.toLowerCase(Locale.ROOT);
        Timed<Resolution> cached = RESOLVE_CACHE.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }

        String cleaned = extractSearchQuery(query).trim();
        List<String> titlesToTry = new ArrayList<>();
        titlesToTry.add(query);
        if (!cleaned.isEmpty() && !cleaned.equalsIgnoreCase(query)) {
            titlesToTry.add(cleaned);
        }
        for (String title : titlesToTry) {
            WikiPage page = fetchPage(client, gson, title);
            if (page != null) {
                Resolution r = Resolution.found(page, query, true, Collections.emptyList());
                RESOLVE_CACHE.put(key, new Timed<>(r));
                return r;
            }
        }

        String searchTerms = cleaned.isEmpty() ? query : cleaned;
        List<String> hits = searchTitles(client, gson, searchTerms);
        boolean wantsLeague = query.toLowerCase(Locale.ROOT).contains("league");
        List<String> candidates = new ArrayList<>();
        for (String hit : hits) {
            if (wantsLeague || !hit.toLowerCase(Locale.ROOT).contains(" league")) {
                candidates.add(hit);
            }
        }

        Resolution result;
        if (candidates.isEmpty()) {
            result = Resolution.failed(status("not_found", "No OSRS Wiki page matched '" + query
                    + "'. Try the exact in-game name or different wording. Don't state facts about it that you"
                    + " can't verify. If it seems to be RuneScape 3 content, say so."));
        } else {
            String best = null;
            for (String c : candidates) {
                if (isTitleRelevantToQuery(c, query) || isTitleRelevantToQuery(c, searchTerms)) {
                    best = c;
                    break;
                }
            }
            WikiPage page = best == null ? null : fetchPage(client, gson, best);
            if (page == null) {
                JsonObject failure = status("no_match", "No OSRS Wiki page is titled '" + query
                        + "', and none of the search results look like a match. If one of 'searchResults' is what"
                        + " you meant, call search_osrs_wiki again with that exact title. Otherwise don't state facts"
                        + " about it that you can't verify.");
                JsonArray results = new JsonArray();
                for (String c : candidates) {
                    results.add(c);
                }
                failure.add("searchResults", results);
                result = Resolution.failed(failure);
            } else {
                List<String> others = new ArrayList<>(candidates);
                others.remove(best);
                result = Resolution.found(page, query, false, others);
            }
        }
        RESOLVE_CACHE.put(key, new Timed<>(result));
        return result;
    }

    private static JsonObject buildResult(Resolution resolution, String section) {
        WikiPage page = resolution.page;
        JsonObject o = new JsonObject();
        o.addProperty("status", "success");
        o.addProperty("title", page.title);
        o.addProperty("url", pageUrl(page.title));
        o.addProperty("license", WIKI_LICENSE);
        if (resolution.exactTitle && !sameTitle(resolution.query, page.title)
                && !sameTitle(extractSearchQuery(resolution.query), page.title)) {
            o.addProperty("redirectNote", "The wiki redirects '" + resolution.query + "' to '" + page.title
                    + "'. If that is not the same thing the player asked about (for example, a RuneScape 3 name),"
                    + " say so.");
        }
        if (!resolution.exactTitle) {
            o.addProperty("matchNote", "No page is titled exactly '" + resolution.query
                    + "'. This is the closest search result; check it is what you were looking for before using it.");
            if (!resolution.otherMatches.isEmpty()) {
                JsonArray others = new JsonArray();
                for (String t : resolution.otherMatches) {
                    others.add(t);
                }
                o.add("otherSearchResults", others);
            }
        }

        if (section == null || section.trim().isEmpty()) {
            String all = page.renderAll();
            if (all.length() <= WIKI_EXTRACT_CHARS) {
                o.addProperty("section", "(whole page)");
                o.addProperty("extract", all);
                return o;
            }
            o.addProperty("section", "(summary and infobox)");
            o.addProperty("extract", cap(summary(page)));
            o.add("availableSections", outline(page.outline()));
            o.addProperty("hint", "This page is long, so only its summary is shown. To read more, call"
                    + " search_osrs_wiki again with the same query and 'section' set to one of availableSections.");
            return o;
        }

        int index = page.findSection(section);
        if (index < 0) {
            o.addProperty("status", "section_not_found");
            o.addProperty("message", "This page has no section matching '" + section
                    + "'. Pick one of availableSections, or omit 'section' to get the page summary.");
            o.add("availableSections", outline(page.outline()));
            return o;
        }
        String text = page.renderSection(index);
        o.addProperty("section", page.sections.get(index).name);
        if (text.length() <= WIKI_EXTRACT_CHARS) {
            o.addProperty("extract", text);
            return o;
        }
        o.addProperty("extract", cap(text));
        o.addProperty("truncated", true);
        List<String> subsections = page.subsectionNames(index);
        if (!subsections.isEmpty()) {
            o.add("subsections", outline(subsections));
            o.addProperty("hint", "This section was cut off. Request one of 'subsections' by name to read the rest.");
        } else {
            o.addProperty("hint", "This section was cut off; the rest is on the wiki page at 'url'.");
        }
        return o;
    }

    /**
     * The page lead plus any core sections that hold the facts most questions need: a quest's
     * "Details" (requirements) and an item's "Combat stats" (equipment bonuses).
     */
    private static String summary(WikiPage page) {
        StringBuilder sb = new StringBuilder(page.renderLead());
        for (String core : CORE_SECTIONS) {
            int index = page.findTopLevel(core);
            if (index > 0) {
                String text = page.renderSection(index);
                if (sb.length() + text.length() + 2 <= WIKI_EXTRACT_CHARS) {
                    sb.append("\n\n").append(text);
                }
            }
        }
        return sb.toString();
    }

    private static boolean sameTitle(String a, String b) {
        return a != null && b != null
                && a.trim().replace('_', ' ').equalsIgnoreCase(b.trim().replace('_', ' '));
    }

    private static JsonArray outline(List<String> names) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < names.size() && i < MAX_OUTLINE_ENTRIES; i++) {
            arr.add(names.get(i));
        }
        if (names.size() > MAX_OUTLINE_ENTRIES) {
            arr.add("(" + (names.size() - MAX_OUTLINE_ENTRIES) + " more)");
        }
        return arr;
    }

    /** Cuts text to the extract budget at a line break, marking that it was cut. */
    static String cap(String text) {
        if (text.length() <= WIKI_EXTRACT_CHARS) {
            return text;
        }
        int cut = text.lastIndexOf('\n', WIKI_EXTRACT_CHARS);
        if (cut < WIKI_EXTRACT_CHARS / 2) {
            cut = WIKI_EXTRACT_CHARS;
        }
        return text.substring(0, cut).trim() + "\n...[cut off]";
    }

    // =========================================================================
    // HTTP
    // =========================================================================

    /**
     * Fetches and parses a page by title, following redirects.
     *
     * @return the page, or {@code null} if no page has that title
     * @throws IOException if the wiki could not be reached or returned an unexpected response
     */
    static WikiPage fetchPage(OkHttpClient client, Gson gson, String title) throws IOException {
        String key = title.trim().toLowerCase(Locale.ROOT);
        Timed<WikiPage> cached = PAGE_CACHE.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }

        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(WIKI_API)).newBuilder()
                .addQueryParameter("action", "parse")
                .addQueryParameter("page", title)
                .addQueryParameter("prop", "text")
                .addQueryParameter("redirects", "1")
                .addQueryParameter("disableeditsection", "1")
                .addQueryParameter("disabletoc", "1")
                .addQueryParameter("disablelimitreport", "1")
                .addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2")
                .build();
        JsonObject json = getJson(client, gson, url);
        if (json.has("error")) {
            String code = stringOrEmpty(json.getAsJsonObject("error"), "code");
            if ("missingtitle".equals(code) || "invalidtitle".equals(code)) {
                return null;
            }
            throw new IOException("wiki API error '" + code + "'");
        }
        JsonObject parse = json.getAsJsonObject("parse");
        if (parse == null || !parse.has("text")) {
            throw new IOException("unexpected wiki response");
        }
        String resolvedTitle = parse.has("title") ? parse.get("title").getAsString() : title;
        WikiPage page = WikiHtmlParser.parse(resolvedTitle, parse.get("text").getAsString());
        Timed<WikiPage> entry = new Timed<>(page);
        PAGE_CACHE.put(key, entry);
        PAGE_CACHE.put(resolvedTitle.toLowerCase(Locale.ROOT), entry);
        return page;
    }

    private static List<String> searchTitles(OkHttpClient client, Gson gson, String query) throws IOException {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(WIKI_API)).newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("list", "search")
                .addQueryParameter("srsearch", query)
                .addQueryParameter("srnamespace", "0")
                .addQueryParameter("srlimit", String.valueOf(WIKI_SEARCH_SRLIMIT))
                .addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2")
                .build();
        JsonObject json = getJson(client, gson, url);
        List<String> titles = new ArrayList<>();
        JsonObject q = json.getAsJsonObject("query");
        JsonArray results = q == null ? null : q.getAsJsonArray("search");
        if (results != null) {
            for (JsonElement e : results) {
                String t = stringOrEmpty(e.getAsJsonObject(), "title");
                if (!t.isEmpty()) {
                    titles.add(t);
                }
            }
        }
        return titles;
    }

    private static JsonObject getJson(OkHttpClient client, Gson gson, HttpUrl url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", OSRS_AI_USER_AGENT)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("empty response");
            }
            JsonObject json = gson.fromJson(body.string(), JsonObject.class);
            if (json == null) {
                throw new IOException("empty response");
            }
            return json;
        }
    }

    private static String stringOrEmpty(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    // =========================================================================
    // Query helpers
    // =========================================================================

    /**
     * Strips common conversational question prefixes and query suffixes to produce
     * a concise search term suitable for the Wiki API.
     *
     * @param question user prompt or tool search parameter
     * @return cleaned OSRS search term
     */
    public static String extractSearchQuery(String question) {
        if (question == null) {
            return "";
        }
        String q = question.trim().toLowerCase(Locale.ROOT);

        if (q.endsWith("?")) {
            q = q.substring(0, q.length() - 1).trim();
        }

        boolean prefixFound;
        do {
            prefixFound = false;
            for (String prefix : SEARCH_PREFIXES) {
                if (q.startsWith(prefix)) {
                    q = q.substring(prefix.length()).trim();
                    prefixFound = true;
                    break;
                }
            }
        } while (prefixFound);

        if (q.startsWith("the ")) {
            q = q.substring(4).trim();
        } else if (q.startsWith("a ")) {
            q = q.substring(2).trim();
        } else if (q.startsWith("an ")) {
            q = q.substring(3).trim();
        }

        boolean suffixFound;
        do {
            suffixFound = false;
            for (String suffix : SEARCH_SUFFIXES) {
                if (q.endsWith(suffix)) {
                    String trimmed = q.substring(0, q.length() - suffix.length()).trim();
                    if (!trimmed.isEmpty()) {
                        q = trimmed;
                        suffixFound = true;
                        break;
                    }
                }
            }
        } while (suffixFound);

        return q.isEmpty() ? question : q;
    }

    /**
     * Builds the public wiki URL for an article title, so answers can link back to their source.
     *
     * @param title article title
     * @return article URL
     */
    public static String pageUrl(String title) {
        return Objects.requireNonNull(HttpUrl.parse(WIKI_PAGE_BASE)).newBuilder()
                .addPathSegments(title.trim().replace(' ', '_'))
                .build()
                .toString();
    }

    /**
     * Checks whether a search result title plausibly names what the query asked for: a shared
     * significant word (ignoring plural "s"), or one appearing inside the other once spaces are
     * removed (so "super antipoison" matches "Superantipoison").
     *
     * @param title search result title
     * @param query search query
     * @return {@code true} if the title appears related to the query
     */
    static boolean isTitleRelevantToQuery(String title, String query) {
        if (title == null || query == null || title.isEmpty() || query.isEmpty()) {
            return false;
        }
        String titleLower = title.toLowerCase(Locale.ROOT);
        String queryLower = query.toLowerCase(Locale.ROOT);

        Set<String> titleWords = new HashSet<>();
        for (String t : PATTERN_NON_ALPHA.split(titleLower)) {
            if (t.length() >= 3 && !STOP_WORDS.contains(t)) {
                titleWords.add(singular(t));
            }
        }
        String titleCompact = PATTERN_NON_ALPHA.matcher(titleLower).replaceAll("");
        String queryCompact = PATTERN_NON_ALPHA.matcher(queryLower).replaceAll("");

        for (String q : PATTERN_NON_ALPHA.split(queryLower)) {
            if (q.length() < 3 || STOP_WORDS.contains(q)) {
                continue;
            }
            if (titleWords.contains(singular(q)) || q.length() >= 5 && titleCompact.contains(singular(q))) {
                return true;
            }
        }
        return titleCompact.length() >= 5 && queryCompact.contains(titleCompact);
    }

    private static String singular(String word) {
        return word.length() > 3 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
    }

    /**
     * Converts wiki page HTML to Markdown, keeping every section. Used by tests and tools that
     * want the whole page.
     *
     * @param title page title
     * @param html  HTML from the wiki's {@code action=parse} API
     * @return the page as Markdown
     */
    public static String parseWikiHtmlToMarkdown(String title, String html) {
        return WikiHtmlParser.parse(title, html).renderAll();
    }
}
