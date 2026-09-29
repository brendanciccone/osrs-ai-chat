package com.aichat;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * What's kept of the chats between RuneLite sessions, when "Remember chats" is on: names and messages, nothing else.
 * API keys aren't part of a chat, and Claude's replayable reasoning stays in memory only. Converting happens on the
 * Swing EDT (where chats live); reading and writing the file happen elsewhere.
 */
final class ChatStore
{
	static final String FILE_NAME = "chats.json";
	/** Older messages of a long chat aren't kept. */
	static final int MAX_MESSAGES = 200;

	private ChatStore()
	{
	}

	static final class Saved
	{
		int version = 1;
		String current;
		/** Null in a file that isn't ours. */
		List<SavedChat> chats;
	}

	static final class SavedChat
	{
		String id;
		String name;
		boolean defaultName;
		boolean namedByPlayer;
		List<SavedMessage> messages = new ArrayList<>();
	}

	static final class SavedMessage
	{
		String role;
		String text;
		long time;
		String who;
		String context;
		boolean unanswered;
	}

	/** EDT. */
	static String toJson(Gson gson, List<Chat> chats, Chat current)
	{
		Saved saved = new Saved();
		saved.current = current == null ? null : current.id;
		saved.chats = new ArrayList<>();
		for (Chat c : chats)
		{
			SavedChat sc = new SavedChat();
			sc.id = c.id;
			sc.name = c.name;
			sc.defaultName = c.defaultName;
			sc.namedByPlayer = c.namedByPlayer;
			List<Chat.Message> messages = c.messages.subList(Math.max(0, c.messages.size() - MAX_MESSAGES), c.messages.size());
			for (Chat.Message m : messages)
			{
				SavedMessage sm = new SavedMessage();
				sm.role = m.role.name();
				sm.text = m.text;
				sm.time = m.time;
				sm.who = m.who;
				sm.context = m.context;
				// A question still waiting when RuneLite closes won't be answered.
				sm.unanswered = m.unanswered || c.isRunning() && m.role == Chat.Role.USER && m == lastUserMessage(c);
				sc.messages.add(sm);
			}
			saved.chats.add(sc);
		}
		return gson.toJson(saved);
	}

	static final class Loaded
	{
		final List<Chat> chats = new ArrayList<>();
		/** The chat that was open, if it's among them. */
		Chat current;
	}

	/** The chats in a saved file, in their order, or null if the file is damaged (not JSON, or not ours). */
	static Loaded fromJson(Gson gson, String json)
	{
		Loaded loaded = new Loaded();
		Saved saved;
		try
		{
			saved = gson.fromJson(json, Saved.class);
		}
		catch (JsonParseException | IllegalStateException e)
		{
			return null;
		}
		if (saved == null || saved.chats == null)
		{
			return null;
		}
		for (SavedChat sc : saved.chats)
		{
			if (sc == null || sc.id == null || sc.name == null)
			{
				continue;
			}
			Chat c = new Chat(sc.id, sc.name);
			c.defaultName = sc.defaultName;
			c.namedByPlayer = sc.namedByPlayer;
			if (sc.messages != null)
			{
				for (SavedMessage sm : sc.messages)
				{
					Chat.Role role = role(sm == null ? null : sm.role);
					if (role == null || sm.text == null)
					{
						continue;
					}
					Chat.Message m = new Chat.Message(role, sm.text, sm.time);
					m.who = sm.who;
					m.context = sm.context;
					m.unanswered = sm.unanswered;
					c.messages.add(m);
				}
			}
			// The last question never got its answer if nothing came after it.
			if (!c.messages.isEmpty() && c.messages.get(c.messages.size() - 1).role == Chat.Role.USER)
			{
				c.messages.get(c.messages.size() - 1).unanswered = true;
			}
			loaded.chats.add(c);
			if (sc.id.equals(saved.current))
			{
				loaded.current = c;
			}
		}
		return loaded;
	}

	private static Chat.Role role(String name)
	{
		if (name == null)
		{
			return null;
		}
		for (Chat.Role r : Chat.Role.values())
		{
			if (r.name().equals(name))
			{
				return r;
			}
		}
		return null;
	}

	private static Chat.Message lastUserMessage(Chat c)
	{
		for (int i = c.messages.size() - 1; i >= 0; i--)
		{
			if (c.messages.get(i).role == Chat.Role.USER)
			{
				return c.messages.get(i);
			}
		}
		return null;
	}
}
