package com.osrsai;

import com.google.inject.Provides;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import java.awt.image.BufferedImage;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

/**
 * The main RuneLite plugin class for the OSRS AI Assistant.
 * <p>
 * This class handles plugin startup/shutdown, registers the sidebar navigation
 * button and UI panel,
 * injects required RuneLite services, and routes user questions and
 * configuration change events.
 */
@Slf4j
@PluginDescriptor(name = "OSRS AI Assistant", description = "An AI chatbot assistant that reads your in-game stats. WARNING: Sends query & selected game details to third-party AI APIs (Gemini/OpenAI/Claude/Grok). Requires an external API key.", tags = {
        "ai", "chatbot", "gemini", "assistant" })
public class OsrsAiPlugin extends Plugin {
    /**
     * Config group key used for persisting plugin settings in RuneLite's
     * ConfigManager.
     */
    public static final String CONFIG_GROUP = "osrsai";

    @Inject
    private OsrsAiConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private AiService aiService;

    /** The primary UI panel attached to the RuneLite sidebar or detached window. */
    private OsrsAiPanel panel;

    /** Navigation button displayed on the RuneLite sidebar. */
    private NavigationButton navButton;

    /**
     * Starts up the plugin by initializing the UI panel, scaling the navigation
     * icon,
     * adding the sidebar button to the client toolbar, and restoring detached
     * window state if enabled.
     *
     * @throws Exception if an initialization error occurs during startup
     */
    @Override
    protected void startUp() throws Exception {
        log.info("OSRS AI Assistant started!");
        panel = new OsrsAiPanel(this, configManager);

        BufferedImage rawIcon = ImageUtil.loadImageResource(getClass(), "/com/osrsai/icon.png");
        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = icon.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(rawIcon, 0, 0, 16, 16, null);
        g.dispose();

        navButton = NavigationButton.builder()
                .tooltip("AI Assistant")
                .icon(icon)
                .priority(5)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);

        if (config.windowDetached()) {
            SwingUtilities.invokeLater(panel::restoreDetachedWindow);
        }
    }

    /**
     * Shuts down the plugin by closing any detached windows and removing the
     * navigation button.
     *
     * @throws Exception if an error occurs during shutdown
     */
    @Override
    protected void shutDown() throws Exception {
        log.info("OSRS AI Assistant stopped!");
        if (panel != null) {
            panel.closeDetachedWindow();
        }
        clientToolbar.removeNavigation(navButton);
    }

    /**
     * Sends a user query to the AI service for processing and response rendering.
     *
     * @param question the user's natural language question
     */
    public void askQuestion(String question) {
        log.debug("Question asked: {}", question);
        aiService.sendQuestion(question, panel);
    }

    /**
     * Gets the active plugin configuration instance.
     *
     * @return the {@link OsrsAiConfig} instance
     */
    public OsrsAiConfig getConfig() {
        return config;
    }

    /**
     * Listens for changes to the plugin's configuration settings and updates UI
     * components accordingly.
     *
     * @param event the {@link ConfigChanged} event emitted by RuneLite
     */
    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (CONFIG_GROUP.equals(event.getGroup())) {
            if ("shareCharacterInfo".equals(event.getKey())) {
                if (panel != null) {
                    SwingUtilities.invokeLater(panel::updateWarningLabel);
                }
            }
        }
    }

    /**
     * Listens for item container updates to maintain offline bank caching.
     *
     * @param event the {@link net.runelite.api.events.ItemContainerChanged} event
     */
    @Subscribe
    public void onItemContainerChanged(net.runelite.api.events.ItemContainerChanged event) {
        if (event.getContainerId() == net.runelite.api.InventoryID.BANK.getId()) {
            aiService.updateCachedBank(event.getItemContainer());
        }
    }

    /**
     * Provides the configuration instance bound to the RuneLite Dependency
     * Injection system.
     *
     * @param configManager the RuneLite {@link ConfigManager}
     * @return the configured {@link OsrsAiConfig} instance
     */
    @Provides
    OsrsAiConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(OsrsAiConfig.class);
    }
}
