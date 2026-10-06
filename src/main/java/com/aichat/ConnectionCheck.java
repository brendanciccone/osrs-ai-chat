package com.aichat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * "Test" in the panel: the provider's list of models, asked for with the player's key, and what it says about their
 * setup. The result is kept for the provider, address and key it was made with, and read against whichever model is
 * set now, so choosing another model (here or in the settings) updates it without asking again.
 */
final class ConnectionCheck
{
	/** Model ids that aren't for chatting: OpenAI's embeddings, speech, images, moderation and the like. */
	private static final String[] NOT_CHAT = {"embed", "tts", "whisper", "dall-e", "moderation", "image", "audio",
		"transcribe", "realtime", "search"};

	enum Kind
	{
		/** Still asking. */
		TESTING,
		OK,
		/** Connected, but the model needs a look. */
		WARNING,
		ERROR
	}

	/** What the panel shows under the setup: a sentence, and the models to choose from (empty for none). */
	static final class Note
	{
		final Kind kind;
		final String text;
		final List<String> models;

		Note(Kind kind, String text, List<String> models)
		{
			this.kind = kind;
			this.text = text;
			this.models = models;
		}
	}

	/** Which provider, address and key the check was for (see {@link #setupKey}). */
	final String setup;
	/** The chat models listed, or null while asking, after an error, or when the service has no list. */
	final List<String> models;
	/** Why it failed, or null. */
	final String error;

	private ConnectionCheck(String setup, List<String> models, String error)
	{
		this.setup = setup;
		this.models = models;
		this.error = error;
	}

	static ConnectionCheck testing(String setup)
	{
		return new ConnectionCheck(setup, null, null);
	}

	/** {@code sorted}: put them in alphabetical order (Anthropic's list comes newest first, which is better kept). */
	static ConnectionCheck listed(String setup, List<String> ids, boolean sorted)
	{
		return new ConnectionCheck(setup, chatModels(ids, sorted), null);
	}

	static ConnectionCheck failed(String setup, String error)
	{
		return new ConnectionCheck(setup, null, error);
	}

	/**
	 * Tells setups apart without keeping the key itself: a different provider, address or key makes an earlier check
	 * say nothing about this one.
	 */
	static String setupKey(AiChatConfig.Provider provider, String url, String key)
	{
		return provider + "\u0000" + (url == null ? "" : url.trim()) + "\u0000" + (key == null ? 0 : key.hashCode());
	}

	/** The ids worth offering as chat models, without duplicates. */
	static List<String> chatModels(List<String> ids, boolean sorted)
	{
		List<String> models = new ArrayList<>();
		for (String id : ids)
		{
			if (id != null && !id.trim().isEmpty() && !models.contains(id) && isChatModel(id))
			{
				models.add(id);
			}
		}
		if (sorted)
		{
			models.sort(String.CASE_INSENSITIVE_ORDER);
		}
		return models;
	}

	private static boolean isChatModel(String id)
	{
		String lower = id.toLowerCase(Locale.ROOT);
		for (String word : NOT_CHAT)
		{
			if (lower.contains(word))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * What the check says about {@code model} (blank when none is set yet). {@code service}: "Anthropic", "OpenAI", or
	 * the address of a compatible service; {@code keyed}: the player's own key decides which models they get (Claude,
	 * ChatGPT); {@code keyUnchecked}: a key went to a service whose list may not need one, so the list says nothing
	 * about it.
	 */
	Note note(String service, String model, boolean keyed, boolean keyUnchecked)
	{
		if (error != null)
		{
			if (OpenAiApi.NO_MODEL_LIST.equals(error))
			{
				// Any web server answers "not found", so this says little: not even that the URL is right. With no model
				// set, there's none to choose here either: say where it goes, as the setup help did before the Test.
				if (model == null || model.trim().isEmpty())
				{
					return new Note(Kind.WARNING, service + " answered, but not with a list of models, so Test can't "
						+ "check the URL or offer a model. Set the model in the OpenAI-compatible section of the AI Chat "
						+ "settings, with its name from the service's website.", Collections.emptyList());
				}
				return new Note(Kind.WARNING, service + " answered, but not with a list of models, so Test can't check "
					+ "the URL or the model name. Check both on the service's website.", Collections.emptyList());
			}
			return new Note(Kind.ERROR, error, Collections.emptyList());
		}
		if (models == null)
		{
			return new Note(Kind.TESTING, "Testing the connection...", Collections.emptyList());
		}
		if (models.isEmpty())
		{
			return new Note(Kind.WARNING, "Connected to " + service + ", but it listed no models to chat with.", models);
		}
		// OpenRouter lists its models for anyone: a wrong key there only shows when a message is sent.
		String key = keyUnchecked ? " Your key is checked when you send: some services list their models for anyone." : "";
		if (model == null || model.trim().isEmpty())
		{
			return new Note(Kind.OK, "Connected to " + service + "." + key + " Choose a model:", models);
		}
		if (has(models, model))
		{
			return new Note(Kind.OK, "Connected to " + service + ". " + model + " is available." + key, models);
		}
		return new Note(Kind.WARNING, keyed ? "Connected, but " + model + " isn't in the list your key can use."
			: "Connected to " + service + ", but " + model + " isn't one of its models.", models);
	}

	/**
	 * Whether {@code model} is one of {@code models}, as named there: Ollama lists "llama3.2:latest" for the model asked
	 * for as "llama3.2", and Anthropic lists an alias such as "claude-haiku-4-5" as the dated model it points to,
	 * "claude-haiku-4-5-20251001".
	 */
	private static boolean has(List<String> models, String model)
	{
		if (models.contains(model) || !model.contains(":") && models.contains(model + ":latest"))
		{
			return true;
		}
		for (String id : models)
		{
			if (id.length() == model.length() + 9 && id.startsWith(model + "-") && id.substring(model.length() + 1).matches("\\d{8}"))
			{
				return true;
			}
		}
		return false;
	}
}
