package com.osrsai;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("osrsai")
public interface OsrsAiConfig extends Config {
        @ConfigSection(name = "API Settings", description = "Configure your AI provider and API keys. Note: Chat queries and selected game details are sent to external AI servers.", position = 0)
        String apiSection = "api";

        @ConfigSection(name = "Custom / Local AI Settings", description = "Configure settings for custom or local AI models.", position = 5, closedByDefault = true)
        String customSection = "customApi";

        @ConfigSection(name = "Data Sharing", description = "Configure what game context is sent to the AI", position = 10)
        String sharingSection = "sharing";

        @ConfigItem(keyName = "aiProvider", name = "AI", description = "Select the AI brain to use.", position = 1, section = apiSection)
        default AiProvider aiProvider() {
                return AiProvider.OPENAI;
        }

        @ConfigItem(keyName = "apiKey", name = "API Key", description = "The API key for your selected provider. Kept secret, but used for external AI API communication.", position = 2, secret = true, section = apiSection)
        default String apiKey() {
                return "";
        }

        @ConfigItem(keyName = "clientId", name = "Org ID", description = "Optional client ID (Required if provider specifies it).", position = 3, secret = true, section = apiSection)
        default String clientId() {
                return "";
        }

        @Range(min = 1, max = AiService.MAX_DEPTH_COUNT)
        @ConfigItem(keyName = "maxSearchDepth", name = "Max Search Depth", description = "The maximum number of recursive tool calls/wiki searches the AI can perform for a single question.", position = 4, section = apiSection)
        default int maxSearchDepth() {
                return AiService.MAX_DEPTH_COUNT / 2;
        }

        @ConfigItem(keyName = "customEndpoint", name = "Custom Endpoint", description = "The endpoint URL for a custom or local OpenAI-compatible API (e.g. http://localhost:11434/v1/chat/completions). Only used when Provider is Custom.", position = 6, section = customSection)
        default String customEndpoint() {
                return "";
        }

        @ConfigItem(keyName = "customModel", name = "Custom Model ID", description = "Override the AI model name/ID (e.g., gpt-4-turbo, gemini-1.5-pro, or your local model name). Leave blank to use default.", position = 7, section = customSection)
        default String customModel() {
                return "";
        }

        @ConfigItem(keyName = "shareCharacterInfo", name = "Share Character Info", description = "WARNING: When enabled, this option will send your in-game stats, location, active task, inventory, equipment, quests, achievement diaries, and bank contents to the external AI provider whenever you submit a query.", position = 11, section = sharingSection)
        default boolean shareCharacterInfo() {
                return true;
        }

        @ConfigItem(keyName = "notifyOnResponse", name = "Notify on Response", description = "Play a RuneScape-themed sound effect and send a notification when the AI response is ready.", position = 16, section = sharingSection)
        default boolean notifyOnResponse() {
                return true;
        }

        // --- Hidden window persistence entries (not shown in config UI) ---

        @ConfigItem(keyName = "windowDetached", name = "", description = "", hidden = true)
        default boolean windowDetached() {
                return false;
        }

        @ConfigItem(keyName = "windowX", name = "", description = "", hidden = true)
        default int windowX() {
                return -1;
        }

        @ConfigItem(keyName = "windowY", name = "", description = "", hidden = true)
        default int windowY() {
                return -1;
        }

        @ConfigItem(keyName = "windowWidth", name = "", description = "", hidden = true)
        default int windowWidth() {
                return 700;
        }

        @ConfigItem(keyName = "windowHeight", name = "", description = "", hidden = true)
        default int windowHeight() {
                return 650;
        }
}
