# OSRS AI Assistant

RuneLite plugin (Java 11, Gradle, Lombok) that adds an AI chat panel. The model answers OSRS questions by calling tools that read live game state and search the OSRS Wiki.

## Commands

- Run RuneLite in developer mode with the plugin: `./gradlew.bat run`
- Tests (skips Swing frame tests): `./gradlew.bat test -Djava.awt.headless=true`
- CI (`.github/workflows/gradle.yml`) runs `./gradlew build` on JDK 11.

## Architecture

- `OsrsAiPlugin` — startup/shutdown, sidebar button, bank cache via `ItemContainerChanged`.
- `AiService` — request pipeline: reads the active `AiProfile` (JSON in config), builds game context on the client thread, sends the request on the executor, then loops on tool calls up to `maxSearchDepth` (hard cap `MAX_DEPTH_COUNT` = 15). On the last allowed step it calls `disableToolCalling`. Client-thread tools have a 30s timeout.
- `provider/` — `AiProvider` enum lists models and maps each to a `ProviderHandler`:
  - `ClaudeProviderHandler` (Messages API, prompt caching, server-side refusal fallback for Opus/Sonnet 5.5/Fable)
  - `GeminiProviderHandler`
  - `OpenAiProviderHandler` — shared by OpenAI, Grok and custom/local endpoints.
  Retired model names stay as `@SerializedName` alternates so saved profiles keep loading. Never drop an alternate when replacing a model.
- `tools/OsrsToolRegistry` — all tool definitions (name, description, params, `requiresCharacterInfo`, `runOnClientThread`, executor). Executors delegate through `AiService` to domain classes in `tools/`.
- `util/PromptUtils` — system prompt (`INSTRUCTIONS`): source-of-truth order, grounding rules, how to use the wiki tool's sections and statuses, and a few behaviour rules (account type, risk, gear slots). Don't add game facts (levels, drop rates, gear requirements) here: they go stale and override the wiki. Fix wrong answers by improving the tools or the wiki parser instead. Keep it small for local models (`PromptUtilsTest` caps it at 7,000 chars).
- `util/WikiSearchUtil` — `search_osrs_wiki` lookups: exact title, then wiki search (irrelevant results are offered as candidates, not returned). Long pages return summary + `availableSections`; the model requests sections by name. Connection failures return `status: error`, never "not found". Pages are cached 30 min.
- `util/WikiHtmlParser` — dependency-free (no jsoup) HTML tree builder and Markdown renderer for wiki `action=parse` output. Icon-only stat cells are labelled from `alt`/`title`; `.hidden`/switch-resource data and wiki UI are dropped. `WikiHtmlParserTest` covers it offline with a fake HTTP layer.
- `ui/OsrsAiPanel` — chat sessions, profile manager, detachable window.

## Constraints

- Compile against what RuneLite ships at runtime: OkHttp 3.x (use `RequestBody.create(MediaType, String)`), RuneLite's Gson. Don't add third-party dependencies; Plugin Hub review rejects most.
- Game state (`Client`) must be read on the client thread.
- Player data can appear in requests/responses; keep it out of INFO logs (DEBUG only).
- Keep `README.md` in sync when tools or the model lineup change.
