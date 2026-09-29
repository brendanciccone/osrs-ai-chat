package com.aichat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.Set;
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
	/** Room for a full answer; replies are billed by what's used, and the system prompt asks for short ones. */
	private static final int MAX_TOKENS = 16000;
	/**
	 * Models that take server-side fallback: when their safety filter declines a request, Anthropic retries it on
	 * another Claude model instead of refusing outright.
	 */
	private static final Set<String> FALLBACK_MODELS = Set.of("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5");
	private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl url;
	private final String apiKey;

	AnthropicApi(OkHttpClient http, Gson gson, HttpUrl url, String apiKey)
	{
		this.http = http;
		this.gson = gson;
		this.url = url;
		this.apiKey = apiKey;
	}

	@Override
	public String displayName()
	{
		return "Claude";
	}

	@Override
	public Pending send(Conversation request, Listener callback)
	{
		Pending pending = new Pending();
		send(request, callback, pending, true, FALLBACK_MODELS.contains(request.model));
		return pending;
	}

	/**
	 * {@code replay}: send earlier replies back as they came. {@code fallback}: ask for server-side fallback. Each is
	 * dropped once if Anthropic refuses it.
	 */
	private void send(Conversation request, Listener callback, Pending pending, boolean replay, boolean fallback)
	{
		String model = request.model;
		Request.Builder http = new Request.Builder()
			.url(url)
			.header("x-api-key", apiKey)
			.header("anthropic-version", "2023-06-01")
			.post(RequestBody.create(JSON, gson.toJson(body(request, replay, fallback))));
		if (fallback)
		{
			http.header("anthropic-beta", FALLBACK_BETA);
		}
		Call call = this.http.newCall(http.build());
		pending.set(call);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call c, IOException e)
			{
				if (!pending.isCancelled())
				{
					callback.onError(ChatApi.tookTooLong(e)
						? "Claude took too long to answer. Try again, maybe with a shorter question or a faster model."
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
						callback.onError("Couldn't read Anthropic's answer: " + e.getMessage());
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
					// The fallback beta was retired or isn't enabled for this account: do without it.
					if (fallback && response.code() == 400 && message != null
						&& (message.contains("anthropic-beta") || message.contains("fallbacks")))
					{
						log.debug("retrying without server-side fallback: {}", message);
						send(request, callback, pending, replay, false);
						return;
					}
					// Claude checks that the reasoning it's given back belongs to this exact conversation; after a
					// settings change it may not. Once, carry on without it.
					if (replay && response.code() == 400 && message != null && message.contains("thinking"))
					{
						log.debug("retrying without earlier reasoning: {}", message);
						send(request, callback, pending, false, fallback);
						return;
					}
					callback.onError(explain(response.code(), message, model));
					return;
				}
				handle(text, callback, !replay);
			}
		});
	}

	/** The request body. {@code replay}: send earlier replies back as they came, reasoning included, where allowed. */
	JsonObject body(Conversation request, boolean replay, boolean fallback)
	{
		JsonObject body = new JsonObject();
		body.addProperty("model", request.model);
		body.addProperty("max_tokens", MAX_TOKENS);
		body.addProperty("system", request.system);
		JsonArray messages = new JsonArray();
		for (Turn t : request.turns)
		{
			JsonObject m = new JsonObject();
			m.addProperty("role", t.user ? "user" : "assistant");
			// Only unchanged: same model, same instructions (the plugin drops rawContent if earlier turns changed).
			if (replay && t.rawContent != null && request.model.equals(t.rawModel) && request.system.equals(t.rawSystem))
			{
				m.add("content", t.rawContent);
			}
			else
			{
				m.addProperty("content", t.text);
			}
			messages.add(m);
		}
		body.add("messages", messages);
		if (fallback)
		{
			body.addProperty("fallbacks", "default");
		}
		return body;
	}

	private void handle(String json, Listener callback, boolean historyAsText)
	{
		JsonObject o;
		try
		{
			o = gson.fromJson(json, JsonObject.class);
		}
		catch (JsonParseException e)
		{
			callback.onError("Anthropic sent an answer AI Chat couldn't read.");
			return;
		}
		if (o == null)
		{
			callback.onError("Anthropic sent an empty answer.");
			return;
		}
		String stop = string(o, "stop_reason");
		JsonArray content = o.has("content") && o.get("content").isJsonArray() ? o.getAsJsonArray("content") : new JsonArray();
		StringBuilder text = new StringBuilder();
		for (JsonElement block : content)
		{
			if (block.isJsonObject() && "text".equals(string(block.getAsJsonObject(), "type")))
			{
				String t = string(block.getAsJsonObject(), "text");
				if (t != null)
				{
					text.append(t);
				}
			}
		}
		if ("refusal".equals(stop))
		{
			callback.onError("Claude declined to answer that.");
			return;
		}
		if (text.toString().trim().isEmpty())
		{
			callback.onError("Claude sent an empty reply" + (stop == null ? "." : " (" + stop + ")."));
			return;
		}
		Reply r = new Reply();
		r.text = text.toString().trim();
		r.model = string(o, "model");
		r.cutShort = "max_tokens".equals(stop);
		r.rawContent = content;
		r.historyAsText = historyAsText;
		callback.onReply(r);
	}

	private static String explain(int code, String message, String model)
	{
		String detail = message == null ? "" : " (" + ChatApi.shorten(message, 200) + ")";
		switch (code)
		{
			case 401:
				return "Anthropic didn't accept your API key. Check \"Claude API key\" in the AI Chat settings.";
			case 402:
				return "Anthropic says there's a billing problem with your account" + detail + ".";
			case 403:
				return "Your Anthropic API key isn't allowed to do that" + detail + ".";
			case 404:
				return "The Claude model \"" + model + "\" isn't available to your key. Check \"Claude model\" in the AI Chat settings" + detail + ".";
			case 413:
				return "This chat has grown too long to send. Start a new chat.";
			case 429:
				return "Anthropic's rate limit was hit. Try again in a minute" + detail + ".";
			case 500:
			case 502:
			case 503:
			case 529:
				return "Anthropic is overloaded or having trouble right now. Try again shortly.";
			default:
				return "Anthropic answered HTTP " + code + detail + ".";
		}
	}

	private static String string(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}
}
