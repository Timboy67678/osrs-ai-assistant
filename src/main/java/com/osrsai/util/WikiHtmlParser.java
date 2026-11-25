package com.osrsai.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the HTML that the OSRS Wiki's {@code action=parse} API returns into compact Markdown,
 * split into the page's sections.
 * <p>
 * The wiki's output is well-formed, so a small stack-based tree builder is enough. Working on a
 * tree (rather than regexes over the raw HTML) keeps nested elements intact, lets hidden
 * elements be dropped whole, and gives table cells their real labels: the wiki labels many stat
 * cells only with an icon, whose {@code alt}/{@code title} text is used here.
 */
public final class WikiHtmlParser {

    /** Data rows kept per table before the rest are summarised as "N more rows". */
    static final int MAX_TABLE_ROWS = 60;

    /** Longest text kept for a single table cell. */
    private static final int MAX_CELL_CHARS = 300;

    /** Icons with longer alt text are file descriptions, not labels. */
    private static final int MAX_ICON_LABEL_CHARS = 40;

    private static final Set<String> VOID_TAGS = Set.of("area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr");

    private static final Set<String> RAW_TEXT_TAGS = Set.of("script", "style");

    private static final Set<String> SKIP_TAGS = Set.of("script", "style", "noscript", "img", "figure", "figcaption",
            "video", "audio", "svg", "map", "button", "input", "select", "textarea", "form", "link", "meta");

    /** Classes for wiki UI, navigation, citations and hidden data, none of which is article content. */
    private static final Set<String> SKIP_CLASSES = Set.of("mw-editsection", "toc", "navbox", "navbox-subgroup",
            "reflist", "references", "mw-references-wrap", "reference", "hidden", "infobox-switch-resources",
            "infobox-buttons", "infobox-switch-buttons-caption", "noprint", "dps-calc", "navbar", "catlinks",
            "printfooter", "mw-empty-elt", "gallery", "mw-collapsible-toggle", "rs-external-header-links",
            "infobox-padding", "infobox-bonuses-image", "mw-cite-backlink", "mw-references");

    /** Infobox rows that only hold wiki tooling links. */
    private static final Set<String> NOISE_ROW_KEYS = Set.of("link", "links");

    /** Placeholder text from interactive widgets that never loads in an API response. */
    private static final Set<String> NOISE_TEXT = Set.of("loading...", "view real-time prices", "real-time prices");

    /** Sections with no gameplay value for answering questions. Their subsections go too. */
    private static final Set<String> DROPPED_SECTIONS = Set.of("changes", "update history", "gallery",
            "gallery (historical)", "references", "transcript", "sound effects", "music", "trivia",
            "external links", "navigation");

    private static final Set<String> BLOCK_TAGS = Set.of("p", "div", "li", "tr", "dd", "dt", "h1", "h2", "h3", "h4",
            "h5", "h6", "table", "ul", "ol", "dl", "section", "blockquote", "center", "caption", "tbody", "thead",
            "tfoot");

    /** Opening one of these closes a still-open {@code <p>}, as browsers do. */
    private static final Set<String> CLOSES_PARAGRAPH = Set.of("p", "div", "table", "ul", "ol", "dl", "h1", "h2",
            "h3", "h4", "h5", "h6", "blockquote", "section");

    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00a0\\u2009\\u202f]+");
    private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile(" ([,.;:!?)\\]%])");
    private static final Pattern SPACE_AFTER_OPENING = Pattern.compile("([(\\[]) ");
    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);");
    private static final Pattern DISPLAY_NONE = Pattern.compile("display\\s*:\\s*none", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> NAMED_ENTITIES = new HashMap<>();

    static {
        String[][] entities = {
                { "amp", "&" }, { "lt", "<" }, { "gt", ">" }, { "quot", "\"" }, { "apos", "'" }, { "nbsp", " " },
                { "ndash", "–" }, { "mdash", "—" }, { "minus", "−" }, { "times", "×" }, { "hellip", "…" },
                { "lsquo", "‘" }, { "rsquo", "’" }, { "ldquo", "“" }, { "rdquo", "”" }, { "middot", "·" },
                { "bull", "•" }, { "deg", "°" }, { "frac12", "½" }, { "frac14", "¼" }, { "frac34", "¾" },
                { "plusmn", "±" }, { "le", "≤" }, { "ge", "≥" }, { "rarr", "→" }, { "larr", "←" },
                { "thinsp", " " }, { "ensp", " " }, { "emsp", " " }, { "zwj", "" }, { "zwnj", "" },
                { "shy", "" }, { "copy", "©" }, { "eacute", "é" }, { "auml", "ä" }, { "ouml", "ö" },
                { "uuml", "ü" } };
        for (String[] e : entities) {
            NAMED_ENTITIES.put(e[0], e[1]);
        }
    }

    // =========================================================================
    // Result types
    // =========================================================================

    /** One heading-delimited part of a page. The lead (text before the first heading) is level 1. */
    public static final class Section {
        public final int level;
        public final String name;
        final StringBuilder body = new StringBuilder();

        Section(int level, String name) {
            this.level = level;
            this.name = name;
        }

        public String getContent() {
            return body.toString().trim();
        }
    }

    /** A parsed page: its title and its sections in page order. Index 0 is always the lead. */
    public static final class WikiPage {
        public final String title;
        public final List<Section> sections;

        WikiPage(String title, List<Section> sections) {
            this.title = title;
            this.sections = Collections.unmodifiableList(sections);
        }

        /** The lead text, including the infobox, with the page title as a heading. */
        public String renderLead() {
            return ("# " + title + "\n\n" + sections.get(0).getContent()).trim();
        }

        /** Every kept section of the page. */
        public String renderAll() {
            StringBuilder sb = new StringBuilder(renderLead());
            for (int i = 1; i < sections.size(); i++) {
                appendSection(sb, sections.get(i));
            }
            return sb.toString().trim();
        }

        /** The section at {@code index} followed by all of its subsections. */
        public String renderSection(int index) {
            Section root = sections.get(index);
            StringBuilder sb = new StringBuilder();
            appendSection(sb, root);
            for (int i = index + 1; i < sections.size() && sections.get(i).level > root.level; i++) {
                appendSection(sb, sections.get(i));
            }
            return sb.toString().trim();
        }

        /** Names of the subsections nested under the section at {@code index}, as {@link #label}s. */
        public List<String> subsectionNames(int index) {
            List<String> names = new ArrayList<>();
            Section root = sections.get(index);
            for (int i = index + 1; i < sections.size() && sections.get(i).level > root.level; i++) {
                names.add(label(i));
            }
            return names;
        }

        /** Every section (excluding the lead) as a {@link #label}, in page order. */
        public List<String> outline() {
            List<String> names = new ArrayList<>();
            for (int i = 1; i < sections.size(); i++) {
                names.add(label(i));
            }
            return names;
        }

        /**
         * A section's name, prefixed with its parent's for nested sections ("Wilderness Slayer
         * Cave > Weapons and armour"), so repeated subsection names can be told apart.
         */
        public String label(int index) {
            Section s = sections.get(index);
            int parent = parentOf(index);
            return parent > 0 && s.level > 2 ? sections.get(parent).name + " > " + s.name : s.name;
        }

        /** Index of the top-level section whose name matches, or -1. */
        public int findTopLevel(String name) {
            String want = normalizeName(name);
            for (int i = 1; i < sections.size(); i++) {
                if (sections.get(i).level == 2 && normalizeName(sections.get(i).name).equals(want)) {
                    return i;
                }
            }
            return -1;
        }

        private int parentOf(int index) {
            int level = sections.get(index).level;
            for (int i = index - 1; i >= 1; i--) {
                if (sections.get(i).level < level) {
                    return i;
                }
            }
            return -1;
        }

        private boolean hasAncestorNamed(int index, String want) {
            for (int p = parentOf(index); p > 0; p = parentOf(p)) {
                String name = normalizeName(sections.get(p).name);
                if (name.equals(want) || name.startsWith(want) || name.contains(want)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Finds the section a free-text request most likely refers to. An exact name beats a
         * name starting with the request, which beats a shared word stem (so "drop table" finds
         * "Drops"), which beats a name merely containing it; ties go to the shallower section.
         * "Parent > Child" requests only match a child under a matching parent.
         *
         * @return the section index, or -1 if nothing matches
         */
        public int findSection(String request) {
            String want = normalizeName(request);
            String parentWant = null;
            if (want.contains(">")) {
                String[] parts = want.split(">");
                parentWant = normalizeName(parts[0]);
                want = normalizeName(parts[parts.length - 1]);
            }
            if (want.isEmpty()) {
                return -1;
            }
            int best = -1;
            int bestScore = 0;
            for (int i = 1; i < sections.size(); i++) {
                if (parentWant != null && !parentWant.isEmpty() && !hasAncestorNamed(i, parentWant)) {
                    continue;
                }
                int score = matchScore(normalizeName(sections.get(i).name), want);
                if (score == 0) {
                    continue;
                }
                score = score * 10 - sections.get(i).level;
                if (score > bestScore) {
                    best = i;
                    bestScore = score;
                }
            }
            return best;
        }

        private static int matchScore(String name, String want) {
            if (name.equals(want)) {
                return 5;
            }
            if (name.startsWith(want)) {
                return 4;
            }
            if (sharesStem(name, want)) {
                return 3;
            }
            if (name.contains(want)) {
                return 2;
            }
            return want.contains(name) && name.length() >= 4 ? 1 : 0;
        }

        private static void appendSection(StringBuilder sb, Section s) {
            StringBuilder hashes = new StringBuilder();
            for (int l = 0; l < Math.min(s.level, 4); l++) {
                hashes.append('#');
            }
            sb.append("\n\n").append(hashes).append(' ').append(s.name);
            String content = s.getContent();
            if (!content.isEmpty()) {
                sb.append("\n\n").append(content);
            }
        }

        private static boolean sharesStem(String name, String want) {
            for (String w : want.split(" ")) {
                if (w.length() < 4) {
                    continue;
                }
                String stem = w.length() > 5 ? w.substring(0, w.length() - 2) : w;
                for (String n : name.split(" ")) {
                    if (n.startsWith(stem)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static String normalizeName(String s) {
            if (s == null) {
                return "";
            }
            return WHITESPACE.matcher(s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9>%' ]", " ")).replaceAll(" ")
                    .trim();
        }
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    private final String pageTitle;
    private final List<Section> sections = new ArrayList<>();
    private Section current;

    private WikiHtmlParser(String pageTitle) {
        this.pageTitle = pageTitle;
        this.current = new Section(1, "");
        sections.add(current);
    }

    /**
     * Parses wiki page HTML into sections of Markdown.
     *
     * @param title page title
     * @param html  HTML from the wiki's {@code action=parse} API
     * @return the parsed page
     */
    public static WikiPage parse(String title, String html) {
        WikiHtmlParser parser = new WikiHtmlParser(title);
        if (html != null && !html.isEmpty()) {
            parser.renderChildren(buildTree(html));
        }
        return new WikiPage(title, parser.keptSections());
    }

    private List<Section> keptSections() {
        List<Section> kept = new ArrayList<>();
        int droppingBelowLevel = Integer.MAX_VALUE;
        for (Section s : sections) {
            if (s.level <= droppingBelowLevel) {
                droppingBelowLevel = Integer.MAX_VALUE;
            }
            if (droppingBelowLevel != Integer.MAX_VALUE) {
                continue;
            }
            if (s.level > 1 && DROPPED_SECTIONS.contains(s.name.toLowerCase(Locale.ROOT))) {
                droppingBelowLevel = s.level;
                continue;
            }
            kept.add(s);
        }
        return kept;
    }

    // =========================================================================
    // Tree building
    // =========================================================================

    static final class Node {
        final String tag;
        final Map<String, String> attrs;
        final String text;
        final List<Node> children = new ArrayList<>();

        private Node(String tag, Map<String, String> attrs, String text) {
            this.tag = tag;
            this.attrs = attrs;
            this.text = text;
        }

        static Node element(String tag, Map<String, String> attrs) {
            return new Node(tag, attrs, null);
        }

        static Node text(String text) {
            return new Node(null, Collections.emptyMap(), text);
        }

        boolean isText() {
            return tag == null;
        }

        boolean is(String... tags) {
            if (tag == null) {
                return false;
            }
            for (String t : tags) {
                if (tag.equals(t)) {
                    return true;
                }
            }
            return false;
        }

        String attr(String name) {
            String v = attrs.get(name);
            return v == null ? "" : v;
        }

        Set<String> classes() {
            String c = attrs.get("class");
            if (c == null || c.isEmpty()) {
                return Collections.emptySet();
            }
            Set<String> set = new HashSet<>();
            for (String part : WHITESPACE.split(c.trim())) {
                set.add(part);
            }
            return set;
        }

        boolean hasClass(String name) {
            return classes().contains(name);
        }

        boolean hasClassContaining(String fragment) {
            for (String c : classes()) {
                if (c.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }
    }

    static Node buildTree(String html) {
        Node root = Node.element("#root", Collections.emptyMap());
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        int i = 0;
        int n = html.length();
        while (i < n) {
            int lt = html.indexOf('<', i);
            if (lt < 0) {
                addText(stack.peek(), html.substring(i));
                break;
            }
            if (lt > i) {
                addText(stack.peek(), html.substring(i, lt));
            }
            if (html.startsWith("<!--", lt)) {
                int end = html.indexOf("-->", lt + 4);
                i = end < 0 ? n : end + 3;
                continue;
            }
            int gt = findTagEnd(html, lt + 1);
            if (gt < 0) {
                addText(stack.peek(), html.substring(lt));
                break;
            }
            String inner = html.substring(lt + 1, gt);
            i = gt + 1;
            if (inner.isEmpty() || inner.charAt(0) == '!' || inner.charAt(0) == '?') {
                continue;
            }
            if (inner.charAt(0) == '/') {
                closeTag(stack, tagName(inner.substring(1)));
                continue;
            }
            String name = tagName(inner);
            if (name.isEmpty()) {
                addText(stack.peek(), "<" + inner + ">");
                continue;
            }
            implicitlyClose(stack, name);
            Node el = Node.element(name, parseAttributes(inner.substring(name.length())));
            stack.peek().children.add(el);
            if (RAW_TEXT_TAGS.contains(name)) {
                int end = html.toLowerCase(Locale.ROOT).indexOf("</" + name, i);
                int close = end < 0 ? -1 : html.indexOf('>', end);
                i = close < 0 ? n : close + 1;
                continue;
            }
            if (!VOID_TAGS.contains(name) && !inner.endsWith("/")) {
                stack.push(el);
            }
        }
        return root;
    }

    private static void addText(Node parent, String raw) {
        if (!raw.isEmpty()) {
            parent.children.add(Node.text(decodeEntities(raw)));
        }
    }

    private static int findTagEnd(String html, int from) {
        char quote = 0;
        for (int i = from; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static String tagName(String s) {
        int end = 0;
        while (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '-'
                || s.charAt(end) == ':')) {
            end++;
        }
        return s.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> parseAttributes(String s) {
        if (s.trim().isEmpty() || s.trim().equals("/")) {
            return Collections.emptyMap();
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        Matcher m = ATTRIBUTE.matcher(s);
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            attrs.put(m.group(1).toLowerCase(Locale.ROOT), value == null ? "" : decodeEntities(value));
        }
        return attrs;
    }

    private static void closeTag(Deque<Node> stack, String name) {
        boolean open = false;
        for (Node node : stack) {
            if (name.equals(node.tag)) {
                open = true;
                break;
            }
        }
        if (!open) {
            return;
        }
        while (stack.size() > 1) {
            if (name.equals(stack.pop().tag)) {
                return;
            }
        }
    }

    private static void implicitlyClose(Deque<Node> stack, String name) {
        switch (name) {
            case "td":
            case "th":
                popWhileTop(stack, "td", "th");
                break;
            case "tr":
                popWhileTop(stack, "td", "th");
                popWhileTop(stack, "tr");
                break;
            case "li":
                popWhileTop(stack, "li");
                break;
            case "dt":
            case "dd":
                popWhileTop(stack, "dt", "dd");
                break;
            default:
                break;
        }
        if (CLOSES_PARAGRAPH.contains(name)) {
            popWhileTop(stack, "p");
        }
    }

    private static void popWhileTop(Deque<Node> stack, String... tags) {
        while (stack.size() > 1 && stack.peek().is(tags)) {
            stack.pop();
        }
    }

    // =========================================================================
    // Rendering: block flow
    // =========================================================================

    private static boolean isSkipped(Node node) {
        if (node.isText()) {
            return false;
        }
        if (SKIP_TAGS.contains(node.tag) || "toc".equals(node.attr("id"))) {
            return true;
        }
        if (node.is("sup") && node.hasClass("reference")) {
            return true;
        }
        for (String c : node.classes()) {
            if (SKIP_CLASSES.contains(c)) {
                return true;
            }
        }
        return DISPLAY_NONE.matcher(node.attr("style")).find();
    }

    private void renderChildren(Node node) {
        for (Node child : node.children) {
            render(child);
        }
    }

    private void render(Node node) {
        if (node.isText()) {
            appendFlowText(node.text);
            return;
        }
        if (isSkipped(node)) {
            return;
        }
        switch (node.tag) {
            case "h2":
            case "h3":
            case "h4":
            case "h5":
            case "h6":
                startSection(node.tag.charAt(1) - '0', inlineText(node));
                break;
            case "h1":
                break;
            case "p":
                blankLine();
                append(inlineText(node));
                blankLine();
                break;
            case "ul":
            case "ol":
                newLine();
                renderList(node, 0);
                blankLine();
                break;
            case "dl":
                newLine();
                renderDefinitionList(node);
                blankLine();
                break;
            case "table":
                blankLine();
                renderTable(node);
                blankLine();
                break;
            case "br":
                append("\n");
                break;
            case "div":
            case "section":
            case "center":
            case "blockquote":
            case "main":
            case "article":
            case "aside":
                newLine();
                renderChildren(node);
                newLine();
                break;
            default:
                renderChildren(node);
                break;
        }
    }

    private void startSection(int level, String name) {
        if (name.isEmpty()) {
            return;
        }
        current = new Section(level, name);
        sections.add(current);
    }

    private void append(String s) {
        if (!s.isEmpty()) {
            current.body.append(s);
        }
    }

    private void appendFlowText(String raw) {
        String text = WHITESPACE.matcher(raw).replaceAll(" ");
        StringBuilder body = current.body;
        boolean atLineStart = body.length() == 0 || body.charAt(body.length() - 1) == '\n';
        if (text.trim().isEmpty()) {
            if (!atLineStart && body.charAt(body.length() - 1) != ' ') {
                body.append(' ');
            }
            return;
        }
        if (atLineStart) {
            text = text.replaceAll("^ +", "");
        }
        body.append(text);
    }

    private void newLine() {
        StringBuilder body = current.body;
        trimTrailingSpaces(body);
        if (body.length() > 0 && body.charAt(body.length() - 1) != '\n') {
            body.append('\n');
        }
    }

    private void blankLine() {
        StringBuilder body = current.body;
        trimTrailingSpaces(body);
        if (body.length() == 0) {
            return;
        }
        if (body.charAt(body.length() - 1) != '\n') {
            body.append("\n\n");
        } else if (body.length() < 2 || body.charAt(body.length() - 2) != '\n') {
            body.append('\n');
        }
    }

    private static void trimTrailingSpaces(StringBuilder sb) {
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1);
        }
    }

    private void renderList(Node list, int depth) {
        int index = 1;
        for (Node item : list.children) {
            if (item.isText() || isSkipped(item)) {
                continue;
            }
            if (item.is("ul", "ol")) {
                renderList(item, depth + 1);
                continue;
            }
            if (!item.is("li")) {
                continue;
            }
            String text = inlineText(item, true);
            if (!text.isEmpty()) {
                StringBuilder line = new StringBuilder();
                for (int d = 0; d < depth; d++) {
                    line.append("  ");
                }
                line.append(list.is("ol") ? (index++) + ". " : "- ").append(text).append('\n');
                append(line.toString());
            }
            for (Node sub : nestedLists(item)) {
                renderList(sub, depth + 1);
            }
        }
    }

    private void renderDefinitionList(Node dl) {
        for (Node child : dl.children) {
            if (child.isText() || isSkipped(child)) {
                continue;
            }
            String text = inlineText(child, true);
            if (child.is("dt") && !text.isEmpty()) {
                append("**" + text + "**\n");
            } else if (child.is("dd")) {
                if (!text.isEmpty()) {
                    append(text + "\n");
                }
                for (Node sub : nestedLists(child)) {
                    renderList(sub, 1);
                }
            }
        }
    }

    /** Lists nested inside a list item, not counting lists inside nested tables. */
    private static List<Node> nestedLists(Node node) {
        List<Node> found = new ArrayList<>();
        for (Node child : node.children) {
            if (child.isText() || isSkipped(child) || child.is("table")) {
                continue;
            }
            if (child.is("ul", "ol")) {
                found.add(child);
            } else {
                found.addAll(nestedLists(child));
            }
        }
        return found;
    }

    // =========================================================================
    // Rendering: inline text
    // =========================================================================

    static String inlineText(Node node) {
        return inlineText(node, false);
    }

    /**
     * Visible text of a node on one line. With {@code skipLists}, nested lists are left out so
     * the caller can render them as indented items.
     */
    static String inlineText(Node node, boolean skipLists) {
        StringBuilder sb = new StringBuilder();
        collectInline(node, sb, skipLists);
        return cleanInline(sb.toString());
    }

    private static void collectInline(Node node, StringBuilder sb, boolean skipLists) {
        for (Node child : node.children) {
            if (child.isText()) {
                sb.append(child.text);
                continue;
            }
            if (isSkipped(child)) {
                continue;
            }
            if (child.is("br")) {
                sb.append(' ');
            } else if (child.is("ul", "ol", "dl")) {
                if (!skipLists) {
                    for (Node item : child.children) {
                        if (!item.isText() && !isSkipped(item)) {
                            String t = inlineText(item);
                            if (!t.isEmpty()) {
                                sb.append(' ').append(t).append(';');
                            }
                        }
                    }
                    sb.append(' ');
                }
            } else if (BLOCK_TAGS.contains(child.tag) || child.is("td", "th")) {
                sb.append(' ');
                collectInline(child, sb, skipLists);
                sb.append(' ');
            } else {
                collectInline(child, sb, skipLists);
            }
        }
    }

    private static String cleanInline(String s) {
        String t = WHITESPACE.matcher(s).replaceAll(" ").trim();
        t = SPACE_BEFORE_PUNCTUATION.matcher(t).replaceAll("$1");
        t = SPACE_AFTER_OPENING.matcher(t).replaceAll("$1");
        if (t.endsWith(";")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        return t;
    }

    /** Short alt texts of icons inside a node, used when a cell has no visible text. */
    private static List<String> iconLabels(Node node) {
        List<String> labels = new ArrayList<>();
        collectIconLabels(node, labels);
        return labels;
    }

    private static void collectIconLabels(Node node, List<String> labels) {
        for (Node child : node.children) {
            if (child.isText()) {
                continue;
            }
            if (child.is("img")) {
                String alt = child.attr("alt").trim();
                if (!alt.isEmpty() && alt.length() <= MAX_ICON_LABEL_CHARS
                        && !alt.toLowerCase(Locale.ROOT).contains(".png") && !labels.contains(alt)) {
                    labels.add(alt);
                }
            } else if (!child.is("script", "style") && !child.hasClass("hidden")) {
                collectIconLabels(child, labels);
            }
        }
    }

    private static void collectLinkTitles(Node node, List<String> titles) {
        for (Node child : node.children) {
            if (child.isText() || isSkipped(child) && !child.is("img")) {
                continue;
            }
            if (child.is("a") && !child.attr("title").isEmpty() && !titles.contains(child.attr("title"))) {
                titles.add(child.attr("title"));
            } else {
                collectLinkTitles(child, titles);
            }
        }
    }

    /** First link title inside a node, used to name icon-only header cells. */
    private static String linkTitle(Node node) {
        for (Node child : node.children) {
            if (child.isText()) {
                continue;
            }
            if (child.is("a") && !child.attr("title").isEmpty()) {
                return child.attr("title");
            }
            String t = linkTitle(child);
            if (!t.isEmpty()) {
                return t;
            }
        }
        return "";
    }

    // =========================================================================
    // Rendering: tables
    // =========================================================================

    private void renderTable(Node table) {
        if (isKeyValueTable(table)) {
            renderKeyValueTable(table);
        } else {
            renderGridTable(table);
        }
    }

    private static List<Node> rows(Node table) {
        List<Node> rows = new ArrayList<>();
        for (Node child : table.children) {
            if (child.is("tr")) {
                rows.add(child);
            } else if (child.is("thead", "tbody", "tfoot")) {
                for (Node r : child.children) {
                    if (r.is("tr")) {
                        rows.add(r);
                    }
                }
            }
        }
        return rows;
    }

    private static List<Node> cells(Node row) {
        List<Node> cells = new ArrayList<>();
        for (Node c : row.children) {
            if (c.is("td", "th") && !isSkipped(c)) {
                cells.add(c);
            }
        }
        return cells;
    }

    private static boolean isKeyValueTable(Node table) {
        if (table.hasClassContaining("infobox") || table.hasClass("questdetails")) {
            return true;
        }
        if (table.hasClass("wikitable")) {
            return false;
        }
        int pairs = 0;
        int total = 0;
        for (Node row : rows(table)) {
            List<Node> cells = cells(row);
            if (cells.isEmpty()) {
                continue;
            }
            total++;
            if (cells.size() == 2 && cells.get(0).is("th") && cells.get(1).is("td")) {
                pairs++;
            }
        }
        return total >= 2 && pairs * 10 >= total * 6;
    }

    /** Infoboxes and quest details: one "- **label**: value" line per row. */
    private void renderKeyValueTable(Node table) {
        List<String> versions = infoboxVersions(table);
        if (versions.size() > 1) {
            append("- **Versions**: " + String.join(", ", versions)
                    + " (values below are for " + versions.get(0) + ")\n");
        }
        List<Node> rows = rows(table);
        for (int i = 0; i < rows.size(); i++) {
            List<Node> cells = cells(rows.get(i));
            if (cells.isEmpty()) {
                continue;
            }
            boolean allTh = true;
            boolean allTd = true;
            for (Node c : cells) {
                allTh &= c.is("th");
                allTd &= c.is("td");
            }

            if (allTh && (cells.size() == 1 || allHaveClass(cells, "infobox-subheader"))) {
                List<String> parts = new ArrayList<>();
                for (Node c : cells) {
                    String t = cellLabel(c);
                    if (!t.isEmpty()) {
                        parts.add(t);
                    }
                }
                String heading = String.join(" / ", parts);
                if (!heading.isEmpty() && !heading.equalsIgnoreCase(pageTitle)) {
                    newLine();
                    append("**" + heading + "**\n");
                }
                continue;
            }

            if (allTh && i + 1 < rows.size()) {
                // A row of (often icon-only) labels with the values in the next row.
                List<Node> values = cells(rows.get(i + 1));
                if (values.size() == cells.size() && allAre(values, "td")) {
                    List<String> pairs = new ArrayList<>();
                    for (int k = 0; k < cells.size(); k++) {
                        String label = cellLabel(cells.get(k));
                        String value = cellValue(values.get(k), true);
                        if (!value.isEmpty()) {
                            pairs.add(label.isEmpty() || label.equalsIgnoreCase(value) ? value : label + ": " + value);
                        }
                    }
                    if (!pairs.isEmpty()) {
                        append("- " + String.join(", ", pairs) + "\n");
                    }
                    i++;
                    continue;
                }
            }

            if (cells.get(0).is("th") && cells.size() >= 2 && !allTh) {
                String key = cellLabel(cells.get(0));
                List<String> values = new ArrayList<>();
                List<Node> listCells = new ArrayList<>();
                for (int k = 1; k < cells.size(); k++) {
                    if (!nestedLists(cells.get(k)).isEmpty()) {
                        listCells.add(cells.get(k));
                    } else {
                        String v = cellValue(cells.get(k), true);
                        if (!v.isEmpty()) {
                            values.add(v);
                        }
                    }
                }
                if (!listCells.isEmpty()) {
                    append("- **" + key + "**:" + (values.isEmpty() ? "" : " " + String.join(" / ", values)) + "\n");
                    for (Node lc : listCells) {
                        String lead = inlineText(lc, true);
                        if (!lead.isEmpty()) {
                            append("  - " + lead + "\n");
                        }
                        for (Node list : nestedLists(lc)) {
                            renderList(list, 1);
                        }
                    }
                } else if (!values.isEmpty() && !key.isEmpty()
                        && !NOISE_ROW_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                    append("- **" + key + "**: " + String.join(" / ", values) + "\n");
                }
                continue;
            }

            List<String> values = new ArrayList<>();
            for (Node c : cells) {
                String v = allTh ? cellLabel(c) : cellValue(c);
                if (!v.isEmpty()) {
                    values.add(v);
                }
            }
            values.removeIf(v -> NOISE_TEXT.contains(v.toLowerCase(Locale.ROOT)));
            if (values.size() == 2 && allTd) {
                append("- **" + values.get(0) + "**: " + values.get(1) + "\n");
            } else if (!values.isEmpty()) {
                append("- " + String.join(" | ", values) + "\n");
            }
        }
    }

    private static List<String> infoboxVersions(Node table) {
        List<String> versions = new ArrayList<>();
        Node buttons = findByClass(table, "infobox-buttons");
        if (buttons != null) {
            for (Node b : buttons.children) {
                if (!b.isText()) {
                    String t = inlineTextIgnoringSkips(b);
                    if (!t.isEmpty()) {
                        versions.add(t);
                    }
                }
            }
        }
        return versions;
    }

    private static String inlineTextIgnoringSkips(Node node) {
        StringBuilder sb = new StringBuilder();
        collectAllText(node, sb);
        return cleanInline(sb.toString());
    }

    private static void collectAllText(Node node, StringBuilder sb) {
        for (Node child : node.children) {
            if (child.isText()) {
                sb.append(child.text);
            } else {
                collectAllText(child, sb);
            }
        }
    }

    private static Node findByClass(Node node, String cls) {
        for (Node child : node.children) {
            if (child.isText() || child.is("table")) {
                continue;
            }
            if (child.hasClass(cls)) {
                return child;
            }
            Node found = findByClass(child, cls);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean allHaveClass(List<Node> cells, String cls) {
        for (Node c : cells) {
            if (!c.hasClass(cls)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allAre(List<Node> cells, String tag) {
        for (Node c : cells) {
            if (!c.is(tag)) {
                return false;
            }
        }
        return true;
    }

    /** A header cell's label: its text, or the name of the icon or link it shows instead. */
    private static String cellLabel(Node cell) {
        String text = inlineText(cell);
        if (!text.isEmpty()) {
            return text;
        }
        List<String> icons = iconLabels(cell);
        if (!icons.isEmpty()) {
            return String.join(", ", icons);
        }
        String link = linkTitle(cell);
        return link.isEmpty() ? cell.attr("title") : link;
    }

    /** A data cell's value: its text, or the names of the icons it shows instead. */
    private static String cellValue(Node cell) {
        return cellValue(cell, false);
    }

    /**
     * A data cell's value. With {@code allowLinkTitles}, a cell showing only linked images (such
     * as an infobox's "Assigned by" row of Slayer master heads) is named by its link titles.
     */
    private static String cellValue(Node cell, boolean allowLinkTitles) {
        String text = inlineText(cell);
        if (text.isEmpty()) {
            text = String.join(", ", iconLabels(cell));
        }
        if (text.isEmpty() && allowLinkTitles) {
            List<String> titles = new ArrayList<>();
            collectLinkTitles(cell, titles);
            text = String.join(", ", titles);
        }
        if (text.length() > MAX_CELL_CHARS) {
            text = text.substring(0, MAX_CELL_CHARS) + "…";
        }
        return text;
    }

    private static int span(Node cell, String attr) {
        try {
            int v = Integer.parseInt(cell.attr(attr).trim());
            return Math.max(1, Math.min(v, 50));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Regular tables: a Markdown table with row/column spans expanded and empty columns removed. */
    private void renderGridTable(Node table) {
        List<List<String>> grid = new ArrayList<>();
        List<Boolean> headerRows = new ArrayList<>();
        Map<Integer, String> pendingText = new HashMap<>();
        Map<Integer, Integer> pendingRows = new HashMap<>();

        for (Node row : rows(table)) {
            List<Node> cells = cells(row);
            if (cells.isEmpty() && pendingRows.isEmpty()) {
                continue;
            }
            List<String> out = new ArrayList<>();
            boolean allTh = !cells.isEmpty();
            int col = 0;
            int next = 0;
            while (next < cells.size() || pendingRows.containsKey(col)) {
                Integer remaining = pendingRows.get(col);
                if (remaining != null) {
                    out.add(pendingText.get(col));
                    if (remaining <= 1) {
                        pendingRows.remove(col);
                        pendingText.remove(col);
                    } else {
                        pendingRows.put(col, remaining - 1);
                    }
                    col++;
                    continue;
                }
                Node cell = cells.get(next++);
                allTh &= cell.is("th");
                String text = (cell.is("th") ? cellLabel(cell) : cellValue(cell)).replace("|", "\\|");
                int colspan = span(cell, "colspan");
                int rowspan = span(cell, "rowspan");
                for (int k = 0; k < colspan; k++) {
                    out.add(text);
                    if (rowspan > 1) {
                        pendingRows.put(col, rowspan - 1);
                        pendingText.put(col, text);
                    }
                    col++;
                }
            }
            if (!out.isEmpty()) {
                grid.add(out);
                headerRows.add(allTh);
            }
        }
        if (grid.isEmpty()) {
            return;
        }

        int width = 0;
        for (List<String> r : grid) {
            width = Math.max(width, r.size());
        }
        for (List<String> r : grid) {
            while (r.size() < width) {
                r.add("");
            }
        }

        boolean hasHeader = headerRows.get(0) && grid.size() > 1;
        List<String> header = hasHeader ? grid.get(0) : null;
        List<List<String>> data = hasHeader ? grid.subList(1, grid.size()) : grid;

        List<Integer> keep = new ArrayList<>();
        for (int c = 0; c < width; c++) {
            boolean empty = true;
            for (List<String> r : data) {
                if (!r.get(c).isEmpty()) {
                    empty = false;
                    break;
                }
            }
            if (!empty) {
                keep.add(c);
            }
        }
        if (keep.isEmpty()) {
            return;
        }

        if (keep.size() == 1) {
            // Single-column tables are message boxes ("This article has a strategy guide"), not data.
            for (List<String> r : grid) {
                String v = r.get(keep.get(0));
                if (!v.isEmpty()) {
                    append(v + "\n");
                }
            }
            return;
        }

        Node caption = null;
        for (Node child : table.children) {
            if (child.is("caption")) {
                caption = child;
            }
        }
        if (caption != null) {
            String c = inlineText(caption);
            if (!c.isEmpty()) {
                append("**" + c + "**\n");
            }
        }

        StringBuilder sb = new StringBuilder();
        if (header != null) {
            sb.append(markdownRow(header, keep, true));
        } else {
            List<String> generic = new ArrayList<>();
            for (int c = 0; c < width; c++) {
                generic.add("");
            }
            sb.append(markdownRow(generic, keep, true));
        }
        StringBuilder divider = new StringBuilder("|");
        for (int k = 0; k < keep.size(); k++) {
            divider.append(" --- |");
        }
        sb.append(divider).append('\n');
        int shown = 0;
        for (List<String> r : data) {
            if (shown >= MAX_TABLE_ROWS) {
                break;
            }
            sb.append(markdownRow(r, keep, false));
            shown++;
        }
        if (data.size() > shown) {
            sb.append("(").append(data.size() - shown).append(" more rows not shown)\n");
        }
        append(sb.toString());
    }

    private static String markdownRow(List<String> row, List<Integer> keep, boolean header) {
        StringBuilder sb = new StringBuilder("|");
        for (int c : keep) {
            String v = row.get(c);
            if (header && v.isEmpty()) {
                v = "-";
            }
            sb.append(' ').append(v).append(" |");
        }
        return sb.append('\n').toString();
    }

    // =========================================================================
    // Entities
    // =========================================================================

    /**
     * Decodes HTML character references: numeric ones and the named ones the wiki uses.
     *
     * @param text text containing entities
     * @return decoded text
     */
    public static String decodeEntities(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text == null ? "" : text;
        }
        Matcher m = ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String ref = m.group(1);
            String replacement;
            if (ref.charAt(0) == '#') {
                try {
                    int code = (ref.charAt(1) == 'x' || ref.charAt(1) == 'X')
                            ? Integer.parseInt(ref.substring(2), 16)
                            : Integer.parseInt(ref.substring(1));
                    replacement = code == 0x200B ? "" : new String(Character.toChars(code));
                } catch (IllegalArgumentException e) {
                    replacement = m.group();
                }
            } else {
                replacement = NAMED_ENTITIES.getOrDefault(ref, m.group());
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString().replace("\u200b", "");
    }
}
