package com.osrsai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.Map;
import java.util.Objects;

@Slf4j
public class WikiSearchUtil {
    public static final String OSRS_AI_USER_AGENT = "OSRS AI Assistant RuneLite Plugin - https://github.com/Timboy67678/osrs-ai-assistant";
    public static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";
    public static final int MAX_TEMPLATE_REMOVALS = 5;
    public static final int WIKI_EXTRACT_CHARS = 2500;

    private WikiSearchUtil() {
        // Utility class
    }

    public static String executeWikiSearch(OkHttpClient wikiClient, Gson gson, String query) {
        String cleanedQuery = extractSearchQuery(query);
        String title = searchWikiTopResult(wikiClient, gson, cleanedQuery);
        if (title != null) {
            String extract = fetchWikiExtract(wikiClient, gson, title);
            if (extract != null && !extract.isEmpty()) {
                JsonObject res = new JsonObject();
                res.addProperty("title", title);
                res.addProperty("extract", extract);
                return gson.toJson(res);
            }
        }
        JsonObject err = new JsonObject();
        err.addProperty("status", "not_found");
        err.addProperty("message", "No OSRS wiki article found for query '" + query + "'. This entity, reward, or feature does NOT exist in OSRS (it may be a hallucination, RS3 content, or invalid terminology). Do NOT fabricate mechanics or quest rewards.");
        return gson.toJson(err);
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
                " buy shops locations",
                " shop locations osrs",
                " elemental weakness",
                " shops locations",
                " spawn locations",
                " ingredients for",
                " spawn location",
                " shop locations",
                " shop location",
                " requirements",
                " requirement",
                " ingredients",
                " drop rates",
                " drop table",
                " drop rate",
                " locations",
                " location",
                " weakness",
                " recipe",
                " spawns",
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
                for (Map.Entry<String, com.google.gson.JsonElement> entry : pages.entrySet()) {
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
        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(WIKI_API)).newBuilder()
                    .addQueryParameter("action", "query")
                    .addQueryParameter("titles", title)
                    .addQueryParameter("prop", "revisions")
                    .addQueryParameter("rvprop", "content")
                    .addQueryParameter("rvlimit", "1")
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
                for (Map.Entry<String, com.google.gson.JsonElement> entry : pages.entrySet()) {
                    if ("-1".equals(entry.getKey()))
                        continue;
                    JsonObject page = entry.getValue().getAsJsonObject();
                    if (page.has("revisions")) {
                        JsonArray revisions = page.getAsJsonArray("revisions");
                        if (revisions != null && revisions.size() > 0) {
                            JsonObject rev = revisions.get(0).getAsJsonObject();
                            if (rev.has("*")) {
                                String wikitext = rev.get("*").getAsString();
                                String cleaned = cleanWikitext(wikitext);
                                if (cleaned.length() > WIKI_EXTRACT_CHARS) {
                                    cleaned = cleaned.substring(0, WIKI_EXTRACT_CHARS) + "\n...[truncated]";
                                }
                                return cleaned;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Wiki extract fetch failed for: {}", title, e);
        }
        return null;
    }

    public static String cleanWikitext(String wikitext) {
        if (wikitext == null) {
            return "";
        }

        String clean = wikitext.replaceAll("(?s)<!--.*?-->", "");
        clean = clean.replaceAll("(?s)\\{\\|.*?\\|\\}", "");
        clean = clean.replaceAll("(?i)\\[\\[(File|Image|Category):.*?\\]\\]", "");
        clean = clean.replaceAll("\\[\\[[^]]*?\\|([^]]+?)\\]\\]", "$1");
        clean = clean.replaceAll("\\[\\[([^]]+?)\\]\\]", "$1");
        clean = clean.replaceAll("'''(.*?)'''", "**$1**");
        clean = clean.replaceAll("''(.*?)''", "*$1*");

        for (int i = 0; i < MAX_TEMPLATE_REMOVALS; i++) {
            String next = clean.replaceAll("\\{\\{[^{}]*?\\}\\}", "");
            if (next.equals(clean)) {
                break;
            }
            clean = next;
        }

        clean = clean.replaceAll("(?m)^[ \t]*\r?\n", "");

        return clean.trim();
    }
}
