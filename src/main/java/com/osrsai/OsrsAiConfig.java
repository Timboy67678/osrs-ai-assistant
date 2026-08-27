package com.osrsai;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

/**
 * Configuration interface for the OSRS AI Assistant plugin.
 * <p>
 * Defines config options managed by RuneLite's ConfigManager, including tool
 * execution depth,
 * pathfinding integration, profile persistence, and data sharing options.
 */
@ConfigGroup(OsrsAiPlugin.CONFIG_GROUP)
public interface OsrsAiConfig extends Config {
        /** Section identifier for general plugin settings. */
        @ConfigSection(name = "General Settings", description = "General configuration for the AI assistant", position = 0)
        String generalSection = "general";

        /** Section identifier for data sharing and privacy settings. */
        @ConfigSection(name = "Data Sharing", description = "Configure what game context is sent to the AI", position = 10)
        String sharingSection = "sharing";

        /**
         * Gets the maximum search depth (recursive tool calls / wiki lookups) allowed
         * per question.
         *
         * @return max search depth count
         */
        @Range(min = 1, max = AiService.MAX_DEPTH_COUNT)
        @ConfigItem(keyName = "maxSearchDepth", name = "Max Search Depth", description = "The maximum number of recursive tool calls/wiki searches the AI can perform for a single question.", position = 1, section = generalSection)
        default int maxSearchDepth() {
                return AiService.MAX_DEPTH_COUNT / 2;
        }

        /**
         * Indicates whether the AI is permitted to set route overlays using the
         * Shortest Path plugin.
         *
         * @return {@code true} if Shortest Path integration is enabled; {@code false}
         *         otherwise
         */
        @ConfigItem(keyName = "useShortestPath", name = "Use Shortest Path Plugin", description = "Allow the AI to set path destinations using the Shortest Path plugin if it is installed and enabled.", position = 2, section = generalSection)
        default boolean useShortestPath() {
                return true;
        }

        /**
         * Hidden configuration entry storing serialized AI profiles as a JSON array
         * string.
         *
         * @return JSON string of AI profiles
         */
        @ConfigItem(keyName = "ai_profiles_v1", name = "", description = "", hidden = true, secret = true)
        default String aiProfilesJson() {
                return "[]";
        }

        /**
         * Hidden configuration entry storing the ID of the currently active AI profile.
         *
         * @return active profile ID
         */
        @ConfigItem(keyName = "active_profile_id", name = "", description = "", hidden = true)
        default String activeProfileId() {
                return "";
        }

        /**
         * Indicates whether in-game player context (stats, inventory, bank, location,
         * etc.) is sent to the AI provider.
         *
         * @return {@code true} if character information is shared; {@code false}
         *         otherwise
         */
        @ConfigItem(keyName = "shareCharacterInfo", name = "Share Character Info", description = "WARNING: When enabled, this option will send your in-game stats, location, active task, inventory, equipment, quests, achievement diaries, and bank contents to the external AI provider whenever you submit a query.", position = 11, section = sharingSection)
        default boolean shareCharacterInfo() {
                return true;
        }

        /**
         * Indicates whether a RuneScape sound effect and notification are played when
         * an AI response completes.
         *
         * @return {@code true} if notifications are enabled; {@code false} otherwise
         */
        @ConfigItem(keyName = "notifyOnResponse", name = "Notify on Response", description = "Play a RuneScape-themed sound effect and send a notification when the AI response is ready.", position = 16, section = sharingSection)
        default boolean notifyOnResponse() {
                return true;
        }

        // --- Hidden window persistence entries (not shown in config UI) ---

        /**
         * Indicates whether the plugin UI panel is currently detached into its own
         * window frame.
         *
         * @return {@code true} if detached; {@code false} if docked in sidebar
         */
        @ConfigItem(keyName = "windowDetached", name = "", description = "", hidden = true)
        default boolean windowDetached() {
                return false;
        }

        /**
         * Gets the stored screen X position of the detached window frame.
         *
         * @return X coordinate, or -1 if uninitialized
         */
        @ConfigItem(keyName = "windowX", name = "", description = "", hidden = true)
        default int windowX() {
                return -1;
        }

        /**
         * Gets the stored screen Y position of the detached window frame.
         *
         * @return Y coordinate, or -1 if uninitialized
         */
        @ConfigItem(keyName = "windowY", name = "", description = "", hidden = true)
        default int windowY() {
                return -1;
        }

        /**
         * Gets the stored width of the detached window frame in pixels.
         *
         * @return window width
         */
        @ConfigItem(keyName = "windowWidth", name = "", description = "", hidden = true)
        default int windowWidth() {
                return 700;
        }

        /**
         * Gets the stored height of the detached window frame in pixels.
         *
         * @return window height
         */
        @ConfigItem(keyName = "windowHeight", name = "", description = "", hidden = true)
        default int windowHeight() {
                return 650;
        }
}
