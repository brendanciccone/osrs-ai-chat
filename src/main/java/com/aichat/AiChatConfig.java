package com.aichat;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Notification;
import net.runelite.client.config.Range;

@ConfigGroup(AiChatConfig.GROUP)
public interface AiChatConfig extends Config
{
	String GROUP = "osrs-ai-chat";

	enum Provider
	{
		CLAUDE("Claude"),
		CHATGPT("ChatGPT"),
		OPENAI_COMPATIBLE("OpenAI-compatible");

		private final String label;

		Provider(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	// RuneLite's settings can't show or hide settings based on another one, so each provider has its own section:
	// open the one you picked. Claude's starts open, being the default.

	enum Thinking
	{
		SHORT("Short (faster)", "low"),
		DEFAULT("Model default", null);

		private final String label;
		/** What's sent as reasoning_effort; null sends nothing. */
		final String effort;

		Thinking(String label, String effort)
		{
			this.label = label;
			this.effort = effort;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigSection(
		name = "General",
		description = "Turn AI requests on, choose who answers, and add your own instructions",
		position = 0
	)
	String GENERAL = "assistant";

	@ConfigSection(
		name = "Claude",
		description = "Settings for the Claude provider",
		position = 1
	)
	String CLAUDE_SECTION = "claude";

	@ConfigSection(
		name = "ChatGPT",
		description = "Settings for the ChatGPT provider",
		position = 2,
		closedByDefault = true
	)
	String CHATGPT_SECTION = "chatgpt";

	@ConfigSection(
		name = "Other (OpenAI-compatible)",
		description = "Settings for an OpenAI-compatible service: OpenRouter, Groq, Ollama, LM Studio, Unsloth...",
		position = 3,
		closedByDefault = true
	)
	String COMPATIBLE_SECTION = "compatible";

	@ConfigSection(
		name = "Data & privacy",
		description = "What the assistant may see of your account, and whether your chats are saved",
		position = 4
	)
	String PRIVACY = "character";

	@ConfigSection(
		name = "Notifications & game chat",
		description = "How replies show up in game",
		position = 5
	)
	String GAME_CHAT = "chat";

	// General

	@ConfigItem(
		keyName = "aiRequests",
		name = "Enable AI requests",
		description = "Send your messages to the AI provider you choose. Nothing is sent anywhere while this is off, Wiki look-ups included.",
		warning = "This feature submits your IP address and the messages you type to the AI provider you choose, and (with \"Wiki look-ups\", on by default) the search words and page titles the assistant looks up to the OSRS Wiki: 3rd-party servers not controlled or verified by RuneLite developers.",
		section = GENERAL,
		position = 0
	)
	default boolean aiRequests()
	{
		return false;
	}

	@ConfigItem(
		keyName = "provider",
		name = "Provider",
		description = "Claude (Anthropic), ChatGPT (OpenAI), or any service with an OpenAI-compatible API, such as OpenRouter, Groq, or a model on your own computer with Ollama, LM Studio or Unsloth. Then fill in its section below.",
		section = GENERAL,
		position = 1
	)
	default Provider provider()
	{
		return Provider.CLAUDE;
	}

	@ConfigItem(
		keyName = "instructions",
		name = "Custom instructions",
		description = "Added to what the assistant is told before every chat, for example \"I'm an ironman\" or \"Answer in Dutch\".",
		section = GENERAL,
		position = 2
	)
	default String instructions()
	{
		return "";
	}

	@ConfigItem(
		keyName = "wikiLookups",
		name = "Wiki look-ups",
		description = "Let the assistant search and read the OSRS Wiki while it answers, and link the pages it used. The Wiki gets the search words and page titles, and your IP address. AI Chat adds nothing about your account, but the assistant writes the search words from your question and anything you've shared. GE prices come from RuneLite's own price data and stay available with this off: they never go to the Wiki.",
		section = GENERAL,
		position = 3
	)
	default boolean wikiLookups()
	{
		return true;
	}

	// Claude

	@ConfigItem(
		keyName = "claudeApiKey",
		name = "API key",
		description = "Your Claude API key, from console.anthropic.com (billed per use by Anthropic; a Claude subscription doesn't include API access). Only sent to Anthropic.",
		secret = true,
		section = CLAUDE_SECTION,
		position = 0
	)
	default String claudeApiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "claudeModel",
		name = "Model",
		description = "For example claude-opus-5-5, claude-sonnet-5-5 or claude-haiku-4-5. You can also pick a model in the AI Chat panel, next to Send.",
		section = CLAUDE_SECTION,
		position = 1
	)
	default String claudeModel()
	{
		return "claude-opus-5-5";
	}

	// ChatGPT

	@ConfigItem(
		keyName = "openaiApiKey",
		name = "API key",
		description = "An OpenAI API key from platform.openai.com (billed per use by OpenAI; a ChatGPT subscription doesn't include API access). Only sent to OpenAI.",
		secret = true,
		section = CHATGPT_SECTION,
		position = 0
	)
	default String openaiApiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "openaiModel",
		name = "Model",
		description = "For example gpt-6-luna (fast and cheap) or gpt-6.1-sol. You can also pick a model in the AI Chat panel, next to Send.",
		section = CHATGPT_SECTION,
		position = 1
	)
	default String openaiModel()
	{
		return OpenAiApi.DEFAULT_MODEL;
	}

	// Other (OpenAI-compatible)

	@ConfigItem(
		keyName = "compatibleUrl",
		name = "Base URL",
		description = "The service's base URL, usually ending in /v1. For example http://localhost:11434/v1 (Ollama), http://localhost:1234/v1 (LM Studio), http://127.0.0.1:8888/v1 (Unsloth) or https://openrouter.ai/api/v1.",
		section = COMPATIBLE_SECTION,
		position = 0
	)
	default String compatibleUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "compatibleApiKey",
		name = "API key",
		description = "The service's API key, if it needs one (Ollama and LM Studio don't). Only sent to the Base URL.",
		secret = true,
		section = COMPATIBLE_SECTION,
		position = 1
	)
	default String compatibleApiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "compatibleModel",
		name = "Model",
		description = "The model name the service expects, for example llama3.2 for Ollama. You can also pick a model in the AI Chat panel, next to Send.",
		section = COMPATIBLE_SECTION,
		position = 2
	)
	default String compatibleModel()
	{
		return "";
	}

	@ConfigItem(
		keyName = "compatibleThinking",
		name = "Thinking",
		description = "How long a reasoning model thinks before answering. Short makes most thinking models answer much faster. Model default leaves it to the service; pick it if a model that doesn't normally think starts to.",
		section = COMPATIBLE_SECTION,
		position = 3
	)
	default Thinking compatibleThinking()
	{
		return Thinking.SHORT;
	}

	// Data & privacy

	@ConfigItem(
		keyName = "sendCharacter",
		name = "Share character details",
		description = "Send your character name, account type (ironman or not), levels and quest progress with your messages, and let the assistant look up your Slayer task and achievement diaries, for answers that fit your account. Off by default.",
		warning = "This feature submits your IP address, character name, account type, skill levels, quest progress, Slayer task and achievement diaries with your messages to the AI provider you choose: a 3rd-party server not controlled or verified by RuneLite developers.",
		section = PRIVACY,
		position = 0
	)
	default boolean sendCharacter()
	{
		return false;
	}

	@ConfigItem(
		keyName = "shareItems",
		name = "Share items and gear",
		description = "Let the assistant look at your equipment, inventory and bank (as it was when you last had it open) when a question needs them. Each look is listed with the reply. Off by default.",
		warning = "This feature submits your IP address and the items you wear, carry and keep in your bank, with their prices, to the AI provider you choose when the assistant looks at them: a 3rd-party server not controlled or verified by RuneLite developers.",
		section = PRIVACY,
		position = 1
	)
	default boolean shareItems()
	{
		return false;
	}

	@ConfigItem(
		keyName = "rememberChats",
		name = "Save chat history",
		description = "Keep your chats when RuneLite closes, saved on this computer in .runelite/plugin-data/osrs-ai-chat (the latest 200 messages of each, and older ones still sent to the assistant). Turning this off deletes the saved copy; chats then last only while RuneLite is open.",
		section = PRIVACY,
		position = 2
	)
	default boolean rememberChats()
	{
		return true;
	}

	// Notifications & game chat

	@ConfigItem(
		keyName = "notifyOnDone",
		name = "Notify on reply",
		description = "Notification when a reply arrives. RuneLite skips notifications while the game is focused unless you enable that here.",
		section = GAME_CHAT,
		position = 0
	)
	default Notification notifyOnDone()
	{
		return Notification.ON;
	}

	@ConfigItem(
		keyName = "echoToChat",
		name = "Show replies in game chat",
		description = "Print replies in the chatbox, not only in the side panel.",
		section = GAME_CHAT,
		position = 1
	)
	default boolean echoToChat()
	{
		return true;
	}

	@Range(min = 50, max = 4000)
	@ConfigItem(
		keyName = "echoMaxChars",
		name = "Game chat reply length",
		description = "Longer replies are cut short in the chatbox; the side panel always has the full text.",
		section = GAME_CHAT,
		position = 2
	)
	default int echoMaxChars()
	{
		return 500;
	}

	@ConfigItem(
		keyName = "askHotkey",
		name = "Ask hotkey",
		description = "Opens an 'Ask:' prompt in the chatbox.",
		section = GAME_CHAT,
		position = 3
	)
	default Keybind askHotkey()
	{
		return Keybind.NOT_SET;
	}
}
