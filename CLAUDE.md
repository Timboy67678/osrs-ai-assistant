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
- `util/PromptUtils` — system prompt, including many hand-written game grounding rules. Edit carefully; rules exist to stop specific wrong answers.
- `util/WikiSearchUtil` — wiki search plus a dependency-free HTML→markdown converter (no jsoup).
- `ui/OsrsAiPanel` — chat sessions, profile manager, detachable window.

## Constraints

- Compile against what RuneLite ships at runtime: OkHttp 3.x (use `RequestBody.create(MediaType, String)`), RuneLite's Gson. Don't add third-party dependencies; Plugin Hub review rejects most.
- Game state (`Client`) must be read on the client thread.
- Player data can appear in requests/responses; keep it out of INFO logs (DEBUG only).
- Keep `README.md` in sync when tools or the model lineup change.

## Commit messages

- Base the message only on the actual diff (`git status`, `git diff HEAD`, and unpushed commits via `git log @{u}..HEAD`), not on conversation context.
- Subject: English, starts with a capital, imperative (e.g. `Fix login redirection issue`), no trailing period, concise.
- Body: short bullet list of the changes. Add 1–2 paragraphs only for complicated or breaking API changes.
- Show the proposed message and get confirmation before running `git commit`.
