package com.osrsai;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(OsrsAiPlugin.CONFIG_GROUP)
public interface OsrsAiConfig extends Config {
        @ConfigSection(name = "General Settings", description = "General configuration for the AI assistant", position = 0)
        String generalSection = "general";

        @ConfigSection(name = "Data Sharing", description = "Configure what game context is sent to the AI", position = 10)
        String sharingSection = "sharing";

        @Range(min = 1, max = AiService.MAX_DEPTH_COUNT)
        @ConfigItem(keyName = "maxSearchDepth", name = "Max Search Depth", description = "The maximum number of recursive tool calls/wiki searches the AI can perform for a single question.", position = 1, section = generalSection)
        default int maxSearchDepth() {
                return AiService.MAX_DEPTH_COUNT / 2;
        }

        @ConfigItem(keyName = "ai_profiles_v1", name = "", description = "", hidden = true, secret = true)
        default String aiProfilesJson() {
                return "[]";
        }

        @ConfigItem(keyName = "active_profile_id", name = "", description = "", hidden = true)
        default String activeProfileId() {
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
