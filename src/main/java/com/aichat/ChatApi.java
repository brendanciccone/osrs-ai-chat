package com.aichat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import okhttp3.Call;
import okhttp3.MediaType;

/** An AI provider: send the conversation so far, get back one reply or one error. */
interface ChatApi
{
	MediaType JSON = MediaType.get("application/json; charset=utf-8");

	/**
	 * Starts the request; the listener hears back later on an OkHttp thread, exactly once, unless the request is
	 * cancelled first (then not at all).
	 */
	Pending send(Conversation conversation, Listener listener);

	/** The provider's name for this setup, shown as the author of its replies. */
	String displayName();

	final class Conversation
	{
		String model;
		String system;
		final List<Turn> turns = new ArrayList<>();
	}

	/** One turn of the conversation as the provider sees it. */
	final class Turn
	{
		final boolean user;
		final String text;
		/** Anthropic only: the reply's content blocks as returned, see {@link Chat.Message#rawContent}. */
		JsonArray rawContent;
		String rawModel;
		String rawSystem;

		Turn(boolean user, String text)
		{
			this.user = user;
			this.text = text;
		}
	}

	final class Reply
	{
		String text;
		/** The model that answered, which can differ from the one asked for (e.g. a fallback). */
		String model;
		/** The reply hit the length limit. */
		boolean cutShort;
		JsonArray rawContent;
		/**
		 * Anthropic only: earlier replies had to be sent as plain text for this one (see AnthropicApi). They can't be
		 * sent any other way from now on, or this reply's reasoning wouldn't match what it was built on.
		 */
		boolean historyAsText;
	}

	interface Listener
	{
		void onReply(Reply reply);

		/** A short, readable explanation for the panel. */
		void onError(String message);
	}

	/** A request in flight. A retry replaces the call inside, so cancelling always stops whatever is running. */
	final class Pending
	{
		private volatile Call call;
		private volatile boolean cancelled;

		void set(Call c)
		{
			call = c;
			if (cancelled)
			{
				c.cancel();
			}
		}

		void cancel()
		{
			cancelled = true;
			Call c = call;
			if (c != null)
			{
				c.cancel();
			}
		}

		boolean isCancelled()
		{
			return cancelled;
		}
	}

	/**
	 * The error message inside a provider's error body, or null. Anthropic, OpenAI and most others send
	 * {"error":{"message":...}}; Gemini wraps that in a list, some local servers send {"error":"text"}, Mistral
	 * {"detail":...}. The body is read whatever its Content-Type says: OpenAI labels some JSON errors as plain text.
	 */
	static String errorMessage(Gson gson, String body)
	{
		try
		{
			JsonElement parsed = gson.fromJson(body, JsonElement.class);
			if (parsed != null && parsed.isJsonArray() && parsed.getAsJsonArray().size() > 0)
			{
				parsed = parsed.getAsJsonArray().get(0);
			}
			if (parsed == null || !parsed.isJsonObject())
			{
				return null;
			}
			JsonObject o = parsed.getAsJsonObject();
			JsonElement error = o.get("error");
			if (error != null && error.isJsonObject())
			{
				JsonElement message = error.getAsJsonObject().get("message");
				return message != null && message.isJsonPrimitive() ? message.getAsString() : null;
			}
			if (error != null && error.isJsonPrimitive())
			{
				return error.getAsString();
			}
			JsonElement detail = o.get("detail");
			if (detail != null && detail.isJsonPrimitive())
			{
				return detail.getAsString();
			}
			return null;
		}
		catch (JsonParseException | IllegalStateException | ClassCastException | UnsupportedOperationException e)
		{
			return null;
		}
	}

	/**
	 * A pasted API key without what copying tends to add: spaces, line breaks, non-breaking and zero-width spaces.
	 */
	static String cleanKey(String key)
	{
		return key == null ? "" : key.replaceAll("[\\s\\p{Z}\\p{Cf}]", "");
	}

	/** Whether a (cleaned) key can go in an HTTP header: printable ASCII only, so no curly quotes or ellipses. */
	static boolean sendableKey(String key)
	{
		for (int i = 0; i < key.length(); i++)
		{
			char c = key.charAt(i);
			if (c < 0x21 || c > 0x7e)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * The provider was reached but took too long to answer. A connect timeout is a different story: nothing answered.
	 */
	static boolean tookTooLong(IOException e)
	{
		if (!(e instanceof InterruptedIOException))
		{
			return false;
		}
		String m = e.getMessage();
		return m == null || !m.toLowerCase(Locale.ROOT).contains("connect");
	}

	/** Cut to a length that fits the panel. */
	static String shorten(String s, int max)
	{
		if (s == null)
		{
			return "";
		}
		String t = s.trim();
		return t.length() <= max ? t : t.substring(0, max).trim() + "...";
	}
}
