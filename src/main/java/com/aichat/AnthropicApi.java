package com.aichat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Claude, through Anthropic's Messages API with the player's own API key. */
@Slf4j
class AnthropicApi implements ChatApi
{
	static final HttpUrl URL = HttpUrl.get("https://api.anthropic.com/v1/messages");
	private static final String VERSION = "2023-06-01";
	/** Room for a full answer; replies are billed by what's used, and the system prompt asks for short ones. */
	private static final int MAX_TOKENS = 16000;
	/**
	 * Server-side fallback: when a model's safety filter declines a request, Anthropic answers it with another Claude
	 * model instead of refusing outright. Asked for with every model; a model (or account) that doesn't take it says
	 * so, and is then asked without it for as long as the plugin runs.
	 */
	private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
	private static final String FALLBACKS = "fallbacks";
	/** The model list comes in pages of up to 1000; more than a few pages would be something else going wrong. */
	private static final int MAX_MODEL_PAGES = 5;
	private static final String BUSY = "Anthropic is busy";
	private static final String RATE_LIMITED = "Anthropic's rate limit was hit";
	private static final String TOO_LONG = "Claude took too long to answer. Try again, maybe with a shorter question or a faster model.";
	private static final String COULDNT_SEND = "AI Chat couldn't send the next part of this. Check the Claude API key in the settings.";

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl url;
	private final String apiKey;
	/** The plugin's scheduler, for waiting before a retry. */
	private final ScheduledExecutorService scheduler;
	/**
	 * What models have refused, by {@link #refusedKey}: here, server-side fallback. Shared by the plugin's requests (and
	 * with the OpenAI-compatible ones' refused settings) for as long as it runs.
	 */
	private final Map<String, Set<String>> refused;

	AnthropicApi(OkHttpClient http, Gson gson, HttpUrl url, String apiKey, ScheduledExecutorService scheduler,
		Map<String, Set<String>> refused)
	{
		this.http = http;
		this.gson = gson;
		this.url = url;
		this.apiKey = apiKey;
		this.scheduler = scheduler;
		this.refused = refused;
	}

	@Override
	public String displayName()
	{
		return "Claude";
	}

	@Override
	public Pending send(Conversation conversation, Listener listener)
	{
		Run run = new Run(conversation, listener);
		run.request();
		return run.pending;
	}

	static String refusedKey(String model)
	{
		return "anthropic " + model;
	}

	/** The request body for the first round, for tests. */
	JsonObject body(Conversation request, boolean replay, boolean fallback)
	{
		return body(request, replay, fallback, new JsonArray());
	}

	/**
	 * The request body. {@code replay}: send earlier replies back as they came, reasoning and look-ups included, where
	 * they were made under the same model, instructions and tools. {@code produced}: this reply's messages so far, when
	 * it has stopped to look things up.
	 */
	JsonObject body(Conversation request, boolean replay, boolean fallback, JsonArray produced)
	{
		String key = ChatApi.promptKey(request);
		JsonObject body = new JsonObject();
		body.addProperty("model", request.model);
		body.addProperty("max_tokens", request.maxTokens > 0 ? Math.min(MAX_TOKENS, request.maxTokens) : MAX_TOKENS);
		body.addProperty("system", request.system);
		if (!request.tools.isEmpty())
		{
			JsonArray tools = new JsonArray();
			for (ToolSpec t : request.tools)
			{
				JsonObject tool = new JsonObject();
				tool.addProperty("name", t.name);
				tool.addProperty("description", t.description);
				tool.add("input_schema", t.inputSchema);
				tools.add(tool);
			}
			body.add("tools", tools);
		}
		JsonArray messages = new JsonArray();
		for (Turn t : request.turns)
		{
			if (replay && replayable(t, key))
			{
				for (JsonElement m : t.rawMessages)
				{
					messages.add(m);
				}
			}
			else
			{
				JsonObject m = new JsonObject();
				m.addProperty("role", t.user ? "user" : "assistant");
				m.addProperty("content", t.text);
				messages.add(m);
			}
		}
		for (JsonElement m : produced)
		{
			messages.add(m);
		}
		body.add("messages", messages);
		body.addProperty("stream", true);
		// Prompt caching: everything up to the newest message is kept for a few minutes, so the next message in the chat
		// (or the next round of look-ups) reads it at a tenth of the price. The instructions and tools stay the same for a
		// whole chat, so the start of every request matches the one before.
		JsonObject cache = new JsonObject();
		cache.addProperty("type", "ephemeral");
		body.add("cache_control", cache);
		if (fallback)
		{
			body.addProperty(FALLBACKS, "default");
		}
		return body;
	}

	/** An earlier reply that can go back exactly as it came: made under the same prompt as this request. */
	private static boolean replayable(Turn t, String key)
	{
		return !t.user && t.rawMessages != null && t.rawMessages.size() > 0 && key.equals(t.rawKey);
	}

	/** One reply: its rounds of look-ups, its retries, and the one answer the listener gets. */
	private final class Run
	{
		private final Conversation conversation;
		private final Listener listener;
		private final Pending pending = new Pending();
		private final String key;
		/** This reply's messages so far: Claude's, and the tool results sent back. */
		private JsonArray produced = new JsonArray();
		/** The text of the rounds before this one. */
		private final List<String> texts = new ArrayList<>();
		private final Usage usage = new Usage();
		private final AtomicBoolean over = new AtomicBoolean();
		private String model;
		private boolean replay = true;
		private boolean fallback;
		/** Fallback was refused and left out; remembered once a request works without it. */
		private boolean fallbackDropped;
		/** The last request sent reasoning from earlier: Claude may refuse it. */
		private boolean replayed;
		private int toolRounds;
		private int retries;

		Run(Conversation conversation, Listener listener)
		{
			this.conversation = conversation;
			this.listener = listener;
			key = ChatApi.promptKey(conversation);
			model = conversation.model;
			fallback = !refused.getOrDefault(refusedKey(conversation.model), Set.of()).contains(FALLBACKS);
		}

		/** Sends this round's request. Throws if OkHttp won't take it (a header value it rejects). */
		void request()
		{
			JsonObject body = body(conversation, replay, fallback, produced);
			replayed = replay && (produced.size() > 0 || anyReplayable());
			Request.Builder request = new Request.Builder()
				.url(url)
				.header("x-api-key", apiKey)
				.header("anthropic-version", VERSION)
				.post(RequestBody.create(JSON, gson.toJson(body)));
			if (fallback)
			{
				request.header("anthropic-beta", FALLBACK_BETA);
			}
			Call call = http.newCall(request.build());
			pending.set(call);
			call.enqueue(new Callback()
			{
				@Override
				public void onFailure(Call c, IOException e)
				{
					failed(e);
				}

				@Override
				public void onResponse(Call c, Response response)
				{
					try (ResponseBody body = response.body())
					{
						answered(response, body);
					}
				}
			});
		}

		private boolean anyReplayable()
		{
			for (Turn t : conversation.turns)
			{
				if (replayable(t, key))
				{
					return true;
				}
			}
			return false;
		}

		/** A later request (a retry or the next round), from a callback: a problem goes to the listener, not the thread. */
		private void again()
		{
			if (pending.isCancelled())
			{
				return;
			}
			try
			{
				request();
			}
			catch (RuntimeException e)
			{
				// The exception's message can contain the API key: it isn't passed on or logged.
				fail(COULDNT_SEND);
			}
		}

		private void failed(IOException e)
		{
			if (pending.isCancelled())
			{
				return;
			}
			if (ChatApi.tookTooLong(e))
			{
				// It got the request, so it may have been answering it.
				usage.incomplete = true;
				fail(TOO_LONG);
				return;
			}
			if (!retry("Couldn't reach Anthropic", ChatApi.retryDelay(null, null, retries + 1)))
			{
				fail("Couldn't reach Anthropic: " + e.getMessage());
			}
		}

		private void answered(Response response, ResponseBody body)
		{
			if (pending.isCancelled())
			{
				return;
			}
			if (!response.isSuccessful() || body == null)
			{
				String text;
				try
				{
					text = body == null ? "" : body.string();
				}
				catch (IOException e)
				{
					text = "";
				}
				if (!pending.isCancelled())
				{
					errorAnswer(response, text);
				}
				return;
			}
			if (fallbackDropped)
			{
				// Only a request that worked without it shows fallback was the problem: remember that.
				refused.computeIfAbsent(refusedKey(conversation.model), k -> ConcurrentHashMap.newKeySet()).add(FALLBACKS);
				fallbackDropped = false;
			}
			Message m = new Message();
			try
			{
				if (Sse.isEventStream(response))
				{
					Sse.read(body.source(), (event, data) -> onEvent(m, event, data));
				}
				else
				{
					// Not streamed after all: one answer, read whole.
					String text;
					try
					{
						text = body.string();
					}
					catch (IOException e)
					{
						if (!pending.isCancelled())
						{
							brokeOff(m);
							fail("Couldn't read Anthropic's answer: " + e.getMessage());
						}
						return;
					}
					JsonObject o = gson.fromJson(text, JsonObject.class);
					if (o == null)
					{
						fail("Anthropic sent an empty answer.");
						return;
					}
					m.whole(o);
				}
			}
			catch (IOException e)
			{
				// Stop closes the stream: not a problem to report.
				if (!pending.isCancelled())
				{
					brokeOff(m);
					fail(ChatApi.tookTooLong(e) ? TOO_LONG : CUT_OFF);
				}
				return;
			}
			catch (JsonParseException | IllegalStateException | ClassCastException | UnsupportedOperationException | NumberFormatException e)
			{
				brokeOff(m);
				fail("Anthropic sent an answer AI Chat couldn't read.");
				return;
			}
			if (!pending.isCancelled())
			{
				completed(m);
			}
		}

		/** An error answer: some are worth asking again for, differently or after a wait. */
		private void errorAnswer(Response response, String text)
		{
			int code = response.code();
			String message = ChatApi.errorMessage(gson, text);
			// Claude checks that the reasoning it's given back belongs to this exact conversation; after a settings
			// change it may not. Once, carry on without it: earlier replies as text, and none of this reply's reasoning
			// so far, which was built on them. Checked first: that error also names the anthropic-beta header (for a
			// setting this plugin doesn't use), and isn't about fallback.
			if (replayed && code == 400 && message != null && message.contains("thinking"))
			{
				log.debug("asking again without earlier reasoning: {}", message);
				replay = false;
				produced = withoutThinking(produced);
				again();
				return;
			}
			// The fallback beta was retired, isn't enabled for this account, or this model doesn't take it: do without.
			if (fallback && code == 400 && message != null && (message.contains(FALLBACK_BETA) || message.contains(FALLBACKS)))
			{
				log.debug("asking again without server-side fallback: {}", message);
				fallback = false;
				fallbackDropped = true;
				again();
				return;
			}
			if (ChatApi.tooLongForModel(code, text))
			{
				fail(ChatApi.tooLong(conversation.model, ""));
				return;
			}
			if (ChatApi.retryableStatus(code, text) && retries < MAX_RETRIES)
			{
				long delay = ChatApi.retryDelay(response.header("retry-after-ms"), response.header("retry-after"), retries + 1);
				String why = code == 429 ? RATE_LIMITED : BUSY;
				if (delay > MAX_RETRY_WAIT_MS)
				{
					fail(why + "; it asked to wait " + ChatApi.waitText(delay) + ". Try again then.");
					return;
				}
				retry(why, delay);
				return;
			}
			fail(explain(code, message, conversation.model));
		}

		/** Sends the request again after {@code delay}, if it may be tried again. */
		private boolean retry(String why, long delay)
		{
			if (retries >= MAX_RETRIES)
			{
				return false;
			}
			retries++;
			log.debug("{}; trying again in {}ms", why, delay);
			if (!over.get() && !pending.isCancelled())
			{
				listener.onRetrying(why, ChatApi.seconds(delay));
			}
			if (!ChatApi.later(scheduler, pending, delay, this::again))
			{
				fail(why + ". Try again shortly.");
			}
			return true;
		}

		/** One streamed event. False once the response is over. */
		private boolean onEvent(Message m, String event, String data)
		{
			JsonObject o = gson.fromJson(data, JsonObject.class);
			String type = o == null ? null : string(o, "type");
			if (type == null)
			{
				type = event;
			}
			switch (type)
			{
				case "message_start":
				{
					JsonObject message = object(o, "message");
					if (message != null)
					{
						String started = string(message, "model");
						m.model = started != null ? started : m.model;
						readUsage(object(message, "usage"), m.usage);
					}
					break;
				}
				case "content_block_start":
					m.start(index(o), object(o, "content_block"));
					break;
				case "content_block_delta":
					if (m.delta(index(o), object(o, "delta")))
					{
						partial(m);
					}
					break;
				case "message_delta":
				{
					JsonObject delta = object(o, "delta");
					String stop = delta == null ? null : string(delta, "stop_reason");
					m.stop = stop != null ? stop : m.stop;
					readUsage(object(o, "usage"), m.usage);
					break;
				}
				case "message_stop":
					m.complete = true;
					return false;
				case "error":
				{
					JsonObject error = object(o, "error");
					m.errorType = error == null || string(error, "type") == null ? "error" : string(error, "type");
					m.errorMessage = error == null ? null : string(error, "message");
					return false;
				}
				default:
					// "ping", "content_block_stop", and anything new: nothing to do.
			}
			return true;
		}

		/** The reply so far, for the panel: earlier rounds and this one. */
		private void partial(Message m)
		{
			String now = m.text.toString();
			if (now.trim().isEmpty() || over.get() || pending.isCancelled())
			{
				return;
			}
			m.shown = true;
			listener.onPartial(ChatApi.joinRounds(texts, now));
		}

		/** A whole response: the reply, or the tools it wants run before it carries on. */
		private void completed(Message m)
		{
			if (m.errorType != null)
			{
				streamError(m);
				return;
			}
			if (!m.complete)
			{
				brokeOff(m);
				fail(CUT_OFF);
				return;
			}
			// Counted before anything else can go wrong: this response is billed whatever happens next.
			usage.add(m.usage);
			model = m.model != null ? m.model : model;
			// Checked before anything else: a refusal can cut the reply (or a tool call) off part way. What it wrote
			// before that isn't an answer, so it isn't kept (as Anthropic advises).
			if ("refusal".equals(m.stop))
			{
				Failure declined = new Failure("Claude declined to answer that.");
				declined.withdrawn = true;
				fail(declined);
				return;
			}
			JsonArray content = echo(m.content(gson));
			String text = textOf(content);
			JsonObject assistant = message("assistant", content);
			List<JsonObject> calls = toolUses(content);
			if ("tool_use".equals(m.stop) && !calls.isEmpty())
			{
				if (toolRounds >= MAX_TOOL_ROUNDS)
				{
					fail(TOO_MANY_ROUNDS);
					return;
				}
				toolRounds++;
				produced.add(assistant);
				texts.add(text);
				List<String> names = new ArrayList<>();
				List<JsonObject> inputs = new ArrayList<>();
				for (JsonObject call : calls)
				{
					names.add(string(call, "name") == null ? "" : string(call, "name"));
					JsonObject input = object(call, "input");
					inputs.add(m.badInputs.contains(string(call, "id")) ? null : input == null ? new JsonObject() : input.deepCopy());
				}
				ChatApi.runTools(conversation.toolRunner, names, inputs, results -> toolsDone(calls, results));
				return;
			}
			String all = ChatApi.joinRounds(texts, text);
			if (all.isEmpty())
			{
				fail("Claude sent an empty reply" + (m.stop == null ? "." : " (" + m.stop + ")."));
				return;
			}
			produced.add(assistant);
			Reply r = new Reply();
			r.text = all;
			r.model = model;
			r.cutShort = "max_tokens".equals(m.stop) || "model_context_window_exceeded".equals(m.stop);
			r.historyAsText = !replay;
			// A reply cut off part way can end in a tool call that was never run (Claude wants a result right after
			// one), or in reasoning that was never signed; a last round without text (empty, say) can't go back either.
			// Such a reply goes back as its text, as do the replies built on it later.
			if (!r.cutShort && calls.isEmpty() && !text.isEmpty())
			{
				r.rawMessages = produced;
				r.rawKey = key;
			}
			r.usage.add(usage);
			finish(r);
		}

		/** The tools have answered: send their results back, all in one message, in the order they were asked for. */
		private void toolsDone(List<JsonObject> calls, List<ToolResult> results)
		{
			if (pending.isCancelled() || over.get())
			{
				return;
			}
			JsonArray blocks = new JsonArray();
			for (int i = 0; i < calls.size(); i++)
			{
				ToolResult result = results.get(i);
				JsonObject block = new JsonObject();
				block.addProperty("type", "tool_result");
				block.addProperty("tool_use_id", string(calls.get(i), "id"));
				block.addProperty("content", result.content == null || result.content.isEmpty() ? "(nothing)" : result.content);
				if (result.error)
				{
					block.addProperty("is_error", true);
				}
				blocks.add(block);
			}
			produced.add(message("user", blocks));
			retries = 0;
			try
			{
				// Off the thread that ran the last tool, which may be one that mustn't wait on anything.
				scheduler.execute(this::again);
			}
			catch (RejectedExecutionException e)
			{
				fail(COULDNT_SEND);
			}
		}

		/** An error event in the stream: worth a retry if the provider is busy and nothing has been shown yet. */
		private void streamError(Message m)
		{
			String type = m.errorType;
			boolean busy = "overloaded_error".equals(type) || "rate_limit_error".equals(type) || "api_error".equals(type);
			// Text already shown is never quietly swapped for a different reply.
			if (busy && !m.shown && retry("rate_limit_error".equals(type) ? RATE_LIMITED : BUSY, ChatApi.retryDelay(null, null, retries + 1)))
			{
				return;
			}
			brokeOff(m);
			fail(explain(code(type), m.errorMessage, conversation.model));
		}

		/**
		 * A response that broke off: what it counted so far is billed, and the rest of what it used was never counted.
		 * (One that's asked again was turned away, and isn't counted.)
		 */
		private void brokeOff(Message m)
		{
			usage.add(m.usage);
			usage.incomplete = true;
			model = m.model != null ? m.model : model;
		}

		private void finish(Reply reply)
		{
			if (!pending.isCancelled() && over.compareAndSet(false, true))
			{
				listener.onReply(reply);
			}
		}

		private void fail(String message)
		{
			fail(new Failure(message));
		}

		/** Fails the reply, with the tokens its requests used so far. */
		private void fail(Failure failure)
		{
			if (!pending.isCancelled() && over.compareAndSet(false, true))
			{
				failure.usage.add(usage);
				failure.model = model;
				listener.onError(failure);
			}
		}
	}

	/** One response from Claude, put together from its stream as it arrives, or read whole. */
	private static final class Message
	{
		private final TreeMap<Integer, Block> blocks = new TreeMap<>();
		/** The content of a response read whole. */
		private JsonArray whole;
		/** The text so far, for the panel. */
		final StringBuilder text = new StringBuilder();
		/** Tool calls (by id) whose input couldn't be read. */
		final Set<String> badInputs = new HashSet<>();
		final Usage usage = new Usage();
		String model;
		String stop;
		/** The stream reached its end ("message_stop"). */
		boolean complete;
		/** Text has been shown to the player. */
		boolean shown;
		/** An error event in the stream, and what it said. */
		String errorType;
		String errorMessage;

		void whole(JsonObject o)
		{
			if ("error".equals(string(o, "type")))
			{
				JsonObject error = object(o, "error");
				errorType = error == null || string(error, "type") == null ? "error" : string(error, "type");
				errorMessage = error == null ? null : string(error, "message");
				return;
			}
			JsonElement content = o.get("content");
			whole = content != null && content.isJsonArray() ? content.getAsJsonArray() : new JsonArray();
			model = string(o, "model");
			stop = string(o, "stop_reason");
			readUsage(object(o, "usage"), usage);
			for (JsonElement block : whole)
			{
				usage.incomplete |= "fallback".equals(type(block));
			}
			complete = true;
		}

		void start(int index, JsonObject block)
		{
			if (block == null)
			{
				return;
			}
			Block b = new Block(block.deepCopy());
			blocks.put(index, b);
			String type = string(block, "type");
			if ("text".equals(type) && string(block, "text") != null)
			{
				text.append(string(block, "text"));
			}
			// Another model took over after this one declined: it's the one answering now. What the first one wrote is
			// billed too, at its own prices, and may not be in the counts: the cost can't be told.
			usage.incomplete |= "fallback".equals(type);
			JsonObject to = "fallback".equals(type) ? object(block, "to") : null;
			if (to != null && string(to, "model") != null)
			{
				model = string(to, "model");
			}
		}

		/** Adds a piece to a block. True if it was more text for the panel. */
		boolean delta(int index, JsonObject delta)
		{
			Block b = blocks.get(index);
			String type = delta == null ? null : string(delta, "type");
			if (b == null || type == null)
			{
				return false;
			}
			switch (type)
			{
				case "text_delta":
				{
					String t = string(delta, "text");
					if (t == null)
					{
						return false;
					}
					b.text = b.builder(b.text, "text").append(t);
					text.append(t);
					return true;
				}
				case "thinking_delta":
					b.thinking = b.builder(b.thinking, "thinking").append(nonNull(string(delta, "thinking")));
					return false;
				case "signature_delta":
					b.signature = b.builder(b.signature, "signature").append(nonNull(string(delta, "signature")));
					return false;
				case "input_json_delta":
					// The input arrives as pieces of JSON text, readable only once it's all there.
					if (b.json == null)
					{
						b.json = new StringBuilder();
					}
					b.json.append(nonNull(string(delta, "partial_json")));
					return false;
				case "citations_delta":
				{
					JsonElement citation = delta.get("citation");
					if (citation != null)
					{
						if (b.citations == null)
						{
							JsonElement existing = b.start.get("citations");
							b.citations = existing != null && existing.isJsonArray() ? existing.getAsJsonArray().deepCopy() : new JsonArray();
						}
						b.citations.add(citation);
					}
					return false;
				}
				default:
					return false;
			}
		}

		/** The content blocks, each exactly as Claude made it: what gets sent back later. */
		JsonArray content(Gson gson)
		{
			if (whole != null)
			{
				for (JsonElement e : whole)
				{
					if (e.isJsonObject() && "tool_use".equals(string(e.getAsJsonObject(), "type")))
					{
						JsonElement input = e.getAsJsonObject().get("input");
						if (input != null && !input.isJsonObject())
						{
							badInputs.add(string(e.getAsJsonObject(), "id"));
						}
					}
				}
				return whole;
			}
			JsonArray content = new JsonArray();
			for (Block b : blocks.values())
			{
				content.add(b.built(badInputs, gson));
			}
			return content;
		}

		private static String nonNull(String s)
		{
			return s == null ? "" : s;
		}
	}

	/** A content block as it streams in: what it started as, and what has been added to it since. */
	private static final class Block
	{
		final JsonObject start;
		StringBuilder text;
		StringBuilder thinking;
		StringBuilder signature;
		StringBuilder json;
		JsonArray citations;

		Block(JsonObject start)
		{
			this.start = start;
		}

		/** The builder for a text field, started from what the block began with. */
		StringBuilder builder(StringBuilder current, String field)
		{
			if (current != null)
			{
				return current;
			}
			String initial = string(start, field);
			return new StringBuilder(initial == null ? "" : initial);
		}

		JsonObject built(Set<String> badInputs, Gson gson)
		{
			JsonObject b = start.deepCopy();
			if (text != null)
			{
				b.addProperty("text", text.toString());
			}
			if (thinking != null)
			{
				b.addProperty("thinking", thinking.toString());
			}
			if (signature != null)
			{
				b.addProperty("signature", signature.toString());
			}
			if (citations != null)
			{
				b.add("citations", citations);
			}
			if (json != null)
			{
				b.add("input", input(json.toString(), b, badInputs, gson));
			}
			return b;
		}

		/** A tool call's input; {} if it was empty or couldn't be read (and then it isn't run). */
		private static JsonObject input(String json, JsonObject block, Set<String> badInputs, Gson gson)
		{
			if (json.trim().isEmpty())
			{
				return new JsonObject();
			}
			try
			{
				JsonElement parsed = gson.fromJson(json, JsonElement.class);
				if (parsed != null && parsed.isJsonObject())
				{
					return parsed.getAsJsonObject();
				}
			}
			catch (JsonParseException e)
			{
				// Below.
			}
			badInputs.add(string(block, "id"));
			return new JsonObject();
		}
	}

	/**
	 * What of a reply goes back on later requests. After a fallback (another model answered once the first declined),
	 * only the text from before the switch may go back; the declining model's reasoning and tool calls stay out
	 * (Anthropic's rules for echoing a fallback turn). Fallback markers themselves are harmless and kept.
	 */
	static JsonArray echo(JsonArray content)
	{
		int last = -1;
		for (int i = 0; i < content.size(); i++)
		{
			if ("fallback".equals(type(content.get(i))))
			{
				last = i;
			}
		}
		if (last < 0)
		{
			return content;
		}
		JsonArray echoed = new JsonArray();
		for (int i = 0; i < content.size(); i++)
		{
			String type = type(content.get(i));
			if (i >= last || "text".equals(type) || "fallback".equals(type))
			{
				echoed.add(content.get(i));
			}
		}
		return echoed;
	}

	/** Messages without their reasoning blocks, for when Claude won't take back reasoning made under an earlier prompt. */
	private static JsonArray withoutThinking(JsonArray messages)
	{
		JsonArray out = new JsonArray();
		for (JsonElement m : messages)
		{
			JsonElement content = m.isJsonObject() ? m.getAsJsonObject().get("content") : null;
			if (content == null || !content.isJsonArray())
			{
				out.add(m);
				continue;
			}
			JsonArray kept = new JsonArray();
			for (JsonElement block : content.getAsJsonArray())
			{
				String type = type(block);
				if (!"thinking".equals(type) && !"redacted_thinking".equals(type))
				{
					kept.add(block);
				}
			}
			JsonObject copy = m.getAsJsonObject().deepCopy();
			copy.add("content", kept);
			out.add(copy);
		}
		return out;
	}

	private static String textOf(JsonArray content)
	{
		StringBuilder text = new StringBuilder();
		for (JsonElement block : content)
		{
			if ("text".equals(type(block)))
			{
				String t = string(block.getAsJsonObject(), "text");
				text.append(t == null ? "" : t);
			}
		}
		return text.toString().trim();
	}

	private static List<JsonObject> toolUses(JsonArray content)
	{
		List<JsonObject> calls = new ArrayList<>();
		for (JsonElement block : content)
		{
			if ("tool_use".equals(type(block)))
			{
				calls.add(block.getAsJsonObject());
			}
		}
		return calls;
	}

	private static JsonObject message(String role, JsonArray content)
	{
		JsonObject m = new JsonObject();
		m.addProperty("role", role);
		m.add("content", content);
		return m;
	}

	/**
	 * Token counts, which come in two parts when streamed: the input at the start, the output (and sometimes the input
	 * again) at the end. Later counts replace earlier ones.
	 */
	private static void readUsage(JsonObject u, Usage usage)
	{
		if (u == null)
		{
			return;
		}
		JsonElement iterations = u.get("iterations");
		if (iterations != null && iterations.isJsonArray() && iterations.getAsJsonArray().size() > 0)
		{
			// Each attempt at the message, when another model took over from one that declined: all of them are billed,
			// but the counts outside cover only the last.
			Usage all = new Usage();
			for (JsonElement attempt : iterations.getAsJsonArray())
			{
				if (attempt.isJsonObject())
				{
					Usage one = new Usage();
					readCounts(attempt.getAsJsonObject(), one);
					all.add(one);
				}
			}
			usage.input = all.input;
			usage.cacheRead = all.cacheRead;
			usage.cacheWrite = all.cacheWrite;
			usage.output = all.output;
			// More than one model's tokens, each at its own prices.
			usage.incomplete |= iterations.getAsJsonArray().size() > 1;
			return;
		}
		readCounts(u, usage);
	}

	private static void readCounts(JsonObject u, Usage usage)
	{
		usage.input = count(u, "input_tokens", usage.input);
		usage.cacheRead = count(u, "cache_read_input_tokens", usage.cacheRead);
		usage.cacheWrite = count(u, "cache_creation_input_tokens", usage.cacheWrite);
		usage.output = count(u, "output_tokens", usage.output);
	}

	private static long count(JsonObject o, String key, long otherwise)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : otherwise;
	}

	@Override
	public Pending listModels(ModelsListener listener)
	{
		Pending pending = new Pending();
		listModels(listener, pending, new ArrayList<>(), null, 1);
		return pending;
	}

	private void listModels(ModelsListener listener, Pending pending, List<String> ids, String after, int page)
	{
		HttpUrl.Builder list = url.resolve("models").newBuilder().addQueryParameter("limit", "1000");
		if (after != null)
		{
			list.addQueryParameter("after_id", after);
		}
		Call call = http.newCall(new Request.Builder()
			.url(list.build())
			.header("x-api-key", apiKey)
			.header("anthropic-version", VERSION)
			.get()
			.build());
		pending.set(call);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call c, IOException e)
			{
				if (!pending.isCancelled())
				{
					listener.onError(ChatApi.tookTooLong(e) ? "Anthropic didn't answer in time. Try again in a moment."
						: "Couldn't reach Anthropic: " + e.getMessage());
				}
			}

			@Override
			public void onResponse(Call c, Response response)
			{
				String text;
				try (ResponseBody body = response.body())
				{
					text = body == null ? "" : body.string();
				}
				catch (IOException e)
				{
					if (!pending.isCancelled())
					{
						listener.onError("Couldn't read Anthropic's answer: " + e.getMessage());
					}
					return;
				}
				if (pending.isCancelled())
				{
					return;
				}
				if (!response.isSuccessful())
				{
					listener.onError(explain(response.code(), ChatApi.errorMessage(gson, text), null));
					return;
				}
				try
				{
					JsonObject o = gson.fromJson(text, JsonObject.class);
					JsonElement data = o == null ? null : o.get("data");
					if (data == null || !data.isJsonArray())
					{
						listener.onError("Anthropic sent a model list AI Chat couldn't read.");
						return;
					}
					for (JsonElement m : data.getAsJsonArray())
					{
						String id = m.isJsonObject() ? string(m.getAsJsonObject(), "id") : null;
						if (id != null)
						{
							ids.add(id);
						}
					}
					String last = string(o, "last_id");
					JsonElement more = o.get("has_more");
					if (more != null && more.isJsonPrimitive() && more.getAsBoolean() && last != null && page < MAX_MODEL_PAGES)
					{
						listModels(listener, pending, ids, last, page + 1);
						return;
					}
				}
				catch (JsonParseException | IllegalStateException | ClassCastException | UnsupportedOperationException e)
				{
					listener.onError("Anthropic sent a model list AI Chat couldn't read.");
					return;
				}
				listener.onModels(ids);
			}
		});
	}

	/** The HTTP status that goes with an error type in a stream, so it's explained the same way; 0 if unknown. */
	private static int code(String errorType)
	{
		switch (errorType == null ? "" : errorType)
		{
			case "invalid_request_error":
				return 400;
			case "authentication_error":
				return 401;
			case "billing_error":
				return 402;
			case "permission_error":
				return 403;
			case "not_found_error":
				return 404;
			case "request_too_large":
				return 413;
			case "rate_limit_error":
				return 429;
			case "api_error":
				return 500;
			case "overloaded_error":
				return 529;
			default:
				return 0;
		}
	}

	/** {@code model}: the one asked for, or null when no model was involved (the model list). */
	private static String explain(int code, String message, String model)
	{
		String detail = message == null ? "" : " (" + ChatApi.shorten(message, 200) + ")";
		switch (code)
		{
			case 0:
				return "Anthropic reported a problem" + detail + ".";
			case 401:
				return "Anthropic didn't accept your API key. Check \"Claude API key\" in the AI Chat settings.";
			case 402:
				return "Anthropic says there's a billing problem with your account" + detail + ".";
			case 403:
				return "Your Anthropic API key isn't allowed to do that" + detail + ".";
			case 404:
				return model == null ? "Anthropic answered HTTP 404" + detail + "."
					: "The Claude model \"" + model + "\" isn't available to your key. Check \"Claude model\" in the AI Chat settings" + detail + ".";
			case 413:
				return "This chat has grown too long to send. Start a new chat.";
			case 429:
				return "Anthropic's rate limit was hit. Try again in a minute" + detail + ".";
			case 500:
			case 502:
			case 503:
			case 504:
			case 529:
				return "Anthropic is overloaded or having trouble right now. Try again shortly.";
			default:
				return "Anthropic answered HTTP " + code + detail + ".";
		}
	}

	/** A block's type, or null if it isn't a block. */
	private static String type(JsonElement block)
	{
		return block != null && block.isJsonObject() ? string(block.getAsJsonObject(), "type") : null;
	}

	private static int index(JsonObject o)
	{
		JsonElement e = o == null ? null : o.get("index");
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : 0;
	}

	private static JsonObject object(JsonObject o, String key)
	{
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static String string(JsonObject o, String key)
	{
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}
}
