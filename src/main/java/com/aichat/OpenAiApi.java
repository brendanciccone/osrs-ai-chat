package com.aichat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
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
class OpenAiApi implements ChatApi
{
	static final HttpUrl OPENAI_URL = HttpUrl.get("https://api.openai.com/v1/");
	/** OpenAI's small, fast model: plenty for chat, and cheap. */
	static final String DEFAULT_MODEL = "gpt-6-luna";
	/**
	 * Reply length limits. Most services count the model's reasoning against them too, so they leave plenty of room;
	 * only what's used is billed, and the system prompt asks for short answers.
	 */
	private static final int OPENAI_MAX_TOKENS = 8000;
	private static final int COMPATIBLE_MAX_TOKENS = 16000;
	/** Some models (Qwen, DeepSeek R1 on some hosts, local models) put their reasoning inside the reply. */
	private static final Pattern THINKING = Pattern.compile("(?s)<think>.*?</think>");

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl base;
	private final String apiKey;
	private final String name;
	/** OpenAI itself, rather than a compatible service with its own quirks. */
	private final boolean openai;
	/** The reasoning effort to ask for, or null for the model's own default. */
	private final String reasoningEffort;
	/**
	 * Optional settings each service and model has refused, by {@link #refusedKey}: left out of later requests instead
	 * of being refused again every time. Shared by the plugin's requests for as long as it runs.
	 */
	private final Map<String, Set<String>> refused;

	OpenAiApi(OkHttpClient http, Gson gson, HttpUrl base, String apiKey, String name, boolean openai,
		String reasoningEffort, Map<String, Set<String>> refused)
	{
		this.http = http;
		this.gson = gson;
		this.base = base;
		this.apiKey = apiKey;
		this.name = name;
		this.openai = openai;
		this.reasoningEffort = reasoningEffort;
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
		Pending pending = new Pending();
		send(conversation, listener, pending, body(conversation), new ArrayList<>());
		return pending;
	}

	/** {@code dropped}: optional settings already left out of this request because they were refused. */
	private void send(Conversation conversation, Listener listener, Pending pending, JsonObject body, List<String> dropped)
	{
		Request.Builder request = new Request.Builder()
			.url(base.resolve("chat/completions"))
			.post(RequestBody.create(JSON, gson.toJson(body)));
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
				if (ChatApi.tookTooLong(e))
				{
					listener.onError(describe(base) + " took too long to answer."
						+ (isPrivate(base.host()) ? " Models on your own computer can be slow; try a smaller one." : " Try again."));
				}
				else
				{
					listener.onError("Couldn't reach " + describe(base) + ": " + e.getMessage()
						+ (isPrivate(base.host()) ? ". Is the service running?" : ""));
				}
			}

			@Override
			public void onResponse(Call c, Response response)
			{
				String text;
				try (ResponseBody b = response.body())
				{
					text = b == null ? "" : b.string().trim();
				}
				catch (IOException e)
				{
					if (!pending.isCancelled())
					{
						listener.onError("Couldn't read the answer from " + describe(base) + ": " + e.getMessage());
					}
					return;
				}
				if (pending.isCancelled())
				{
					return;
				}
				if (!response.isSuccessful())
				{
					String message = ChatApi.errorMessage(gson, text);
					// A model that doesn't take one of our optional settings says so: once per setting, do without it.
					// 400 from most services, 422 from those that validate the request's fields (Mistral).
					String rejected = response.code() == 400 || response.code() == 422 ? rejectedOption(gson, body, text) : null;
					if (rejected != null)
					{
						JsonObject retry = body.deepCopy();
						retry.remove(rejected);
						List<String> now = new ArrayList<>(dropped);
						now.add(rejected);
						send(conversation, listener, pending, retry, now);
						return;
					}
					listener.onError(explain(response.code(), message, conversation.model));
					return;
				}
				// Only a request that worked without them shows the dropped settings were the problem: remember those.
				if (!dropped.isEmpty())
				{
					refused.computeIfAbsent(refusedKey(conversation.model), k -> ConcurrentHashMap.newKeySet()).addAll(dropped);
				}
				handle(text, listener);
			}
		});
	}

	JsonObject body(Conversation conversation)
	{
		JsonObject body = new JsonObject();
		body.addProperty("model", conversation.model);
		JsonArray messages = new JsonArray();
		messages.add(message("system", conversation.system));
		for (Turn t : conversation.turns)
		{
			messages.add(message(t.user ? "user" : "assistant", t.text));
		}
		body.add("messages", messages);
		Set<String> skip = refused.getOrDefault(refusedKey(conversation.model), Set.of());
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
				body.addProperty("max_completion_tokens", OPENAI_MAX_TOKENS);
			}
			// OpenAI otherwise keeps chat completions for new accounts.
			body.addProperty("store", false);
		}
		else if (!skip.contains("max_tokens"))
		{
			// The name every compatible service knows (Ollama ignores the newer max_completion_tokens).
			body.addProperty("max_tokens", COMPATIBLE_MAX_TOKENS);
		}
		return body;
	}

	/** One service and model: what one refuses, another may take. */
	private String refusedKey(String model)
	{
		return base + " " + model;
	}

	/**
	 * Settings a request can do without. Not "store": that one keeps OpenAI from storing the chat, so if it's ever
	 * refused the player sees the error rather than a quiet retry without it.
	 */
	private static final Set<String> OPTIONAL = new LinkedHashSet<>(Arrays.asList(
		"reasoning_effort", "max_completion_tokens", "max_tokens"));

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

	private void handle(String json, Listener listener)
	{
		JsonElement parsed;
		try
		{
			parsed = gson.fromJson(json, JsonElement.class);
		}
		catch (JsonParseException e)
		{
			parsed = null;
		}
		if (parsed == null || !parsed.isJsonObject())
		{
			listener.onError(describe(base) + " sent an answer AI Chat couldn't read.");
			return;
		}
		JsonObject o = parsed.getAsJsonObject();
		JsonElement choices = o.get("choices");
		JsonObject choice = choices != null && choices.isJsonArray() && choices.getAsJsonArray().size() > 0
			&& choices.getAsJsonArray().get(0).isJsonObject() ? choices.getAsJsonArray().get(0).getAsJsonObject() : null;
		// OpenRouter can report a failure inside a 200 answer, with or without choices.
		String error = ChatApi.errorMessage(gson, json);
		if (error == null && choice != null && choice.get("error") != null)
		{
			error = ChatApi.errorMessage(gson, gson.toJson(choice));
		}
		if (choice == null || error != null)
		{
			listener.onError(error != null ? describe(base) + ": " + ChatApi.shorten(error, 300) : describe(base) + " sent no reply.");
			return;
		}
		JsonObject message = choice.has("message") && choice.get("message").isJsonObject() ? choice.getAsJsonObject("message") : new JsonObject();
		String refusal = string(message, "refusal");
		String text = withoutThinking(content(message.get("content")));
		boolean cutShort = "length".equals(string(choice, "finish_reason"));
		if (text.isEmpty())
		{
			listener.onError(refusal != null ? name + " declined: " + ChatApi.shorten(refusal, 300)
				: name + " sent an empty reply" + (cutShort ? ": it used up its reply length, probably thinking. Try again, or a different model." : "."));
			return;
		}
		Reply r = new Reply();
		r.text = text;
		r.model = string(o, "model");
		r.cutShort = cutShort;
		listener.onReply(r);
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
}
