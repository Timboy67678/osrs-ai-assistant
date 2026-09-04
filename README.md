<h1 align="center">OSRS AI Assistant</h1>

<p align="center">
  <strong>An intelligent, context-aware AI companion plugin for RuneLite</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/RuneLite-Latest-blue.svg" alt="RuneLite Version" />
  <img src="https://img.shields.io/badge/Build-Gradle-green.svg" alt="Build Status" />
  <img src="https://img.shields.io/badge/Java-11-orange.svg" alt="Java Version" />
  <img src="https://img.shields.io/badge/AI--Powered-Grok%20%7C%20Gemini%20%7C%20Claude%20%7C%20GPT--4o%20%7C%20Custom-purple.svg" alt="AI Powered" />
</p>

---

**OSRS AI Assistant** is a RuneLite plugin that brings state-of-the-art AI language models directly into your Old School RuneScape client. Designed as a real-time, context-aware in-game companion, it autonomously inspects your character's stats, gear, quests, slayer tasks, bank, surroundings, and active location to provide accurate strategy, gearing advice, skilling guides, PvM tactics, and quest help.

> [!NOTE]
> Powered by an autonomous function-calling system, the AI dynamically inspects live player state and searches the **OSRS Wiki** in real time to guarantee answers stay grounded in current game mechanics.

---

## ✨ Features

- **🎮 Autonomous Game Context Integration:**
  - Automatically analyzes player base & boosted skill levels, XP, run energy, prayer points, active prayers, and combat vitals.
  - Detects current location, Wilderness level (HUD widget & coordinate fallback), multi-combat zones, instanced areas, and world types (PvP, High Risk, Members).
  - Inspects active inventory, equipped items, and bank contents (with automatic offline snapshot caching when closed) with live GE and High Alchemy prices.
  - Scans surrounding game environment for nearby NPCs/monsters, other players (with Wilderness threat detection), valuable ground items, and interactable game objects (altars, bank booths, fairy rings, portals).
  - Monitors farming patch growth, harvest readiness, birdhouse run timers, Hespori growth, and Tears of Guthix cooldowns.
  - Tracks active vessel stats, sail trim, knot speed, cargo, and crew when sailing.
- **🗺️ Interactive Pathfinding & Navigation:**
  - Integrates with the **Shortest Path** plugin via cross-plugin communication to automatically set route overlays to coordinates or named POIs (e.g. Farming Guild, Barrows, Chasm of Fire).
- **🛡️ Account-Type Awareness:**
  - Full support for Main, Ironman, Ultimate Ironman (UIM), Hardcore Ironman (HCIM), Group Ironman (GIM), Hardcore GIM, and Unranked GIM accounts.
  - Automatically tailors advice based on account restrictions (e.g. prioritizing High Alchemy values over GE prices, noting self-sufficiency, and avoiding invalid trading suggestions for Ironmen).
- **🧠 Multi-Model AI Support & Profiles:**
  - Built-in **AI Profile Manager** to easily create, configure, and switch between multiple AI profiles and providers.
  - **Google Gemini:** Gemini 3.6 Flash, Gemini 2.5 Flash, Gemini 2.5 Pro.
  - **Anthropic Claude:** Claude Sonnet 3.7, Claude Sonnet 3.5, Claude Haiku 3.5.
  - **OpenAI:** GPT-4o, GPT-4o Mini, o3-mini, o1.
  - **xAI Grok:** Grok 4.20 Reasoning, Grok 4.3, Grok 3, Grok 3 Reasoning.
  - **Custom / Local AI:** Connect to Ollama, LM Studio, LocalAI, vLLM, or any OpenAI-compatible API endpoint with custom model IDs.
- **🗂️ Multi-Session & Window Flexibility:**
  - Create, switch, and delete multiple chat sessions with persistent local history.
  - Usable as a standard RuneLite sidebar panel or detached as an independent floating window (remembers position, size, and multi-monitor bounds).
- **🔔 Audio & OS Notifications:**
  - Optional RuneScape-themed sound effects and desktop/client notifications when AI answers finish generating.

---

## 🛠️ Exposed AI Tools (Function Calling)

The AI assistant has access to a comprehensive suite of **22 custom tools** enabling it to query live game state, scan surroundings, navigate, and verify wiki data before generating answers:

### 📊 Player State & Status Tools
- `get_player_skills` — Retrieves base levels, boosted levels, XP, and level-up progress. Supports filtering by specific skill and calculating remaining XP to a target level.
- `get_player_inventory` — Retrieves items, quantities, Grand Exchange market prices, and High Alchemy values currently in inventory.
- `get_player_equipment` — Retrieves equipped gear, quantities, GE prices, and High Alchemy values across all equipment slots.
- `get_player_bank` — Retrieves bank items, quantities, GE prices, and High Alchemy values when open (or from cached snapshot when closed). Supports search filtering and value thresholds.
- `get_player_status` — Retrieves real-time combat status (current HP, Prayer points, active prayers, poison/venom state, run energy, special attack %, and active status timers).
- `get_player_currencies_and_points` — Retrieves minigame currencies, tokens, and reward points (e.g. NMZ points, Pest Control commendations, Tithe Farm points, Golden Nuggets, Abyssal Pearls, Marks of Grace, Slayer points, Archery tickets).
- `get_player_location_details` — Retrieves location attributes including Wilderness level, multi-combat status, instanced area check, world types, and region ID.

### ⚔️ Activities, Quests & Progression Tools
- `get_player_slayer_task` — Retrieves current standard Slayer task AND Mortimer Slayer task, remaining kill counts, assigned locations, Slayer masters, Slayer points, streaks (standard & Mortimer), unlocked perks, and block lists.
- `get_player_quests` — Retrieves total quest points, completed quest count, and status lists (`IN_PROGRESS` with quest stages, `NOT_STARTED`, `COMPLETED`, `ALL`).
- `get_player_achievement_diaries` — Retrieves Achievement Diary completion progress for all 12 regions across Easy, Medium, Hard, and Elite tiers.
- `get_player_combat_achievements` — Retrieves Combat Achievement tier progress (Easy through Grandmaster), boss/monster kill counts (KC), and task completion. Supports filtering by tier, boss name, completion status, or task name.
- `get_player_clues` — Retrieves active clue scroll details (current step text, requirements, solution) and clue scroll items in inventory or bank.
- `get_player_farming_and_timers` — Retrieves active farming patch growth stages, harvest readiness, birdhouse run timers, Hespori growth state, Tears of Guthix cooldown, and active infobox activity timers.
- `get_player_sailing_status` — Retrieves active vessel details, ship tier/type, hull HP/condition, sail trim, knot speed, wind direction, anchor status, cargo hold items, crew members, and sea location.

### 🗺️ Environment, World & Navigation Tools
- `get_surrounding_environment` — Scans immediate surroundings within render distance for nearby NPCs and monsters, nearby players (with Wilderness threat status), valuable unlooted ground items, and interactable game objects (altars, bank booths, fairy rings, portals).
- `get_player_transportation` — Retrieves unlocked travel networks, teleport unlocks (Fairy Rings, Spirit Trees, Gnome Gliders, Balloons, Ectophial, Drakkan's Medallion, Royal Seed Pod), current spellbook teleports, Construction POH portal access, and teleport items in inventory/bank.
- `set_shortest_path_target` — Sets a destination coordinate (X, Y, Plane) or named POI in the Shortest Path plugin to draw a route overlay on the game screen.
- `clear_shortest_path_target` — Clears the currently displayed route overlay and destination target in the Shortest Path plugin.

### 💰 Economy & Knowledge Tools
- `get_player_ge_offers` — Retrieves Grand Exchange offer slots (active, completed, or collecting buy/sell orders, prices, filled vs total quantities, and GP spent/received).
- `get_market_prices` — Retrieves live Grand Exchange market prices, High Alchemy values, Nature Rune costs, and calculated High Alchemy profit margins for specified items.
- `get_item_stats` — Retrieves equipment stats, combat bonuses, weight, slot type, and market prices for specified item names or IDs.
- `search_osrs_wiki` — Performs a live search on the Old School RuneScape Wiki for authoritative game mechanics, boss guides, drop tables, quest requirements, training methods, and recent game updates.

---

## ⚙️ Configuration

Open **RuneLite Settings (wrench icon)**, search for **OSRS AI Assistant**, or open the plugin panel to configure:

### General & Privacy Settings (RuneLite Settings Panel)
| Setting | Description |
| :--- | :--- |
| **Max Search Depth** | Maximum recursive tool calls/wiki searches the AI can perform for a single question (1–10, default 5). |
| **Use Shortest Path Plugin** | Allow the AI to set path destinations using the Shortest Path plugin if installed and enabled. |
| **Share Character Info** | Toggle sharing player stats, location, gear, quests, and bank data with the external AI provider. |
| **Notify on Response** | Play a RuneScape-themed sound effect and send a notification when an AI response is ready. |

### AI Profile Manager (In-Plugin UI Panel)
Click the profile selector or settings icon in the top header of the AI Assistant panel to manage AI profiles:
- **Preset Selection:** Choose from popular ready-to-use presets (Grok 4.20 Reasoning, Gemini 2.5 Flash, Claude Sonnet 3.7, GPT-4o, etc.).
- **API Key & Org ID:** Securely configure API keys per profile.
- **Custom / Local AI:** Point to local OpenAI-compatible endpoints (e.g. `http://localhost:11434/v1/chat/completions`) with custom model names.

---

## 🎨 UI Preview

<div align="center">

| 💬 Sidebar Panel | 🪟 Detached Window | ⚙️ Plugin Settings |
| :---: | :---: | :---: |
| [<img src="assets/ui_preview.png" alt="Sidebar Panel Preview" width="100%" />](assets/ui_preview.png) | [<img src="assets/detached_preview.png" alt="Detached Window Preview" width="100%" />](assets/detached_preview.png) | [<img src="assets/settings_preview.png" alt="Plugin Settings Preview" width="100%" />](assets/settings_preview.png) |

</div>

---

## 🚀 Installation

### For Players

Installation is supported via the **RuneLite Plugin Hub** only.

- **Plugin Hub listing: TBA**
---

## 🧪 Development

If you’re contributing or running locally for development:

1. Clone this repository:
   ```bash
   git clone https://github.com/Timboy67678/osrs-ai-assistant.git
   cd osrs-ai-assistant
   ```
2. Run the plugin in developer mode:
   ```bash
   ./gradlew run
   ```
   *(On Windows, use `./gradlew.bat run`)*

---

## 🤖 AI Disclosure & Development Notes

This project was built entirely by **Google Antigravity AI**, an agentic coding assistant developed by the Google DeepMind team. 

- **Primary Developer:** Google Antigravity AI (via autonomous execution loops)
- **Collaboration Model:** Pair programming with @Timboy67678
- **Core Technology:** DeepMind Gemini Models and advanced software agent toolkits

Every component in this repository—including the RuneLite plugin configuration interface, the Swing UI panels, thread management, dynamic OSRS Wiki context resolvers, function-calling tool registries, and the Gradle build configuration—was structured, written, and refactored by the AI assistant. 

---

## 📄 License

This project is licensed under the BSD 2-Clause License - see the [LICENSE](LICENSE) file for details.
