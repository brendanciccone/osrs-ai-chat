package com.aichat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The Chat Completions API: OpenAI itself (ChatGPT), and the many services that copy it (OpenRouter, Groq, Gemini's
 * compatibility endpoint, Ollama, LM Studio...), each with the player's own URL, key and model.
 */
@Slf4j
class OpenAiApi implements ChatApi
{
	static final HttpUrl OPENAI_URL = HttpUrl.get("https://api.openai.com/v1/");
	/** OpenAI's small, fast model: plenty for chat, and cheap. */
	static final String DEFAULT_MODEL = "gpt-6-luna";
	/** What {@link #listModels} says about a service without a model list, or with one AI Chat can't find. */
	static final String NO_MODEL_LIST = "This service doesn't list its models; check the model name on its website.";
	/**
	 * Reply length limits. Most services count the model's reasoning against them too, so they leave plenty of room;
	 * only what's used is billed, and the system prompt asks for short answers.
	 */
	private static final int OPENAI_MAX_TOKENS = 8000;
	private static final int COMPATIBLE_MAX_TOKENS = 16000;
	/** Some models (Qwen, DeepSeek R1 on some hosts, local models) put their reasoning inside the reply. */
	private static final Pattern THINKING = Pattern.compile("(?s)<think>.*?</think>");
	private static final String COULDNT_SEND = "AI Chat couldn't send the next part of this. Check the API key and URL in the settings.";
	/**
	 * Added to the instructions when the model can't use tools: they say which setting allows a game-data tool that
	 * isn't offered, which would be the wrong advice when none can be.
	 */
	static final String NO_TOOLS = "\n\n(This model can't use tools here, so you have none, whatever the player's "
		+ "settings: answer from what you know.)";

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl base;
	private final String apiKey;
	private final String name;
	/** OpenAI itself, rather than a compatible service with its own quirks. */
	private final boolean openai;
	/** The reasoning effort to ask for, or null for the model's own default. */
	private final String reasoningEffort;
	/** The plugin's scheduler, for waiting before a retry. */
	private final ScheduledExecutorService scheduler;
	/**
	 * Optional settings each service and model has refused, by {@link #refusedKey}: left out of later requests instead
	 * of being refused again every time. Shared by the plugin's requests for as long as it runs.
	 */
	private final Map<String, Set<String>> refused;

	OpenAiApi(OkHttpClient http, Gson gson, HttpUrl base, String apiKey, String name, boolean openai,
		String reasoningEffort, ScheduledExecutorService scheduler, Map<String, Set<String>> refused)
	{
		this.http = http;
		this.gson = gson;
		this.base = base;
		this.apiKey = apiKey;
		this.name = name;
		this.openai = openai;
		this.reasoningEffort = reasoningEffort;
		this.scheduler = scheduler;
		this.refused = refused;
	}

	@Override
	public String displayName()
	{
		return name;
	}

	@Override
	public Pending send(Conversation conversation, Listener listener)
	{
		Run run = new Run(conversation, listener);
		run.request();
		return run.pending;
	}

	/**
	 * The request body. {@code skip}: optional settings to leave out, because they were refused. {@code produced}: this
	 * reply's messages so far (tool calls and their results), when it has stopped to look things up.
	 */
	JsonObject body(Conversation conversation, Set<String> skip, JsonArray produced)
	{
		JsonObject body = new JsonObject();
		body.addProperty("model", conversation.model);
		boolean noTools = !conversation.tools.isEmpty() && skip.contains("tools");
		JsonArray messages = new JsonArray();
		messages.add(message("system", noTools ? conversation.system + NO_TOOLS : conversation.system));
		for (Turn t : conversation.turns)
		{
			messages.add(message(t.user ? "user" : "assistant", t.text));
		}
		for (JsonElement m : produced)
		{
			messages.add(m);
		}
		body.add("messages", messages);
		body.addProperty("stream", true);
		if (!skip.contains("stream_options"))
		{
			// The token counts come in one last piece, only when asked for.
			JsonObject options = new JsonObject();
			options.addProperty("include_usage", true);
			body.add("stream_options", options);
		}
		if (!conversation.tools.isEmpty() && !noTools)
		{
			JsonArray tools = new JsonArray();
			for (ToolSpec t : conversation.tools)
			{
				JsonObject function = new JsonObject();
				function.addProperty("name", t.name);
				function.addProperty("description", t.description);
				function.add("parameters", t.inputSchema);
				JsonObject tool = new JsonObject();
				tool.addProperty("type", "function");
				tool.add("function", function);
				tools.add(tool);
			}
			body.add("tools", tools);
		}
		// "low" asks reasoning models to think briefly. Most think more than that by default, so replies come much
		// faster (a local Qwen: ~11s instead of ~40s). A few think less or not at all by default, and "low" makes them
		// think a little; for those the player can choose the model's default. Services and models that don't take
		// it say so, and we ask again without it.
		if (reasoningEffort != null && !skip.contains("reasoning_effort"))
		{
			body.addProperty("reasoning_effort", reasoningEffort);
		}
		if (openai)
		{
			if (!skip.contains("max_completion_tokens"))
			{
				body.addProperty("max_completion_tokens", limit(OPENAI_MAX_TOKENS, conversation.maxTokens));
			}
			// OpenAI otherwise keeps chat completions for new accounts.
			body.addProperty("store", false);
		}
		else if (!skip.contains("max_tokens"))
		{
			// The name every compatible service knows (Ollama ignores the newer max_completion_tokens).
			body.addProperty("max_tokens", limit(COMPATIBLE_MAX_TOKENS, conversation.maxTokens));
		}
		return body;
	}

	/** The provider's limit, or the conversation's own when that's lower. */
	private static int limit(int ours, int asked)
	{
		return asked > 0 ? Math.min(ours, asked) : ours;
	}

	/** One service and model: what one refuses, another may take. */
	private String refusedKey(String model)
	{
		return base + " " + model;
	}

	/**
	 * Settings a request can do without. Not "store": that one keeps OpenAI from storing the chat, so if it's ever
	 * refused the player sees the error rather than a quiet retry without it. "tools" too: a model that can't call them
	 * (Ollama: "... does not support tools") still answers, just without looking things up.
	 */
	private static final Set<String> OPTIONAL = new LinkedHashSet<>(Arrays.asList(
		"reasoning_effort", "max_completion_tokens", "max_tokens", "stream_options", "tools"));

	/**
	 * The optional setting a 400/422 answer complains about, if it's one we sent: by its "param", by name anywhere in
	 * the answer (Mistral lists invalid fields), or, for reasoning_effort, a complaint about thinking or reasoning
	 * (Ollama's "does not support thinking" for models that don't think, and on 0.11.8-0.17.6 "think value "low" is
	 * not supported for this model").
	 */
	static String rejectedOption(Gson gson, JsonObject body, String errorBody)
	{
		String param = errorParam(gson, errorBody);
		String message = ChatApi.errorMessage(gson, errorBody);
		String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
		for (String key : OPTIONAL)
		{
			if (!body.has(key))
			{
				continue;
			}
			Pattern named = Pattern.compile("(?<![A-Za-z_])" + Pattern.quote(key) + "(?![A-Za-z_])");
			if (key.equals(param) || message != null && named.matcher(message).find() || errorBody != null && named.matcher(errorBody).find()
				|| key.equals("reasoning_effort") && (lower.contains("think") || lower.contains("reasoning")))
			{
				return key;
			}
		}
		return null;
	}

	/** OpenAI names the offending parameter in error.param. */
	private static String errorParam(Gson gson, String errorBody)
	{
		try
		{
			JsonElement parsed = gson.fromJson(errorBody, JsonElement.class);
			JsonElement error = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject().get("error") : null;
			return error != null && error.isJsonObject() ? string(error.getAsJsonObject(), "param") : null;
		}
		catch (JsonParseException | IllegalStateException e)
		{
			return null;
		}
	}

	private static JsonObject message(String role, String content)
	{
		JsonObject m = new JsonObject();
		m.addProperty("role", role);
		m.addProperty("content", content);
		return m;
	}

	/** One reply: its rounds of tool calls, its retries, and the one answer the listener gets. */
	private final class Run
	{
		private final Conversation conversation;
		private final Listener listener;
		private final Pending pending = new Pending();
		/** This reply's messages so far: the model's tool calls and their results. */
		private final JsonArray produced = new JsonArray();
		/** The text of the rounds before this one. */
		private final List<String> texts = new ArrayList<>();
		private final Usage usage = new Usage();
		private final AtomicBoolean over = new AtomicBoolean();
		/** Optional settings refused during this reply and left out since; remembered once a request works without them. */
		private final Set<String> dropped = new LinkedHashSet<>();
		/** The last request's body, which a refusal is checked against. */
		private JsonObject sent;
		private String model;
		private int toolRounds;
		private int retries;

		Run(Conversation conversation, Listener listener)
		{
			this.conversation = conversation;
			this.listener = listener;
			model = conversation.model;
		}

		/** Sends this round's request. Throws if OkHttp won't take it (a header value it rejects). */
		void request()
		{
			Set<String> skip = new HashSet<>(refused.getOrDefault(refusedKey(conversation.model), Set.of()));
			skip.addAll(dropped);
			sent = body(conversation, skip, produced);
			Request.Builder request = new Request.Builder()
				.url(base.resolve("chat/completions"))
				.post(RequestBody.create(JSON, gson.toJson(sent)));
			if (!apiKey.isEmpty())
			{
				request.header("Authorization", "Bearer " + apiKey);
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
			boolean local = isPrivate(base.host());
			if (ChatApi.tookTooLong(e))
			{
				// It got the request, so it may have been answering it.
				usage.incomplete = true;
				fail(describe(base) + " took too long to answer."
					+ (local ? " Models on your own computer can be slow; try a smaller one." : " Try again."));
				return;
			}
			// A service on this computer or network that can't be reached isn't running: waiting won't help.
			if (!local && retry("Couldn't reach " + describe(base), ChatApi.retryDelay(null, null, retries + 1)))
			{
				return;
			}
			fail("Couldn't reach " + describe(base) + ": " + e.getMessage() + (local ? ". Is the service running?" : ""));
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
					text = body == null ? "" : body.string().trim();
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
			// Only a request that worked without them shows the dropped settings were the problem: remember those.
			if (!dropped.isEmpty())
			{
				refused.computeIfAbsent(refusedKey(conversation.model), k -> ConcurrentHashMap.newKeySet()).addAll(dropped);
			}
			Message m = new Message();
			try
			{
				if (Sse.isEventStream(response))
				{
					Sse.read(body.source(), (event, data) -> onChunk(m, data));
				}
				else
				{
					// Not streamed after all: one answer, read whole.
					String text;
					try
					{
						text = body.string().trim();
					}
					catch (IOException e)
					{
						if (!pending.isCancelled())
						{
							brokeOff(m);
							fail("Couldn't read the answer from " + describe(base) + ": " + e.getMessage());
						}
						return;
					}
					m.whole(gson, text);
				}
			}
			catch (IOException e)
			{
				// Stop closes the stream: not a problem to report.
				if (!pending.isCancelled())
				{
					brokeOff(m);
					fail(ChatApi.tookTooLong(e) ? describe(base) + " took too long to answer." : CUT_OFF);
				}
				return;
			}
			catch (JsonParseException | IllegalStateException | ClassCastException | UnsupportedOperationException | NumberFormatException e)
			{
				brokeOff(m);
				fail(describe(base) + " sent an answer AI Chat couldn't read.");
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
			// A model that doesn't take one of our optional settings says so: once per setting, do without it.
			// 400 from most services, 422 from those that validate the request's fields (Mistral).
			String rejected = code == 400 || code == 422 ? rejectedOption(gson, sent, text) : null;
			// A model that has just called tools can use them: an error about them now is about something else.
			if ("tools".equals(rejected) && produced.size() > 0)
			{
				rejected = null;
			}
			if (rejected != null)
			{
				log.debug("asking again without {}", rejected);
				dropped.add(rejected);
				again();
				return;
			}
			// After the optional settings: a reply length limit bigger than a small model's context is refused in the
			// same words, and leaving it out is all that takes.
			if (ChatApi.tooLongForModel(code, text))
			{
				fail(ChatApi.tooLong(conversation.model, !openai && isPrivate(base.host())
					? " A model on your own computer may also take more with a bigger context size in its own settings." : ""));
				return;
			}
			if (ChatApi.retryableStatus(code, text) && retries < MAX_RETRIES)
			{
				long delay = ChatApi.retryDelay(response.header("retry-after-ms"), response.header("retry-after"), retries + 1);
				String why = code == 429 ? describe(base) + "'s rate limit was hit" : describe(base) + " is busy";
				if (delay > MAX_RETRY_WAIT_MS)
				{
					fail(why + "; it asked to wait " + ChatApi.waitText(delay) + ". Try again then.");
					return;
				}
				retry(why, delay);
				return;
			}
			fail(explain(code, ChatApi.errorMessage(gson, text), conversation.model));
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

		/** One streamed piece of the reply. False once it's over. */
		private boolean onChunk(Message m, String data)
		{
			if (Sse.DONE.equals(data.trim()))
			{
				m.done = true;
				return false;
			}
			JsonElement parsed = gson.fromJson(data, JsonElement.class);
			if (parsed == null || !parsed.isJsonObject())
			{
				return true;
			}
			JsonObject o = parsed.getAsJsonObject();
			// OpenRouter reports a failure part way through as a piece with an error in it.
			String error = errorIn(o);
			if (error != null)
			{
				m.error = error;
				return false;
			}
			m.read(o);
			JsonObject choice = firstChoice(o);
			JsonObject delta = choice == null ? null : object(choice, "delta");
			if (delta != null)
			{
				String piece = content(delta.get("content"));
				if (!piece.isEmpty())
				{
					m.content.append(piece);
					partial(m, piece);
				}
				String refusal = string(delta, "refusal");
				if (refusal != null)
				{
					m.refusal = m.refusal == null ? refusal : m.refusal + refusal;
				}
				String reasoning = string(delta, "reasoning_content");
				if (reasoning != null)
				{
					m.reasoning.append(reasoning);
				}
				JsonElement calls = delta.get("tool_calls");
				if (calls != null && calls.isJsonArray())
				{
					for (JsonElement call : calls.getAsJsonArray())
					{
						if (call.isJsonObject())
						{
							m.streamedCall(call.getAsJsonObject());
						}
					}
				}
			}
			return true;
		}

		/** The reply so far, for the panel: earlier rounds, and this one without any reasoning written into it. */
		private void partial(Message m, String piece)
		{
			m.thinks |= piece.contains("think>") || m.content.indexOf("think>", Math.max(0, m.content.length() - piece.length() - 8)) >= 0;
			String now = m.thinks ? withoutThinking(m.content.toString()) : m.content.toString().trim();
			// The start of a tag that hasn't finished arriving.
			if (now.isEmpty() || "<think>".startsWith(now) || now.equals(m.lastShown) || over.get() || pending.isCancelled())
			{
				return;
			}
			m.lastShown = now;
			listener.onPartial(ChatApi.joinRounds(texts, now));
		}

		/** A whole response: the reply, or the tools it wants run before it carries on. */
		private void completed(Message m)
		{
			if (m.error != null)
			{
				brokeOff(m);
				fail(describe(base) + ": " + ChatApi.shorten(m.error, 300));
				return;
			}
			if (!m.done && m.finish == null)
			{
				brokeOff(m);
				fail(m.whole ? describe(base) + " sent no reply." : CUT_OFF);
				return;
			}
			// Counted before anything else can go wrong: this response is billed whatever happens next. Some services
			// don't send counts at all.
			usage.add(m.usage);
			usage.incomplete |= !m.counted;
			model = m.model != null ? m.model : model;
			String text = withoutThinking(m.content.toString());
			boolean cutShort = "length".equals(m.finish);
			if ("content_filter".equals(m.finish))
			{
				// What it wrote before that isn't an answer, so it isn't kept.
				Failure filtered = new Failure(name + "'s content filter stopped the reply.");
				filtered.withdrawn = true;
				fail(filtered);
				return;
			}
			// A call cut off by the length limit has half its arguments: not run.
			if (!m.calls.isEmpty() && !cutShort)
			{
				toolRound(m, text);
				return;
			}
			String all = ChatApi.joinRounds(texts, text);
			if (all.isEmpty())
			{
				fail(m.refusal != null ? name + " declined: " + ChatApi.shorten(m.refusal, 300)
					: name + " sent an empty reply" + (cutShort ? ": it used up its reply length, probably thinking. Try again, or a different model." : "."));
				return;
			}
			Reply r = new Reply();
			r.text = all;
			r.model = model;
			r.cutShort = cutShort;
			r.toolsUnavailable = !conversation.tools.isEmpty() && !sent.has("tools");
			r.usage.add(usage);
			finish(r);
		}

		/** The model asked for tools: run them, then send the call and the results back and carry on. */
		private void toolRound(Message m, String text)
		{
			if (toolRounds >= MAX_TOOL_ROUNDS)
			{
				fail(TOO_MANY_ROUNDS);
				return;
			}
			toolRounds++;
			JsonArray calls = new JsonArray();
			List<String> ids = new ArrayList<>();
			List<String> names = new ArrayList<>();
			List<JsonObject> inputs = new ArrayList<>();
			for (ToolCall c : m.calls.values())
			{
				// Some local services leave the id out; the results must still say which call they answer.
				String id = c.id != null ? c.id : "call_" + toolRounds + "_" + ids.size();
				JsonObject function = new JsonObject();
				function.addProperty("name", c.name == null ? "" : c.name);
				function.addProperty("arguments", c.arguments.toString());
				JsonObject call = new JsonObject();
				call.addProperty("id", id);
				call.addProperty("type", "function");
				call.add("function", function);
				if (c.extra != null)
				{
					call.add("extra_content", c.extra);
				}
				calls.add(call);
				ids.add(id);
				names.add(c.name == null ? "" : c.name);
				inputs.add(arguments(gson, c.arguments.toString()));
			}
			JsonObject assistant = new JsonObject();
			assistant.addProperty("role", "assistant");
			// Empty rather than null: Gson leaves nulls out unless told otherwise, and some services want the field.
			assistant.addProperty("content", text);
			// DeepSeek and Kimi want the reasoning behind tool calls back while the reply carries on, and refuse the next
			// round without it. Only where the service sent it: others may not take the field.
			if (m.reasoning.length() > 0)
			{
				assistant.addProperty("reasoning_content", m.reasoning.toString());
			}
			assistant.add("tool_calls", calls);
			produced.add(assistant);
			texts.add(text);
			ChatApi.runTools(conversation.toolRunner, names, inputs, results -> toolsDone(ids, results));
		}

		/** The tools have answered: one message per result, in the order they were asked for. */
		private void toolsDone(List<String> ids, List<ToolResult> results)
		{
			if (pending.isCancelled() || over.get())
			{
				return;
			}
			for (int i = 0; i < ids.size(); i++)
			{
				JsonObject result = new JsonObject();
				result.addProperty("role", "tool");
				result.addProperty("tool_call_id", ids.get(i));
				result.addProperty("content", results.get(i).content == null ? "" : results.get(i).content);
				produced.add(result);
			}
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

		/** A response that broke off: what it counted so far is billed, and the rest of what it used was never counted. */
		private void brokeOff(Message m)
		{
			usage.add(m.usage);
			usage.incomplete = true;
			model = m.model != null ? m.model : model;
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

	/** One response, put together from its streamed pieces as they arrive, or read whole. */
	private static final class Message
	{
		final StringBuilder content = new StringBuilder();
		/** Reasoning the service sent apart from the reply (reasoning_content): never shown. */
		final StringBuilder reasoning = new StringBuilder();
		/** Tool calls by their index, their arguments still arriving in pieces. */
		final TreeMap<Integer, ToolCall> calls = new TreeMap<>();
		final Usage usage = new Usage();
		/** The token counts came (only the last piece of a stream has them, and only when asked for). */
		boolean counted;
		String model;
		String refusal;
		String finish;
		String error;
		/** Read whole, not streamed. */
		boolean whole;
		/** The stream said it was done. */
		boolean done;
		/** The content has reasoning tags in it, so what's shown has to leave them out. */
		boolean thinks;
		String lastShown;

		/** What any piece can carry: the model, and (in the last one) the token counts. */
		void read(JsonObject o)
		{
			String m = string(o, "model");
			model = m != null && !m.isEmpty() ? m : model;
			JsonObject u = object(o, "usage");
			if (u != null)
			{
				long prompt = count(u, "prompt_tokens");
				JsonObject details = object(u, "prompt_tokens_details");
				long cached = details == null ? 0 : count(details, "cached_tokens");
				usage.input = Math.max(0, prompt - cached);
				usage.cacheRead = cached;
				usage.cacheWrite = 0;
				usage.output = count(u, "completion_tokens");
				counted = true;
			}
			JsonObject choice = firstChoice(o);
			String f = choice == null ? null : string(choice, "finish_reason");
			finish = f != null ? f : finish;
		}

		/** A piece of a tool call: the first has its id and name, the rest more of its arguments. */
		void streamedCall(JsonObject c)
		{
			JsonElement index = c.get("index");
			String id = string(c, "id");
			int i;
			if (index != null && index.isJsonPrimitive() && index.getAsJsonPrimitive().isNumber())
			{
				i = index.getAsInt();
			}
			else if (calls.isEmpty())
			{
				i = 0;
			}
			else if (id != null && !id.equals(calls.lastEntry().getValue().id))
			{
				// No index (some local services): a new id is a new call.
				i = calls.lastKey() + 1;
			}
			else
			{
				i = calls.lastKey();
			}
			calls.computeIfAbsent(i, k -> new ToolCall()).add(c);
		}

		void whole(Gson gson, String json)
		{
			whole = true;
			JsonElement parsed = gson.fromJson(json, JsonElement.class);
			if (parsed == null || !parsed.isJsonObject())
			{
				throw new JsonParseException("not an object");
			}
			JsonObject o = parsed.getAsJsonObject();
			// OpenRouter can report a failure inside a 200 answer, with or without choices.
			error = errorIn(o);
			if (error != null)
			{
				return;
			}
			JsonObject choice = firstChoice(o);
			if (choice == null)
			{
				return;
			}
			read(o);
			done = true;
			JsonObject message = object(choice, "message");
			if (message == null)
			{
				return;
			}
			content.append(content(message.get("content")));
			String r = string(message, "reasoning_content");
			reasoning.append(r == null ? "" : r);
			refusal = string(message, "refusal");
			JsonElement toolCalls = message.get("tool_calls");
			if (toolCalls != null && toolCalls.isJsonArray())
			{
				int i = 0;
				for (JsonElement call : toolCalls.getAsJsonArray())
				{
					if (call.isJsonObject())
					{
						calls.computeIfAbsent(i++, k -> new ToolCall()).add(call.getAsJsonObject());
					}
				}
			}
		}
	}

	/** A tool call the model made. */
	private static final class ToolCall
	{
		String id;
		String name;
		final StringBuilder arguments = new StringBuilder();
		/**
		 * What else the service put on the call, to go back with it: Gemini's signature for the reasoning behind it
		 * ({"google": {"thought_signature": ...}}), without which it refuses the next round.
		 */
		JsonElement extra;

		void add(JsonObject piece)
		{
			String i = string(piece, "id");
			id = i != null && !i.isEmpty() ? i : id;
			JsonElement e = piece.get("extra_content");
			extra = e != null && !e.isJsonNull() ? e.deepCopy() : extra;
			JsonObject function = object(piece, "function");
			if (function == null)
			{
				return;
			}
			String n = string(function, "name");
			name = name == null && n != null && !n.isEmpty() ? n : name;
			JsonElement args = function.get("arguments");
			if (args != null && args.isJsonPrimitive())
			{
				arguments.append(args.getAsString());
			}
			else if (args != null && args.isJsonObject())
			{
				// A few services send the arguments as an object rather than as JSON text.
				arguments.append(args);
			}
		}
	}

	/** A call's arguments as an object; null if they aren't valid JSON (then it isn't run, and the model is told). */
	private static JsonObject arguments(Gson gson, String json)
	{
		if (json.trim().isEmpty())
		{
			return new JsonObject();
		}
		try
		{
			JsonElement parsed = gson.fromJson(json, JsonElement.class);
			if (parsed == null || parsed.isJsonNull())
			{
				return new JsonObject();
			}
			return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
		}
		catch (JsonParseException e)
		{
			return null;
		}
	}

	/** The error a response or streamed piece carries, at its top or in its choice, or null. */
	private static String errorIn(JsonObject o)
	{
		String top = errorText(o.get("error"));
		if (top != null)
		{
			return top;
		}
		JsonObject choice = firstChoice(o);
		return choice == null ? null : errorText(choice.get("error"));
	}

	/** An error's message: {"message": ...} or plain text. Null if there's no error. */
	private static String errorText(JsonElement error)
	{
		if (error == null || error.isJsonNull())
		{
			return null;
		}
		String message = error.isJsonPrimitive() ? error.getAsString() : error.isJsonObject() ? string(error.getAsJsonObject(), "message") : null;
		return message != null ? message : "the service reported an error";
	}

	private static JsonObject firstChoice(JsonObject o)
	{
		JsonElement choices = o.get("choices");
		return choices != null && choices.isJsonArray() && choices.getAsJsonArray().size() > 0
			&& choices.getAsJsonArray().get(0).isJsonObject() ? choices.getAsJsonArray().get(0).getAsJsonObject() : null;
	}

	@Override
	public Pending listModels(ModelsListener listener)
	{
		Pending pending = new Pending();
		listModels(listener, pending, false);
		return pending;
	}

	/**
	 * Asks for {@code base}/models, or with {@code withV1} for {@code base}/v1/models: a URL that leaves out the /v1
	 * most services need (Ollama's "http://localhost:11434") isn't found, and chats sent there wouldn't be either.
	 */
	private void listModels(ModelsListener listener, Pending pending, boolean withV1)
	{
		Request.Builder request = new Request.Builder().url(base.resolve(withV1 ? "v1/models" : "models")).get();
		if (!apiKey.isEmpty())
		{
			request.header("Authorization", "Bearer " + apiKey);
		}
		Call call = http.newCall(request.build());
		pending.set(call);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call c, IOException e)
			{
				if (pending.isCancelled())
				{
					return;
				}
				if (withV1)
				{
					listener.onError(NO_MODEL_LIST);
					return;
				}
				listener.onError(ChatApi.tookTooLong(e) ? describe(base) + " didn't answer in time. Try again in a moment."
					: "Couldn't reach " + describe(base) + ": " + e.getMessage() + (isPrivate(base.host()) ? ". Is the service running?" : ""));
			}

			@Override
			public void onResponse(Call c, Response response)
			{
				String text;
				try (ResponseBody body = response.body())
				{
					text = body == null ? "" : body.string().trim();
				}
				catch (IOException e)
				{
					if (!pending.isCancelled())
					{
						listener.onError(withV1 ? NO_MODEL_LIST : "Couldn't read the answer from " + describe(base) + ": " + e.getMessage());
					}
					return;
				}
				if (pending.isCancelled())
				{
					return;
				}
				if (withV1)
				{
					String fixed = base.resolve("v1").toString();
					listener.onError(response.isSuccessful() && modelIds(gson, text) != null
						? "The URL is missing /v1: set the Compatible API URL to " + fixed + " in the AI Chat settings."
						: NO_MODEL_LIST);
					return;
				}
				if (response.code() == 404)
				{
					if (!base.encodedPath().endsWith("/v1/"))
					{
						listModels(listener, pending, true);
						return;
					}
					listener.onError(NO_MODEL_LIST);
					return;
				}
				if (!response.isSuccessful())
				{
					// No model is involved in listing them; explain only names one for a 404, handled above.
					listener.onError(explain(response.code(), ChatApi.errorMessage(gson, text), ""));
					return;
				}
				List<String> ids = modelIds(gson, text);
				if (ids == null)
				{
					listener.onError(describe(base) + " sent a model list AI Chat couldn't read.");
					return;
				}
				listener.onModels(ids);
			}
		});
	}

	/** The ids in a model list: {"data": [{"id": ...}, ...]}, or just the list. Null if it isn't one. */
	static List<String> modelIds(Gson gson, String json)
	{
		try
		{
			JsonElement parsed = gson.fromJson(json, JsonElement.class);
			JsonElement data = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject().get("data") : parsed;
			if (data == null || !data.isJsonArray())
			{
				return null;
			}
			List<String> ids = new ArrayList<>();
			for (JsonElement m : data.getAsJsonArray())
			{
				String id = m.isJsonObject() ? string(m.getAsJsonObject(), "id") : null;
				if (id != null)
				{
					ids.add(id);
				}
			}
			return ids;
		}
		catch (JsonParseException | IllegalStateException e)
		{
			return null;
		}
	}

	/**
	 * The reply without reasoning some models write into it: whole {@code <think>} blocks, reasoning that ran out of
	 * room before its closing tag, and reasoning whose opening tag was part of the prompt.
	 */
	static String withoutThinking(String content)
	{
		String text = THINKING.matcher(content).replaceAll("");
		int close = text.lastIndexOf("</think>");
		if (close >= 0)
		{
			text = text.substring(close + "</think>".length());
		}
		int open = text.indexOf("<think>");
		if (open >= 0)
		{
			text = text.substring(0, open);
		}
		return text.trim();
	}

	/** Content is usually a string; some services send a list of parts, including thinking we don't show. */
	private static String content(JsonElement content)
	{
		if (content == null || content.isJsonNull())
		{
			return "";
		}
		if (content.isJsonPrimitive())
		{
			return content.getAsString();
		}
		StringBuilder sb = new StringBuilder();
		if (content.isJsonArray())
		{
			for (JsonElement part : content.getAsJsonArray())
			{
				if (!part.isJsonObject())
				{
					continue;
				}
				String type = string(part.getAsJsonObject(), "type");
				String t = string(part.getAsJsonObject(), "text");
				if (t != null && (type == null || type.equals("text") || type.equals("output_text")))
				{
					sb.append(t);
				}
			}
		}
		return sb.toString();
	}

	private String explain(int code, String message, String model)
	{
		String detail = message == null ? "" : " (" + ChatApi.shorten(message, 200) + ")";
		String where = describe(base);
		String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
		// Gemini answers a bad key with 400, not 401.
		if (code == 401 || code == 400 && lower.contains("api key"))
		{
			return where + " didn't accept the API key. Check it in the AI Chat settings" + detail + ".";
		}
		switch (code)
		{
			case 402:
				return where + " says your account is out of credits" + detail + ".";
			case 403:
				return where + " refused the request" + detail + ".";
			case 404:
				return "Not found at " + where + ": check the model \"" + model + "\"" + (openai ? "" : " and the URL") + " in the AI Chat settings" + detail + ".";
			case 429:
				return lower.contains("quota") || lower.contains("credit") || lower.contains("spend") || lower.contains("billing")
					? where + " says your account is out of credits or over its limit" + detail + "."
					: where + "'s rate limit was hit. Try again in a minute" + detail + ".";
			case 500:
			case 502:
			case 503:
			case 504:
			case 529:
				return where + " is having trouble right now. Try again shortly" + detail + ".";
			default:
				return where + " answered HTTP " + code + detail + ".";
		}
	}

	/**
	 * The base URL a player typed, e.g. "http://localhost:11434/v1" or "https://openrouter.ai/api/v1", or null if
	 * it isn't one. A pasted ".../chat/completions" is fine too. Nothing is added: services differ in their paths.
	 */
	static HttpUrl parseBaseUrl(String typed)
	{
		if (typed == null || typed.trim().isEmpty())
		{
			return null;
		}
		String s = typed.trim();
		while (s.endsWith("/"))
		{
			s = s.substring(0, s.length() - 1);
		}
		if (s.endsWith("/chat/completions"))
		{
			s = s.substring(0, s.length() - "/chat/completions".length());
		}
		return HttpUrl.parse(s + "/");
	}

	/** "localhost:11434" or "openrouter.ai", for messages. */
	static String describeUrl(String typed)
	{
		HttpUrl url = parseBaseUrl(typed);
		return url == null ? "?" : describe(url);
	}

	private static String describe(HttpUrl url)
	{
		boolean defaultPort = url.port() == HttpUrl.defaultPort(url.scheme());
		return url.host() + (defaultPort ? "" : ":" + url.port());
	}

	/**
	 * This computer or the home network, where plain http:// doesn't cross the internet: "localhost", ".local" names
	 * (resolved on the local network), and IP addresses in the loopback, private and link-local ranges. Only literal
	 * addresses count: a name like 10.example.com could point anywhere.
	 */
	static boolean isPrivate(String host)
	{
		String h = host.toLowerCase(Locale.ROOT);
		if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local"))
		{
			return true;
		}
		if (h.startsWith("[") && h.endsWith("]"))
		{
			h = h.substring(1, h.length() - 1);
		}
		if (h.contains(":"))
		{
			// IPv6: loopback, unique local (fc00::/7) and link-local (fe80::/10).
			return h.equals("::1") || h.matches("f[cd][0-9a-f]{0,2}:.*") || h.matches("fe[89ab][0-9a-f]?:.*");
		}
		int[] ip = ipv4(h);
		if (ip == null)
		{
			return false;
		}
		return ip[0] == 127 || ip[0] == 10 || ip[0] == 192 && ip[1] == 168 || ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31
			|| ip[0] == 169 && ip[1] == 254;
	}

	/** The four numbers of a dotted IPv4 address, or null if the host isn't one. */
	private static int[] ipv4(String host)
	{
		String[] parts = host.split("\\.", -1);
		if (parts.length != 4)
		{
			return null;
		}
		int[] ip = new int[4];
		for (int i = 0; i < 4; i++)
		{
			if (!parts[i].matches("\\d{1,3}"))
			{
				return null;
			}
			ip[i] = Integer.parseInt(parts[i]);
			if (ip[i] > 255)
			{
				return null;
			}
		}
		return ip;
	}

	private static String string(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static JsonObject object(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static long count(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : 0;
	}
}
