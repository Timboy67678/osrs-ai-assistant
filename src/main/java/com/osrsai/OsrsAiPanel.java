package com.osrsai;

import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.config.ConfigManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
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

public class OsrsAiPanel extends PluginPanel {
    private static final int MAX_PROMPT_MESSAGES = 6;
    private static final String CONFIG_GROUP = "osrsai";
    private static final int MAX_SESSIONS = 15;
    private static final int MAX_MESSAGES_PER_SESSION = 50;

    private final OsrsAiPlugin plugin;
    private final ConfigManager configManager;
    private final JPanel dockedHostPanel;
    private final JPanel contentPanel;
    private final JEditorPane chatArea;
    private final JTextField inputField;
    private final JButton sendButton;
    private final JButton detachButton;
    private final JButton newChatButton;
    private final JButton deleteChatButton;
    private final JComboBox<ChatSession> chatSessionComboBox;
    private final StringBuilder chatHistory = new StringBuilder();
    private final Deque<ChatTurn> recentMessages = new ArrayDeque<>();
    private JFrame detachedFrame;

    private final Gson gson = new Gson();
    private final List<ChatSession> sessions = new ArrayList<>();
    private ChatSession activeSession;
    private boolean updatingComboBox = false;

    public OsrsAiPanel(OsrsAiPlugin plugin) {
        this(plugin, null);
    }

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

        contentPanel = new JPanel(new BorderLayout());
        contentPanel.setOpaque(false);

        JPanel topBar = new JPanel(new GridBagLayout());
        topBar.setOpaque(false);

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 2, 2, 2);

        newChatButton = new JButton("+ New");
        newChatButton.setFocusable(false);
        newChatButton.setToolTipText("Create a new chat session");

        deleteChatButton = new JButton("Delete");
        deleteChatButton.setFocusable(false);
        deleteChatButton.setToolTipText("Delete the current chat history");

        detachButton = new JButton("Detach");
        detachButton.setFocusable(false);

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

        // Row 1: Dropdown
        c.gridy = 1;
        c.gridx = 0;
        c.gridwidth = 3;
        c.weightx = 1.0;
        topBar.add(chatSessionComboBox, c);

        // Row 2: Warning Label
        JLabel warningLabel = new JLabel("<html><div style='text-align: center;'>⚠️ Sends query & game context to external AI APIs</div></html>");
        warningLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        warningLabel.setForeground(ColorScheme.BRAND_ORANGE);
        warningLabel.setHorizontalAlignment(SwingConstants.CENTER);
        c.gridy = 2;
        c.gridx = 0;
        c.gridwidth = 3;
        topBar.add(warningLabel, c);

        contentPanel.add(topBar, BorderLayout.NORTH);

        chatArea = new JEditorPane();
        chatArea.setEditable(false);
        chatArea.setContentType("text/html");
        chatArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        chatArea.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true); // Use inherited font

        JScrollPane scrollPane = new JScrollPane(chatArea);
        contentPanel.add(scrollPane, BorderLayout.CENTER);

        JPanel inputPanel = new JPanel();
        inputPanel.setLayout(new BorderLayout());
        inputField = new JTextField();
        sendButton = new JButton("Ask");

        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);

        contentPanel.add(inputPanel, BorderLayout.SOUTH);
        dockedHostPanel.add(contentPanel, BorderLayout.CENTER);

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
    }

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
        revalidate();
        repaint();
        detachButton.setText("Detach");
        saveConfig("windowDetached", false);
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
            configManager.setConfiguration(CONFIG_GROUP, key, value);
        }
    }

    private int loadInt(String key, int defaultValue) {
        if (configManager == null) {
            return defaultValue;
        }

        String raw = configManager.getConfiguration(CONFIG_GROUP, key);
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
                    html.append("<ul style='margin:4px 0 6px 18px; padding:0;'>");
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
        html = html.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");
        html = html.replaceAll("\\*(.*?)\\*", "<i>$1</i>");
        return html;
    }

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

        SwingUtilities.invokeLater(() -> {
            chatArea.setText("<html><body style='color:white; font-family: sans-serif; font-size: 11px; padding: 5px;'>"
                    + chatHistory.toString() + "</body></html>");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        });
    }

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

            sb.append(turn.sender).append(": ").append(turn.message);
        }

        return sb.length() == 0 ? "None" : sb.toString();
    }

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
            configManager.setConfiguration(CONFIG_GROUP, "chat_sessions_v1", json);
            if (activeSession != null) {
                configManager.setConfiguration(CONFIG_GROUP, "active_session_id", activeSession.getId());
            }
        }
    }

    private void loadSessions() {
        if (configManager == null) {
            createNewSession();
            return;
        }

        String json = configManager.getConfiguration(CONFIG_GROUP, "chat_sessions_v1");

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

            // Always create a new chat session at startup and select it
            createNewSession();
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

        SwingUtilities.invokeLater(() -> {
            chatArea.setText("<html><body style='color:white; font-family: sans-serif; font-size: 11px; padding: 5px;'>"
                    + chatHistory.toString() + "</body></html>");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
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
}
