package com.osrsai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Slf4j
public class WikiSearchUtil {
    public static final String OSRS_AI_USER_AGENT = "OSRS AI Assistant RuneLite Plugin - https://github.com/Timboy67678/osrs-ai-assistant";
    public static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";
    public static final int MAX_TEMPLATE_REMOVALS = 5;
    public static final int WIKI_EXTRACT_CHARS = 8000;
    private static final int CACHE_MAX_ENTRIES = 500;

    private static final Map<String, String> SEARCH_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(CACHE_MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            }
    );

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
            "(?ims)^==\\s*(Changes|Update history|History|Gallery|References|External links|Navigation)\\s*==.*?(?=(^==|\\z))"
    );

    private WikiSearchUtil() {
        // Utility class
    }

    public static void clearCache() {
        SEARCH_CACHE.clear();
    }

    public static String executeWikiSearch(OkHttpClient wikiClient, Gson gson, String query) {
        String cleanedQuery = extractSearchQuery(query);
        String cacheKey = cleanedQuery.trim().toLowerCase();

        if (SEARCH_CACHE.containsKey(cacheKey)) {
            log.debug("Wiki search cache hit for: {}", cacheKey);
            return SEARCH_CACHE.get(cacheKey);
        }

        // 1. Direct title parse fetch via MediaWiki action=parse
        String result = fetchDirectTitleExtract(wikiClient, gson, cleanedQuery);
        if (result == null) {
            // 2. Generator search parse fallback
            result = fetchGeneratorSearchExtract(wikiClient, gson, cleanedQuery);
        }

        if (result != null) {
            SEARCH_CACHE.put(cacheKey, result);
            return result;
        }

        JsonObject err = new JsonObject();
        err.addProperty("status", "not_found");
        err.addProperty("message", "No OSRS wiki article found for query '" + query + "'. This entity, reward, or feature does NOT exist in OSRS (it may be a hallucination, RS3 content, or invalid terminology). Do NOT fabricate mechanics or quest rewards.");
        String errJson = gson.toJson(err);
        SEARCH_CACHE.put(cacheKey, errJson);
        return errJson;
    }

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
                " map location",
                " requirements",
                " requirement",
                " coordinates",
                " ingredients",
                " drop rates",
                " drop table",
                " drop rate",
                " locations",
                " location",
                " weakness",
                " recipe",
                " spawns",
                " coords",
                " shops",
                " spawn",
                " drops",
                " stats",
                " guide",
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

    public static String fetchGeneratorSearchExtract(OkHttpClient wikiClient, Gson gson, String query) {
        String topTitle = searchWikiTopResult(wikiClient, gson, query);
        if (topTitle != null) {
            return fetchDirectTitleExtract(wikiClient, gson, topTitle);
        }
        return null;
    }

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

    public static String searchWikiTopResult(OkHttpClient wikiClient, Gson gson, String query) {
        String directTitle = resolveTitleDirectly(wikiClient, gson, query);
        if (directTitle != null) {
            return directTitle;
        }

        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(WIKI_API)).newBuilder()
                    .addQueryParameter("action", "query")
                    .addQueryParameter("list", "search")
                    .addQueryParameter("srsearch", query)
                    .addQueryParameter("srnamespace", "0")
                    .addQueryParameter("srlimit", "1")
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
                return results.get(0).getAsJsonObject().get("title").getAsString();
            }
        } catch (Exception e) {
            log.warn("Wiki search failed for: {}", query, e);
            return null;
        }
    }

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

    /**
     * Converts server-rendered MediaWiki HTML into concise, structured Markdown.
     */
    public static String parseWikiHtmlToMarkdown(String title, String html) {
        if (html == null || html.trim().isEmpty()) {
            return "";
        }

        Document doc = Jsoup.parse(html);
        Element root = doc.selectFirst(".mw-parser-output");
        if (root == null) {
            root = doc.body();
        }

        // 1. Remove noise DOM elements
        root.select("script, style, .mw-editsection, #toc, .toc, .mw-empty-elt, .noexcerpt, .navbox, .vertical-navbox, .catlinks, .printfooter, img, svg, audio, video, figure, iframe").remove();

        // 2. Remove useless trailing/side sections
        removeUselessSectionsFromDom(root);

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");

        // 3. Extract Infobox & Quest Details/Stats
        String infoboxMarkdown = extractInfoboxData(root);

        // 4. Extract Lead Paragraphs (summary before first major heading)
        String leadSummary = extractLeadSummary(root);
        if (!leadSummary.isEmpty()) {
            sb.append(leadSummary).append("\n\n");
        }

        if (!infoboxMarkdown.isEmpty()) {
            sb.append("## Overview & Stats\n").append(infoboxMarkdown).append("\n\n");
        }

        // 5. Format Wikitables & Drop Tables into Markdown Tables
        convertWikitablesToMarkdown(root);

        // 6. Convert remaining body structure
        String bodyMarkdown = convertBodyToMarkdown(root);
        sb.append(bodyMarkdown);

        String result = sb.toString().trim();
        result = result.replaceAll("\n{3,}", "\n\n");

        if (result.length() > WIKI_EXTRACT_CHARS) {
            result = result.substring(0, WIKI_EXTRACT_CHARS) + "\n...[truncated]";
        }
        return result;
    }

    private static void removeUselessSectionsFromDom(Element root) {
        Elements headings = root.select("h2, h3");
        List<Element> toRemove = new ArrayList<>();
        for (Element heading : headings) {
            String headingText = heading.text().trim();
            if (headingText.matches("(?i)^(changes|update history|history|gallery|references|external links|navigation)$")) {
                toRemove.add(heading);
                Element next = heading.nextElementSibling();
                while (next != null && !next.tagName().equalsIgnoreCase("h2")) {
                    toRemove.add(next);
                    next = next.nextElementSibling();
                }
            }
        }
        for (Element el : toRemove) {
            el.remove();
        }
    }

    private static String extractInfoboxData(Element root) {
        Elements infoboxes = root.select("table[class*='infobox'], table.questdetails, table.questreq, div.questreq, table.equipment-stats, table[class*='quest']");
        if (infoboxes.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Element infobox : infoboxes) {
            Elements rows = infobox.select("tr");
            for (Element row : rows) {
                Elements headers = row.select("th");
                Elements cells = row.select("td");
                if (!headers.isEmpty() && !cells.isEmpty()) {
                    String key = headers.text().trim();
                    String val = cells.text().trim();
                    if (!key.isEmpty() && !val.isEmpty() && !key.equalsIgnoreCase("Image") && !key.equalsIgnoreCase("Caption")) {
                        sb.append("- **").append(key).append("**: ").append(val).append("\n");
                    }
                } else if (cells.size() >= 2) {
                    String key = cells.get(0).text().trim();
                    String val = cells.get(1).text().trim();
                    if (!key.isEmpty() && !val.isEmpty() && !key.equalsIgnoreCase("Image")) {
                        sb.append("- **").append(key).append("**: ").append(val).append("\n");
                    }
                } else if (cells.size() == 1) {
                    String val = cells.get(0).text().trim();
                    if (!val.isEmpty() && !val.equalsIgnoreCase("Details")) {
                        sb.append("- ").append(val).append("\n");
                    }
                }
            }
            infobox.remove();
        }
        return sb.toString().trim();
    }

    private static String extractLeadSummary(Element root) {
        StringBuilder sb = new StringBuilder();
        for (Element child : root.children()) {
            if (child.tagName().equalsIgnoreCase("h2")) {
                break;
            }
            if (child.tagName().equalsIgnoreCase("p")) {
                String text = child.text().trim();
                if (!text.isEmpty()) {
                    sb.append(text).append("\n\n");
                }
            }
        }
        return sb.toString().trim();
    }

    private static void convertWikitablesToMarkdown(Element root) {
        Elements tables = root.select("table.wikitable, table.item-drops, table.drop-table");
        for (Element table : tables) {
            Elements rows = table.select("tr");
            if (rows.isEmpty()) {
                table.remove();
                continue;
            }

            StringBuilder tableSb = new StringBuilder("\n\n");
            List<String> headers = new ArrayList<>();

            Element firstRow = rows.get(0);
            Elements ths = firstRow.select("th");
            if (!ths.isEmpty()) {
                for (Element th : ths) {
                    String hText = th.text().trim().replace("|", "\\|");
                    headers.add(hText.isEmpty() ? "-" : hText);
                }
            }

            int startRowIdx = headers.isEmpty() ? 0 : 1;
            List<List<String>> dataRows = new ArrayList<>();

            for (int i = startRowIdx; i < rows.size(); i++) {
                Element r = rows.get(i);
                Elements tds = r.select("td, th");
                if (tds.isEmpty()) continue;
                List<String> cellValues = new ArrayList<>();
                for (Element td : tds) {
                    String val = td.text().trim().replace("|", "\\|");
                    cellValues.add(val);
                }
                dataRows.add(cellValues);

                if (dataRows.size() >= 35) {
                    break;
                }
            }

            if (headers.isEmpty() && !dataRows.isEmpty()) {
                int maxCols = 0;
                for (List<String> dr : dataRows) {
                    maxCols = Math.max(maxCols, dr.size());
                }
                for (int col = 1; col <= maxCols; col++) {
                    headers.add("Col " + col);
                }
            }

            if (!headers.isEmpty()) {
                tableSb.append("| ").append(String.join(" | ", headers)).append(" |\n");
                tableSb.append("| ").append(headers.stream().map(h -> "---").reduce((a, b) -> a + " | " + b).orElse("---")).append(" |\n");

                for (List<String> rowData : dataRows) {
                    while (rowData.size() < headers.size()) {
                        rowData.add("");
                    }
                    tableSb.append("| ").append(String.join(" | ", rowData.subList(0, headers.size()))).append(" |\n");
                }

                if (rows.size() > 36) {
                    tableSb.append("*...[").append(rows.size() - 36).append(" additional rows truncated]*\n");
                }
                tableSb.append("\n");

                table.replaceWith(new TextNode(tableSb.toString()));
            } else {
                table.remove();
            }
        }
    }

    private static String convertBodyToMarkdown(Element root) {
        StringBuilder sb = new StringBuilder();
        for (Node childNode : root.childNodes()) {
            if (childNode instanceof TextNode) {
                String text = ((TextNode) childNode).text().trim();
                if (!text.isEmpty()) {
                    sb.append(text).append("\n\n");
                }
            } else if (childNode instanceof Element) {
                Element child = (Element) childNode;
                String tag = child.tagName().toLowerCase();
                switch (tag) {
                    case "h2":
                        String h2Text = child.text().trim();
                        if (!h2Text.isEmpty()) {
                            sb.append("\n## ").append(h2Text).append("\n\n");
                        }
                        break;
                    case "h3":
                        String h3Text = child.text().trim();
                        if (!h3Text.isEmpty()) {
                            sb.append("\n### ").append(h3Text).append("\n\n");
                        }
                        break;
                    case "h4":
                        String h4Text = child.text().trim();
                        if (!h4Text.isEmpty()) {
                            sb.append("\n#### ").append(h4Text).append("\n\n");
                        }
                        break;
                    case "p":
                        String pText = child.text().trim();
                        if (!pText.isEmpty()) {
                            sb.append(pText).append("\n\n");
                        }
                        break;
                    case "ul":
                    case "ol":
                        for (Element li : child.select("> li")) {
                            String liText = li.text().trim();
                            if (!liText.isEmpty()) {
                                sb.append("- ").append(liText).append("\n");
                            }
                        }
                        sb.append("\n");
                        break;
                    case "div":
                    case "span":
                    case "blockquote":
                        String divText = child.text().trim();
                        if (!divText.isEmpty()) {
                            sb.append(divText).append("\n\n");
                        }
                        break;
                    default:
                        break;
                }
            }
        }
        return sb.toString();
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
                     .replace("&quot;", "\"")
        ).replaceAll("");

        clean = convertWikitables(clean);
        clean = PATTERN_FILES.matcher(clean).replaceAll("");
        clean = PATTERN_PIPE_LINKS.matcher(clean).replaceAll("$1");
        clean = PATTERN_SIMPLE_LINKS.matcher(clean).replaceAll("$1");
        clean = PATTERN_BOLD.matcher(clean).replaceAll("**$1**");
        clean = PATTERN_ITALIC.matcher(clean).replaceAll("*$1*");
        clean = clean.replaceAll("(?i)\\{\\{(stub|clear|sic|!)\\}\\}", "");
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
