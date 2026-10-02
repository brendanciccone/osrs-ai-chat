package com.aichat;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * Chat with Claude, ChatGPT or any OpenAI-compatible model from inside RuneLite, with the player's own API key.
 * Sends what the player types, and their character's name, levels and quests only if they turn that on.
 */
@Slf4j
@PluginDescriptor(
	name = "AI Chat",
	internalName = "osrs-ai-chat",
	description = "Chat with Claude, ChatGPT or any OpenAI-compatible model from a side panel or ::ai, with your own API key; get notified in game when it replies",
	tags = {"claude", "chatgpt", "openai", "anthropic", "ollama", "ai", "llm", "assistant", "chat", "notifications"}
)
public class AiChatPlugin extends Plugin
{
	/** Typed in the chatbox: "::ai what should I train next?". */
	private static final String PREFIX = "::ai";
	/** Game chat messages per reply (the game wraps each one), not counting the note that it was cut. */
	static final int ECHO_MAX_MESSAGES = 8;
	/** Longer words (links) are shortened in game chat: the game wraps only at spaces and hyphens. */
	static final int ECHO_MAX_WORD = 60;
	/** "- item", "* item", "+ item", bullet, "1. item", "2) item". */
	private static final Pattern LIST_ITEM = Pattern.compile("([-*+\u2022]|\\d{1,3}[.)])\\s+(.+)");
	/** Markdown that's only layout (rules, code fences, table borders): left out of game chat. */
	private static final Pattern LAYOUT_ONLY = Pattern.compile("```.*|[-*_=#|`~:+ ]+");
	/** A paragraph isn't started with less than this much of the length setting left: it'd be a stub. */
	private static final int ECHO_MIN_PART = 20;
	/** Earlier messages sent with each request; older ones are left out to keep requests (and costs) bounded. */
	private static final int MAX_HISTORY = 40;

	static final String SYSTEM_PROMPT = "You are the assistant in AI Chat, a RuneLite plugin: an Old School RuneScape "
		+ "player is chatting with you from inside the game client, often while playing. Keep answers short and direct "
		+ "by default, but when the player asks for something long, like a full list or a step-by-step guide, give all "
		+ "of it: the side panel scrolls, and the game chat only shows the beginning. Write plain text without Markdown "
		+ "headings, tables or bold, because the panel is narrow; lists are fine. Questions about the game are about Old School RuneScape, not "
		+ "RuneScape 3. Game details such as drop rates, requirements and prices change, so say when you're unsure and "
		+ "suggest the OSRS Wiki. You can't see or control the game. If the player has chosen to share their character, "
		+ "their message starts with a [Character: ...] note with details from the game.";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private Notifier notifier;

	@Inject
	private AiChatConfig config;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient okHttpClient;

	/** For reading and writing the saved chats, off the Swing and client threads. */
	@Inject
	private ScheduledExecutorService executor;

	/** For AI requests: they can take a while, and replies shouldn't land in RuneLite's disk cache. */
	private OkHttpClient apiHttp;
	/** Optional request settings each OpenAI-compatible service and model has refused, while the plugin runs. */
	private final Map<String, Set<String>> refusedOptions = new ConcurrentHashMap<>();
	/** The saved chats on disk ("Remember chats"); its file work runs on {@link #executor}. */
	private ChatFile chatFile;
	/** EDT: whether this window owns the saved chats; null until the file has been opened. */
	private ChatFile.State fileState;
	/** EDT: the saved chats are being opened; a save now would overwrite them, so it waits. */
	private boolean loading;
	private boolean saveAfterLoading;
	/** EDT: the next save, if one is waiting. */
	private ScheduledFuture<?> pendingSave;
	private long saveCount;
	/** EDT: RuneLite is closing; save at once instead of a moment later. */
	private boolean closing;
	private AiChatPanel panel;
	private NavigationButton navButton;

	// Swing EDT state.
	@Getter
	private final List<Chat> chats = new ArrayList<>();
	private Chat current;
	private int chatCounter;
	/** Client thread: replies that arrived while logged out, echoed once the player logs in. */
	private final List<String[]> heldEchoes = new ArrayList<>();

	private final HotkeyListener askHotkey = new HotkeyListener(() -> config.askHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			clientThread.invoke(AiChatPlugin.this::openAskPrompt);
		}
	};

	@Provides
	AiChatConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AiChatConfig.class);
	}

	@Override
	protected void startUp()
	{
		// startUp runs on the EDT.
		apiHttp = okHttpClient.newBuilder()
			.cache(null)
			// A redirect would carry the API key to wherever it points.
			.followRedirects(false)
			.followSslRedirects(false)
			// Answers arrive in one piece once written, which can take minutes for a long reply or a local model.
			.readTimeout(10, TimeUnit.MINUTES)
			.callTimeout(10, TimeUnit.MINUTES)
			.build();
		refusedOptions.clear();
		if (chatFile == null)
		{
			chatFile = new ChatFile(this::getPluginDirectory, gson);
		}
		// Chats outlive turning the plugin off and on: they're kept for as long as RuneLite runs, and, with "Remember
		// chats", on disk for the next time.
		if (chats.isEmpty())
		{
			current = addChat();
		}
		else if (current == null || !chats.contains(current))
		{
			current = chats.get(chats.size() - 1);
		}
		panel = new AiChatPanel(this);
		BufferedImage icon = ImageUtil.loadImageResource(AiChatPlugin.class, "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("AI Chat")
			.icon(icon)
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		keyManager.registerKeyListener(askHotkey);
		// "Remember chats" may have changed while AI Chat was off, unseen by onConfigChanged: act on it now.
		applyRememberChats();
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(askHotkey);
		// The chats are kept for when the plugin is turned back on, but nothing is left waiting for a reply.
		for (Chat c : chats)
		{
			stop(c, "Stopped: AI Chat was turned off.");
		}
		saveNow();
		panel.refreshAll();
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
		clientThread.invokeLater(heldEchoes::clear);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (AiChatConfig.GROUP.equals(e.getGroup()))
		{
			SwingUtilities.invokeLater(() ->
			{
				if (panel == null)
				{
					return;
				}
				// "Nothing is sent while this is off": that includes requests already on their way.
				if ("aiRequests".equals(e.getKey()) && !config.aiRequests())
				{
					for (Chat c : chats)
					{
						stop(c, "Stopped: AI requests were turned off.");
					}
				}
				if ("rememberChats".equals(e.getKey()))
				{
					applyRememberChats();
				}
				panel.refreshAll();
			});
		}
	}

	// ------------------------------------------------------------------
	// Provider setup
	// ------------------------------------------------------------------

	/** What's missing before messages can be sent, or null when the chosen provider is set up. */
	String setupProblem()
	{
		if (!config.aiRequests())
		{
			return "Turn on \"Enable AI requests\" in the AI Chat settings, then choose a provider and add your API key.";
		}
		switch (config.provider())
		{
			case CLAUDE:
			{
				String key = ChatApi.cleanKey(config.claudeApiKey());
				if (key.isEmpty())
				{
					return "Add your Claude API key in the Claude section of the AI Chat settings (from console.anthropic.com).";
				}
				if (!ChatApi.sendableKey(key))
				{
					return BAD_KEY;
				}
				return blank(config.claudeModel()) ? "Set a Claude model in the Claude section of the AI Chat settings." : null;
			}
			case CHATGPT:
			{
				String key = ChatApi.cleanKey(config.openaiApiKey());
				if (key.isEmpty())
				{
					return "Add your OpenAI API key in the ChatGPT section of the AI Chat settings (from platform.openai.com).";
				}
				if (!ChatApi.sendableKey(key))
				{
					return BAD_KEY;
				}
				return blank(config.openaiModel()) ? "Set a ChatGPT model in the ChatGPT section of the AI Chat settings." : null;
			}
			default:
			{
				HttpUrl url = OpenAiApi.parseBaseUrl(config.compatibleUrl());
				if (url == null)
				{
					return "Set the URL in the OpenAI-compatible section of the AI Chat settings, for example http://localhost:11434/v1.";
				}
				String key = ChatApi.cleanKey(config.compatibleApiKey());
				if (!ChatApi.sendableKey(key))
				{
					return BAD_KEY;
				}
				if (!url.isHttps() && !key.isEmpty() && !OpenAiApi.isPrivate(url.host()))
				{
					return "Use an https:// URL for " + url.host() + ": with http:// your API key would cross the internet unencrypted.";
				}
				return blank(config.compatibleModel()) ? "Set the model in the OpenAI-compatible section of the AI Chat settings." : null;
			}
		}
	}

	/** "Claude · claude-opus-5-5": what answers new messages. */
	String setupSummary()
	{
		switch (config.provider())
		{
			case CLAUDE:
				return "Claude · " + config.claudeModel().trim();
			case CHATGPT:
				return "ChatGPT · " + config.openaiModel().trim();
			default:
				return config.compatibleModel().trim() + " · " + OpenAiApi.describeUrl(config.compatibleUrl());
		}
	}

	/** Null if not set up; see {@link #setupProblem()}. */
	private ChatApi api()
	{
		if (setupProblem() != null)
		{
			return null;
		}
		switch (config.provider())
		{
			case CLAUDE:
				return new AnthropicApi(apiHttp, gson, AnthropicApi.URL, ChatApi.cleanKey(config.claudeApiKey()));
			case CHATGPT:
				return new OpenAiApi(apiHttp, gson, OpenAiApi.OPENAI_URL, ChatApi.cleanKey(config.openaiApiKey()), "ChatGPT", true,
					"low", refusedOptions);
			default:
				String model = config.compatibleModel().trim();
				return new OpenAiApi(apiHttp, gson, OpenAiApi.parseBaseUrl(config.compatibleUrl()), ChatApi.cleanKey(config.compatibleApiKey()), model, false,
					config.compatibleThinking().effort, refusedOptions);
		}
	}

	private String model()
	{
		switch (config.provider())
		{
			case CLAUDE:
				return config.claudeModel().trim();
			case CHATGPT:
				return config.openaiModel().trim();
			default:
				return config.compatibleModel().trim();
		}
	}

	private String systemPrompt()
	{
		String extra = config.instructions().trim();
		return extra.isEmpty() ? SYSTEM_PROMPT : SYSTEM_PROMPT + "\n\nThe player's own instructions: " + extra;
	}

	private static final String BAD_KEY = "Your API key has a character that can't be sent, like a curly quote copied along "
		+ "with it. Paste it into the AI Chat settings again.";

	private static boolean blank(String s)
	{
		return s == null || s.trim().isEmpty();
	}

	// ------------------------------------------------------------------
	// Chatbox input (client thread)
	// ------------------------------------------------------------------

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent e)
	{
		if (!"chatDefaultReturn".equals(e.getEventName()))
		{
			return;
		}
		String text = stripPrefix(client.getVarcStrValue(VarClientID.CHATINPUT));
		if (text == null)
		{
			return;
		}

		// Blocked here like the core Twitch plugin's "/t", not via CommandExecuted: an unblocked "::" line is also
		// sent to the game server by the chat script (docheat), and the player's message shouldn't go there.
		int[] intStack = client.getIntStack();
		intStack[client.getIntStackSize() - 3] = 1;

		if (text.isEmpty())
		{
			clientThread.invokeLater(this::openAskPrompt);
		}
		else
		{
			sendFromGame(text);
		}
	}

	/** The message after "::ai", or null if the input isn't for us. */
	static String stripPrefix(String typed)
	{
		if (typed == null)
		{
			return null;
		}
		String t = typed.trim();
		String lower = t.toLowerCase(Locale.ROOT);
		if (lower.equals(PREFIX) || lower.startsWith(PREFIX + " "))
		{
			return t.substring(PREFIX.length()).trim();
		}
		return null;
	}

	private void openAskPrompt()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		chatboxPanelManager.openTextInput("Ask:")
			.onDone((Consumer<String>) text ->
			{
				// onDone runs on the AWT thread.
				if (text != null && !text.trim().isEmpty())
				{
					sendFromGame(text.trim());
				}
			})
			.build();
	}

	/** Any thread. */
	private void sendFromGame(String text)
	{
		SwingUtilities.invokeLater(() ->
		{
			String problem = setupProblem();
			if (problem != null)
			{
				keepDraft(text);
				gameMessage("AI Chat: " + problem + " Your message is waiting in the AI Chat panel.");
				return;
			}
			Chat chat = current;
			if (chat == null || chat.isRunning())
			{
				keepDraft(text);
				gameMessage("AI Chat: still waiting for the last reply. Your message is waiting in the AI Chat panel.");
				return;
			}
			if (send(text))
			{
				gameMessage("Asked " + api().displayName() + (chat.namedByPlayer ? " (" + chat.name + ")" : "")
					+ ". You'll be pinged when there's a reply.");
			}
		});
	}

	// ------------------------------------------------------------------
	// Chats (EDT)
	// ------------------------------------------------------------------

	Chat currentChat()
	{
		return current;
	}

	/** Returns false if the message can't be sent right now; the panel keeps the text. */
	boolean send(String text)
	{
		Chat chat = current;
		ChatApi api = api();
		if (chat == null)
		{
			return false;
		}
		if (api == null)
		{
			showError(setupProblem());
			return false;
		}
		if (chat.isRunning())
		{
			showError("Still waiting for the last reply. Send this when it's in, or press Stop.");
			return false;
		}

		Chat.Message message = new Chat.Message(Chat.Role.USER, text);
		chat.messages.add(message);
		if (chat.defaultName)
		{
			chat.name = ChatApi.shorten(text.replaceAll("\\s+", " "), 40);
			chat.defaultName = false;
		}
		saveSoon();
		String model = model();
		String system = systemPrompt();
		boolean shareCharacter = config.sendCharacter();
		// Busy from now on, so nothing else is sent in this chat while character details are gathered.
		chat.pending = new ChatApi.Pending();
		chat.runStartedAt = System.currentTimeMillis();
		panel.refreshAll();

		if (shareCharacter)
		{
			ChatApi.Pending placeholder = chat.pending;
			// invokeLater: reading quest states runs a game script, which can't happen inside another one.
			clientThread.invokeLater(() ->
			{
				String context = null;
				try
				{
					context = client.getGameState() == GameState.LOGGED_IN ? CharacterInfo.describe(client) : null;
				}
				catch (RuntimeException e)
				{
					// Send the message without it rather than leave the chat waiting forever.
					log.debug("couldn't read character info", e);
				}
				String sent = context;
				SwingUtilities.invokeLater(() ->
				{
					// Stopped (or the plugin turned off) while we were on the client thread.
					if (chat.pending == placeholder && !placeholder.isCancelled())
					{
						dispatch(chat, message, api, model, system, true, sent);
					}
				});
			});
		}
		else
		{
			dispatch(chat, message, api, model, system, false, null);
		}
		return true;
	}

	/**
	 * Sends the chat so far. {@code shareCharacter}: the setting when the message was sent; {@code context}: the
	 * character details read for it, if any.
	 */
	private void dispatch(Chat chat, Chat.Message message, ChatApi api, String model, String system,
		boolean shareCharacter, String context)
	{
		if (!config.aiRequests())
		{
			stop(chat, "Stopped: AI requests are turned off.");
			return;
		}

		ChatApi.Conversation conversation = new ChatApi.Conversation();
		conversation.model = model;
		conversation.system = system;
		List<Chat.Message> history = new ArrayList<>();
		for (Chat.Message m : chat.messages)
		{
			boolean sent = m.role == Chat.Role.USER && !m.unanswered || m.role == Chat.Role.ASSISTANT;
			if (sent)
			{
				history.add(m);
			}
		}
		// Claude only accepts earlier replies back verbatim when everything before them is unchanged: not if the
		// oldest messages had to be left out. A conversation also has to start with the player.
		boolean trimmed = history.size() > MAX_HISTORY;
		List<Chat.Message> recent = new ArrayList<>(history.subList(Math.max(0, history.size() - MAX_HISTORY), history.size()));
		while (!recent.isEmpty() && recent.get(0).role != Chat.Role.USER)
		{
			recent.remove(0);
		}

		// Character details go with this message unless the latest ones the provider will see are the same. Decided
		// from what's actually sent: an earlier note may have gone with a failed request or been trimmed off.
		if (shareCharacter && context != null)
		{
			String seen = null;
			for (Chat.Message m : recent)
			{
				if (m != message && m.context != null)
				{
					seen = m.context;
				}
			}
			if (!context.equals(seen))
			{
				message.context = context;
			}
		}
		// With sharing turned off, earlier notes stay out too. That changes earlier turns, so Claude's replies can't be
		// replayed as they were.
		boolean notesLeftOut = false;
		for (Chat.Message m : recent)
		{
			notesLeftOut |= !shareCharacter && m.context != null;
		}
		for (Chat.Message m : recent)
		{
			boolean withNote = shareCharacter && m.context != null;
			ChatApi.Turn t = new ChatApi.Turn(m.role == Chat.Role.USER, withNote ? m.context + "\n\n" + m.text : m.text);
			if (!trimmed && !notesLeftOut)
			{
				t.rawContent = m.rawContent;
				t.rawModel = m.rawModel;
				t.rawSystem = m.rawSystem;
			}
			conversation.turns.add(t);
		}

		String who = api.displayName();
		// Set before any answer can be handled: answers are handled on this (the EDT) thread, after this method.
		ChatApi.Pending[] request = new ChatApi.Pending[1];
		try
		{
			request[0] = api.send(conversation, new ChatApi.Listener()
			{
				@Override
				public void onReply(ChatApi.Reply reply)
				{
					SwingUtilities.invokeLater(() -> finished(chat, request[0], message, who, system, reply, null));
				}

				@Override
				public void onError(String error)
				{
					SwingUtilities.invokeLater(() -> finished(chat, request[0], message, who, system, null, error));
				}
			});
		}
		catch (RuntimeException e)
		{
			// Building the request failed (OkHttp rejects some header values). Its message can contain the API key:
			// don't pass it on or log it.
			chat.pending = null;
			message.unanswered = true;
			chat.messages.add(new Chat.Message(Chat.Role.ERROR, "AI Chat couldn't send this. Check the API key and URL in the settings."));
			saveSoon();
			panel.refreshAll();
			return;
		}
		chat.pending = request[0];
	}

	private void finished(Chat chat, ChatApi.Pending request, Chat.Message question, String who, String system,
		ChatApi.Reply reply, String error)
	{
		// Only the answer to the request still in flight counts: not one that was stopped, or a chat that's gone.
		if (chat.pending != request || request.isCancelled() || !chats.contains(chat))
		{
			return;
		}
		chat.pending = null;
		if (reply != null)
		{
			if (reply.historyAsText)
			{
				// This reply was built on plain-text history; keep sending the earlier replies that way.
				for (Chat.Message earlier : chat.messages)
				{
					earlier.rawContent = null;
					earlier.rawModel = null;
					earlier.rawSystem = null;
				}
			}
			Chat.Message m = new Chat.Message(Chat.Role.ASSISTANT, reply.text + (reply.cutShort ? "\n\n(The reply was cut short.)" : ""));
			m.who = who;
			m.rawContent = reply.rawContent;
			m.rawModel = reply.model;
			m.rawSystem = system;
			chat.messages.add(m);
			ping(chat, m);
		}
		else
		{
			question.unanswered = true;
			Chat.Message m = new Chat.Message(Chat.Role.ERROR, error);
			chat.messages.add(m);
			ping(chat, m);
		}
		saveSoon();
		if (panel != null)
		{
			panel.refreshAll();
		}
	}

	/** EDT. Put text back in the panel's input box. */
	private void keepDraft(String text)
	{
		if (panel != null)
		{
			panel.restoreDraft(text);
		}
	}

	private Chat addChat()
	{
		Chat c = new Chat("Chat " + (++chatCounter));
		chats.add(c);
		return c;
	}

	void newChat()
	{
		current = addChat();
		panel.refreshAll();
		panel.focusInput();
	}

	void selectChat(Chat chat)
	{
		if (chat != null && chat != current && chats.contains(chat))
		{
			current = chat;
			panel.refreshAll();
		}
	}

	void stop()
	{
		if (current != null)
		{
			stop(current, "Stopped.");
		}
	}

	private void stop(Chat chat, String note)
	{
		if (chat.pending == null)
		{
			return;
		}
		chat.pending.cancel();
		chat.pending = null;
		// The unanswered question stays in the transcript but isn't sent again.
		for (int i = chat.messages.size() - 1; i >= 0; i--)
		{
			Chat.Message m = chat.messages.get(i);
			if (m.role == Chat.Role.USER)
			{
				m.unanswered = true;
				break;
			}
		}
		chat.messages.add(new Chat.Message(Chat.Role.NOTE, note));
		saveSoon();
		if (panel != null)
		{
			panel.refreshAll();
		}
	}

	// These take the chat from where the action started: the selection can change while a dialog is open.

	void renameChat(Chat chat, String name)
	{
		chat.name = name;
		chat.defaultName = false;
		chat.namedByPlayer = true;
		saveSoon();
		panel.refreshAll();
	}

	void clearChat(Chat chat)
	{
		if (chat.pending != null)
		{
			chat.pending.cancel();
			chat.pending = null;
		}
		chat.messages.clear();
		saveSoon();
		panel.refreshAll();
	}

	void deleteChat(Chat chat)
	{
		if (chat.pending != null)
		{
			chat.pending.cancel();
			chat.pending = null;
		}
		int index = chats.indexOf(chat);
		chats.remove(chat);
		if (chats.isEmpty())
		{
			addChat();
		}
		if (current == chat)
		{
			current = chats.get(Math.max(0, Math.min(index, chats.size() - 1)));
		}
		saveSoon();
		panel.refreshAll();
	}

	// ------------------------------------------------------------------
	// Saved chats ("Remember chats")
	// ------------------------------------------------------------------

	/** EDT. Makes the saved chats match the setting: open (and load) them, save, or delete them. */
	private void applyRememberChats()
	{
		if (!config.rememberChats())
		{
			deleteSaved();
		}
		else if (fileState == null)
		{
			openSaved();
		}
		else if (fileState == ChatFile.State.OWNER)
		{
			saveSoon();
		}
		else
		{
			showFileNote();
		}
	}

	/** EDT. Opens the saved chats in the background, brings them back, and from then on saves this window's. */
	private void openSaved()
	{
		if (loading)
		{
			return;
		}
		loading = true;
		executor.execute(() ->
		{
			ChatFile.Opened opened = chatFile.open();
			SwingUtilities.invokeLater(() ->
			{
				loading = false;
				fileState = opened.state;
				if (opened.loaded != null)
				{
					restore(opened.loaded);
				}
				showFileNote();
				saveAfterLoading = false;
				// Brings the file up to date with chats from before it was opened, if any.
				saveSoon();
			});
		});
	}

	/** EDT. Saved chats go first; the empty chat made at start-up gives way to them. */
	private void restore(ChatStore.Loaded loaded)
	{
		List<Chat> fresh = new ArrayList<>();
		for (Chat c : loaded.chats)
		{
			if (chats.stream().noneMatch(existing -> existing.id.equals(c.id)))
			{
				fresh.add(c);
			}
		}
		if (fresh.isEmpty())
		{
			return;
		}
		chats.removeIf(c -> c.messages.isEmpty() && !c.isRunning() && !c.namedByPlayer);
		chats.addAll(0, fresh);
		if (current == null || !chats.contains(current))
		{
			current = loaded.current != null && chats.contains(loaded.current) ? loaded.current : fresh.get(fresh.size() - 1);
		}
		chatCounter = Math.max(chatCounter, chats.size());
		if (panel != null)
		{
			panel.refreshAll();
		}
	}

	/** EDT. Says why this window's chats aren't being remembered, if they aren't. */
	private void showFileNote()
	{
		if (panel == null || !config.rememberChats())
		{
			return;
		}
		if (fileState == ChatFile.State.OTHER_WINDOW)
		{
			panel.showNote("Chats in this window won't be remembered: AI Chat already remembers chats in another RuneLite window.");
		}
		else if (fileState == ChatFile.State.UNREADABLE)
		{
			panel.showNote("Your saved chats couldn't be read, so this session won't save over them. Restarting RuneLite may help.");
		}
	}

	/** EDT. Saves the chats a moment from now; more changes in the meantime make it one save. */
	private void saveSoon()
	{
		save(1);
	}

	/** EDT. */
	private void saveNow()
	{
		save(0);
	}

	/** EDT. The save scheduled, or null if there's nothing to save to. */
	private ScheduledFuture<?> save(long delaySeconds)
	{
		if (!config.rememberChats())
		{
			return null;
		}
		if (loading)
		{
			saveAfterLoading = true;
			return null;
		}
		if (fileState != ChatFile.State.OWNER)
		{
			return null;
		}
		// Taken now, on the EDT, where the chats live.
		String json = ChatStore.toJson(gson, chats, current);
		long number = ++saveCount;
		if (pendingSave != null)
		{
			pendingSave.cancel(false);
		}
		pendingSave = executor.schedule(() -> chatFile.write(json, number, config::rememberChats),
			closing ? 0 : delaySeconds, TimeUnit.SECONDS);
		return pendingSave;
	}

	/** EDT. "Remember chats" is off: no saves waiting, and no saved copy left. */
	private void deleteSaved()
	{
		if (pendingSave != null)
		{
			pendingSave.cancel(false);
			pendingSave = null;
		}
		ChatFile file = chatFile;
		executor.execute(file::delete);
	}

	/** RuneLite doesn't turn plugins off when it closes, so this is the last chance to save. */
	@Subscribe
	public void onClientShutdown(ClientShutdown e)
	{
		// RuneLite posts this from the Swing thread, where the chats live; anywhere else, save without waiting.
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() ->
			{
				closing = true;
				saveNow();
			});
			return;
		}
		closing = true;
		ScheduledFuture<?> saved = save(0);
		if (saved != null)
		{
			// RuneLite waits for this (up to a few seconds) before exiting.
			e.waitFor(saved);
		}
	}

	private void showError(String message)
	{
		if (panel != null)
		{
			panel.showNote(message);
		}
	}

	// ------------------------------------------------------------------
	// Game chat
	// ------------------------------------------------------------------

	private void ping(Chat chat, Chat.Message m)
	{
		boolean ok = m.role == Chat.Role.ASSISTANT;
		String who = ok ? m.who : "AI Chat";
		// An automatic chat name is just the start of the player's question: left out of notifications (they can
		// outlive the session) and of the game chat (where it was just asked).
		String name = chat.namedByPlayer ? chat.name : null;
		String text = m.text;
		String where = name != null ? " in \"" + name + "\"" : "";
		clientThread.invokeLater(() ->
		{
			notifier.notify(config.notifyOnDone(), (ok ? who + " replied" : "AI Chat hit a problem") + where);
			if (!config.echoToChat())
			{
				return;
			}
			String label = ok ? who : "AI Chat (error)";
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				echo(label, name, text);
			}
			else if (heldEchoes.size() < 10)
			{
				// Chat added at the login screen is never seen; keep it for later.
				heldEchoes.add(new String[]{label, name, text});
			}
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN && !heldEchoes.isEmpty())
		{
			for (String[] held : heldEchoes)
			{
				echo(held[0], held[1], held[2]);
			}
			heldEchoes.clear();
		}
	}

	/** Client thread. {@code chatName}: shown with the first message, or null. */
	private void echo(String label, String chatName, String text)
	{
		for (String message : echoMessages(label, chatName, text, config.echoMaxChars()))
		{
			queueChat(message);
		}
	}

	/**
	 * A reply as game chat: one message per paragraph or list item, which the game wraps to the chatbox's width.
	 * Each starts with a marker drawn in the highlight colour (who it's from, or the list marker), so only the game's
	 * own wrapped rows go unmarked. The model's text is escaped and never in the highlight colour: it can't draw a
	 * marker, or pass for one of the game's red warnings.
	 */
	static List<String> echoMessages(String label, String chatName, String text, int maxChars)
	{
		List<String> messages = new ArrayList<>();
		int left = maxChars;
		boolean cut = false;
		for (String raw : text.split("\\r\\n|[\\n\\r\u2028\u2029]"))
		{
			String line = chatText(raw);
			if (line.isEmpty() || LAYOUT_ONLY.matcher(line).matches())
			{
				continue;
			}
			if (messages.size() == ECHO_MAX_MESSAGES || left <= 0)
			{
				cut = true;
				break;
			}
			String marker = null;
			Matcher item = LIST_ITEM.matcher(line);
			if (item.matches())
			{
				marker = Character.isDigit(line.charAt(0)) ? item.group(1) : "-";
				line = item.group(2);
			}
			if (line.length() > left && left < ECHO_MIN_PART && !messages.isEmpty())
			{
				cut = true;
				break;
			}
			if (line.length() > left)
			{
				line = cutAtSpace(line, left) + " ...";
				cut = true;
			}
			left -= line.length();
			String lead;
			if (messages.isEmpty())
			{
				lead = label + (chatName == null ? "" : " (" + ChatApi.shorten(chatName, 30) + ")") + ":"
					+ (marker == null ? "" : " " + marker);
			}
			else
			{
				lead = marker != null ? marker : label + ":";
			}
			messages.add(new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT).append(lead + " ")
				.append(ChatColorType.NORMAL).append(line)
				.build());
			if (cut)
			{
				break;
			}
		}
		if (messages.isEmpty())
		{
			messages.add(highlighted(label + ": (empty reply)"));
		}
		if (cut)
		{
			messages.add(highlighted("AI Chat: the full reply is in the side panel."));
		}
		return messages;
	}

	/** A line of a reply for the chat font: plain punctuation, no Markdown bold or headings, no overlong words. */
	static String chatText(String raw)
	{
		String s = raw
			.replace('\u2018', '\'').replace('\u2019', '\'')
			.replace('\u201C', '"').replace('\u201D', '"')
			.replace('\u2013', '-').replace('\u2014', '-')
			.replace("\u2026", "...").replace("**", "")
			.replaceAll("[\\s\\p{Z}\\p{Cc}]+", " ").trim()
			.replaceFirst("^#{1,6} ", "");
		StringBuilder out = new StringBuilder();
		for (String word : s.split(" "))
		{
			out.append(out.length() == 0 ? "" : " ")
				.append(word.length() <= ECHO_MAX_WORD ? word : word.substring(0, ECHO_MAX_WORD - 3) + "...");
		}
		return out.toString();
	}

	/** {@code s} cut to at most {@code max} characters, at a space if there's one in the second half. */
	static String cutAtSpace(String s, int max)
	{
		int space = s.lastIndexOf(' ', max);
		return (space > max / 2 ? s.substring(0, space) : s.substring(0, max)).trim();
	}

	private static String highlighted(String text)
	{
		return new ChatMessageBuilder().append(ChatColorType.HIGHLIGHT).append(text).build();
	}

	private void gameMessage(String text)
	{
		queueChat(highlighted(text));
	}

	/** Thread-safe: the queue is flushed on the client thread. ChatMessageBuilder.append(String) escapes tags. */
	private void queueChat(String formatted)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(formatted)
			.build());
	}
}
