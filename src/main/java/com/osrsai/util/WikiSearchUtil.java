package com.osrsai.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for searching and parsing the Old School RuneScape Wiki via
 * MediaWiki API.
 * <p>
 * Cleans natural language search queries, performs API requests, caches query
 * results,
 * strips MediaWiki HTML noise, and converts article HTML into structured
 * Markdown extracts.
 */
@Slf4j
public class WikiSearchUtil {
    /**
     * Custom User-Agent header string required by Old School RuneScape Wiki API
     * guidelines.
     */
    public static final String OSRS_AI_USER_AGENT = "OSRS AI Assistant RuneLite Plugin - https://github.com/Timboy67678/osrs-ai-assistant";

    /** Endpoint URL for the Old School RuneScape Wiki MediaWiki API. */
    public static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";

    /** Maximum nested template cleaning iterations. */
    public static final int MAX_TEMPLATE_REMOVALS = 5;

    /**
     * Maximum character limit for wiki article extracts passed to the AI prompt
     * context.
     */
    public static final int WIKI_EXTRACT_CHARS = 8000;

    /**
     * Maximum number of data rows to include when formatting wikitables/drop tables
     * into Markdown.
     */
    public static final int MAX_WIKITABLE_DATA_ROWS = 35;

    /**
     * Total table row threshold (including header) for formatting truncation
     * notice.
     */
    public static final int MAX_WIKITABLE_TOTAL_ROWS_THRESHOLD = 36;

    /** Maximum search candidates to retrieve from MediaWiki search API. */
    public static final int WIKI_SEARCH_SRLIMIT = 5;

    /** Cache TTL in seconds (30 minutes). Stale entries are silently re-fetched. */
    private static final long CACHE_TTL_SECONDS = 30 * 60;

    /** Maximum number of entries retained in the synchronized search cache. */
    private static final int CACHE_MAX_ENTRIES = 500;

    /**
     * Immutable wrapper pairing a wiki result JSON string with the epoch-second
     * timestamp at which it was cached, used to enforce the {@link #CACHE_TTL_SECONDS}
     * expiry policy.
     */
    private static final class CacheEntry {
        final String result;
        final long cachedAt;

        CacheEntry(String result) {
            this.result = result;
            this.cachedAt = System.currentTimeMillis() / 1000L;
        }

        boolean isExpired() {
            return (System.currentTimeMillis() / 1000L) - cachedAt > CACHE_TTL_SECONDS;
        }
    }

    /**
     * LRU cache storing recent wiki query results to prevent redundant HTTP network
     * calls. Values are wrapped in {@link CacheEntry} to support TTL expiry.
     */
    private static final Map<String, CacheEntry> SEARCH_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(CACHE_MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            });

    private static final Pattern PATTERN_COMMENTS = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern PATTERN_MAGIC = Pattern.compile("(?i)__(TOC|NOTOC|NOEDITSECTION)__");
    private static final Pattern PATTERN_FILES = Pattern.compile("(?i)\\[\\[(File|Image|Category):.*?\\]\\]");
    private static final Pattern PATTERN_PIPE_LINKS = Pattern.compile("\\[\\[[^]]*?\\|([^]]+?)\\]\\]");
    private static final Pattern PATTERN_SIMPLE_LINKS = Pattern.compile("\\[\\[([^]]+?)\\]\\]");
    private static final Pattern PATTERN_BOLD = Pattern.compile("'''(.*?)'''");
    private static final Pattern PATTERN_ITALIC = Pattern.compile("''(.*?)''");
    private static final Pattern PATTERN_EMPTY_LINES = Pattern.compile("(?m)^[ \t]*\r?\n");
    private static final Pattern PATTERN_TABLE_START = Pattern.compile("(?s)\\{\\|[^\n]*\n");
    private static final Pattern PATTERN_TABLE_END = Pattern.compile("\\|\\}");
    private static final Pattern PATTERN_TABLE_CLASS = Pattern.compile("(?m)^\\|+[^\n]*class=[^\n]*\n?");
    private static final Pattern PATTERN_TABLE_STYLE = Pattern.compile("(?m)^\\|+[^\n]*style=[^\n]*\n?");
    private static final Pattern PATTERN_TABLE_ROW = Pattern.compile("\\|\\-");
    private static final Pattern PATTERN_TABLE_DELIM = Pattern.compile("!|\\|\\|");
    private static final Pattern PATTERN_TABLE_CELL = Pattern.compile("(?m)^\\|");
    private static final Pattern PATTERN_TABLE_HEADER = Pattern.compile("(?m)^!");
    private static final Pattern PATTERN_USELESS_SECTIONS = Pattern.compile(
            "(?ims)^==\\s*(Changes|Update history|History|Gallery|References|External links|Navigation)\\s*==.*?(?=(^==|\\z))");
    private static final Pattern PATTERN_EDIT_BUTTONS = Pattern.compile("(?i)\\b(\\?\\s*)?\\(edit\\)");
    private static final Pattern PATTERN_MOID_NOISE = Pattern.compile("(?i)\\bMOID+\\b");
    private static final Pattern PATTERN_MULTIPLE_NEWLINES = Pattern.compile("\n{3,}");
    private static final Pattern PATTERN_USELESS_HEADINGS = Pattern
            .compile("(?i)^(changes|update history|history|gallery|references|external links|navigation)$");
    private static final Pattern PATTERN_STUB_TAGS = Pattern.compile("(?i)\\{\\{(stub|clear|sic|!)\\}\\}");

    private WikiSearchUtil() {
        // Utility class
    }

    /**
     * Clears all cached wiki search entries from memory.
     */
    public static void clearCache() {
        SEARCH_CACHE.clear();
    }

    /**
     * Executes an OSRS Wiki search for the specified query, checking cache first
     * before issuing network requests.
     *
     * @param wikiClient {@link OkHttpClient} configured for wiki HTTP calls
     * @param gson       {@link Gson} instance for JSON parsing
     * @param query      entity, location, item, monster, or topic search query
     * @return JSON string containing article title and markdown extract, or error
     *         JSON if not found
     */
    public static String executeWikiSearch(OkHttpClient wikiClient, Gson gson, String query) {
        String cleanedQuery = extractSearchQuery(query);
        String cacheKey = cleanedQuery.trim().toLowerCase();

        if (SEARCH_CACHE.containsKey(cacheKey)) {
            CacheEntry cached = SEARCH_CACHE.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                log.debug("Wiki search cache hit for: {}", cacheKey);
                return cached.result;
            }
            log.debug("Wiki search cache expired for: {}", cacheKey);
        }

        // 1. Direct title parse fetch via MediaWiki action=parse
        String result = fetchDirectTitleExtract(wikiClient, gson, cleanedQuery);
        if (result == null) {
            // 2. Generator search parse fallback
            result = fetchGeneratorSearchExtract(wikiClient, gson, cleanedQuery);
        }

        if (result != null) {
            SEARCH_CACHE.put(cacheKey, new CacheEntry(result));
            return result;
        }

        JsonObject err = new JsonObject();
        err.addProperty("status", "not_found");
        err.addProperty("message", "No OSRS wiki article found for query '" + query
                + "'. This entity, reward, or feature does NOT exist in OSRS (it may be a hallucination, RS3 content, or invalid terminology). Do NOT fabricate mechanics or quest rewards.");
        String errJson = gson.toJson(err);
        SEARCH_CACHE.put(cacheKey, new CacheEntry(errJson));
        return errJson;
    }

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
        String q = question.trim().toLowerCase();

        if (q.endsWith("?")) {
            q = q.substring(0, q.length() - 1).trim();
        }

        String[] prefixes = {
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

        boolean prefixFound;
        do {
            prefixFound = false;
            for (String prefix : prefixes) {
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

        String[] suffixes = {
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

        boolean suffixFound;
        do {
            suffixFound = false;
            for (String suffix : suffixes) {
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
     * Fetches and parses a wiki page directly by page title using MediaWiki
     * action=parse.
     *
     * @param wikiClient {@link OkHttpClient} instance
     * @param gson       {@link Gson} instance
     * @param query      article title string
     * @return JSON string with article title and markdown extract, or {@code null}
     *         if page not found
     */
    public static String fetchDirectTitleExtract(OkHttpClient wikiClient, Gson gson, String query) {
        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(WIKI_API)).newBuilder()
                    .addQueryParameter("action", "parse")
                    .addQueryParameter("page", query)
                    .addQueryParameter("prop", "text")
                    .addQueryParameter("redirects", "1")
                    .addQueryParameter("format", "json")
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", OSRS_AI_USER_AGENT)
                    .build();

            try (Response response = wikiClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null)
                    return null;
                JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
                if (json.has("parse")) {
                    JsonObject parseObj = json.getAsJsonObject("parse");
                    String title = parseObj.has("title") ? parseObj.get("title").getAsString() : query;
                    if (parseObj.has("text")) {
                        JsonObject textObj = parseObj.getAsJsonObject("text");
                        if (textObj.has("*")) {
                            String rawHtml = textObj.get("*").getAsString();
                            String cleanedMarkdown = parseWikiHtmlToMarkdown(title, rawHtml);
                            if (cleanedMarkdown != null && !cleanedMarkdown.trim().isEmpty()) {
                                JsonObject res = new JsonObject();
                                res.addProperty("title", title);
                                res.addProperty("extract", cleanedMarkdown);
                                return gson.toJson(res);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Direct title parse fetch failed for: {}", query, e);
        }
        return null;
    }

    /**
     * Fallback search strategy that searches the wiki via search list before
     * parsing the top result.
     *
     * @param wikiClient {@link OkHttpClient} instance
     * @param gson       {@link Gson} instance
     * @param query      search query
     * @return JSON string with article title and markdown extract, or {@code null}
     */
    public static String fetchGeneratorSearchExtract(OkHttpClient wikiClient, Gson gson, String query) {
        String topTitle = searchWikiTopResult(wikiClient, gson, query);
        if (topTitle != null) {
            return fetchDirectTitleExtract(wikiClient, gson, topTitle);
        }
        return null;
    }

    /**
     * Resolves a query string directly to a canonical wiki article title (handling
     * redirects).
     *
     * @param wikiClient {@link OkHttpClient} instance
     * @param gson       {@link Gson} instance
     * @param query      query or page title
     * @return resolved article title, or {@code null}
     */
    public static String resolveTitleDirectly(OkHttpClient wikiClient, Gson gson, String query) {
        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(WIKI_API)).newBuilder()
                    .addQueryParameter("action", "query")
                    .addQueryParameter("titles", query)
                    .addQueryParameter("redirects", "1")
                    .addQueryParameter("format", "json")
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", OSRS_AI_USER_AGENT)
                    .build();

            try (Response response = wikiClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null)
                    return null;
                JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
                JsonObject queryObj = json.getAsJsonObject("query");
                if (queryObj == null)
                    return null;
                JsonObject pages = queryObj.getAsJsonObject("pages");
                if (pages == null)
                    return null;
                for (Map.Entry<String, JsonElement> entry : pages.entrySet()) {
                    if ("-1".equals(entry.getKey()))
                        continue;
                    JsonObject page = entry.getValue().getAsJsonObject();
                    if (page.has("title")) {
                        return page.get("title").getAsString();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Direct title resolution failed for: {}", query, e);
        }
        return null;
    }

    /**
     * Executes a MediaWiki search API query to retrieve the top matching article
     * title.
     *
     * @param wikiClient {@link OkHttpClient} instance
     * @param gson       {@link Gson} instance
     * @param query      search query
     * @return top article title, or {@code null}
     */
    public static String searchWikiTopResult(OkHttpClient wikiClient, Gson gson, String query) {
        String directTitle = resolveTitleDirectly(wikiClient, gson, query);
        // Guard against the MediaWiki API resolving a query to a completely unrelated
        // page (e.g. "dragon platelegs drop sources" resolving to "Uri transform").
        // Only trust the direct-resolve result if the returned title shares at least
        // one meaningful keyword (3+ chars, ignoring common stop words) with the query.
        if (directTitle != null && isTitleRelevantToQuery(directTitle, query)) {
            return directTitle;
        } else if (directTitle != null) {
            log.info("Direct title resolve '{}' deemed irrelevant to query '{}'; falling back to full-text search",
                    directTitle, query);
        }

        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(WIKI_API)).newBuilder()
                    .addQueryParameter("action", "query")
                    .addQueryParameter("list", "search")
                    .addQueryParameter("srsearch", query)
                    .addQueryParameter("srnamespace", "0")
                    .addQueryParameter("srlimit", String.valueOf(WIKI_SEARCH_SRLIMIT))
                    .addQueryParameter("format", "json")
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", OSRS_AI_USER_AGENT)
                    .build();

            try (Response response = wikiClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null)
                    return null;
                JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
                JsonObject queryObj = json.getAsJsonObject("query");
                if (queryObj == null)
                    return null;
                JsonArray results = queryObj.getAsJsonArray("search");
                if (results == null || results.size() == 0)
                    return null;

                boolean wantsLeague = query != null && query.toLowerCase().contains("league");
                for (int i = 0; i < results.size(); i++) {
                    JsonObject item = results.get(i).getAsJsonObject();
                    if (item.has("title")) {
                        String candidateTitle = item.get("title").getAsString();
                        if (!wantsLeague && candidateTitle.toLowerCase().contains(" league")) {
                            continue;
                        }
                        return candidateTitle;
                    }
                }
                return results.get(0).getAsJsonObject().get("title").getAsString();
            }
        } catch (Exception e) {
            log.warn("Wiki search failed for: {}", query, e);
            return null;
        }
    }

    /**
     * Checks whether a resolved wiki article title is relevant to the original search
     * query by testing for at least one shared keyword of 3+ characters, ignoring
     * common English and OSRS stop words.
     *
     * @param title resolved article title from the MediaWiki API
     * @param query original cleaned search query
     * @return {@code true} if the title appears related to the query
     */
    static boolean isTitleRelevantToQuery(String title, String query) {
        if (title == null || query == null || title.isEmpty() || query.isEmpty()) {
            return false;
        }
        // Common stop words to exclude from keyword matching
        java.util.Set<String> stopWords = new java.util.HashSet<>(java.util.Arrays.asList(
                "the", "and", "for", "from", "with", "that", "this", "how", "what",
                "where", "when", "can", "you", "are", "not", "osrs", "wiki",
                "get", "has", "its", "your", "their", "they", "have", "been"));

        String titleLower = title.toLowerCase(java.util.Locale.ROOT);
        String queryLower = query.toLowerCase(java.util.Locale.ROOT);

        // Tokenise both strings on non-alpha characters
        String[] queryTokens = queryLower.split("[^a-z]+");
        String[] titleTokens = titleLower.split("[^a-z]+");

        java.util.Set<String> titleWords = new java.util.HashSet<>();
        for (String t : titleTokens) {
            if (t.length() >= 3 && !stopWords.contains(t)) {
                titleWords.add(t);
            }
        }

        for (String q : queryTokens) {
            if (q.length() >= 3 && !stopWords.contains(q) && titleWords.contains(q)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fetches the markdown extract of a specific wiki page by title.
     *
     * @param wikiClient {@link OkHttpClient} instance
     * @param gson       {@link Gson} instance
     * @param title      article title
     * @return markdown extract string, or {@code null}
     */
    public static String fetchWikiExtract(OkHttpClient wikiClient, Gson gson, String title) {
        String jsonRes = fetchDirectTitleExtract(wikiClient, gson, title);
        if (jsonRes != null) {
            try {
                JsonObject obj = gson.fromJson(jsonRes, JsonObject.class);
                if (obj.has("extract")) {
                    return obj.get("extract").getAsString();
                }
            } catch (Exception e) {
                log.warn("Failed to extract content from json response for title: {}", title, e);
            }
        }
        return null;
    }

    private static final Pattern PATTERN_HTML_NOISE = Pattern.compile(
            "(?is)<(script|style|svg|img|video|audio|figure|iframe|noscript)[^>]*>.*?</\\1>|<img[^>]*>|<br\\s*/?>");
    private static final Pattern PATTERN_HTML_CLASS_NOISE = Pattern.compile(
            "(?is)<(div|span|sup|table|ul|ol|p)[^>]*class=[\"'][^\"']*?\\b(mw-editsection|mw-editsection-visualeditor|toc|noexcerpt|navbox|vertical-navbox|catlinks|printfooter|hatnote|dablink|ambox|cmbox|notice|reflist|references|reference|mw-empty-elt|mw-collapsible|mw-collapsed|collapsed|collapsible|infobox-cell-hidden|hidden-cell)\\b[^\"']*?[\"'][^>]*>.*?</\\1>");
    private static final Pattern PATTERN_HTML_DISPLAY_NONE = Pattern.compile(
            "(?is)<[a-zA-Z0-9]+[^>]*style=[\"'][^\"']*?display:\\s*none[^\"']*?[\"'][^>]*>.*?</[a-zA-Z0-9]+>");
    private static final Pattern PATTERN_HTML_USELESS_SECTION = Pattern.compile(
            "(?is)<h[23][^>]*>(?:<[^>]+>)*\\s*(?:changes|update history|history|gallery|references|external links|navigation)\\s*(?:</[^>]+>)*</h[23]>.*?(?=<h2|$)");
    private static final Pattern PATTERN_HTML_INFOBOX = Pattern.compile(
            "(?is)<table[^>]*class=[\"'][^\"']*?\\b(infobox|questdetails|questreq|equipment-stats|quest)[^\"']*?[\"'][^>]*>(.*?)</table>");
    private static final Pattern PATTERN_HTML_WIKITABLE = Pattern.compile(
            "(?is)<table[^>]*class=[\"'][^\"']*?\\b(wikitable|item-drops|drop-table)[^\"']*?[\"'][^>]*>(.*?)</table>");
    private static final Pattern PATTERN_HTML_TR = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern PATTERN_HTML_TH = Pattern.compile("(?is)<th[^>]*>(.*?)</th>");
    private static final Pattern PATTERN_HTML_TD = Pattern.compile("(?is)<td[^>]*>(.*?)</td>");
    private static final Pattern PATTERN_HTML_H2 = Pattern.compile("(?is)<h2[^>]*>(.*?)</h2>");
    private static final Pattern PATTERN_HTML_H3 = Pattern.compile("(?is)<h3[^>]*>(.*?)</h3>");
    private static final Pattern PATTERN_HTML_H4 = Pattern.compile("(?is)<h4[^>]*>(.*?)</h4>");
    private static final Pattern PATTERN_HTML_P = Pattern.compile("(?is)<p[^>]*>(.*?)</p>");
    private static final Pattern PATTERN_HTML_LI = Pattern.compile("(?is)<li[^>]*>(.*?)</li>");
    private static final Pattern PATTERN_HTML_TAGS = Pattern.compile("<[^>]+>");

    /**
     * Converts server-rendered MediaWiki HTML into concise, structured Markdown.
     *
     * @param title article title
     * @param html  raw HTML from MediaWiki action=parse
     * @return formatted Markdown string
     */
    public static String parseWikiHtmlToMarkdown(String title, String html) {
        if (html == null || html.trim().isEmpty()) {
            return "";
        }

        String cleaned = html;
        for (int i = 0; i < 3; i++) {
            cleaned = PATTERN_HTML_NOISE.matcher(cleaned).replaceAll(" ");
            cleaned = PATTERN_HTML_CLASS_NOISE.matcher(cleaned).replaceAll(" ");
            cleaned = PATTERN_HTML_DISPLAY_NONE.matcher(cleaned).replaceAll(" ");
        }
        cleaned = PATTERN_HTML_USELESS_SECTION.matcher(cleaned).replaceAll(" ");

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");

        // 1. Extract Infobox & Quest Details/Stats
        StringBuilder infoboxSb = new StringBuilder();
        Matcher infoboxMatcher = PATTERN_HTML_INFOBOX.matcher(cleaned);
        while (infoboxMatcher.find()) {
            String tableHtml = infoboxMatcher.group(2);
            Matcher trMatcher = PATTERN_HTML_TR.matcher(tableHtml);
            while (trMatcher.find()) {
                String rowHtml = trMatcher.group(1);
                List<String> ths = extractCellTexts(rowHtml, PATTERN_HTML_TH);
                List<String> tds = extractCellTexts(rowHtml, PATTERN_HTML_TD);

                if (!ths.isEmpty() && !tds.isEmpty()) {
                    String key = cleanCellText(ths.get(0));
                    String val = cleanCellText(tds.get(0));
                    if (isValidInfoboxPair(key, val)) {
                        infoboxSb.append("- **").append(key).append("**: ").append(val).append("\n");
                    }
                } else if (tds.size() >= 2) {
                    String key = cleanCellText(tds.get(0));
                    String val = cleanCellText(tds.get(1));
                    if (isValidInfoboxPair(key, val)) {
                        infoboxSb.append("- **").append(key).append("**: ").append(val).append("\n");
                    }
                } else if (tds.size() == 1) {
                    String val = cleanCellText(tds.get(0));
                    if (!val.isEmpty() && !val.equalsIgnoreCase("Details")
                            && !val.contains("You will have to buy another")) {
                        infoboxSb.append("- ").append(val).append("\n");
                    }
                }
            }
        }
        cleaned = PATTERN_HTML_INFOBOX.matcher(cleaned).replaceAll(" ");

        String infoboxMarkdown = infoboxSb.toString().trim();
        if (!infoboxMarkdown.isEmpty()) {
            sb.append("## Overview & Stats\n").append(infoboxMarkdown).append("\n\n");
        }

        // 2. Format Wikitables & Drop Tables into Markdown Tables
        StringBuffer tableReplaced = new StringBuffer();
        Matcher tableMatcher = PATTERN_HTML_WIKITABLE.matcher(cleaned);
        while (tableMatcher.find()) {
            String tableHtml = tableMatcher.group(2);
            String mdTable = formatHtmlTableToMarkdown(tableHtml);
            tableMatcher.appendReplacement(tableReplaced, Matcher.quoteReplacement("\n\n" + mdTable + "\n\n"));
        }
        tableMatcher.appendTail(tableReplaced);
        cleaned = tableReplaced.toString();

        // 3. Convert Headings, Paragraphs, Lists
        cleaned = PATTERN_HTML_H2.matcher(cleaned).replaceAll("\n\n## $1\n\n");
        cleaned = PATTERN_HTML_H3.matcher(cleaned).replaceAll("\n\n### $1\n\n");
        cleaned = PATTERN_HTML_H4.matcher(cleaned).replaceAll("\n\n#### $1\n\n");
        cleaned = PATTERN_HTML_P.matcher(cleaned).replaceAll("\n\n$1\n\n");
        cleaned = PATTERN_HTML_LI.matcher(cleaned).replaceAll("\n- $1");

        // 4. Clean bold, italic, tags, and entities
        cleaned = cleaned.replaceAll("(?i)<(b|strong)>([\\s\\S]*?)</\\1>", "**$2**");
        cleaned = cleaned.replaceAll("(?i)<(i|em)>([\\s\\S]*?)</\\1>", "*$2*");
        cleaned = PATTERN_HTML_TAGS.matcher(cleaned).replaceAll(" ");
        cleaned = decodeHtmlEntities(cleaned);
        cleaned = PATTERN_EDIT_BUTTONS.matcher(cleaned).replaceAll("");
        cleaned = PATTERN_MOID_NOISE.matcher(cleaned).replaceAll("");

        String[] lines = cleaned.split("\r?\n");
        StringBuilder bodySb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                bodySb.append(trimmed).append("\n\n");
            }
        }
        sb.append(bodySb.toString().trim());

        String result = sb.toString().trim();
        result = PATTERN_MULTIPLE_NEWLINES.matcher(result).replaceAll("\n\n");

        if (result.length() > WIKI_EXTRACT_CHARS) {
            result = result.substring(0, WIKI_EXTRACT_CHARS) + "\n...[truncated]";
        }
        return result;
    }

    private static String formatHtmlTableToMarkdown(String tableHtml) {
        Matcher trMatcher = PATTERN_HTML_TR.matcher(tableHtml);
        List<List<String>> allRows = new ArrayList<>();
        List<String> headers = new ArrayList<>();

        while (trMatcher.find()) {
            String rowHtml = trMatcher.group(1);
            List<String> ths = extractCellTexts(rowHtml, PATTERN_HTML_TH);
            List<String> tds = extractCellTexts(rowHtml, PATTERN_HTML_TD);

            if (headers.isEmpty() && !ths.isEmpty()) {
                for (String th : ths) {
                    String hText = cleanCellText(th).replace("|", "\\|");
                    headers.add(hText.isEmpty() ? "-" : hText);
                }
            } else {
                List<String> rowCells = new ArrayList<>();
                for (String th : ths) {
                    rowCells.add(cleanCellText(th).replace("|", "\\|"));
                }
                for (String td : tds) {
                    rowCells.add(cleanCellText(td).replace("|", "\\|"));
                }
                if (!rowCells.isEmpty()) {
                    allRows.add(rowCells);
                }
            }
        }

        if (headers.isEmpty() && !allRows.isEmpty()) {
            int maxCols = 0;
            for (List<String> dr : allRows) {
                maxCols = Math.max(maxCols, dr.size());
            }
            for (int col = 1; col <= maxCols; col++) {
                headers.add("Col " + col);
            }
        }

        if (headers.isEmpty()) {
            return "";
        }

        StringBuilder tableSb = new StringBuilder();
        tableSb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        tableSb.append("| ");
        for (int i = 0; i < headers.size(); i++) {
            tableSb.append("---").append(i < headers.size() - 1 ? " | " : "");
        }
        tableSb.append(" |\n");

        int rowCount = 0;
        for (List<String> rowData : allRows) {
            while (rowData.size() < headers.size()) {
                rowData.add("");
            }
            tableSb.append("| ").append(String.join(" | ", rowData.subList(0, headers.size()))).append(" |\n");
            rowCount++;
            if (rowCount >= MAX_WIKITABLE_DATA_ROWS) {
                break;
            }
        }

        if (allRows.size() > MAX_WIKITABLE_TOTAL_ROWS_THRESHOLD) {
            tableSb.append("*...[").append(allRows.size() - MAX_WIKITABLE_TOTAL_ROWS_THRESHOLD)
                    .append(" additional rows truncated]*\n");
        }
        return tableSb.toString().trim();
    }

    private static List<String> extractCellTexts(String html, Pattern cellPattern) {
        List<String> list = new ArrayList<>();
        Matcher m = cellPattern.matcher(html);
        while (m.find()) {
            list.add(m.group(1));
        }
        return list;
    }

    private static String cleanCellText(String cellHtml) {
        if (cellHtml == null) {
            return "";
        }
        String text = PATTERN_HTML_TAGS.matcher(cellHtml).replaceAll(" ").trim();
        text = decodeHtmlEntities(text);
        text = PATTERN_EDIT_BUTTONS.matcher(text).replaceAll("").trim();
        text = PATTERN_MOID_NOISE.matcher(text).replaceAll("").trim();
        return text.replaceAll("\\s+", " ").trim();
    }

    private static boolean isValidInfoboxPair(String key, String val) {
        return !key.isEmpty() && !val.isEmpty()
                && !key.equalsIgnoreCase("Image")
                && !key.equalsIgnoreCase("Caption")
                && !isRawStatKey(key)
                && !val.equalsIgnoreCase("Not alchemisable")
                && !val.equalsIgnoreCase("Not sold")
                && !val.equalsIgnoreCase("No data to display");
    }

    static boolean isRawStatKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        return key.matches("[+\\-]?\\d+%?");
    }

    public static String decodeHtmlEntities(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&apos;", "'")
                .replace("&#8203;", "")
                .replace("&ndash;", "–")
                .replace("&mdash;", "—");
    }

    /**
     * Fallback wikitext cleaner for backwards compatibility with raw text.
     */
    public static String cleanWikitext(String wikitext) {
        if (wikitext == null) {
            return "";
        }

        String clean = PATTERN_COMMENTS.matcher(wikitext).replaceAll("");
        clean = PATTERN_USELESS_SECTIONS.matcher(clean).replaceAll("");

        clean = PATTERN_MAGIC.matcher(
                clean.replace("&nbsp;", " ")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\""))
                .replaceAll("");

        clean = convertWikitables(clean);
        clean = PATTERN_FILES.matcher(clean).replaceAll("");
        clean = PATTERN_PIPE_LINKS.matcher(clean).replaceAll("$1");
        clean = PATTERN_SIMPLE_LINKS.matcher(clean).replaceAll("$1");
        clean = PATTERN_BOLD.matcher(clean).replaceAll("**$1**");
        clean = PATTERN_ITALIC.matcher(clean).replaceAll("*$1*");
        clean = PATTERN_STUB_TAGS.matcher(clean).replaceAll("");
        clean = clean.replace("{{", "[ ").replace("}}", " ]");
        clean = PATTERN_EMPTY_LINES.matcher(clean).replaceAll("");

        return clean.trim();
    }

    private static String convertWikitables(String input) {
        if (input == null) {
            return "";
        }
        String res = PATTERN_TABLE_START.matcher(input).replaceAll("\n");
        res = PATTERN_TABLE_END.matcher(res).replaceAll("\n");
        res = PATTERN_TABLE_CLASS.matcher(res).replaceAll("");
        res = PATTERN_TABLE_STYLE.matcher(res).replaceAll("");
        res = PATTERN_TABLE_ROW.matcher(res).replaceAll("\n");
        res = PATTERN_TABLE_DELIM.matcher(res).replaceAll(" | ");
        res = PATTERN_TABLE_CELL.matcher(res).replaceAll(" ");
        return PATTERN_TABLE_HEADER.matcher(res).replaceAll(" ");
    }
}
