<p align="center">
  <img src="assets/logo.png" alt="OSRS AI Assistant Logo" width="350" />
</p>

<h1 align="center">OSRS AI Assistant</h1>

<p align="center">
  <strong>An intelligent, context-aware chatbot plugin for RuneLite</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/RuneLite-1.12.32-blue.svg" alt="RuneLite Version" />
  <img src="https://img.shields.io/badge/Build-Gradle-green.svg" alt="Build Status" />
  <img src="https://img.shields.io/badge/Java-11-orange.svg" alt="Java Version" />
  <img src="https://img.shields.io/badge/AI--Powered-Gemini%20%7C%20Claude%20%7C%20GPT--4%20%7C%20Grok-purple.svg" alt="AI Powered" />
  <img src="https://img.shields.io/badge/Developed%20By-Antigravity%20AI-red.svg" alt="Developed By Antigravity AI" />
</p>

---

**OSRS AI Assistant** is a powerful RuneLite plugin that integrates advanced Large Language Models directly into your Old School RuneScape client. Designed to act as an omniscient in-game companion, it reads your real-time character stats, equipment, inventory, and location to provide tailored advice, quest guidance, slayer strategies, and general game information.

> [!NOTE]
> All queries are dynamically supplemented with real-time searches from the official **OSRS Wiki** to ensure responses are up-to-date with the latest game updates and meta strategies.

---

## ✨ Features

- **🎮 Real-Time Game Context:** Dynamically analyzes your player profile including:
  - Combat levels, skill levels, total level, and current run energy.
  - Equipped items, inventory slots, and active bank contents (when open).
  - Detailed location mapping (including instanced areas like Raids or Boss rooms, coordinates, and Region IDs).
  - Active Slayer tasks, target monsters, and slayer streaks.
- **🧠 Multi-Model Integration:** Support for major AI providers. Select your preferred brain in the configuration panel:
  - **Google Gemini 2.5 Flash** (Default)
  - **OpenAI GPT-4o**
  - **Anthropic Claude 3.5 Sonnet**
  - **xAI Grok 4**
- **🗂️ Session & History Management:**
  - Create, save, and switch between up to 15 concurrent chat sessions.
  - Automatic persistent storage of chat histories using RuneLite's ConfigManager.
  - Ability to delete sessions or start fresh with a single click.
- **🖥️ Detachable UI Panel:**
  - Sidebar plugin panel for standard play.
  - Quick-detach button to move the chatbot into its own independent OS window.
  - Automatically remembers window position, size, and layout between sessions.
- **📚 OSRS Wiki Integration:**
  - The plugin automatically scans your questions and character status to perform keyword-based Wiki searches.
  - Pulls context extracts from the official wiki to feed directly into the AI prompt budget.
- **🔊 RuneScape-themed Notifications:**
  - Plays classic, immersion-friendly sound effects when a reply is ready.
  - Sends OS notifications if the RuneLite window is minimized or unfocused.

---

## 🛠️ Configuration

To set up the plugin, navigate to the **RuneLite Settings (wrench icon)**, search for **OSRS AI Assistant**, and adjust the following configurations:

| Section | Setting | Description |
| :--- | :--- | :--- |
| **API Settings** | **AI Provider** | Choose between Gemini, OpenAI, Claude, or Grok. |
| **API Settings** | **API Key** | Input the secret API key for your chosen provider. |
| **API Settings** | **Org ID** | Optional Organization/Client ID if required by the provider. |
| **Data Sharing** | **Share Character Info** | Toggle whether character stats, inventory, and coordinates are sent to the AI. |
| **Data Sharing** | **Notify on Response** | Toggle OSRS sound effects and desktop notifications on response completion. |

---

## 🚀 Getting Started

### Prerequisites

- [Java 11 Development Kit (JDK)](https://adoptium.net/temurin/releases/?version=11)
- Gradle (Included via wrapper `./gradlew`)

### For Players (Manual Installation)

1. Clone or download this repository.
2. Locate your local `.runelite/plugins` directory.
3. Build the plugin jar using Gradle:
   ```bash
   ./gradlew build
   ```
4. Copy the compiled `.jar` file from `runelite-plugin/build/libs/` into your RuneLite plugin directory.
5. Restart your RuneLite client.

### For Developers

1. Clone this repository:
   ```bash
   git clone https://github.com/Timboy67678/osrs-ai-assistant.git
   cd osrs-ai-assistant
   ```
2. Run the plugin in developer mode with RuneLite:
   ```bash
   cd runelite-plugin
   ./gradlew run
   ```
   *(On Windows, use `.\gradlew.bat run`)*

---

## 🎨 UI Preview

Below is a preview of the plugin interface in action inside RuneLite:

<p align="center">
  <img src="assets/ui_preview.png" alt="OSRS AI Assistant UI Preview" width="300" />
</p>

---

## 🤖 AI Disclosure & Development Notes

This project was built entirely by **Google Antigravity AI**, an agentic coding assistant developed by the Google DeepMind team. 

- **Primary Developer:** Google Antigravity AI (via autonomous execution loops)
- **Collaboration Model:** Pair programming with @Timboy67678
- **Core Technology:** DeepMind Gemini Models and advanced software agent toolkits

Every component in this repository—including the RuneLite plugin configuration interface, the Swing UI panels, thread management, dynamic OSRS Wiki context resolvers, and the Gradle build configuration—was structured, written, and refactored by the AI assistant. 

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
