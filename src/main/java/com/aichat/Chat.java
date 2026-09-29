package com.aichat;

import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One conversation in the panel. Kept in memory while the plugin runs. Only touched on the Swing EDT. */
class Chat
{
	enum Role
	{
		USER, ASSISTANT, NOTE, ERROR
	}

	static class Message
	{
		final Role role;
		final String text;
		final long time;
		/** Who wrote an ASSISTANT reply: "Claude", "ChatGPT", or the model of an OpenAI-compatible service. */
		String who;
		/** Character details sent along with this USER message, if any. Part of what the provider saw. */
		String context;
		/** A USER message whose request failed or was stopped. Left out of later requests. */
		boolean unanswered;
		/**
		 * Anthropic only: the reply's content blocks exactly as returned, with the model and system prompt that
		 * produced them. Claude expects them back unchanged on the next turn, as long as nothing before them changed.
		 */
		JsonArray rawContent;
		String rawModel;
		String rawSystem;

		Message(Role role, String text)
		{
			this(role, text, System.currentTimeMillis());
		}

		Message(Role role, String text, long time)
		{
			this.role = role;
			this.text = text;
			this.time = time;
		}

		String author()
		{
			switch (role)
			{
				case USER:
					return "You";
				case ASSISTANT:
					return who != null ? who : "Assistant";
				case ERROR:
					return "Error";
				default:
					return "Note";
			}
		}
	}

	final String id;
	String name;
	/** Whether {@link #name} was made up by us, so the first message can replace it with something better. */
	boolean defaultName = true;
	/** The player named this chat themselves, so the name can appear in notifications (it isn't their question). */
	boolean namedByPlayer;
	final List<Message> messages = new ArrayList<>();

	/** The request in flight, or null. */
	ChatApi.Pending pending;
	long runStartedAt;

	Chat(String name)
	{
		this(UUID.randomUUID().toString(), name);
	}

	/** A chat brought back from {@link ChatStore}. */
	Chat(String id, String name)
	{
		this.id = id;
		this.name = name;
	}

	boolean isRunning()
	{
		return pending != null;
	}
}
