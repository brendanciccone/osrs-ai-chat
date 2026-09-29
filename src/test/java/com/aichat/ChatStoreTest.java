package com.aichat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** What "Remember chats" keeps, and how it comes back. */
public class ChatStoreTest
{
	private final Gson gson = new Gson();

	@Test
	public void chatsComeBackAsTheyWere()
	{
		Chat first = new Chat("Agility");
		first.defaultName = false;
		Chat.Message q = new Chat.Message(Chat.Role.USER, "best way to train Agility at 50?");
		q.context = "[Character: Zezima, combat level 3]";
		first.messages.add(q);
		Chat.Message a = new Chat.Message(Chat.Role.ASSISTANT, "Rooftop courses.");
		a.who = "Claude";
		a.rawContent = gson.fromJson("[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s\"}]", JsonArray.class);
		a.rawModel = "claude-opus-5-5";
		first.messages.add(a);
		Chat second = new Chat("Named");
		second.namedByPlayer = true;

		String json = ChatStore.toJson(gson, List.of(first, second), second);
		ChatStore.Loaded loaded = ChatStore.fromJson(gson, json);

		assertEquals(2, loaded.chats.size());
		assertSame("the chat that was open", loaded.chats.get(1), loaded.current);
		Chat back = loaded.chats.get(0);
		assertEquals(first.id, back.id);
		assertEquals("Agility", back.name);
		assertFalse(back.defaultName);
		assertTrue(loaded.chats.get(1).namedByPlayer);
		assertEquals(2, back.messages.size());
		assertEquals(Chat.Role.USER, back.messages.get(0).role);
		assertEquals(q.time, back.messages.get(0).time);
		assertEquals("[Character: Zezima, combat level 3]", back.messages.get(0).context);
		assertEquals("Claude", back.messages.get(1).who);
		// Claude's replayable reasoning isn't saved; the chat carries on as plain text.
		assertNull(back.messages.get(1).rawContent);
		assertFalse(json.contains("signature"));
	}

	@Test
	public void aQuestionWithoutAnAnswerIsMarkedSo()
	{
		Chat c = new Chat("x");
		c.messages.add(new Chat.Message(Chat.Role.USER, "still waiting when RuneLite closed"));
		c.pending = new ChatApi.Pending();
		Chat back = ChatStore.fromJson(gson, ChatStore.toJson(gson, List.of(c), c)).chats.get(0);
		assertTrue(back.messages.get(0).unanswered);
		assertFalse(back.isRunning());
	}

	@Test
	public void longChatsKeepTheirLatestMessages()
	{
		Chat c = new Chat("long");
		for (int i = 0; i < 250; i++)
		{
			c.messages.add(new Chat.Message(i % 2 == 0 ? Chat.Role.USER : Chat.Role.ASSISTANT, "m" + i));
		}
		Chat back = ChatStore.fromJson(gson, ChatStore.toJson(gson, List.of(c), c)).chats.get(0);
		assertEquals(ChatStore.MAX_MESSAGES, back.messages.size());
		assertEquals("m249", back.messages.get(back.messages.size() - 1).text);
	}

	@Test
	public void aDamagedFileIsReportedAndOddEntriesAreSkipped()
	{
		// Damaged: reported as such (the plugin moves the file aside rather than writing over it).
		assertNull(ChatStore.fromJson(gson, "not json {"));
		assertNull(ChatStore.fromJson(gson, ""));
		assertNull(ChatStore.fromJson(gson, "{\"something\":\"else\"}"));
		ChatStore.Loaded odd = ChatStore.fromJson(gson,
			"{\"chats\":[{\"id\":\"1\",\"name\":\"ok\",\"messages\":[{\"role\":\"WHAT\",\"text\":\"skipped\"},{\"role\":\"NOTE\",\"text\":\"kept\"}]},{\"name\":\"no id\"}]}");
		assertEquals(1, odd.chats.size());
		assertEquals(1, odd.chats.get(0).messages.size());
		assertEquals("kept", odd.chats.get(0).messages.get(0).text);
		assertNull(odd.current);
	}
}
