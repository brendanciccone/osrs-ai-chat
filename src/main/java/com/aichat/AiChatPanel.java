package com.aichat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.text.DefaultEditorKit;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/** The sidebar: pick a chat, read the transcript, type a message. Everything here runs on the Swing EDT. */
class AiChatPanel extends PluginPanel
{
	private static final Font TEXT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final Color USER_COLOR = new Color(0x7fb8ff);
	private static final Color ERROR_COLOR = new Color(0xff6b6b);
	private static final Color OK_COLOR = new Color(0x5fd068);

	private final AiChatPlugin plugin;

	private final JLabel setupLabel = plainLabel();
	private final JTextArea setupHelp = textArea("");
	private final DefaultComboBoxModel<Chat> chatModel = new DefaultComboBoxModel<>();
	private final JComboBox<Chat> chatSelect = new JComboBox<>(chatModel);
	private final JPanel transcript = new TranscriptPanel();
	private final JScrollPane transcriptScroll = new JScrollPane(transcript);
	private final JLabel statusLabel = plainLabel();
	private final JTextArea noteArea = textArea("");
	private final JTextArea input = new JTextArea(3, 1);
	private final JButton sendButton = new JButton("Send");
	private final JButton stopButton = new JButton("Stop");
	private final Timer ticker = new Timer(1000, e -> refreshStatus());

	private boolean updatingCombo;
	/** What the transcript currently shows, to skip rebuilding it when nothing changed. */
	private String shownKey = "";
	private String comboKey = "";

	AiChatPanel(AiChatPlugin plugin)
	{
		super(false);
		this.plugin = plugin;

		setLayout(new BorderLayout(0, 6));
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(header(), BorderLayout.NORTH);

		transcript.setLayout(new StackLayout(6));
		transcript.setBackground(ColorScheme.DARK_GRAY_COLOR);
		transcriptScroll.setBorder(null);
		transcriptScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		transcriptScroll.getVerticalScrollBar().setUnitIncrement(16);
		transcriptScroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(transcriptScroll, BorderLayout.CENTER);

		add(footer(), BorderLayout.SOUTH);
		refreshAll();
	}

	private JComponent header()
	{
		JPanel p = new JPanel(new StackLayout(4));
		p.setOpaque(false);

		setupLabel.setFont(FontManager.getRunescapeSmallFont());
		p.add(setupLabel);
		setupHelp.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		setupHelp.setBackground(ColorScheme.DARK_GRAY_COLOR);
		setupHelp.setFont(TEXT_FONT.deriveFont(11f));
		p.add(setupHelp);

		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		chatSelect.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus)
			{
				super.getListCellRendererComponent(list, value, index, selected, focus);
				// Chat names come from what the player typed; never let Swing render them as HTML.
				putClientProperty("html.disable", Boolean.TRUE);
				if (value instanceof Chat)
				{
					Chat c = (Chat) value;
					setText(c.name + (c.isRunning() ? "  (waiting)" : ""));
				}
				return this;
			}
		});
		chatSelect.addActionListener(e ->
		{
			Object sel = chatSelect.getSelectedItem();
			if (!updatingCombo && sel instanceof Chat)
			{
				plugin.selectChat((Chat) sel);
			}
		});
		chatSelect.setToolTipText("Each chat is its own conversation");
		row.add(chatSelect, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new BorderLayout(2, 0));
		buttons.setOpaque(false);
		buttons.add(smallButton("+", "New chat", e -> plugin.newChat()), BorderLayout.WEST);
		buttons.add(smallButton("...", "Rename, clear or delete this chat", e -> showChatMenu((JComponent) e.getSource())), BorderLayout.EAST);
		row.add(buttons, BorderLayout.EAST);
		p.add(row);
		return p;
	}

	private JComponent footer()
	{
		JPanel p = new JPanel(new StackLayout(4));
		p.setOpaque(false);

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(ColorScheme.BRAND_ORANGE);
		p.add(statusLabel);

		noteArea.setFont(TEXT_FONT.deriveFont(11f));
		noteArea.setForeground(ERROR_COLOR);
		noteArea.setBackground(ColorScheme.DARK_GRAY_COLOR);
		noteArea.setVisible(false);
		p.add(noteArea);

		input.setLineWrap(true);
		input.setWrapStyleWord(true);
		input.setFont(TEXT_FONT);
		input.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		input.setForeground(Color.WHITE);
		input.setCaretColor(Color.WHITE);
		input.setBorder(new EmptyBorder(4, 4, 4, 4));
		input.setToolTipText("Enter sends, Shift+Enter adds a line");
		input.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "ai-chat-send");
		input.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), DefaultEditorKit.insertBreakAction);
		input.getActionMap().put("ai-chat-send", new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				send();
			}
		});
		JScrollPane inputScroll = new JScrollPane(input);
		inputScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		// Fixed height: long messages scroll instead of pushing the transcript away.
		inputScroll.setPreferredSize(new Dimension(0, 62));
		p.add(inputScroll);

		JPanel buttons = new JPanel(new BorderLayout(4, 0));
		buttons.setOpaque(false);
		sendButton.addActionListener(e -> send());
		stopButton.addActionListener(e -> plugin.stop());
		stopButton.setToolTipText("Stop waiting for this reply; you can carry on with the chat afterwards");
		buttons.add(stopButton, BorderLayout.WEST);
		buttons.add(sendButton, BorderLayout.CENTER);
		p.add(buttons);
		return p;
	}

	private void send()
	{
		String text = input.getText().trim();
		if (text.isEmpty())
		{
			return;
		}
		if (plugin.send(text))
		{
			input.setText("");
			showNote(null);
		}
	}

	/** Bring everything in line with the plugin's latest state. Cheap when nothing changed. */
	void refreshAll()
	{
		refreshSetup();
		refreshChats();
		refreshTranscript();
		refreshStatus();
	}

	private void refreshSetup()
	{
		String problem = plugin.setupProblem();
		if (problem == null)
		{
			setupLabel.setText(plugin.setupSummary());
			setupLabel.setForeground(OK_COLOR);
			setupHelp.setText("");
		}
		else
		{
			setupLabel.setText("Not set up yet");
			setupLabel.setForeground(ERROR_COLOR);
			setupHelp.setText(problem + " Open RuneLite's settings (the wrench) and search for AI Chat.");
		}
		setupHelp.setVisible(!setupHelp.getText().isEmpty());
		boolean usable = plugin.currentChat() != null;
		chatSelect.setEnabled(usable);
		input.setEnabled(usable);
	}

	private void refreshChats()
	{
		Chat currentChat = plugin.currentChat();
		StringBuilder key = new StringBuilder(currentChat == null ? "" : currentChat.id);
		for (Chat c : plugin.getChats())
		{
			key.append('|').append(c.id).append(':').append(c.name).append(':').append(c.isRunning());
		}
		if (key.toString().equals(comboKey))
		{
			return;
		}
		comboKey = key.toString();
		updatingCombo = true;
		try
		{
			chatModel.removeAllElements();
			for (Chat c : plugin.getChats())
			{
				chatModel.addElement(c);
			}
			if (currentChat != null)
			{
				chatModel.setSelectedItem(currentChat);
			}
		}
		finally
		{
			updatingCombo = false;
		}
	}

	private void refreshTranscript()
	{
		Chat chat = plugin.currentChat();
		String key = chat == null ? "" : chat.id + ":" + chat.messages.size() + ":" + chat.isRunning();
		if (key.equals(shownKey))
		{
			return;
		}
		shownKey = key;

		transcript.removeAll();
		if (chat == null || chat.messages.isEmpty())
		{
			transcript.add(hint());
		}
		else
		{
			for (Chat.Message m : chat.messages)
			{
				transcript.add(bubble(m));
			}
		}
		transcript.revalidate();
		transcript.repaint();
		SwingUtilities.invokeLater(() ->
		{
			JScrollBar bar = transcriptScroll.getVerticalScrollBar();
			bar.setValue(bar.getMaximum());
		});
	}

	/** The "waiting" line; ticks every second while a reply is on its way. */
	void refreshStatus()
	{
		Chat chat = plugin.currentChat();
		boolean running = chat != null && chat.isRunning();
		stopButton.setEnabled(running);
		sendButton.setEnabled(chat != null && !running);
		if (!running)
		{
			ticker.stop();
			statusLabel.setText(" ");
			return;
		}
		if (!ticker.isRunning())
		{
			ticker.start();
		}
		long secs = Math.max(0, (System.currentTimeMillis() - chat.runStartedAt) / 1000);
		String elapsed = secs < 60 ? secs + "s" : (secs / 60) + "m " + (secs % 60) + "s";
		statusLabel.setText("Waiting for a reply... " + elapsed);
	}

	/** A short error under the transcript, e.g. when a message can't be sent yet; null clears it. */
	void showNote(String text)
	{
		noteArea.setText(text == null ? "" : text);
		noteArea.setVisible(text != null && !text.isEmpty());
		revalidate();
	}

	void focusInput()
	{
		input.requestFocusInWindow();
	}

	/** Put a message that couldn't be sent back in the input box, after anything already there. */
	void restoreDraft(String text)
	{
		String current = input.getText().trim();
		if (current.isEmpty())
		{
			input.setText(text);
		}
		else if (!current.contains(text))
		{
			input.setText(current + "\n\n" + text);
		}
	}

	private JComponent hint()
	{
		JTextArea t = textArea("Ask anything, then go back to playing. You'll get a game chat message (and a "
			+ "notification, if enabled) when the reply is in.\n\n"
			+ "From the chatbox: ::ai <message>, or ::ai alone for a prompt. You can also set a hotkey.\n\n"
			+ "Choose Claude, ChatGPT or an OpenAI-compatible service, and add your API key, in the AI Chat settings.");
		t.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		t.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return t;
	}

	/** A label that never renders its text as HTML: much of what's shown comes from settings or replies. */
	private static JLabel plainLabel()
	{
		JLabel l = new JLabel();
		// Set before any text: the HTML renderer is picked when the text is set.
		l.putClientProperty("html.disable", Boolean.TRUE);
		l.setText(" ");
		return l;
	}

	/** A confirmation dialog whose text is shown as plain text, never HTML. */
	private boolean confirm(String title, String text)
	{
		JTextArea area = new JTextArea(text);
		area.setEditable(false);
		area.setOpaque(false);
		area.setFont(TEXT_FONT);
		return JOptionPane.showConfirmDialog(this, area, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
			== JOptionPane.OK_OPTION;
	}

	private JComponent bubble(Chat.Message m)
	{
		JPanel b = new JPanel(new BorderLayout(0, 2));
		b.setBackground(m.role == Chat.Role.USER ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARKER_GRAY_HOVER_COLOR);
		b.setBorder(new EmptyBorder(4, 6, 6, 6));

		JLabel who = new JLabel();
		// The author can be a model name from the settings: never render it as HTML.
		who.putClientProperty("html.disable", Boolean.TRUE);
		who.setText(m.author() + "  " + new SimpleDateFormat("HH:mm").format(new Date(m.time))
			+ (m.unanswered ? "  (not answered)" : ""));
		who.setFont(FontManager.getRunescapeSmallFont());
		who.setForeground(color(m.role));
		b.add(who, BorderLayout.NORTH);

		JTextArea body = textArea(m.text == null ? "" : m.text);
		body.setBackground(b.getBackground());
		body.setForeground(m.role == Chat.Role.ERROR ? ERROR_COLOR : Color.WHITE);
		b.add(body, BorderLayout.CENTER);
		return b;
	}

	private void showChatMenu(JComponent anchor)
	{
		Chat chat = plugin.currentChat();
		if (chat == null)
		{
			return;
		}
		JPopupMenu menu = new JPopupMenu();
		menu.add("Rename...").addActionListener(e ->
		{
			String name = JOptionPane.showInputDialog(this, "Chat name:", chat.name);
			if (name != null && !name.trim().isEmpty())
			{
				plugin.renameChat(chat, name.trim());
			}
		});
		menu.add("Clear chat").addActionListener(e ->
		{
			if (confirm("AI Chat", "Clear \"" + chat.name + "\"? The assistant forgets it too."))
			{
				plugin.clearChat(chat);
			}
		});
		menu.addSeparator();
		menu.add("Delete chat").addActionListener(e ->
		{
			if (confirm("AI Chat", "Delete \"" + chat.name + "\"?"))
			{
				plugin.deleteChat(chat);
			}
		});
		menu.show(anchor, 0, anchor.getHeight());
	}

	private static Color color(Chat.Role role)
	{
		switch (role)
		{
			case USER:
				return USER_COLOR;
			case ASSISTANT:
				return ColorScheme.BRAND_ORANGE;
			case ERROR:
				return ERROR_COLOR;
			default:
				return ColorScheme.LIGHT_GRAY_COLOR;
		}
	}

	private static JButton smallButton(String text, String tip, java.awt.event.ActionListener action)
	{
		JButton b = new JButton(text);
		b.setToolTipText(tip);
		b.setMargin(new Insets(0, 4, 0, 4));
		b.addActionListener(action);
		return b;
	}

	/** A read-only, wrapping, selectable text block, so replies can be copied. */
	private static JTextArea textArea(String text)
	{
		JTextArea t = new JTextArea(text);
		t.setEditable(false);
		t.setLineWrap(true);
		t.setWrapStyleWord(true);
		t.setFont(TEXT_FONT);
		t.setBorder(null);
		return t;
	}

	/** Tracks the viewport width so the wrapped text areas inside get a real width. */
	private static class TranscriptPanel extends JPanel implements Scrollable
	{
		@Override
		public void setBounds(int x, int y, int width, int height)
		{
			boolean widthChanged = width != getWidth();
			super.setBounds(x, y, width, height);
			if (widthChanged)
			{
				// Heights depend on the width: measure again now that it's known.
				revalidate();
			}
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
		{
			return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}
}
