package com.aichat;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/**
 * Sends a chat's messages and puts the answers in it: the summary a long chat needs first, the character details, the
 * tools, the reply as it streams in, Stop and Retry. Everything here runs on the Swing EDT, where the chats live. The
 * providers answer on their own threads; their answers come back through {@code edt} and count only while their
 * request is still the chat's current one (not stopped, not replaced, the chat not deleted).
 */
final class RequestRunner
{
	/** The reply is redrawn at most this often while it streams in (about 15 times a second): each redraw is a layout. */
	static final long LIVE_GAP_MILLIS = 66;
	/** How the notes that say a request was stopped start: one of them after a question means it can be retried. */
	static final String STOPPED = "Stopped";
	static final String COULDNT_SEND = "AI Chat couldn't send this. Check the API key and URL in the settings.";
	/** Under a reply from a model that can't use tools, whatever the settings say. */
	static final String NO_LOOKUPS = "No look-ups: this model can't use tools, so it answered from memory";

	/** What the runner needs from the plugin. On the EDT unless it says otherwise. */
	interface Host
	{
		/** "Enable AI requests", right now. */
		boolean aiRequests();

		/**
		 * Reads the character details to send with a message and hands them to {@code done}, on any thread: null when
		 * there are none (not logged in, or they couldn't be read).
		 */
		void readCharacter(Consumer<String> done);

		/**
		 * The tools for one request, with the settings in {@code setup}. {@code activity} hears one line per call, and
		 * {@code started} when a call starts, both on the tools' own threads.
		 */
		ToolBox tools(Setup setup, Consumer<String> activity, Runnable started);

		/** Whether the chat is still one of the plugin's (not deleted). */
		boolean has(Chat chat);

		/** The chat's messages changed: show them and save. */
		void changed(Chat chat);

		/** Only what's shown of the request in flight changed (its text, look-ups or status): nothing to save. */
		void live(Chat chat);

		/** A request ended with a reply or an error: tell the player (a notification, game chat). */
		void ended(Chat chat, Chat.Message m);
	}

	/** What a message is sent with: the provider, and the settings of when the player sent it. */
	static final class Setup
	{
		final ChatApi api;
		final String model;
		final String system;
		final boolean shareCharacter;
		final boolean shareItems;
		final boolean wikiLookups;

		Setup(ChatApi api, String model, String system, boolean shareCharacter, boolean shareItems, boolean wikiLookups)
		{
			this.api = api;
			this.model = model;
			this.system = system;
			this.shareCharacter = shareCharacter;
			this.shareItems = shareItems;
			this.wikiLookups = wikiLookups;
		}
	}

	/** A message on its way, with what it's sent with. */
	private static final class Outgoing
	{
		final Chat chat;
		final Chat.Message message;
		final Setup setup;
		/** It was too long for the model once, and goes again after a summary: if that's not enough, it's an error. */
		final boolean shortened;

		Outgoing(Chat chat, Chat.Message message, Setup setup, boolean shortened)
		{
			this.chat = chat;
			this.message = message;
			this.setup = setup;
			this.shortened = shortened;
		}
	}

	private final Host host;
	/** Runs a task on the EDT later, never right away: SwingUtilities::invokeLater. */
	private final Executor edt;
	/** Only for the short waits between redraws of a streaming reply. */
	private final ScheduledExecutorService scheduler;

	RequestRunner(Host host, Executor edt, ScheduledExecutorService scheduler)
	{
		this.host = host;
		this.edt = edt;
		this.scheduler = scheduler;
	}

	// ------------------------------------------------------------------
	// Sending
	// ------------------------------------------------------------------

	/** Sends {@code text} as the chat's next message. Only when the chat isn't waiting for a reply. */
	void send(Chat chat, String text, Setup setup)
	{
		Chat.Message message = new Chat.Message(Chat.Role.USER, text);
		chat.messages.add(message);
		if (chat.defaultName)
		{
			chat.name = ChatApi.shorten(text.replaceAll("\\s+", " "), 40);
			chat.defaultName = false;
		}
		start(new Outgoing(chat, message, setup, false));
	}

	/**
	 * The question Retry would send again, or null: the player's last message, when it went unanswered and nothing
	 * but an error, a "Stopped" note, other notes or what was shown of a reply that didn't finish came after it (or
	 * RuneLite closed while it waited).
	 */
	static Chat.Message retryable(Chat chat)
	{
		if (chat.isRunning() || chat.messages.isEmpty())
		{
			return null;
		}
		Chat.Message last = chat.messages.get(chat.messages.size() - 1);
		boolean ended = last.role == Chat.Role.ERROR || last.role == Chat.Role.USER
			|| last.role == Chat.Role.NOTE && last.text.startsWith(STOPPED);
		if (!ended)
		{
			return null;
		}
		for (int i = chat.messages.size() - 1; i >= 0; i--)
		{
			Chat.Message m = chat.messages.get(i);
			if (m.role == Chat.Role.USER)
			{
				return m.unanswered ? m : null;
			}
			if (m.role == Chat.Role.ASSISTANT && !m.unfinished)
			{
				return null;
			}
		}
		return null;
	}

	/**
	 * Sends the chat's unanswered question again: the same message, with the settings of now, and a new request
	 * (summary first, if the chat needs one). False if there's nothing to retry.
	 */
	boolean retry(Chat chat, Setup setup)
	{
		Chat.Message question = retryable(chat);
		if (question == null)
		{
			return false;
		}
		question.unanswered = false;
		start(new Outgoing(chat, question, setup, false));
		return true;
	}

	private void start(Outgoing out)
	{
		Chat chat = out.chat;
		// Busy from now on, so nothing else is sent in this chat while the request is put together.
		chat.pending = new ChatApi.Pending();
		chat.runStartedAt = System.currentTimeMillis();
		chat.resetLive();
		chat.answering = out.setup.api.displayName();
		List<Chat.Message> old = ConversationBuilder.planSummary(chat);
		if (old.isEmpty())
		{
			readCharacter(out);
		}
		else
		{
			summarise(out, old);
		}
		host.changed(chat);
	}

	/**
	 * The chat has grown long: its oldest messages are summarised with one extra request, and the message goes out
	 * after that, with the summary instead of them or, if there's none, with the whole chat.
	 */
	private void summarise(Outgoing out, List<Chat.Message> old)
	{
		Chat chat = out.chat;
		// Set before any answer can be handled: answers are handled on this (the EDT) thread, after this method.
		ChatApi.Pending[] request = new ChatApi.Pending[1];
		try
		{
			request[0] = out.setup.api.send(ConversationBuilder.summaryConversation(chat, old, out.setup.model), new ChatApi.Listener()
			{
				@Override
				public void onRetrying(String message, int seconds)
				{
					edt.execute(() -> retrying(chat, request[0], message, seconds));
				}

				@Override
				public void onReply(ChatApi.Reply reply)
				{
					edt.execute(() -> summarised(out, old, request[0], reply, null));
				}

				@Override
				public void onError(ChatApi.Failure failure)
				{
					edt.execute(() -> summarised(out, old, request[0], null, failure));
				}
			});
		}
		catch (RuntimeException e)
		{
			couldntSend(out);
			return;
		}
		chat.pending = request[0];
		chat.requestSent = true;
		// Stop skips the summary, not the message: this time the whole chat is sent instead.
		chat.skipSummary = () ->
		{
			request[0].cancel();
			ChatApi.Failure skipped = new ChatApi.Failure("you pressed Stop");
			// It was on its way: some of it may have been answered, and billed.
			skipped.usage.incomplete = true;
			afterSummary(out, old, null, skipped);
		};
	}

	/** The answer to a summary request, if it still counts. */
	private void summarised(Outgoing out, List<Chat.Message> old, ChatApi.Pending request, ChatApi.Reply reply,
		ChatApi.Failure failure)
	{
		if (current(out.chat, request))
		{
			afterSummary(out, old, reply, failure);
		}
	}

	/**
	 * Sends the summary instead of the oldest messages from now on, or says why there's none this time (then nothing is
	 * left out, and the next message tries again). Either note goes just before the question, where the player sees it.
	 * Then the question goes out.
	 */
	private void afterSummary(Outgoing out, List<Chat.Message> old, ChatApi.Reply reply, ChatApi.Failure failure)
	{
		Chat chat = out.chat;
		chat.skipSummary = null;
		// Still busy: the question itself is next, and its time starts now.
		chat.pending = new ChatApi.Pending();
		chat.runStartedAt = System.currentTimeMillis();
		chat.resetLive();
		// A summary cut off by its length limit (a thinking model can spend most of it thinking) would lose the rest of
		// what it was meant to keep, for good: none rather than that.
		boolean whole = reply != null && reply.text != null && !reply.text.trim().isEmpty() && !reply.cutShort;
		if (whole)
		{
			Chat.Message note = ConversationBuilder.applySummary(chat, old, reply.text, out.message);
			counted(note, reply.usage, reply.model, out.setup.model);
		}
		else
		{
			String reason = failure != null ? failure.message
				: reply != null && reply.cutShort ? "the summary was cut short" : "the summary came back empty";
			Chat.Message note = new Chat.Message(Chat.Role.NOTE, ConversationBuilder.summaryFailed(reason));
			// What the summary request used is billed even though there's no summary to show for it.
			if (failure != null)
			{
				counted(note, failure.usage, failure.model, out.setup.model);
			}
			else if (reply != null)
			{
				counted(note, reply.usage, reply.model, out.setup.model);
			}
			ConversationBuilder.addBefore(chat, out.message, note);
		}
		host.changed(chat);
		readCharacter(out);
	}

	/** Reads the character details first if they go with the message, then sends it. */
	private void readCharacter(Outgoing out)
	{
		if (!out.setup.shareCharacter)
		{
			dispatch(out, null);
			return;
		}
		Chat chat = out.chat;
		ChatApi.Pending placeholder = chat.pending;
		host.readCharacter(context -> edt.execute(() ->
		{
			// Not if it was stopped (or the plugin turned off) in the meantime.
			if (current(chat, placeholder))
			{
				dispatch(out, context);
			}
		}));
	}

	/** Sends the chat so far. {@code context}: the character details read for the message, if any. */
	private void dispatch(Outgoing out, String context)
	{
		Chat chat = out.chat;
		if (!host.aiRequests())
		{
			stop(chat, "Stopped: AI requests are turned off.");
			return;
		}
		ChatApi.Conversation conversation = ConversationBuilder.conversation(chat, out.message, out.setup.model,
			out.setup.system, out.setup.shareCharacter, context);
		if (out.message.context != null)
		{
			// The character details go with it: the transcript says so under the message, and the chat is saved with them.
			host.changed(chat);
		}
		// This request's look-ups. They go with whatever ends it, so a look-up that finishes after a Stop is still shown.
		List<String> activity = chat.liveActivity;
		// Set before any answer can be handled: answers are handled on this (the EDT) thread, after this method.
		ChatApi.Pending[] request = new ChatApi.Pending[1];
		Throttle<String> partial = new Throttle<>(edt, scheduler, LIVE_GAP_MILLIS, text -> writing(chat, request[0], text));
		ToolBox tools = host.tools(out.setup,
			line -> edt.execute(() -> lookedUp(chat, request[0], activity, line)),
			() ->
			{
				// The text so far, which a look-up that's quicker than the redraws mustn't be mistaken for new.
				String before = partial.latest();
				edt.execute(() -> lookingUp(chat, request[0], before));
			});
		tools.addTo(conversation);
		try
		{
			request[0] = out.setup.api.send(conversation, new ChatApi.Listener()
			{
				@Override
				public void onPartial(String textSoFar)
				{
					partial.offer(textSoFar);
				}

				@Override
				public void onRetrying(String message, int seconds)
				{
					edt.execute(() -> retrying(chat, request[0], message, seconds));
				}

				@Override
				public void onReply(ChatApi.Reply reply)
				{
					edt.execute(() -> finished(out, request[0], reply, null, null));
				}

				@Override
				public void onError(ChatApi.Failure failure)
				{
					// The newest text, which may be a moment ahead of what's drawn: the reply as far as it got.
					String shown = partial.latest();
					edt.execute(() -> finished(out, request[0], null, failure, shown));
				}
			});
		}
		catch (RuntimeException e)
		{
			couldntSend(out);
			return;
		}
		chat.pending = request[0];
		chat.requestSent = true;
	}

	/**
	 * Building the request failed (OkHttp rejects some header values). The exception's message can contain the API
	 * key: it isn't passed on or logged.
	 */
	private void couldntSend(Outgoing out)
	{
		Chat chat = out.chat;
		chat.pending = null;
		chat.skipSummary = null;
		chat.resetLive();
		out.message.unanswered = true;
		chat.messages.add(new Chat.Message(Chat.Role.ERROR, COULDNT_SEND));
		host.changed(chat);
	}

	// ------------------------------------------------------------------
	// The request in flight
	// ------------------------------------------------------------------

	/** Whether an answer to {@code request} still counts: it's the chat's current request, and the chat is still there. */
	private boolean current(Chat chat, ChatApi.Pending request)
	{
		return request != null && chat.pending == request && !request.isCancelled() && host.has(chat);
	}

	/** The reply so far, at most every {@link #LIVE_GAP_MILLIS}. */
	private void writing(Chat chat, ChatApi.Pending request, String text)
	{
		if (!current(chat, request) || text == null || text.equals(chat.liveText))
		{
			return;
		}
		chat.liveText = text;
		// More words: the look-ups are over. (Not the text from before them, arriving late.)
		if (!text.equals(chat.lookupAfter))
		{
			chat.lookingUp = false;
			chat.lookupLine = null;
		}
		host.live(chat);
	}

	/** A tool call started. {@code before}: the reply's text at that moment. */
	private void lookingUp(Chat chat, ChatApi.Pending request, String before)
	{
		if (!current(chat, request))
		{
			return;
		}
		chat.lookingUp = true;
		chat.lookupLine = null;
		chat.lookupAfter = before;
		host.live(chat);
	}

	/** A tool call finished: {@code line} says what it looked up or shared. */
	private void lookedUp(Chat chat, ChatApi.Pending request, List<String> activity, String line)
	{
		// Kept whatever happened to the request since: it was looked up, and the player sees what was. But once it has
		// stopped, the result goes nowhere: game data read too late wasn't shared after all.
		boolean live = current(chat, request);
		activity.add(live ? line : GameDataTools.unshared(line));
		if (live)
		{
			if (chat.lookingUp)
			{
				chat.lookupLine = line;
			}
			host.live(chat);
		}
		else if (host.has(chat))
		{
			// After the request ended: the line went with the message that ended it.
			host.changed(chat);
		}
	}

	/** The provider is busy; the request goes again in {@code seconds}. */
	private void retrying(Chat chat, ChatApi.Pending request, String why, int seconds)
	{
		if (current(chat, request))
		{
			chat.retryWhy = why;
			chat.retryAt = System.currentTimeMillis() + seconds * 1000L;
			host.live(chat);
		}
	}

	/**
	 * The request's answer: a reply, or an error ({@code failure}). {@code shown}: the reply's text so far when it
	 * failed, which stays in the transcript unless the provider took it back.
	 */
	private void finished(Outgoing out, ChatApi.Pending request, ChatApi.Reply reply, ChatApi.Failure failure, String shown)
	{
		Chat chat = out.chat;
		// Only the answer to the request still in flight counts: not one that was stopped, or a chat that's gone.
		if (!current(chat, request))
		{
			return;
		}
		String before = shown != null ? shown : chat.liveText;
		if (failure != null && failure.tooLong && !out.shortened && shorten(out, failure, before))
		{
			return;
		}
		chat.pending = null;
		List<String> activity = chat.liveActivity;
		chat.resetLive();
		Chat.Message m;
		if (reply != null)
		{
			if (reply.historyAsText)
			{
				// This reply was built on plain-text history; keep sending the earlier replies that way.
				ConversationBuilder.forgetRaw(chat);
			}
			m = new Chat.Message(Chat.Role.ASSISTANT, reply.text + (reply.cutShort ? "\n\n(The reply was cut short.)" : ""));
			m.who = out.setup.api.displayName();
			ConversationBuilder.recordReply(m, out.message, reply);
			counted(m, reply.usage, reply.model, out.setup.model);
			if (reply.toolsUnavailable)
			{
				activity.add(NO_LOOKUPS);
			}
		}
		else
		{
			out.message.unanswered = true;
			if (!failure.withdrawn)
			{
				keepUnfinished(chat, out.setup.api.displayName(), before);
			}
			m = new Chat.Message(Chat.Role.ERROR, failure.message);
			// Look-up rounds before it failed, or a reply cut off part way, are billed all the same.
			counted(m, failure.usage, failure.model, out.setup.model);
		}
		m.activity = activity;
		chat.messages.add(m);
		host.ended(chat, m);
		host.changed(chat);
	}

	/**
	 * The chat was too long for the model: once per question, everything before it is summarised, however few
	 * messages that is, and it goes again. A note says so, and keeps what this attempt looked up and used. False when
	 * there's nothing before it to summarise.
	 */
	private boolean shorten(Outgoing out, ChatApi.Failure failure, String shown)
	{
		Chat chat = out.chat;
		List<Chat.Message> old = ConversationBuilder.planSummary(chat, true);
		if (old.isEmpty())
		{
			return false;
		}
		List<String> activity = chat.liveActivity;
		keepUnfinished(chat, out.setup.api.displayName(), shown);
		Chat.Message note = new Chat.Message(Chat.Role.NOTE, "This chat was too long for " + out.setup.model
			+ ", so the earlier messages are summarised first and the question is sent again.");
		note.activity = activity;
		counted(note, failure.usage, failure.model, out.setup.model);
		chat.messages.add(note);
		// Still busy: the summary is next, then the question again.
		chat.pending = new ChatApi.Pending();
		chat.runStartedAt = System.currentTimeMillis();
		chat.resetLive();
		summarise(new Outgoing(chat, out.message, out.setup, true), old);
		host.changed(chat);
		return true;
	}

	/**
	 * What was shown of a reply that won't finish stays where the player was reading it, marked unfinished (the note or
	 * error after it says why), rather than vanishing. It isn't sent: Retry asks the question again from the start.
	 */
	private static void keepUnfinished(Chat chat, String who, String text)
	{
		if (text == null || text.trim().isEmpty())
		{
			return;
		}
		Chat.Message m = new Chat.Message(Chat.Role.ASSISTANT, text);
		m.who = who;
		m.unfinished = true;
		chat.messages.add(m);
	}

	/**
	 * Keeps on {@code m} a copy of the tokens a request used, and the model that answered ({@code model}, or the one
	 * asked for); nothing when the provider gave no counts and nothing says any were used.
	 */
	private static void counted(Chat.Message m, ChatApi.Usage usage, String model, String asked)
	{
		if (usage.total() == 0 && !usage.incomplete)
		{
			return;
		}
		m.usage = new ChatApi.Usage();
		m.usage.add(usage);
		m.model = model != null ? model : asked;
	}

	// ------------------------------------------------------------------
	// Stopping
	// ------------------------------------------------------------------

	/** The player pressed Stop: while summarising, that skips the summary; otherwise the request stops. */
	void stop(Chat chat)
	{
		if (chat.isSummarizing())
		{
			chat.skipSummary.run();
		}
		else
		{
			stop(chat, STOPPED + ".");
		}
	}

	/** Stops the chat's request, if any, and says so with {@code note}; the question can be retried. */
	void stop(Chat chat, String note)
	{
		if (chat.pending == null)
		{
			return;
		}
		List<String> activity = chat.liveActivity;
		// What the player was reading when they pressed Stop, often because they'd read enough.
		String shown = chat.liveText;
		String who = chat.answering;
		boolean sent = chat.requestSent;
		cancel(chat);
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
		keepUnfinished(chat, who, shown);
		Chat.Message stopped = new Chat.Message(Chat.Role.NOTE, note);
		stopped.activity = activity;
		if (sent)
		{
			// What it used before it stopped never got counted: the chat's total can only be a minimum now.
			stopped.usage = new ChatApi.Usage();
			stopped.usage.incomplete = true;
		}
		chat.messages.add(stopped);
		host.changed(chat);
	}

	/** Cancels whatever the chat is waiting for, if anything; its answer won't be heard. */
	static void cancel(Chat chat)
	{
		if (chat.pending != null)
		{
			chat.pending.cancel();
			chat.pending = null;
		}
		chat.skipSummary = null;
		chat.resetLive();
	}
}
