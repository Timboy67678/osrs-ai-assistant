package com.osrsai;

import com.google.inject.Provides;

import javax.inject.Inject;
import javax.swing.SwingUtilities;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameStateChanged;

import java.awt.image.BufferedImage;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
        name = "OSRS AI Assistant",
        description = "An AI chatbot assistant that reads your in-game stats. WARNING: Sends query & selected game details to third-party AI APIs (Gemini/OpenAI/Claude/Grok). Requires an external API key.",
        tags = {"ai", "chatbot", "gemini", "assistant"}
)
public class OsrsAiPlugin extends Plugin {
    @Inject
    private OsrsAiConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private AiService aiService;

    private OsrsAiPanel panel;
    private NavigationButton navButton;

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

    @Override
    protected void shutDown() throws Exception {
        log.info("OSRS AI Assistant stopped!");
        if (panel != null) {
            panel.closeDetachedWindow();
        }
        clientToolbar.removeNavigation(navButton);
    }

    public void askQuestion(String question) {
        log.debug("Question asked: {}", question);
        aiService.sendQuestion(question, panel);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged) {

    }

    @Provides
    OsrsAiConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(OsrsAiConfig.class);
    }
}
