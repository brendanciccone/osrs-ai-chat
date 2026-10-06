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
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

/**
 * Chat with Claude, ChatGPT or any OpenAI-compatible model from inside RuneLite, with the player's own API key. Sends
 * what the player types; with their settings, the assistant also looks things up on the OSRS Wiki and, only if they
 * turn that on, sees their character, items and gear. This class runs the plugin: its lifecycle, threads and wiring.
 * {@link RequestRunner} sends the messages, {@link AiChatPanel} shows them.
 */
@Slf4j
@PluginDescriptor(
	name = "AI Chat",
	internalName = "osrs-ai-chat",
	description = "Chat with Claude, ChatGPT or any OpenAI-compatible model from a side panel or ::ai, with your own API key; it can look things up on the OSRS Wiki, and you get notified in game when it replies",
	tags = {"claude", "chatgpt", "openai", "anthropic", "ollama", "ai", "llm", "assistant", "chat", "wiki", "notifications"}
)
public class AiChatPlugin extends Plugin
{
	/** Typed in the chatbox: "::ai what should I train next?". */
	private static final String PREFIX = "::ai";

	/**
	 * What the assistant is told before every chat. The same words all session, whatever the settings (the tools say
	 * what's available): providers cache the start of a request, and only an unchanged start is read from the cache.
	 */
	static final String SYSTEM_PROMPT = "You are the assistant in AI Chat, a RuneLite plugin: an Old School RuneScape "
		+ "player is chatting with you from inside the game client, often while playing. Keep answers short and direct "
		+ "by default, but when the player asks for something long, like a full list or a step-by-step guide, give all "
		+ "of it: the side panel scrolls, and the game chat only shows the beginning. The panel is narrow and shows "
		+ "light Markdown: short paragraphs, lists, **bold** and links are fine, but don't use tables or headings. "
		+ "Questions about the game are about Old School RuneScape, not RuneScape 3. Game details such as drop rates, "
		+ "requirements and prices change, so check them with your tools when you have them (wiki_search and wiki_page "
		+ "read the OSRS Wiki, ge_price gives Grand Exchange prices from RuneLite) rather than relying on memory. When "
		+ "you used a Wiki page, link it, as https://oldschool.runescape.wiki/w/Page_name. Without the tools, say when "
		+ "you're unsure and suggest the OSRS Wiki. Tools that read the player's equipment, inventory, bank, Slayer task "
		+ "or achievement diaries are only offered when the player has chosen to share those; if you have other tools "
		+ "but not the one you'd need, say which setting would allow it (\"Share items and gear\" or \"Send character "
		+ "info\"). You can't see or control the game otherwise. If the player has chosen to share their character, "
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

	/** The chosen provider, as the settings describe it. */
	private ProviderSetup provider;

	/** For "Choose model": sets the chosen provider's model, as the settings panel would. */
	@Inject
	private ConfigManager configManager;

	/** For RuneLite's "Use actively traded price" setting, which GE prices follow. */
	@Inject
	private RuneLiteConfig runeLiteConfig;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient okHttpClient;

	/**
	 * For reading and writing the saved chats, off the Swing and client threads; for the AI providers' waits before a
	 * retry; and for the short waits between redraws of a reply as it streams in. Shared with RuneLite: short tasks only.
	 */
	@Inject
	private ScheduledExecutorService executor;

	/** For AI requests: they can take a while, and replies shouldn't land in RuneLite's disk cache. */
	private OkHttpClient apiHttp;
	/**
	 * Optional request settings each service and model has refused, while the plugin runs: OpenAI-compatible settings,
	 * and Claude models that don't take server-side fallback.
	 */
	private final Map<String, Set<String>> refusedOptions = new ConcurrentHashMap<>();
	/** The OSRS Wiki, for the assistant's look-ups; it sends nothing while AI requests or Wiki look-ups are off. */
	private WikiClient wikiClient;
	/** RuneLite's own GE prices, for ge_price. */
	private LookupTools.Prices prices;
	/** The player's own account, for the game-data tools. Kept while RuneLite runs: it holds the bank as last seen. */
	private GameData gameData;
	private RequestRunner runner;
	/**
	 * EDT: "Remember chats", saving to the plugin's folder (its file work runs on {@link #executor}). Kept while
	 * RuneLite runs, like the chats: the file is opened once.
	 */
	private ChatSaver saver;
	/** EDT: "Test" and its latest result. */
	private ConnectionTester tester;
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
			// Replies stream in as they're written, but a model on the player's own computer can take minutes to start,
			// and a service that ignores streaming sends nothing until the whole reply is done.
			.readTimeout(10, TimeUnit.MINUTES)
			// A long reply from a slow local model streams in for a long time; Stop is there for the player.
			.callTimeout(30, TimeUnit.MINUTES)
			.build();
		refusedOptions.clear();
		provider = new ProviderSetup(config);
		wikiClient = new WikiClient(okHttpClient, gson, () -> config.aiRequests() && config.wikiLookups());
		prices = new LookupTools.RuneLitePrices(itemManager, clientThread, executor, runeLiteConfig::useWikiItemPrices);
		if (gameData == null)
		{
			gameData = new GameData(client, itemManager);
		}
		runner = new RequestRunner(new Requests(), SwingUtilities::invokeLater, executor);
		tester = new ConnectionTester(SwingUtilities::invokeLater, () ->
		{
			if (panel != null)
			{
				panel.refreshAll();
			}
		});
		if (saver == null)
		{
			saver = new ChatSaver(new ChatFile(this::getPluginDirectory, gson), gson, SwingUtilities::invokeLater, executor,
				new Saving());
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
		saver.apply();
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(askHotkey);
		// The chats are kept for when the plugin is turned back on, but nothing is left waiting for a reply.
		for (Chat c : chats)
		{
			runner.stop(c, "Stopped: AI Chat was turned off.");
		}
		tester.stop();
		saver.saveNow();
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
						runner.stop(c, "Stopped: AI requests were turned off.");
					}
					tester.stop();
				}
				if ("rememberChats".equals(e.getKey()))
				{
					saver.apply();
				}
				panel.refreshAll();
			});
			if (("shareItems".equals(e.getKey()) || "aiRequests".equals(e.getKey())) && !canShareItems())
			{
				// Not kept where it can't be shared.
				clientThread.invokeLater(gameData::forgetBank);
			}
		}
	}

	/**
	 * Client thread. The bank can only be read while it's open, so it's kept as last seen, for get_bank: only while it
	 * could be shared.
	 */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (canShareItems())
		{
			gameData.itemContainerChanged(e);
		}
	}

	private boolean canShareItems()
	{
		return config.aiRequests() && config.shareItems();
	}

	// ------------------------------------------------------------------
	// Provider setup
	// ------------------------------------------------------------------

	/** What's missing before messages can be sent, or null when the chosen provider is set up. */
	String setupProblem()
	{
		return provider.problem();
	}

	/** "Claude · claude-opus-5-5": what answers new messages. */
	String setupSummary()
	{
		return provider.summary();
	}

	/** The model new messages go to, as set for the chosen provider. */
	String model()
	{
		return provider.model();
	}

	/** Null if not set up; see {@link #setupProblem()}. */
	private ChatApi api()
	{
		return provider.api(apiHttp, gson, executor, refusedOptions);
	}

	/** What a message sent now goes with: the provider and the settings of this moment. Null if not set up. */
	private RequestRunner.Setup setup()
	{
		ChatApi api = api();
		return api == null ? null : new RequestRunner.Setup(api, provider.model(), systemPrompt(), config.sendCharacter(),
			config.shareItems(), config.wikiLookups());
	}

	private String systemPrompt()
	{
		String extra = config.instructions().trim();
		return extra.isEmpty() ? SYSTEM_PROMPT : SYSTEM_PROMPT + "\n\nThe player's own instructions: " + extra;
	}

	// ------------------------------------------------------------------
	// Test connection and choose a model (EDT)
	// ------------------------------------------------------------------

	/** "Test" in the panel: asks the provider which models the key can use. Sends nothing while AI requests are off. */
	void testConnection()
	{
		ChatApi api = api();
		if (api == null)
		{
			return;
		}
		// Anthropic lists the newest models first; the others in no useful order.
		tester.start(api, provider.connection(), config.provider() != AiChatConfig.Provider.CLAUDE);
		panel.refreshAll();
	}

	/** What the latest "Test" says about the setup as it is now, or null when there's nothing to say. */
	ConnectionCheck.Note connectionNote()
	{
		if (setupProblem() != null)
		{
			return null;
		}
		return tester.note(provider.connection(), provider.service(), provider.model(), provider.keyed());
	}

	/** "Choose model": the provider's model setting becomes {@code model}, as if typed in the settings. */
	void chooseModel(String model)
	{
		if (model != null && !model.trim().isEmpty())
		{
			// Tells onConfigChanged, which shows the new model.
			configManager.setConfiguration(AiChatConfig.GROUP, provider.modelKey(), model.trim());
		}
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
			if (panel == null)
			{
				// AI Chat was turned off in the meantime.
				return;
			}
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
			RequestRunner.Setup setup = setup();
			runner.send(chat, text, setup);
			gameMessage("Asked " + setup.api.displayName() + (chat.namedByPlayer ? " (" + chat.name + ")" : "")
				+ ". You'll be pinged when there's a reply.");
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
		if (chat == null)
		{
			return false;
		}
		RequestRunner.Setup setup = setup();
		if (setup == null)
		{
			showError(setupProblem());
			return false;
		}
		if (chat.isRunning())
		{
			showError("Still waiting for the last reply. Send this when it's in, or press Stop.");
			return false;
		}
		runner.send(chat, text, setup);
		return true;
	}

	/** "Retry": the chat's unanswered question goes again, with the settings of now. */
	void retry(Chat chat)
	{
		if (!chats.contains(chat) || chat.isRunning())
		{
			return;
		}
		RequestRunner.Setup setup = setup();
		if (setup == null)
		{
			showError(setupProblem());
			return;
		}
		if (runner.retry(chat, setup))
		{
			showError(null);
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
			runner.stop(current);
		}
	}

	// These take the chat from where the action started: the selection can change while a dialog is open.

	void renameChat(Chat chat, String name)
	{
		chat.name = name;
		chat.defaultName = false;
		chat.namedByPlayer = true;
		saver.saveSoon();
		panel.refreshAll();
	}

	void clearChat(Chat chat)
	{
		RequestRunner.cancel(chat);
		chat.messages.clear();
		chat.summary = null;
		chat.leftOut = 0;
		chat.leftOutSummarized = 0;
		chat.leftOutNote = null;
		saver.saveSoon();
		panel.refreshAll();
	}

	void deleteChat(Chat chat)
	{
		RequestRunner.cancel(chat);
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
		saver.saveSoon();
		panel.refreshAll();
	}

	/** What {@link RequestRunner} needs from the plugin. Everything but the tools' callbacks runs on the EDT. */
	private final class Requests implements RequestRunner.Host
	{
		@Override
		public boolean aiRequests()
		{
			return config.aiRequests();
		}

		@Override
		public void readCharacter(Consumer<String> done)
		{
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
				done.accept(context);
			});
		}

		@Override
		public ToolBox tools(RequestRunner.Setup setup, Consumer<String> activity, Runnable started)
		{
			LookupTools lookups = new LookupTools(setup.wikiLookups ? wikiClient : null, prices, activity);
			// The settings as they were when the player sent the message, and as they are at each call: turning one off
			// while a reply is being written stops the sharing at once.
			GameDataTools game = new GameDataTools(gameData, clientThread::invoke, executor,
				() -> setup.shareItems && canShareItems(),
				() -> setup.shareCharacter && config.aiRequests() && config.sendCharacter(),
				activity);
			return new ToolBox(lookups, game, started);
		}

		@Override
		public boolean has(Chat chat)
		{
			return chats.contains(chat);
		}

		@Override
		public void changed(Chat chat)
		{
			saver.saveSoon();
			if (panel != null)
			{
				panel.refreshAll();
			}
		}

		@Override
		public void live(Chat chat)
		{
			if (panel != null)
			{
				panel.refreshLive(chat);
			}
		}

		@Override
		public void ended(Chat chat, Chat.Message m)
		{
			ping(chat, m);
		}
	}

	// ------------------------------------------------------------------
	// Saved chats ("Remember chats")
	// ------------------------------------------------------------------

	/** What {@link ChatSaver} needs from the plugin. On the EDT, except {@link #rememberChats}. */
	private final class Saving implements ChatSaver.Host
	{
		@Override
		public boolean rememberChats()
		{
			return config.rememberChats();
		}

		@Override
		public List<Chat> chats()
		{
			return chats;
		}

		@Override
		public Chat current()
		{
			return current;
		}

		@Override
		public void restored(Chat shown)
		{
			current = shown;
			chatCounter = Math.max(chatCounter, chats.size());
			if (panel != null)
			{
				panel.refreshAll();
			}
		}

		@Override
		public void note(String text)
		{
			showError(text);
		}
	}

	/** RuneLite doesn't turn plugins off when it closes, so this is the last chance to save. */
	@Subscribe
	public void onClientShutdown(ClientShutdown e)
	{
		// RuneLite posts this from the Swing thread, where the chats live; anywhere else, save without waiting.
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(saver::close);
			return;
		}
		ScheduledFuture<?> saved = saver.close();
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
		for (String message : GameChatEcho.echoMessages(label, chatName, text, config.echoMaxChars()))
		{
			queueChat(message);
		}
	}

	private void gameMessage(String text)
	{
		queueChat(GameChatEcho.highlighted(text));
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
