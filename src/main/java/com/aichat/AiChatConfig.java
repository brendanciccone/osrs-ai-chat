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
		name = "Assistant",
		description = "Turn AI requests on, choose which AI answers, and add your own instructions",
		position = 0
	)
	String ASSISTANT = "assistant";

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
		name = "OpenAI-compatible",
		description = "Settings for an OpenAI-compatible service: OpenRouter, Groq, Ollama, LM Studio, Unsloth...",
		position = 3,
		closedByDefault = true
	)
	String COMPATIBLE_SECTION = "compatible";

	@ConfigSection(
		name = "Your character",
		description = "Optionally let the assistant know your character, and see your items and gear",
		position = 4
	)
	String CHARACTER = "character";

	@ConfigSection(
		name = "Chat and notifications",
		description = "How replies show up in game",
		position = 5
	)
	String CHAT = "chat";

	@ConfigItem(
		keyName = "aiRequests",
		name = "Enable AI requests",
		description = "Send your messages to the AI provider you choose. Nothing is sent anywhere while this is off, Wiki look-ups included.",
		warning = "This feature submits your IP address and the messages you type to the AI provider you choose, and (with \"Wiki look-ups\", on by default) the search words and page titles the assistant looks up to the OSRS Wiki: 3rd-party servers not controlled or verified by RuneLite developers.",
		section = ASSISTANT,
		position = 0
	)
	default boolean aiRequests()
	{
		return false;
	}

	@ConfigItem(
		keyName = "provider",
		name = "AI provider",
		description = "Claude (Anthropic), ChatGPT (OpenAI), or any service with an OpenAI-compatible API, such as OpenRouter, Groq, or a model on your own computer with Ollama, LM Studio or Unsloth. Then fill in its section below.",
		section = ASSISTANT,
		position = 1
	)
	default Provider provider()
	{
		return Provider.CLAUDE;
	}

	@ConfigItem(
		keyName = "claudeApiKey",
		name = "Claude API key",
		description = "From console.anthropic.com (billed per use by Anthropic; a Claude subscription doesn't include API access). Only sent to Anthropic.",
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
		name = "Claude model",
		description = "For example claude-opus-5-5, claude-sonnet-5-5 or claude-haiku-4-5.",
		section = CLAUDE_SECTION,
		position = 1
	)
	default String claudeModel()
	{
		return "claude-opus-5-5";
	}

	@ConfigItem(
		keyName = "openaiApiKey",
		name = "ChatGPT API key",
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
		name = "ChatGPT model",
		description = "For example gpt-6-luna (fast and cheap) or gpt-6.1-sol.",
		section = CHATGPT_SECTION,
		position = 1
	)
	default String openaiModel()
	{
		return OpenAiApi.DEFAULT_MODEL;
	}

	@ConfigItem(
		keyName = "compatibleUrl",
		name = "Compatible API URL",
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
		name = "Compatible API key",
		description = "The service's API key, if it needs one (Ollama and LM Studio don't). Only sent to that URL.",
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
		name = "Compatible model",
		description = "The model name the service expects, for example llama3.2 for Ollama.",
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

	@ConfigItem(
		keyName = "instructions",
		name = "Extra instructions",
		description = "Added to what the assistant is told before every chat, for example \"I'm an ironman\" or \"Answer in Dutch\".",
		section = ASSISTANT,
		position = 2
	)
	default String instructions()
	{
		return "";
	}

	@ConfigItem(
		keyName = "wikiLookups",
		name = "Wiki look-ups",
		description = "Let the assistant search and read the OSRS Wiki while it answers, and link the pages it used. The Wiki gets the search words and page titles, and your IP address; nothing about your account.",
		section = ASSISTANT,
		position = 3
	)
	default boolean wikiLookups()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendCharacter",
		name = "Send character info",
		description = "Send your character name, account type (ironman or not), levels and quest progress with your messages, and let the assistant look up your Slayer task and achievement diaries, for answers that fit your account. Off by default.",
		warning = "This feature submits your IP address, character name, account type, skill levels, quest progress, Slayer task and achievement diaries with your messages to the AI provider you choose: a 3rd-party server not controlled or verified by RuneLite developers.",
		section = CHARACTER,
		position = 0
	)
	default boolean sendCharacter()
	{
		return false;
	}

	@ConfigItem(
		keyName = "shareItems",
		name = "Share items and gear",
		description = "Let the assistant look at your equipment, inventory and bank (as it was when you last had it open) when a question needs them. Each look is listed under the reply. Off by default.",
		warning = "This feature submits your IP address and the items you wear, carry and keep in your bank, with their prices, to the AI provider you choose when the assistant looks at them: a 3rd-party server not controlled or verified by RuneLite developers.",
		section = CHARACTER,
		position = 1
	)
	default boolean shareItems()
	{
		return false;
	}

	@ConfigItem(
		keyName = "notifyOnDone",
		name = "Notify on reply",
		description = "Notification when a reply arrives. RuneLite skips notifications while the game is focused unless you enable that here.",
		section = CHAT,
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
		section = CHAT,
		position = 1
	)
	default boolean echoToChat()
	{
		return true;
	}

	@Range(min = 50, max = 4000)
	@ConfigItem(
		keyName = "echoMaxChars",
		name = "Chat reply length",
		description = "Longer replies are cut short in the chatbox; the side panel always has the full text.",
		section = CHAT,
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
		section = CHAT,
		position = 3
	)
	default Keybind askHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "rememberChats",
		name = "Remember chats",
		description = "Keep your chats when RuneLite closes, saved on this computer in .runelite/plugin-data/osrs-ai-chat (the latest 200 messages of each, and older ones still sent to the assistant). Turning this off deletes the saved copy; chats then last only while RuneLite is open.",
		section = CHAT,
		position = 4
	)
	default boolean rememberChats()
	{
		return true;
	}
}
