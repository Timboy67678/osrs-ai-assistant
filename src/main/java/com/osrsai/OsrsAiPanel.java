package com.osrsai;

import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.config.ConfigManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.lang.reflect.Type;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Primary UI panel component for the OSRS AI Assistant plugin.
 * <p>
 * Manages chat session history, rich HTML chat rendering, interactive prompt submission,
 * detached window persistence, privacy warning indicators, and multi-profile AI provider configuration.
 */
public class OsrsAiPanel extends PluginPanel {
    private static final int MAX_PROMPT_MESSAGES = 6;
    private static final int MAX_SESSIONS = 15;
    private static final int MAX_MESSAGES_PER_SESSION = 50;

    private final OsrsAiPlugin plugin;
    private final ConfigManager configManager;
    private final JPanel dockedHostPanel;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentPanel;
    private final JEditorPane chatArea;
    private JScrollPane chatScrollPane;
    private final JTextField inputField;
    private final JButton sendButton;
    private final JButton detachButton;
    private final JButton newChatButton;
    private final JButton deleteChatButton;
    private final JComboBox<ChatSession> chatSessionComboBox;
    private final JLabel warningLabel;
    private final StringBuilder chatHistory = new StringBuilder();
    private final Deque<ChatTurn> recentMessages = new ArrayDeque<>();
    private JFrame detachedFrame;

    private final Gson gson = new Gson();
    private final List<ChatSession> sessions = new ArrayList<>();
    private ChatSession activeSession;
    private boolean updatingComboBox = false;

    // AI Profiles fields
    private final List<AiProfile> profiles = new ArrayList<>();
    private AiProfile activeProfile;
    private JComboBox<AiProfile> profileSelectComboBox;
    private JTextField profileNameField;
    private JComboBox<AiProvider> profileProviderComboBox;
    private JPasswordField profileApiKeyField;
    private JTextField profileClientIdField;
    private JTextField profileCustomModelField;
    private JTextField profileCustomEndpointField;
    private JButton setProfileActiveButton;
    private JButton deleteProfileButton;
    private boolean isUpdatingFields = false;

    /**
     * Constructs a new {@code OsrsAiPanel} UI instance.
     *
     * @param plugin main {@link OsrsAiPlugin} instance
     * @param configManager RuneLite {@link ConfigManager} for settings persistence
     */
    public OsrsAiPanel(OsrsAiPlugin plugin, ConfigManager configManager) {
        super(false);
        this.plugin = plugin;
        this.configManager = configManager;
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        dockedHostPanel = new JPanel(new BorderLayout());
        dockedHostPanel.setOpaque(false);
        add(dockedHostPanel, BorderLayout.CENTER);

        loadProfiles();

        contentPanel = new JPanel(cardLayout);
        contentPanel.setOpaque(false);

        JPanel chatViewPanel = new JPanel(new BorderLayout());
        chatViewPanel.setOpaque(false);

        JPanel topBar = new JPanel(new GridBagLayout());
        topBar.setOpaque(false);

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 2, 2, 2);

        newChatButton = new JButton("+ New");
        newChatButton.setFocusable(false);
        newChatButton.setToolTipText("Create a new chat session");
        newChatButton.setMargin(new Insets(2, 2, 2, 2));

        deleteChatButton = new JButton("Delete");
        deleteChatButton.setFocusable(false);
        deleteChatButton.setToolTipText("Delete the current chat history");
        deleteChatButton.setMargin(new Insets(2, 2, 2, 2));

        detachButton = new JButton("Detach");
        detachButton.setFocusable(false);
        detachButton.setMargin(new Insets(2, 2, 2, 2));

        chatSessionComboBox = new JComboBox<>();
        chatSessionComboBox.setFocusable(false);

        // Row 0: Buttons
        c.gridy = 0;
        c.weightx = 0.3;
        c.gridx = 0;
        topBar.add(newChatButton, c);

        c.weightx = 0.3;
        c.gridx = 1;
        topBar.add(deleteChatButton, c);

        c.weightx = 0.4;
        c.gridx = 2;
        topBar.add(detachButton, c);

        // Row 1: Dropdown & Profiles Button
        c.gridy = 1;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 0.85;
        topBar.add(chatSessionComboBox, c);

        JButton profilesBtn = new JButton("⚙️");
        profilesBtn.setToolTipText("Configure AI Profiles");
        profilesBtn.setFocusable(false);
        profilesBtn.addActionListener(e -> switchToProfilesView());
        c.gridx = 2;
        c.gridwidth = 1;
        c.weightx = 0.15;
        topBar.add(profilesBtn, c);

        // Row 2: Warning Label
        this.warningLabel = new JLabel();
        warningLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        warningLabel.setForeground(ColorScheme.BRAND_ORANGE);
        warningLabel.setHorizontalAlignment(SwingConstants.CENTER);
        updateWarningLabel();
        c.gridy = 2;
        c.gridx = 0;
        c.gridwidth = 3;
        c.weightx = 1.0;
        topBar.add(warningLabel, c);

        chatViewPanel.add(topBar, BorderLayout.NORTH);

        chatArea = new JEditorPane() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return true;
            }

            @Override
            public Dimension getPreferredSize() {
                Container parent = getParent();
                if (parent instanceof JViewport && parent.getWidth() > 0) {
                    setSize(parent.getWidth(), Short.MAX_VALUE);
                }
                return super.getPreferredSize();
            }
        };
        chatArea.setEditable(false);
        chatArea.setContentType("text/html");
        chatArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        chatArea.setBorder(null);
        chatArea.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true); // Use inherited font

        chatScrollPane = new JScrollPane(chatArea);
        chatScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chatScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        chatViewPanel.add(chatScrollPane, BorderLayout.CENTER);

        JPanel inputPanel = new JPanel();
        inputPanel.setLayout(new BorderLayout());
        inputField = new JTextField();
        sendButton = new JButton("Ask");

        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);

        chatViewPanel.add(inputPanel, BorderLayout.SOUTH);

        contentPanel.add(chatViewPanel, "CHAT");

        JPanel profilesPanel = createProfilesPanel();
        contentPanel.add(profilesPanel, "PROFILES");

        dockedHostPanel.add(contentPanel, BorderLayout.CENTER);
        cardLayout.show(contentPanel, "CHAT");

        // Add action listener
        java.awt.event.ActionListener sendAction = e -> {
            String question = inputField.getText();
            if (question != null && !question.isBlank() && this.plugin != null) {
                String trimmed = question.trim();
                addMessage("You", trimmed);
                inputField.setText("");
                this.plugin.askQuestion(trimmed);
            }
        };

        detachButton.addActionListener(e -> toggleDetachedWindow());
        sendButton.addActionListener(sendAction);
        inputField.addActionListener(sendAction);

        newChatButton.addActionListener(e -> createNewSession());
        deleteChatButton.addActionListener(e -> deleteActiveSession());
        chatSessionComboBox.addActionListener(e -> {
            if (updatingComboBox) {
                return;
            }
            ChatSession selected = (ChatSession) chatSessionComboBox.getSelectedItem();
            if (selected != null && selected != activeSession) {
                selectSession(selected);
                saveSessions();
            }
        });

        loadSessions();

        // Initialize dynamic profiles UI elements
        isUpdatingFields = true;
        profileSelectComboBox.removeAllItems();
        for (AiProfile p : profiles) {
            profileSelectComboBox.addItem(p);
        }
        if (activeProfile != null) {
            profileSelectComboBox.setSelectedItem(activeProfile);
            populateFormFields(activeProfile);
        } else if (!profiles.isEmpty()) {
            profileSelectComboBox.setSelectedIndex(0);
            populateFormFields(profiles.get(0));
        }
        isUpdatingFields = false;

        addFieldListeners();

        // Add hyperlink listener to chatArea for empty state links
        chatArea.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                if ("configure".equals(e.getDescription())) {
                    switchToProfilesView();
                }
            }
        });

        updateUiState();
    }

    /**
     * Closes and disposes of the detached window frame, saving its position and dimensions.
     */
    public void closeDetachedWindow() {
        runOnEdt(() -> {
            if (detachedFrame != null) {
                saveWindowBounds();
                detachedFrame.getContentPane().remove(contentPanel);
                detachedFrame.dispose();
                detachedFrame = null;
            }
        });
    }

    /**
     * Restores the detached window frame from saved configuration state.
     */
    public void restoreDetachedWindow() {
        detachToWindow();
    }

    private void toggleDetachedWindow() {
        if (detachedFrame == null) {
            detachToWindow();
            return;
        }

        attachToSidebar();
    }

    private void detachToWindow() {
        dockedHostPanel.removeAll();

        JPanel placeholder = new JPanel(new GridBagLayout());
        placeholder.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(10, 10, 10, 10);

        JLabel label = new JLabel("AI Assistant is detached");
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        placeholder.add(label, gbc);

        JButton reattachBtn = new JButton("Attach Window");
        reattachBtn.setFocusable(false);
        reattachBtn.addActionListener(e -> attachToSidebar());
        gbc.gridy = 1;
        placeholder.add(reattachBtn, gbc);

        dockedHostPanel.add(placeholder, BorderLayout.CENTER);
        revalidate();
        repaint();

        detachedFrame = new JFrame("OSRS AI Assistant");
        detachedFrame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        detachedFrame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                attachToSidebar();
            }
        });

        int w = loadInt("windowWidth", 700);
        int h = loadInt("windowHeight", 650);
        int x = loadInt("windowX", -1);
        int y = loadInt("windowY", -1);

        detachedFrame.getContentPane().add(contentPanel);
        detachedFrame.setSize(w, h);
        if (x >= 0 && y >= 0 && isLocationOnScreen(x, y, w, h)) {
            detachedFrame.setLocation(x, y);
        } else {
            detachedFrame.setLocationByPlatform(true);
        }

        detachedFrame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                saveWindowBounds();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                saveWindowBounds();
            }
        });

        detachedFrame.setVisible(true);
        detachButton.setText("Attach");
        saveConfig("windowDetached", true);
        SwingUtilities.invokeLater(this::scrollToBottom);
    }

    private void attachToSidebar() {
        if (detachedFrame != null) {
            saveWindowBounds();
            detachedFrame.getContentPane().remove(contentPanel);
            detachedFrame.dispose();
            detachedFrame = null;
        }

        dockedHostPanel.removeAll();
        dockedHostPanel.add(contentPanel, BorderLayout.CENTER);
        dockedHostPanel.revalidate();
        dockedHostPanel.repaint();
        detachButton.setText("Detach");
        saveConfig("windowDetached", false);
        SwingUtilities.invokeLater(this::scrollToBottom);
    }

    private void saveWindowBounds() {
        if (detachedFrame == null || !detachedFrame.isShowing()) {
            return;
        }

        int state = detachedFrame.getExtendedState();
        if ((state & Frame.ICONIFIED) != 0 || (state & Frame.MAXIMIZED_BOTH) != 0) {
            return;
        }

        saveConfig("windowWidth", detachedFrame.getWidth());
        saveConfig("windowHeight", detachedFrame.getHeight());
        saveConfig("windowX", detachedFrame.getX());
        saveConfig("windowY", detachedFrame.getY());
    }

    private static boolean isLocationOnScreen(int x, int y, int width, int height) {
        try {
            Rectangle rect = new Rectangle(x, y, width, height);
            for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle bounds = gd.getDefaultConfiguration().getBounds();
                if (bounds.intersects(rect)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void saveConfig(String key, Object value) {
        if (configManager != null) {
            configManager.setConfiguration(OsrsAiPlugin.CONFIG_GROUP, key, value);
        }
    }

    private int loadInt(String key, int defaultValue) {
        if (configManager == null) {
            return defaultValue;
        }

        String raw = configManager.getConfiguration(OsrsAiPlugin.CONFIG_GROUP, key);
        if (raw == null) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private void runOnEdt(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }

        SwingUtilities.invokeLater(runnable);
    }

    /**
     * Updates the data privacy warning banner label depending on whether in-game context sharing is enabled.
     */
    public void updateWarningLabel() {
        runOnEdt(() -> {
            if (warningLabel == null) {
                return;
            }
            boolean share = false;
            if (plugin != null && plugin.getConfig() != null) {
                share = plugin.getConfig().shareCharacterInfo();
            }
            if (share) {
                warningLabel.setText(
                        "<html><div style='text-align: center;'>⚠️ Sends query & game context to external AI APIs</div></html>");
            } else {
                warningLabel.setText(
                        "<html><div style='text-align: center;'>⚠️ Sends query text to external AI APIs</div></html>");
            }
            warningLabel.revalidate();
            warningLabel.repaint();
        });
    }

    private String formatMarkdownToHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder html = new StringBuilder();
        boolean inList = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                continue;
            }

            if (trimmed.startsWith("### ")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<div style='font-weight:bold; margin-top:6px;'>")
                        .append(formatInlineMarkdown(trimmed.substring(4)))
                        .append("</div>");
                continue;
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                if (!inList) {
                    html.append("<ul style='margin:4px 0 6px 0; padding-left:14px;'>");
                    inList = true;
                }
                html.append("<li>")
                        .append(formatInlineMarkdown(trimmed.substring(2)))
                        .append("</li>");
                continue;
            }

            if (inList) {
                html.append("</ul>");
                inList = false;
            }

            html.append("<div>")
                    .append(formatInlineMarkdown(trimmed))
                    .append("</div>");
        }

        if (inList) {
            html.append("</ul>");
        }

        return html.toString();
    }

    private String formatInlineMarkdown(String text) {
        String html = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        html = html.replaceAll("`(.*?)`",
                "<code style='background-color:#2a2a2a; padding:1px 3px; border-radius:3px;'>$1</code>");
        html = html.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");
        html = html.replaceAll("\\*(.*?)\\*", "<i>$1</i>");
        return html;
    }

    /**
     * Appends a user prompt or AI response turn to the active chat history and updates the UI display.
     *
     * @param sender message sender ("You", "AI", or "System")
     * @param message natural language message text
     */
    public void addMessage(String sender, String message) {
        if (activeSession != null) {
            activeSession.getMessages().add(new ChatMessage(sender, message));
            while (activeSession.getMessages().size() > MAX_MESSAGES_PER_SESSION) {
                activeSession.getMessages().remove(0);
            }

            if ("You".equals(sender) && "New Chat".equals(activeSession.getTitle())) {
                String newTitle = message.trim();
                if (newTitle.length() > 25) {
                    newTitle = newTitle.substring(0, 22) + "...";
                }
                if (newTitle.isEmpty()) {
                    newTitle = "Chat " + (sessions.indexOf(activeSession) + 1);
                }
                activeSession.setTitle(newTitle);

                updatingComboBox = true;
                int index = chatSessionComboBox.getSelectedIndex();
                if (index >= 0) {
                    chatSessionComboBox.removeItemAt(index);
                    chatSessionComboBox.insertItemAt(activeSession, index);
                    chatSessionComboBox.setSelectedIndex(index);
                }
                updatingComboBox = false;
            }
            promoteToActive(activeSession);
            saveSessions();
        }

        rememberMessage(sender, message);
        String formattedMessage = formatMarkdownToHtml(message);

        String color;
        switch (sender) {
            case "You":
                color = "#00ffff";
                break; // Cyan
            case "AI":
                color = "#55ff55";
                break; // Green
            default:
                color = "#aaaaaa";
                break; // Gray
        }

        chatHistory.append("<div style='margin-bottom:10px;'>")
                .append("<b style='color:").append(color).append("'>").append(sender).append(":</b><br>")
                .append(formattedMessage)
                .append("</div>");

        updateChatHtml();
    }

    /**
     * Formats recent chat message turns into a string for inclusion in the AI system prompt.
     *
     * @param currentQuestion current user question string to exclude duplicates
     * @return formatted conversation context string, or "None"
     */
    public String getRecentConversationContext(String currentQuestion) {
        List<ChatTurn> turns = new ArrayList<>(recentMessages);
        String normalizedQuestion = normalizeMessage(currentQuestion);

        if (!normalizedQuestion.isEmpty()) {
            for (int i = turns.size() - 1; i >= 0; i--) {
                ChatTurn turn = turns.get(i);
                if ("You".equals(turn.sender) && turn.message.equals(normalizedQuestion)) {
                    turns.remove(i);
                    break;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (ChatTurn turn : turns) {
            if ("System".equals(turn.sender)) {
                continue;
            }

            if (sb.length() > 0) {
                sb.append('\n');
            }

            String msg = turn.message;
            if ("Assistant".equals(turn.sender) && msg.length() > 350) {
                msg = msg.substring(0, 347) + "...";
            }

            sb.append(turn.sender).append(": ").append(msg);
        }

        return sb.length() == 0 ? "None" : sb.toString();
    }

    /**
     * Enables or disables input controls and updates the button label to indicate an active background request.
     *
     * @param thinking {@code true} if a request is in progress; {@code false} when idle
     */
    public void setThinking(boolean thinking) {
        SwingUtilities.invokeLater(() -> {
            inputField.setEnabled(!thinking);
            sendButton.setEnabled(!thinking);
            sendButton.setText(thinking ? "Thinking..." : "Ask");
        });
    }

    private void rememberMessage(String sender, String message) {
        String normalizedMessage = normalizeMessage(message);
        if (normalizedMessage.isEmpty()) {
            return;
        }

        recentMessages.addLast(new ChatTurn(sender, normalizedMessage));
        while (recentMessages.size() > MAX_PROMPT_MESSAGES) {
            recentMessages.removeFirst();
        }
    }

    private String normalizeMessage(String message) {
        return message == null ? "" : message.replace('\n', ' ').trim();
    }

    private static final class ChatTurn {
        private final String sender;
        private final String message;

        private ChatTurn(String sender, String message) {
            this.sender = sender;
            this.message = message;
        }
    }

    private void saveSessions() {
        if (configManager != null) {
            while (sessions.size() > MAX_SESSIONS) {
                ChatSession removed = sessions.remove(sessions.size() - 1);
                updatingComboBox = true;
                chatSessionComboBox.removeItem(removed);
                updatingComboBox = false;
            }
            String json = gson.toJson(sessions);
            configManager.setConfiguration(OsrsAiPlugin.CONFIG_GROUP, "chat_sessions_v1", json);
            if (activeSession != null) {
                configManager.setConfiguration(OsrsAiPlugin.CONFIG_GROUP, "active_session_id", activeSession.getId());
            }
        }
    }

    private void loadSessions() {
        if (configManager == null) {
            createNewSession();
            return;
        }

        String json = configManager.getConfiguration(OsrsAiPlugin.CONFIG_GROUP, "chat_sessions_v1");

        try {
            Type listType = new TypeToken<ArrayList<ChatSession>>() {
            }.getType();
            List<ChatSession> loaded = gson.fromJson(json, listType);

            sessions.clear();
            if (loaded != null) {
                // Remove empty sessions on startup to prevent clutter
                loaded.removeIf(s -> s.getMessages().isEmpty());
                sessions.addAll(loaded);
            }

            updatingComboBox = true;
            chatSessionComboBox.removeAllItems();
            for (ChatSession s : sessions) {
                chatSessionComboBox.addItem(s);
            }
            updatingComboBox = false;

            String activeId = configManager.getConfiguration(OsrsAiPlugin.CONFIG_GROUP, "active_session_id");
            ChatSession toSelect = null;
            if (activeId != null) {
                for (ChatSession s : sessions) {
                    if (s.getId().equals(activeId)) {
                        toSelect = s;
                        break;
                    }
                }
            }

            if (toSelect != null) {
                updatingComboBox = true;
                chatSessionComboBox.setSelectedItem(toSelect);
                updatingComboBox = false;
                selectSession(toSelect);
            } else if (!sessions.isEmpty()) {
                ChatSession first = sessions.get(0);
                updatingComboBox = true;
                chatSessionComboBox.setSelectedItem(first);
                updatingComboBox = false;
                selectSession(first);
            } else {
                createNewSession();
            }
        } catch (Exception e) {
            createNewSession();
        }
    }

    private void selectSession(ChatSession session) {
        this.activeSession = session;
        chatHistory.setLength(0);
        recentMessages.clear();

        for (ChatMessage msg : session.getMessages()) {
            rememberMessage(msg.getSender(), msg.getMessage());

            String formattedMessage = formatMarkdownToHtml(msg.getMessage());
            String color;
            switch (msg.getSender()) {
                case "You":
                    color = "#00ffff";
                    break;
                case "AI":
                    color = "#55ff55";
                    break;
                default:
                    color = "#aaaaaa";
                    break;
            }

            chatHistory.append("<div style='margin-bottom:10px;'>")
                    .append("<b style='color:").append(color).append("'>").append(msg.getSender()).append(":</b><br>")
                    .append(formattedMessage)
                    .append("</div>");
        }

        updateChatHtml();
    }

    private void updateChatHtml() {
        runOnEdt(() -> {
            chatArea.setText("<html><head><style>"
                    + "body { color: white; font-family: sans-serif; font-size: 11px; margin: 4px 8px 4px 4px; padding: 0; }"
                    + "div { margin-bottom: 6px; word-wrap: break-word; }"
                    + "ul { margin: 4px 0 6px 0; padding-left: 14px; }"
                    + "li { margin-bottom: 2px; word-wrap: break-word; }"
                    + "</style></head><body>"
                    + chatHistory.toString() + "</body></html>");
            scrollToBottom();
        });
    }

    public void scrollToBottom() {
        runOnEdt(() -> {
            SwingUtilities.invokeLater(() -> {
                if (chatArea != null) {
                    try {
                        chatArea.setCaretPosition(chatArea.getDocument().getLength());
                    } catch (Exception ignored) {
                    }
                }
                if (chatScrollPane != null) {
                    JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
                    if (vertical != null) {
                        vertical.setValue(vertical.getMaximum());
                    }
                }
            });
        });
    }

    private void createNewSession() {
        ChatSession newSession = new ChatSession("New Chat");
        sessions.add(0, newSession);

        updatingComboBox = true;
        chatSessionComboBox.insertItemAt(newSession, 0);
        chatSessionComboBox.setSelectedItem(newSession);
        updatingComboBox = false;

        selectSession(newSession);
        saveSessions();
    }

    private void promoteToActive(ChatSession session) {
        int index = sessions.indexOf(session);
        if (index > 0) {
            sessions.remove(index);
            sessions.add(0, session);

            updatingComboBox = true;
            chatSessionComboBox.removeItem(session);
            chatSessionComboBox.insertItemAt(session, 0);
            chatSessionComboBox.setSelectedItem(session);
            updatingComboBox = false;
        }
    }

    private void deleteActiveSession() {
        if (activeSession == null) {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete this chat history?",
                "Delete Chat",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        int index = sessions.indexOf(activeSession);
        if (index >= 0) {
            sessions.remove(index);

            updatingComboBox = true;
            chatSessionComboBox.removeItem(activeSession);
            updatingComboBox = false;

            if (sessions.isEmpty()) {
                createNewSession();
            } else {
                int nextSelection = Math.min(sessions.size() - 1, index);
                if (nextSelection >= 0) {
                    ChatSession nextSession = sessions.get(nextSelection);
                    updatingComboBox = true;
                    chatSessionComboBox.setSelectedItem(nextSession);
                    updatingComboBox = false;
                    selectSession(nextSession);
                    saveSessions();
                } else {
                    createNewSession();
                }
            }
        }
    }

    public static class ChatMessage {
        private final String sender;
        private final String message;

        public ChatMessage(String sender, String message) {
            this.sender = sender;
            this.message = message;
        }

        public String getSender() {
            return sender;
        }

        public String getMessage() {
            return message;
        }
    }

    public static class ChatSession {
        private final String id;
        private String title;
        private final List<ChatMessage> messages;

        public ChatSession(String title) {
            this.id = UUID.randomUUID().toString();
            this.title = title;
            this.messages = new ArrayList<>();
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public List<ChatMessage> getMessages() {
            return messages;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private void switchToProfilesView() {
        cardLayout.show(contentPanel, "PROFILES");
    }

    private JPanel createProfilesPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);

        // Header Panel
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);
        headerPanel.setBorder(new EmptyBorder(0, 0, 10, 0));

        JLabel titleLabel = new JLabel("AI Profiles");
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        headerPanel.add(titleLabel, BorderLayout.WEST);

        JButton backBtn = new JButton("Back");
        backBtn.setFocusable(false);
        backBtn.addActionListener(e -> cardLayout.show(contentPanel, "CHAT"));
        headerPanel.add(backBtn, BorderLayout.EAST);

        panel.add(headerPanel, BorderLayout.NORTH);

        // Scrollable content panel with GridBagLayout
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setOpaque(false);
        formPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 2, 4, 2);
        gbc.weightx = 1.0;

        int row = 0;

        // Profile Selector Header
        JLabel selectLabel = new JLabel("Select Profile:");
        selectLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        selectLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        formPanel.add(selectLabel, gbc);

        // Dropdown
        profileSelectComboBox = new JComboBox<>();
        profileSelectComboBox.setFocusable(false);
        profileSelectComboBox.setPreferredSize(new Dimension(0, 28));
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        formPanel.add(profileSelectComboBox, gbc);

        // Set Active button (Full width)
        setProfileActiveButton = new JButton("Set Active");
        setProfileActiveButton.setFocusable(false);
        setProfileActiveButton.setPreferredSize(new Dimension(0, 28));
        setProfileActiveButton.addActionListener(e -> activateSelectedProfile());
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        formPanel.add(setProfileActiveButton, gbc);

        // New and Delete buttons side by side
        JButton newProfileBtn = new JButton("+ New");
        newProfileBtn.setFocusable(false);
        newProfileBtn.setPreferredSize(new Dimension(0, 28));
        newProfileBtn.addActionListener(e -> createNewProfile());
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0.5;
        formPanel.add(newProfileBtn, gbc);

        deleteProfileButton = new JButton("Delete");
        deleteProfileButton.setFocusable(false);
        deleteProfileButton.setPreferredSize(new Dimension(0, 28));
        deleteProfileButton.addActionListener(e -> deleteSelectedProfile());
        gbc.gridx = 1;
        gbc.weightx = 0.5;
        formPanel.add(deleteProfileButton, gbc);

        row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0; // Reset

        // Visual Separator
        JSeparator separator = new JSeparator();
        separator.setForeground(ColorScheme.DARKER_GRAY_COLOR);
        gbc.gridy = row++;
        gbc.insets = new Insets(10, 2, 10, 2);
        formPanel.add(separator, gbc);
        gbc.insets = new Insets(4, 2, 4, 2); // Reset

        // Form Fields Header
        JLabel detailsLabel = new JLabel("Profile Details:");
        detailsLabel.setForeground(Color.WHITE);
        detailsLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        gbc.gridy = row++;
        formPanel.add(detailsLabel, gbc);

        // Profile Name
        JLabel nameLabel = new JLabel("Profile Name:");
        nameLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        gbc.gridy = row++;
        formPanel.add(nameLabel, gbc);

        profileNameField = new JTextField();
        profileNameField.setPreferredSize(new Dimension(0, 26));
        gbc.gridy = row++;
        formPanel.add(profileNameField, gbc);

        // AI Provider
        JLabel providerLabel = new JLabel("AI Provider:");
        providerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        gbc.gridy = row++;
        formPanel.add(providerLabel, gbc);

        profileProviderComboBox = new JComboBox<>(AiProvider.values());
        profileProviderComboBox.setFocusable(false);
        profileProviderComboBox.setPreferredSize(new Dimension(0, 28));
        gbc.gridy = row++;
        formPanel.add(profileProviderComboBox, gbc);

        // API Key
        JLabel apiKeyLabel = new JLabel("API Key:");
        apiKeyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        gbc.gridy = row++;
        formPanel.add(apiKeyLabel, gbc);

        profileApiKeyField = new JPasswordField();
        profileApiKeyField.setPreferredSize(new Dimension(0, 26));
        gbc.gridy = row++;
        formPanel.add(profileApiKeyField, gbc);

        // Custom Model Override
        JLabel modelLabel = new JLabel("Model Override (Optional):");
        modelLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        gbc.gridy = row++;
        formPanel.add(modelLabel, gbc);

        profileCustomModelField = new JTextField();
        profileCustomModelField.setPreferredSize(new Dimension(0, 26));
        gbc.gridy = row++;
        formPanel.add(profileCustomModelField, gbc);

        // Custom Endpoint
        JLabel endpointLabel = new JLabel("Endpoint URL (Custom only):");
        endpointLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        gbc.gridy = row++;
        formPanel.add(endpointLabel, gbc);

        profileCustomEndpointField = new JTextField();
        profileCustomEndpointField.setPreferredSize(new Dimension(0, 26));
        gbc.gridy = row++;
        formPanel.add(profileCustomEndpointField, gbc);

        // Client / Org ID
        JLabel clientLabel = new JLabel("Org ID / Client ID (Optional):");
        clientLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        gbc.gridy = row++;
        formPanel.add(clientLabel, gbc);

        profileClientIdField = new JTextField();
        profileClientIdField.setPreferredSize(new Dimension(0, 26));
        gbc.gridy = row++;
        formPanel.add(profileClientIdField, gbc);

        // Vertical Spacer/Filler at the bottom
        gbc.gridy = row++;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        formPanel.add(new JPanel() {
            {
                setOpaque(false);
            }
        }, gbc);

        JScrollPane scrollPane = new JScrollPane(formPanel);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setBorder(null);

        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private void populateFormFields(AiProfile profile) {
        isUpdatingFields = true;
        profileNameField.setText(profile.getName());
        profileProviderComboBox.setSelectedItem(profile.getProvider());
        profileApiKeyField.setText(profile.getApiKey());
        profileCustomModelField.setText(profile.getCustomModel());
        profileCustomEndpointField.setText(profile.getCustomEndpoint());
        profileClientIdField.setText(profile.getClientId());

        // Update active button state
        if (activeProfile != null && activeProfile.getId().equals(profile.getId())) {
            setProfileActiveButton.setText("Active");
            setProfileActiveButton.setEnabled(false);
        } else {
            setProfileActiveButton.setText("Set Active");
            setProfileActiveButton.setEnabled(true);
        }
        isUpdatingFields = false;
    }

    private void addFieldListeners() {
        DocumentListener textChangeListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                update();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                update();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                update();
            }

            private void update() {
                if (isUpdatingFields)
                    return;
                AiProfile selected = (AiProfile) profileSelectComboBox.getSelectedItem();
                if (selected != null) {
                    selected.setName(profileNameField.getText().trim());
                    selected.setApiKey(new String(profileApiKeyField.getPassword()).trim());
                    selected.setCustomModel(profileCustomModelField.getText().trim());
                    selected.setCustomEndpoint(profileCustomEndpointField.getText().trim());
                    selected.setClientId(profileClientIdField.getText().trim());
                    saveProfiles();
                }
            }
        };

        profileNameField.getDocument().addDocumentListener(textChangeListener);
        profileApiKeyField.getDocument().addDocumentListener(textChangeListener);
        profileCustomModelField.getDocument().addDocumentListener(textChangeListener);
        profileCustomEndpointField.getDocument().addDocumentListener(textChangeListener);
        profileClientIdField.getDocument().addDocumentListener(textChangeListener);

        // Update combobox items display name on name field focus lost
        profileNameField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (isUpdatingFields)
                    return;
                AiProfile selected = (AiProfile) profileSelectComboBox.getSelectedItem();
                if (selected != null) {
                    isUpdatingFields = true;
                    int idx = profileSelectComboBox.getSelectedIndex();
                    if (idx >= 0) {
                        profileSelectComboBox.removeItemAt(idx);
                        profileSelectComboBox.insertItemAt(selected, idx);
                        profileSelectComboBox.setSelectedIndex(idx);
                    }
                    isUpdatingFields = false;
                }
            }
        });

        profileProviderComboBox.addActionListener(e -> {
            if (isUpdatingFields)
                return;
            AiProfile selected = (AiProfile) profileSelectComboBox.getSelectedItem();
            if (selected != null) {
                selected.setProvider((AiProvider) profileProviderComboBox.getSelectedItem());
                saveProfiles();
            }
        });

        profileSelectComboBox.addActionListener(e -> {
            if (isUpdatingFields)
                return;
            AiProfile selected = (AiProfile) profileSelectComboBox.getSelectedItem();
            if (selected != null) {
                populateFormFields(selected);
            }
        });
    }

    private void saveProfiles() {
        if (configManager != null) {
            String json = gson.toJson(profiles);
            configManager.setConfiguration(OsrsAiPlugin.CONFIG_GROUP, "ai_profiles_v1", json);
            if (activeProfile != null) {
                configManager.setConfiguration(OsrsAiPlugin.CONFIG_GROUP, "active_profile_id", activeProfile.getId());
            } else {
                configManager.setConfiguration(OsrsAiPlugin.CONFIG_GROUP, "active_profile_id", "");
            }
        }
    }

    private void loadProfiles() {
        profiles.clear();
        if (configManager == null) {
            return;
        }
        String json = configManager.getConfiguration(OsrsAiPlugin.CONFIG_GROUP, "ai_profiles_v1");
        String activeId = configManager.getConfiguration(OsrsAiPlugin.CONFIG_GROUP, "active_profile_id");

        try {
            if (json != null && !json.isEmpty()) {
                Type listType = new TypeToken<ArrayList<AiProfile>>() {
                }.getType();
                List<AiProfile> loaded = gson.fromJson(json, listType);
                if (loaded != null) {
                    profiles.addAll(loaded);
                }
            }
        } catch (Exception e) {
            // handle parse error
        }

        activeProfile = null;
        if (activeId != null && !activeId.isEmpty()) {
            for (AiProfile p : profiles) {
                if (p.getId().equals(activeId)) {
                    activeProfile = p;
                    break;
                }
            }
        }
        if (activeProfile == null && !profiles.isEmpty()) {
            activeProfile = profiles.get(0);
        }
    }

    private void createNewProfile() {
        AiProfile newProfile = new AiProfile();
        profiles.add(newProfile);

        isUpdatingFields = true;
        profileSelectComboBox.addItem(newProfile);
        profileSelectComboBox.setSelectedItem(newProfile);
        isUpdatingFields = false;

        populateFormFields(newProfile);
        saveProfiles();
        updateUiState();
    }

    private void deleteSelectedProfile() {
        AiProfile selected = (AiProfile) profileSelectComboBox.getSelectedItem();
        if (selected == null) {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete profile \"" + selected.getName() + "\"?",
                "Delete Profile",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        profiles.remove(selected);
        isUpdatingFields = true;
        profileSelectComboBox.removeItem(selected);
        isUpdatingFields = false;

        if (activeProfile == selected) {
            activeProfile = profiles.isEmpty() ? null : profiles.get(0);
        }

        saveProfiles();
        updateUiState();

        AiProfile nextSelected = (AiProfile) profileSelectComboBox.getSelectedItem();
        if (nextSelected != null) {
            populateFormFields(nextSelected);
        } else {
            isUpdatingFields = true;
            profileNameField.setText("");
            profileProviderComboBox.setSelectedIndex(-1);
            profileApiKeyField.setText("");
            profileCustomModelField.setText("");
            profileCustomEndpointField.setText("");
            profileClientIdField.setText("");
            setProfileActiveButton.setText("Set Active");
            setProfileActiveButton.setEnabled(false);
            isUpdatingFields = false;
        }
    }

    private void activateSelectedProfile() {
        AiProfile selected = (AiProfile) profileSelectComboBox.getSelectedItem();
        if (selected != null) {
            activeProfile = selected;
            saveProfiles();
            updateUiState();
            populateFormFields(selected);
        }
    }

    private void updateUiState() {
        runOnEdt(() -> {
            boolean hasProfiles = !profiles.isEmpty();
            boolean hasActiveProfile = activeProfile != null;

            inputField.setEnabled(hasProfiles && hasActiveProfile);
            sendButton.setEnabled(hasProfiles && hasActiveProfile);

            if (!hasProfiles) {
                chatHistory.setLength(0);
                chatArea.setText(
                        "<html><body style='color:#aaaaaa; font-family:sans-serif; font-size:11px; text-align:center;'>"
                                + "<div style='margin-top:50px;'><h3>Welcome to OSRS AI Assistant!</h3>"
                                + "<p>To start chatting, you must configure and activate an AI profile first.</p>"
                                + "<p><a href='configure' style='color:#00ffff;'>Click here to configure profiles</a></p></div>"
                                + "</body></html>");
            } else if (!hasActiveProfile) {
                chatHistory.setLength(0);
                chatArea.setText(
                        "<html><body style='color:#aaaaaa; font-family:sans-serif; font-size:11px; text-align:center;'>"
                                + "<div style='margin-top:50px;'><h3>No Active Profile</h3>"
                                + "<p>Please select and activate an AI profile to start chatting.</p>"
                                + "<p><a href='configure' style='color:#00ffff;'>Click here to manage profiles</a></p></div>"
                                + "</body></html>");
            } else {
                if (activeSession != null) {
                    selectSession(activeSession);
                }
            }
        });
    }
}
