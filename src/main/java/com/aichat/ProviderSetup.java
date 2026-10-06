package com.aichat;

import com.google.gson.Gson;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * The chosen provider as the settings describe it: what's still missing, what answers new messages, and the API to
 * send with. Reads the settings each time it's asked, so it's always the current choice. Any thread.
 */
final class ProviderSetup
{
	static final String BAD_KEY = "Your API key has a character that can't be sent, like a curly quote copied along "
		+ "with it. Paste it into the AI Chat settings again.";

	private final AiChatConfig config;

	ProviderSetup(AiChatConfig config)
	{
		this.config = config;
	}

	/** What's missing before messages can be sent, or null when the chosen provider is set up. */
	String problem()
	{
		String connection = connectionProblem();
		if (connection != null || !blank(model()))
		{
			return connection;
		}
		switch (config.provider())
		{
			case CLAUDE:
				return "Set a Claude model in the Claude section of the AI Chat settings, or press Test to choose one.";
			case CHATGPT:
				return "Set a ChatGPT model in the ChatGPT section of the AI Chat settings, or press Test to choose one.";
			default:
				return "Set the model in the OpenAI-compatible section of the AI Chat settings, or press Test to choose one.";
		}
	}

	/**
	 * What's missing before the provider can be reached, or null: {@link #problem()} without the model, which "Test"
	 * doesn't need (it's how a player finds one).
	 */
	String connectionProblem()
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
				return ChatApi.sendableKey(key) ? null : BAD_KEY;
			}
			case CHATGPT:
			{
				String key = ChatApi.cleanKey(config.openaiApiKey());
				if (key.isEmpty())
				{
					return "Add your OpenAI API key in the ChatGPT section of the AI Chat settings (from platform.openai.com).";
				}
				return ChatApi.sendableKey(key) ? null : BAD_KEY;
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
				return null;
			}
		}
	}

	/** "Claude · claude-opus-5-5": what answers new messages. */
	String summary()
	{
		switch (config.provider())
		{
			case CLAUDE:
				return "Claude · " + model();
			case CHATGPT:
				return "ChatGPT · " + model();
			default:
				return model() + " · " + OpenAiApi.describeUrl(config.compatibleUrl());
		}
	}

	/**
	 * The provider's API, with the player's key; null while something's missing (see {@link #problem()}).
	 * {@code refused}: optional request settings services and models have refused, shared while the plugin runs.
	 */
	ChatApi api(OkHttpClient http, Gson gson, ScheduledExecutorService scheduler, Map<String, Set<String>> refused)
	{
		return problem() != null ? null : build(http, gson, scheduler, refused);
	}

	/**
	 * The provider's API for "Test", which needs no model yet; null while it can't be reached (see
	 * {@link #connectionProblem()}).
	 */
	ChatApi testApi(OkHttpClient http, Gson gson, ScheduledExecutorService scheduler, Map<String, Set<String>> refused)
	{
		return connectionProblem() != null ? null : build(http, gson, scheduler, refused);
	}

	private ChatApi build(OkHttpClient http, Gson gson, ScheduledExecutorService scheduler, Map<String, Set<String>> refused)
	{
		switch (config.provider())
		{
			case CLAUDE:
				return new AnthropicApi(http, gson, AnthropicApi.URL, ChatApi.cleanKey(config.claudeApiKey()), scheduler, refused);
			case CHATGPT:
				return new OpenAiApi(http, gson, OpenAiApi.OPENAI_URL, ChatApi.cleanKey(config.openaiApiKey()), "ChatGPT", true,
					"low", scheduler, refused);
			default:
				return new OpenAiApi(http, gson, OpenAiApi.parseBaseUrl(config.compatibleUrl()),
					ChatApi.cleanKey(config.compatibleApiKey()), model(), false, config.compatibleThinking().effort, scheduler, refused);
		}
	}

	String model()
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

	/** The setting {@link #model()} comes from, for "Choose model". */
	String modelKey()
	{
		switch (config.provider())
		{
			case CLAUDE:
				return "claudeModel";
			case CHATGPT:
				return "openaiModel";
			default:
				return "compatibleModel";
		}
	}

	/** Who "Test" talks to, for its note: "Anthropic", "OpenAI", or a compatible service's address. */
	String service()
	{
		switch (config.provider())
		{
			case CLAUDE:
				return "Anthropic";
			case CHATGPT:
				return "OpenAI";
			default:
				return OpenAiApi.describeUrl(config.compatibleUrl());
		}
	}

	/** Whether the player's key decides which models they can use (not so for a compatible service's list). */
	boolean keyed()
	{
		return config.provider() != AiChatConfig.Provider.OPENAI_COMPATIBLE;
	}

	/**
	 * Whether "Test" can't vouch for the key: a compatible service may list its models for anyone (OpenRouter does),
	 * so a wrong key there only shows when a message is sent.
	 */
	boolean keyUnchecked()
	{
		return !keyed() && !ChatApi.cleanKey(config.compatibleApiKey()).isEmpty();
	}

	/** The provider, address and key: what a "Test" result holds for (see {@link ConnectionCheck#setupKey}). */
	String connection()
	{
		switch (config.provider())
		{
			case CLAUDE:
				return ConnectionCheck.setupKey(config.provider(), null, ChatApi.cleanKey(config.claudeApiKey()));
			case CHATGPT:
				return ConnectionCheck.setupKey(config.provider(), null, ChatApi.cleanKey(config.openaiApiKey()));
			default:
				return ConnectionCheck.setupKey(config.provider(), config.compatibleUrl(), ChatApi.cleanKey(config.compatibleApiKey()));
		}
	}

	private static boolean blank(String s)
	{
		return s == null || s.trim().isEmpty();
	}
}
