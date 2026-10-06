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
import java.util.concurrent.Future;
import java.util.function.Consumer;
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

	/** Lists the models the key can use, for "Test connection". The listener hears back once, on an OkHttp thread. */
	default Pending listModels(ModelsListener listener)
	{
		listener.onError("This provider can't list its models.");
		return new Pending();
	}

	final class Conversation
	{
		String model;
		String system;
		final List<Turn> turns = new ArrayList<>();
		/** Tools the model may call, in the same order every request (the order is part of the cached prompt). */
		final List<ToolSpec> tools = new ArrayList<>();
		/** Runs the tools the model calls; needed when {@link #tools} isn't empty. */
		ToolRunner toolRunner;
		/** A lower reply length limit than the provider's own, or 0 for that. */
		int maxTokens;
	}

	/** A tool the model may call while answering: a name, what it's for, and a JSON Schema for its input. */
	final class ToolSpec
	{
		final String name;
		final String description;
		final JsonObject inputSchema;

		ToolSpec(String name, String description, JsonObject inputSchema)
		{
			this.name = name;
			this.description = description;
			this.inputSchema = inputSchema;
		}
	}

	/** Runs the tools the model calls. */
	interface ToolRunner
	{
		/**
		 * Runs one call and reports back exactly once, on any thread. Never throws: problems become an error result the
		 * model can read.
		 */
		void run(String name, JsonObject input, Consumer<ToolResult> done);
	}

	final class ToolResult
	{
		final String content;
		final boolean error;

		private ToolResult(String content, boolean error)
		{
			this.content = content;
			this.error = error;
		}

		static ToolResult ok(String content)
		{
			return new ToolResult(content, false);
		}

		static ToolResult error(String message)
		{
			return new ToolResult(message, true);
		}
	}

	/** Tokens a reply used, over all its requests (tool rounds included). */
	final class Usage
	{
		/** Input billed at the full price: not read from or written to the provider's prompt cache. */
		long input;
		long cacheRead;
		long cacheWrite;
		long output;

		void add(Usage u)
		{
			input += u.input;
			cacheRead += u.cacheRead;
			cacheWrite += u.cacheWrite;
			output += u.output;
		}

		long total()
		{
			return input + cacheRead + cacheWrite + output;
		}
	}

	interface ModelsListener
	{
		void onModels(List<String> ids);

		void onError(String message);
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
		/**
		 * Anthropic only: the messages this reply was made of (tool calls and their results included), exactly as
		 * exchanged. Sent back as they are only when {@link #rawKey} matches the request's {@link #promptKey}.
		 */
		JsonArray rawMessages;
		String rawKey;

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
		/** Anthropic only: see {@link Turn#rawMessages}. */
		JsonArray rawMessages;
		/** The {@link #promptKey} of the requests that made this reply. */
		String rawKey;
		final Usage usage = new Usage();
	}

	interface Listener
	{
		/** The reply so far while it streams in: all of its text, not just the new part. On an OkHttp thread. */
		default void onPartial(String textSoFar)
		{
		}

		/** The provider is busy; the request is sent again in {@code seconds}. */
		default void onRetrying(String message, int seconds)
		{
		}

		void onReply(Reply reply);

		/** A short, readable explanation for the panel. */
		void onError(String message);
	}

	/**
	 * What earlier replies were made under: the model, the instructions and the tools. Replies are sent back exactly as
	 * they came only while it's unchanged.
	 */
	static String promptKey(Conversation c)
	{
		StringBuilder key = new StringBuilder(c.model).append('\u0000').append(c.system);
		for (ToolSpec t : c.tools)
		{
			key.append('\u0000').append(t.name).append('\u0000').append(t.description).append('\u0000').append(t.inputSchema);
		}
		return key.toString();
	}

	/**
	 * A request in flight. A retry or the next tool round replaces the call (or the wait) inside, so cancelling always
	 * stops whatever is running.
	 */
	final class Pending
	{
		private volatile Call call;
		private volatile Future<?> timer;
		private volatile boolean cancelled;

		void set(Call c)
		{
			call = c;
			if (cancelled)
			{
				c.cancel();
			}
		}

		/** A wait before the next attempt. */
		void setTimer(Future<?> f)
		{
			timer = f;
			if (cancelled)
			{
				f.cancel(false);
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
			Future<?> f = timer;
			if (f != null)
			{
				f.cancel(false);
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
