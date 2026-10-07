package com.aichat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
import javax.swing.JToolTip;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
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
	private static final Font SMALL_FONT = TEXT_FONT.deriveFont(11f);
	private static final Color USER_COLOR = new Color(0x7fb8ff);
	private static final Color ERROR_COLOR = new Color(0xff6b6b);
	private static final Color OK_COLOR = new Color(0x5fd068);
	private static final Color WARNING_COLOR = new Color(0xffb347);
	private static final Color MUTED_COLOR = ColorScheme.LIGHT_GRAY_COLOR;
	/** How close to the end of the transcript still counts as reading the end, in pixels. */
	private static final int BOTTOM_SLACK = 24;
	/** Rows the model list shows before it scrolls. */
	private static final int MODEL_ROWS = 12;
	private static final String TEST_TIP = "Check the connection, and see which models you can use";
	private static final String STOP_TIP = "Stop waiting for this reply; you can carry on with the chat afterwards";
	/** While a long chat is summarised before the question goes, Stop skips the summary instead. */
	private static final String SKIP_TIP = "Skip the summary and send the whole chat this time. Press Stop after that to "
		+ "stop the question too.";

	private final AiChatPlugin plugin;

	private final JLabel setupLabel = new PlainLabel();
	private final JButton testButton;
	private final JTextArea setupHelp = textArea("");
	/** Holds the "Choose model..." button, shown when a test listed models. */
	private final JPanel chooseRow = new JPanel(new BorderLayout());
	private final DefaultComboBoxModel<Chat> chatModel = new DefaultComboBoxModel<>();
	private final JComboBox<Chat> chatSelect = new JComboBox<>(chatModel);
	private final JPanel transcript = new TranscriptPanel();
	private final JScrollPane transcriptScroll = new JScrollPane(transcript);
	private final JLabel statusLabel = new PlainLabel();
	private final JTextArea noteArea = textArea("");
	private final JTextArea input = new JTextArea(3, 1);
	private final JButton sendButton = new JButton("Send");
	private final JButton stopButton = new JButton("Stop");
	private final Timer ticker = new Timer(1000, e -> refreshStatus());

	private boolean updatingCombo;
	/** What the transcript currently shows, to skip rebuilding it when nothing changed. */
	private String shownKey = "";
	private Chat shownChat;
	private String comboKey = "";
	/** The models "Choose model" offers: from the latest "Test". */
	private List<String> models = Collections.emptyList();
	/** The transcript's bubbles, kept between rebuilds so a new message doesn't redraw every earlier one. */
	private final Map<Chat.Message, Bubble> bubbles = new IdentityHashMap<>();
	/** The reply on its way, at the end of the transcript; null when none is. */
	private Bubble live;

	AiChatPanel(AiChatPlugin plugin)
	{
		super(false);
		this.plugin = plugin;
		testButton = smallButton("Test", TEST_TIP, e -> plugin.testConnection());

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

		JPanel setup = new JPanel(new BorderLayout(4, 0));
		setup.setOpaque(false);
		setupLabel.setFont(FontManager.getRunescapeSmallFont());
		setup.add(setupLabel, BorderLayout.CENTER);
		setup.add(testButton, BorderLayout.EAST);
		p.add(setup);

		setupHelp.setForeground(MUTED_COLOR);
		setupHelp.setBackground(ColorScheme.DARK_GRAY_COLOR);
		setupHelp.setFont(SMALL_FONT);
		p.add(setupHelp);

		chooseRow.setOpaque(false);
		chooseRow.add(smallButton("Choose model...", "Pick one of the models your key can use",
			e -> showModelMenu((JComponent) e.getSource())), BorderLayout.WEST);
		p.add(chooseRow);

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

		noteArea.setFont(SMALL_FONT);
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
		stopButton.setToolTipText(STOP_TIP);
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

	/**
	 * Only the reply on its way changed: its text as it streams in, what it looked up, or the status line. Redraws the
	 * live reply alone, and follows it down only if the player was reading the end of the transcript.
	 */
	void refreshLive(Chat chat)
	{
		if (chat == plugin.currentChat() && chat == shownChat && live != null)
		{
			boolean follow = atBottom();
			showLive(chat);
			transcript.revalidate();
			transcript.repaint();
			if (follow)
			{
				scrollToBottom();
			}
		}
		refreshStatus();
	}

	private void refreshSetup()
	{
		String problem = plugin.setupProblem();
		// Test needs no model: it's how a player who doesn't know the names finds one.
		String testProblem = plugin.testProblem();
		ConnectionCheck.Note note = plugin.connectionNote();
		if (problem == null)
		{
			setupLabel.setText(plugin.setupSummary());
			setupLabel.setForeground(OK_COLOR);
		}
		else
		{
			setupLabel.setText("Not set up yet");
			setupLabel.setForeground(ERROR_COLOR);
		}
		if (note != null)
		{
			setupHelp.setText(note.text);
			setupHelp.setForeground(color(note.kind));
		}
		else
		{
			setupHelp.setText(problem == null ? "" : problem + " Open RuneLite's settings (the wrench) and search for AI Chat.");
			setupHelp.setForeground(MUTED_COLOR);
		}
		setupHelp.setVisible(!setupHelp.getText().isEmpty());
		// Nothing is sent while AI requests are off, a test included; the tooltip says what's missing.
		testButton.setEnabled(testProblem == null);
		testButton.setToolTipText(testProblem == null ? TEST_TIP : testProblem);
		models = note == null ? Collections.emptyList() : note.models;
		chooseRow.setVisible(!models.isEmpty());
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

	/** What the transcript shows of a chat: when this changes, it's rebuilt. */
	private static String transcriptKey(Chat chat)
	{
		if (chat == null)
		{
			return "";
		}
		int unanswered = 0;
		int summarized = 0;
		int looked = 0;
		int shared = 0;
		for (Chat.Message m : chat.messages)
		{
			unanswered += m.unanswered ? 1 : 0;
			summarized += m.summarized ? 1 : 0;
			looked += m.activity == null ? 0 : m.activity.size();
			shared += m.context != null ? 1 : 0;
		}
		return chat.id + ":" + chat.messages.size() + ":" + chat.isRunning() + ":" + unanswered + ":" + summarized + ":"
			+ looked + ":" + shared;
	}

	private void refreshTranscript()
	{
		Chat chat = plugin.currentChat();
		String key = transcriptKey(chat);
		if (key.equals(shownKey))
		{
			return;
		}
		boolean switched = chat != shownChat;
		boolean follow = follow(chat, switched, atBottom(), bubbles);
		shownKey = key;
		shownChat = chat;

		transcript.removeAll();
		live = null;
		Map<Chat.Message, Bubble> kept = new IdentityHashMap<>();
		if (chat == null || chat.messages.isEmpty())
		{
			transcript.add(hint());
		}
		else
		{
			Chat.Message retry = RequestRunner.retryable(chat);
			Chat.Message last = chat.messages.get(chat.messages.size() - 1);
			for (Chat.Message m : chat.messages)
			{
				Bubble b = bubbles.get(m);
				if (b == null)
				{
					b = new Bubble();
				}
				b.show(m, retry != null && m == last);
				kept.put(m, b);
				transcript.add(b);
			}
			if (chat.isRunning())
			{
				live = new Bubble();
				transcript.add(live);
				showLive(chat);
			}
		}
		bubbles.clear();
		bubbles.putAll(kept);
		transcript.revalidate();
		transcript.repaint();
		if (follow)
		{
			scrollToBottom();
		}
	}

	/**
	 * Whether a rebuilt transcript goes down to its end: for another chat, a question just sent (the last message, not
	 * shown before), or a player who was reading the end anyway. Not for a note put in above a question already shown
	 * (a summary that came back), which would pull a player who has scrolled up away from what they're reading.
	 */
	static boolean follow(Chat chat, boolean switched, boolean atBottom, Map<Chat.Message, ?> shown)
	{
		if (switched || atBottom)
		{
			return true;
		}
		if (chat == null || chat.messages.isEmpty())
		{
			return false;
		}
		Chat.Message last = chat.messages.get(chat.messages.size() - 1);
		return last.role == Chat.Role.USER && !shown.containsKey(last);
	}

	/** The reply on its way: shown once it has words or look-ups. */
	private void showLive(Chat chat)
	{
		boolean any = chat.liveText != null || !chat.liveActivity.isEmpty();
		live.setVisible(any);
		if (any)
		{
			live.showLive(chat.answering, chat.liveText, chat.liveActivity);
		}
	}

	/** Whether the player is reading the end of the transcript (or it all fits). */
	private boolean atBottom()
	{
		JScrollBar bar = transcriptScroll.getVerticalScrollBar();
		return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - BOTTOM_SLACK;
	}

	private void scrollToBottom()
	{
		// After the layout the change asked for, which is queued before this.
		SwingUtilities.invokeLater(() ->
		{
			JScrollBar bar = transcriptScroll.getVerticalScrollBar();
			bar.setValue(bar.getMaximum());
		});
	}

	/** The status line: what the reply on its way is doing, ticking every second. */
	void refreshStatus()
	{
		Chat chat = plugin.currentChat();
		boolean running = chat != null && chat.isRunning();
		stopButton.setEnabled(running);
		// It doesn't stop the question then, so it doesn't say it does.
		boolean summarising = running && chat.isSummarizing();
		stopButton.setText(summarising ? "Skip" : "Stop");
		stopButton.setToolTipText(summarising ? SKIP_TIP : STOP_TIP);
		sendButton.setEnabled(chat != null && !running);
		if (!running)
		{
			ticker.stop();
			statusLabel.setToolTipText(null);
			statusLabel.setText(" ");
			return;
		}
		if (!ticker.isRunning())
		{
			ticker.start();
		}
		String status = PanelText.status(chat, System.currentTimeMillis());
		statusLabel.setText(status);
		// A long look-up doesn't fit on the line.
		statusLabel.setToolTipText(chat.lookingUp ? status : null);
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
			+ "The assistant can check the OSRS Wiki and GE prices, and lists what it looked up under its reply. In the "
			+ "settings you can also let it see your character, items and gear.\n\n"
			+ "Choose Claude, ChatGPT or an OpenAI-compatible service, and add your API key, in the AI Chat settings.");
		t.setForeground(MUTED_COLOR);
		t.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return t;
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

	/** One message in the transcript: who and when, the text, and what was looked up or shared for it. */
	private final class Bubble extends JPanel
	{
		private final JLabel header = new PlainLabel();
		private final JButton retry = smallButton("Retry", "Send this question again", e -> retry());
		private final MessageView body = new MessageView();
		private final JTextArea activity = textArea("");
		/** Under a message sent with the character details: says so, and shows them when clicked. */
		private final JLabel sharedLine = new PlainLabel();
		private final JTextArea sharedText = textArea("");

		Bubble()
		{
			super(new BorderLayout(0, 2));
			setBorder(new EmptyBorder(4, 6, 6, 6));
			JPanel top = new JPanel(new BorderLayout(4, 0));
			top.setOpaque(false);
			header.setFont(FontManager.getRunescapeSmallFont());
			top.add(header, BorderLayout.CENTER);
			retry.setFont(FontManager.getRunescapeSmallFont());
			top.add(retry, BorderLayout.EAST);
			add(top, BorderLayout.NORTH);
			body.setTextFont(TEXT_FONT);
			add(body, BorderLayout.CENTER);
			JPanel south = new JPanel(new StackLayout(2));
			south.setOpaque(false);
			activity.setOpaque(false);
			activity.setBorder(new EmptyBorder(3, 0, 0, 0));
			activity.setFont(SMALL_FONT);
			activity.setForeground(MUTED_COLOR);
			south.add(activity);
			sharedLine.setFont(SMALL_FONT);
			sharedLine.setForeground(MUTED_COLOR);
			sharedLine.setBorder(new EmptyBorder(3, 0, 0, 0));
			sharedLine.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			sharedLine.setToolTipText("Click to see what was sent with this message");
			sharedLine.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (SwingUtilities.isLeftMouseButton(e))
					{
						toggleShared();
					}
				}
			});
			south.add(sharedLine);
			sharedText.setOpaque(false);
			sharedText.setFont(SMALL_FONT);
			sharedText.setForeground(MUTED_COLOR);
			sharedText.setVisible(false);
			south.add(sharedText);
			add(south, BorderLayout.SOUTH);
		}

		/** {@code retryHere}: this message ends with an unanswered question, so Retry goes on it. */
		void show(Chat.Message m, boolean retryHere)
		{
			setBackground(m.role == Chat.Role.USER ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARKER_GRAY_HOVER_COLOR);
			// What the assistant no longer sees as written: the summary note further down stands in for it.
			header.setText(m.author() + "  " + new SimpleDateFormat("HH:mm").format(new Date(m.time))
				+ (m.unanswered ? "  (not answered)" : "") + (m.unfinished ? "  (unfinished)" : "")
				+ (m.summarized ? "  (summarised)" : ""));
			header.setForeground(color(m.role));
			retry.setVisible(retryHere);
			body.setVisible(true);
			switch (m.role)
			{
				case USER:
					body.setTextColor(Color.WHITE);
					body.setPlainText(m.text);
					break;
				case ERROR:
					body.setTextColor(ERROR_COLOR);
					body.setPlainText(m.text);
					break;
				case NOTE:
					body.setTextColor(MUTED_COLOR);
					body.setMarkdown(m.text);
					break;
				default:
					body.setTextColor(Color.WHITE);
					body.setMarkdown(m.text);
					break;
			}
			showActivity(m.activity);
			showShared(m.role == Chat.Role.USER ? m.context : null);
		}

		/**
		 * The character details sent with a message, if any: the biggest thing a message can share, so it's listed
		 * like a look-up, with the details themselves a click away (they're long). Plain text, like the rest.
		 */
		private void showShared(String context)
		{
			boolean any = context != null && !context.isEmpty();
			sharedLine.setVisible(any);
			if (!any)
			{
				sharedText.setVisible(false);
				sharedText.setText("");
				return;
			}
			if (!context.equals(sharedText.getText()))
			{
				sharedText.setText(context);
				sharedText.setVisible(false);
			}
			sharedLine.setText("Sent your character details" + (sharedText.isVisible() ? " (hide)" : " (show)"));
		}

		private void toggleShared()
		{
			sharedText.setVisible(!sharedText.isVisible());
			sharedLine.setText("Sent your character details" + (sharedText.isVisible() ? " (hide)" : " (show)"));
			// Taller or shorter now: measured again, with the transcript around it.
			revalidate();
			repaint();
		}

		/** The reply on its way. {@code text}: null until its first words. */
		void showLive(String who, String text, List<String> lines)
		{
			setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			header.setText(who == null ? "Assistant" : who);
			header.setForeground(ColorScheme.BRAND_ORANGE);
			retry.setVisible(false);
			body.setTextColor(Color.WHITE);
			body.setMarkdown(text);
			body.setVisible(text != null);
			showActivity(lines);
			showShared(null);
		}

		/** The look-ups, one per line, in plain text: they can hold words the model chose. */
		private void showActivity(List<String> lines)
		{
			String text = lines == null ? "" : String.join("\n", lines);
			if (!text.equals(activity.getText()))
			{
				activity.setText(text);
			}
			activity.setVisible(!text.isEmpty());
		}
	}

	private void retry()
	{
		if (shownChat != null)
		{
			plugin.retry(shownChat);
		}
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

	/** The models from the latest "Test", in a list that scrolls: some services offer hundreds. */
	private void showModelMenu(JComponent anchor)
	{
		if (models.isEmpty())
		{
			return;
		}
		JList<String> list = new JList<>(models.toArray(new String[0]));
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setVisibleRowCount(Math.min(MODEL_ROWS, models.size()));
		list.setCellRenderer(new DefaultListCellRenderer()
		{
			{
				// Model names come from the provider: never let Swing render them as HTML.
				putClientProperty("html.disable", Boolean.TRUE);
			}
		});
		list.setSelectedValue(plugin.model(), true);
		JScrollPane scroll = new JScrollPane(list);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		JPopupMenu menu = new JPopupMenu();
		menu.add(scroll);
		Runnable choose = () ->
		{
			String model = list.getSelectedValue();
			menu.setVisible(false);
			if (model != null)
			{
				plugin.chooseModel(model);
			}
		};
		list.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseReleased(MouseEvent e)
			{
				int i = list.locationToIndex(e.getPoint());
				Rectangle cell = i < 0 ? null : list.getCellBounds(i, i);
				if (SwingUtilities.isLeftMouseButton(e) && cell != null && cell.contains(e.getPoint()))
				{
					list.setSelectedIndex(i);
					choose.run();
				}
			}
		});
		list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "ai-chat-choose");
		list.getActionMap().put("ai-chat-choose", new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				choose.run();
			}
		});
		// As wide as the panel's contents, whatever the names' lengths.
		int width = Math.max(anchor.getWidth(), getWidth() - getInsets().left - getInsets().right);
		scroll.setPreferredSize(new Dimension(width, scroll.getPreferredSize().height));
		menu.show(anchor, 0, anchor.getHeight());
		list.requestFocusInWindow();
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
				return MUTED_COLOR;
		}
	}

	private static Color color(ConnectionCheck.Kind kind)
	{
		switch (kind)
		{
			case OK:
				return OK_COLOR;
			case WARNING:
				return WARNING_COLOR;
			case ERROR:
				return ERROR_COLOR;
			default:
				return MUTED_COLOR;
		}
	}

	private static JButton smallButton(String text, String tip, ActionListener action)
	{
		JButton b = new JButton(text);
		b.setToolTipText(tip);
		b.setMargin(new Insets(0, 4, 0, 4));
		b.addActionListener(action);
		return b;
	}

	/** A read-only, wrapping, selectable block of plain text (a text area never renders HTML). */
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

	/**
	 * A label that never renders its text, or its tooltip, as HTML: much of what's shown comes from settings, providers
	 * or replies.
	 */
	private static class PlainLabel extends JLabel
	{
		PlainLabel()
		{
			// Set before any text: the HTML renderer is picked when the text is set.
			putClientProperty("html.disable", Boolean.TRUE);
			setText(" ");
		}

		@Override
		public JToolTip createToolTip()
		{
			JToolTip tip = super.createToolTip();
			tip.putClientProperty("html.disable", Boolean.TRUE);
			return tip;
		}
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
