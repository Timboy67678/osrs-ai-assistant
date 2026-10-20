package com.osrsai;

import com.osrsai.ui.OsrsAiPanel;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Method;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class OsrsAiPanelTest {
    @Test
    public void formatsHeadingsListsAndEscaping() throws Exception {
        OsrsAiPanel panel = new OsrsAiPanel(null, null);
        Method formatter = OsrsAiPanel.class.getDeclaredMethod("formatMarkdownToHtml", String.class);
        formatter.setAccessible(true);

        String input = "### Player Profile\n- Name: tim\n- **Combat**: 91\n\nSafe <tag>";
        String html = (String) formatter.invoke(panel, input);

        Assert.assertTrue(html.contains("font-weight:bold"));
        Assert.assertTrue(html.contains("<ul"));
        Assert.assertTrue(html.contains("<li>Name: tim</li>"));
        Assert.assertTrue(html.contains("<li><b>Combat</b>: 91</li>"));
        Assert.assertTrue(html.contains("Safe &lt;tag&gt;"));
    }

    @Test
    public void slashWrapPointsKeepUrlsIntact() throws Exception {
        Method wrap = OsrsAiPanel.class.getDeclaredMethod("addSlashWrapPoints", String.class);
        wrap.setAccessible(true);

        String out = (String) wrap.invoke(null,
                "Use runes/staves, see https://oldschool.runescape.wiki/w/Abyssal_whip for details");

        Assert.assertTrue(out.contains("runes/ staves"));
        Assert.assertEquals("URL text must be unchanged apart from zero-width break points",
                "https://oldschool.runescape.wiki/w/Abyssal_whip",
                out.substring(out.indexOf("https"), out.indexOf(" for")).replace("​", ""));
        Assert.assertFalse(out.contains("https:// "));
    }

    @Test
    public void recentConversationTruncatesLongAiReplies() {
        OsrsAiPanel panel = new OsrsAiPanel(null, null);
        StringBuilder longReply = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longReply.append("word ");
        }
        panel.addMessage("You", "hi");
        panel.addMessage("AI", longReply.toString());

        String context = panel.getRecentConversationContext("next question");
        String aiLine = context.substring(context.indexOf("AI: "));
        Assert.assertTrue("AI reply should be truncated, was " + aiLine.length(), aiLine.length() <= 4 + 350);
        Assert.assertTrue(aiLine.endsWith("..."));
    }

    @Test
    public void sessionControlsLockedWhileThinking() throws Exception {
        OsrsAiPanel panel = new OsrsAiPanel(null, null);
        panel.setThinking(true);
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        for (String name : new String[] { "chatSessionComboBox", "newChatButton", "deleteChatButton" }) {
            java.lang.reflect.Field f = OsrsAiPanel.class.getDeclaredField(name);
            f.setAccessible(true);
            Assert.assertFalse(name + " should be disabled while thinking",
                    ((javax.swing.JComponent) f.get(panel)).isEnabled());
        }
        panel.setThinking(false);
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        java.lang.reflect.Field f = OsrsAiPanel.class.getDeclaredField("newChatButton");
        f.setAccessible(true);
        Assert.assertTrue(((javax.swing.JComponent) f.get(panel)).isEnabled());
    }

    @Test
    public void testChatWrappingAdaptsToWidth() throws Exception {
        Assume.assumeFalse("Skipping UI test in headless environment", GraphicsEnvironment.isHeadless());
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                javax.swing.JFrame frame = new javax.swing.JFrame();
                OsrsAiPanel panel = new OsrsAiPanel(null, null);
                frame.getContentPane().add(panel);
                frame.setSize(150, 600);
                frame.setVisible(true);

                java.lang.reflect.Field chatAreaField = OsrsAiPanel.class.getDeclaredField("chatArea");
                chatAreaField.setAccessible(true);
                javax.swing.JEditorPane chatArea = (javax.swing.JEditorPane) chatAreaField.get(panel);

                java.lang.reflect.Field scrollPaneField = OsrsAiPanel.class.getDeclaredField("chatScrollPane");
                scrollPaneField.setAccessible(true);
                javax.swing.JScrollPane scrollPane = (javax.swing.JScrollPane) scrollPaneField.get(panel);

                panel.addMessage("AI", "Do your Cockatrice task in the Slayer Dungeon (south of Rellekka).\n\n"
                        + "You have full access (completed Fremennik Trials + Isles + Exiles).\n\n"
                        + "Recommended gear (Ironman-focused, using what you own):\n\n"
                        + "- Helm: Slayer helmet (I) — gives 15% boost\n"
                        + "- Body/Legs: Black d'hide body & chaps\n"
                        + "- Amulet/Gloves/Boots/Ring: Amulet of glory/fury, Barrows gloves");

                frame.validate();
                int viewportWidth = scrollPane.getViewport().getWidth();
                Assert.assertTrue("Viewport should have a positive width", viewportWidth > 0);

                // Verify all rendered rows fit inside the viewport width without overflowing
                float maxRowWidth = getMaxRowSpan(chatArea.getUI().getRootView(chatArea));
                Assert.assertTrue("Rows must wrap within viewport width (max row: " + maxRowWidth + ", viewport: " + viewportWidth + ")",
                        maxRowWidth <= viewportWidth + 10);

                // Test resizing to detached size (700px)
                frame.setSize(700, 600);
                frame.validate();
                int wideViewportWidth = scrollPane.getViewport().getWidth();
                Assert.assertTrue(wideViewportWidth > 600);

                // Test resizing back down to narrow size (150px)
                frame.setSize(150, 600);
                frame.validate();
                int narrowViewportWidth = scrollPane.getViewport().getWidth();
                float narrowMaxRowWidth = getMaxRowSpan(chatArea.getUI().getRootView(chatArea));
                Assert.assertTrue("Rows must wrap within narrow viewport on resize (max row: " + narrowMaxRowWidth + ", viewport: " + narrowViewportWidth + ")",
                        narrowMaxRowWidth <= narrowViewportWidth + 10);

                frame.dispose();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    public void testDetachedPositioning() throws Exception {
        Assume.assumeFalse("Skipping UI test in headless environment", GraphicsEnvironment.isHeadless());
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                OsrsAiPanel panel = new OsrsAiPanel(null, null);
                Method detach = OsrsAiPanel.class.getDeclaredMethod("detachToWindow");
                detach.setAccessible(true);
                detach.invoke(panel);

                panel.addMessage("You", "where should i do my current slayer task, and with what gear?");
                panel.addMessage("AI", "Do your Cockatrice task in the Fremennik Slayer Dungeon (south of Rellekka).");

                java.lang.reflect.Field frameField = OsrsAiPanel.class.getDeclaredField("detachedFrame");
                frameField.setAccessible(true);
                javax.swing.JFrame detachedFrame = (javax.swing.JFrame) frameField.get(panel);

                detachedFrame.validate();

                java.lang.reflect.Field chatAreaField = OsrsAiPanel.class.getDeclaredField("chatArea");
                chatAreaField.setAccessible(true);
                javax.swing.JEditorPane chatArea = (javax.swing.JEditorPane) chatAreaField.get(panel);

                javax.swing.text.View root = chatArea.getUI().getRootView(chatArea);
                root.setSize(chatArea.getWidth(), chatArea.getHeight());
                java.awt.Shape alloc = new java.awt.Rectangle(0, 0, chatArea.getWidth(), chatArea.getHeight());
                
                // Ensure the body/first message is positioned near the top (Y < 50), not pushed down
                javax.swing.text.View htmlView = root.getView(0);
                javax.swing.text.View bodyView = null;
                for (int i = 0; i < htmlView.getViewCount(); i++) {
                    if ("body".equals(htmlView.getView(i).getElement().getName())) {
                        bodyView = htmlView.getView(i);
                        break;
                    }
                }
                Assert.assertNotNull(bodyView);
                java.awt.Shape bodyAlloc = htmlView.getChildAllocation(htmlView.getViewIndex(bodyView.getStartOffset(), javax.swing.text.Position.Bias.Forward), alloc);
                Assert.assertNotNull(bodyAlloc);
                Assert.assertTrue("Body must be positioned near the top of the chat area (was y=" + bodyAlloc.getBounds().y + ")",
                        bodyAlloc.getBounds().y <= 50);

                detachedFrame.dispose();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static float getMaxRowSpan(javax.swing.text.View view) {
        float max = 0;
        if (view.getClass().getSimpleName().equals("Row")) {
            max = view.getPreferredSpan(javax.swing.text.View.X_AXIS);
        }
        for (int i = 0; i < view.getViewCount(); i++) {
            max = Math.max(max, getMaxRowSpan(view.getView(i)));
        }
        return max;
    }
}
