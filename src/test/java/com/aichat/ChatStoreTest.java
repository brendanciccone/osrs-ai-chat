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

	@Test
	public void summariesAreRemembered()
	{
		Chat c = new Chat("long");
		Chat.Message q1 = new Chat.Message(Chat.Role.USER, "Q1");
		q1.summarized = true;
		c.messages.add(q1);
		Chat.Message a1 = new Chat.Message(Chat.Role.ASSISTANT, "A1");
		a1.summarized = true;
		c.messages.add(a1);
		c.messages.add(new Chat.Message(Chat.Role.NOTE, "Summary of the 2 earlier messages, sent instead of them:\n\nS"));
		c.messages.add(new Chat.Message(Chat.Role.USER, "Q2"));
		c.messages.add(new Chat.Message(Chat.Role.ASSISTANT, "A2"));
		c.summary = "S";
		c.summaryVersion = 3;

		Chat back = ChatStore.fromJson(gson, ChatStore.toJson(gson, List.of(c), c)).chats.get(0);
		assertEquals("S", back.summary);
		assertEquals(3, back.summaryVersion);
		assertEquals(5, back.messages.size());
		assertTrue(back.messages.get(0).summarized);
		assertTrue(back.messages.get(1).summarized);
		assertFalse(back.messages.get(3).summarized);
		assertFalse(back.messages.get(4).summarized);
		// Sent as before: the summary, then what it doesn't cover.
		List<ChatApi.Turn> turns = ConversationBuilder.conversation(back, back.messages.get(3), "m", "s", false, null).turns;
		assertEquals(2, turns.size());
		assertEquals("[Summary of earlier messages in this chat: S]\n\nQ2", turns.get(0).text);
	}

	@Test
	public void filesFromBeforeSummariesStillLoad()
	{
		// As AI Chat 0.1 wrote it.
		String old = "{\"version\":1,\"current\":\"a\",\"chats\":[{\"id\":\"a\",\"name\":\"Old\",\"defaultName\":false,"
			+ "\"namedByPlayer\":false,\"messages\":[{\"role\":\"USER\",\"text\":\"hi\",\"time\":1,\"unanswered\":false},"
			+ "{\"role\":\"ASSISTANT\",\"text\":\"hello\",\"time\":2,\"who\":\"Claude\",\"unanswered\":false}]}]}";
		ChatStore.Loaded loaded = ChatStore.fromJson(gson, old);
		Chat back = loaded.chats.get(0);
		assertSame(back, loaded.current);
		assertNull(back.summary);
		assertEquals(0, back.summaryVersion);
		assertEquals(2, back.messages.size());
		assertFalse(back.messages.get(0).summarized);
		assertEquals(2, ConversationBuilder.history(back).size());
	}

	@Test
	public void summarisedMessagesWithoutTheirSummaryAreSentAgain()
	{
		// Not something AI Chat writes, but a hand-edited file shouldn't make messages disappear from the conversation.
		ChatStore.Loaded loaded = ChatStore.fromJson(gson, "{\"chats\":[{\"id\":\"a\",\"name\":\"x\",\"summary\":\" \","
			+ "\"messages\":[{\"role\":\"USER\",\"text\":\"Q1\",\"summarized\":true},{\"role\":\"ASSISTANT\",\"text\":\"A1\",\"summarized\":true}]}]}");
		Chat back = loaded.chats.get(0);
		assertNull(back.summary);
		assertFalse(back.messages.get(0).summarized);
		assertEquals(2, ConversationBuilder.history(back).size());
	}
}
