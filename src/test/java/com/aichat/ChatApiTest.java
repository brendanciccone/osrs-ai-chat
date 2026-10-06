package com.aichat;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * What both providers share: keys, retries and tool rounds. The providers themselves are tested against a stand-in
 * server in {@link AnthropicApiTest} and {@link OpenAiApiTest}.
 */
public class ChatApiTest
{
	@Test
	public void pastedKeysAreCleanedAndChecked()
	{
		assertEquals("sk-ant-abc", ChatApi.cleanKey(" sk-ant-​abc \n"));
		assertTrue(ChatApi.sendableKey(ChatApi.cleanKey(" sk-ant-abc ")));
		assertFalse(ChatApi.sendableKey(ChatApi.cleanKey("sk-ant-abc…")));
		assertFalse(ChatApi.sendableKey(ChatApi.cleanKey("“sk-ant-abc”")));
	}

	@Test
	public void retriesWaitAsLongAsTheProviderAsks()
	{
		assertEquals(2000, ChatApi.retryDelay(null, null, 1));
		assertEquals(6000, ChatApi.retryDelay(null, null, 2));
		assertEquals(0, ChatApi.retryDelay(null, "0", 1));
		assertEquals(3000, ChatApi.retryDelay(null, "3", 1));
		assertEquals(1500, ChatApi.retryDelay(null, "1.5", 2));
		assertEquals("milliseconds first", 1250, ChatApi.retryDelay("1250", "30", 1));
		assertEquals(120_000, ChatApi.retryDelay(null, "120", 1));
		// A date can't be read here: the usual waits instead.
		assertEquals(2000, ChatApi.retryDelay(null, "Wed, 21 Oct 2026 07:28:00 GMT", 1));
		assertEquals(6000, ChatApi.retryDelay("soon", "-5", 2));
	}

	@Test
	public void onlyPassingProblemsAreRetried()
	{
		for (int code : new int[]{408, 409, 429, 500, 502, 503, 504, 529})
		{
			assertTrue(String.valueOf(code), ChatApi.retryableStatus(code, "{\"error\":{\"message\":\"busy\"}}"));
		}
		for (int code : new int[]{400, 401, 402, 403, 404, 413, 422})
		{
			assertFalse(String.valueOf(code), ChatApi.retryableStatus(code, "{}"));
		}
		assertFalse(ChatApi.retryableStatus(429, "{\"error\":{\"message\":\"You exceeded your current quota\",\"type\":\"insufficient_quota\"}}"));
		assertFalse(ChatApi.retryableStatus(429, "{\"error\":{\"message\":\"Your credit balance is too low\"}}"));
		assertFalse(ChatApi.retryableStatus(429, "{\"error\":{\"message\":\"Monthly spend limit reached\"}}"));
	}

	@Test
	public void waitsAreWrittenForPeople()
	{
		assertEquals(1, ChatApi.seconds(1));
		assertEquals(0, ChatApi.seconds(0));
		assertEquals(6, ChatApi.seconds(6000));
		assertEquals("1 second", ChatApi.waitText(1000));
		assertEquals("90 seconds", ChatApi.waitText(90_000));
		assertEquals("2 minutes", ChatApi.waitText(91_000));
		assertEquals("5 minutes", ChatApi.waitText(300_000));
		assertEquals("2 hours", ChatApi.waitText(7_200_000));
	}

	@Test
	public void thePromptKeyCoversModelInstructionsAndTools()
	{
		ChatApi.Conversation c = StandIn.conversation("claude-opus-5-5", "hi");
		String key = ChatApi.promptKey(c);
		c.turns.add(new ChatApi.Turn(false, "the turns don't count"));
		c.maxTokens = 100;
		assertEquals(key, ChatApi.promptKey(c));
		c.tools.add(StandIn.tool("wiki_search"));
		String withTool = ChatApi.promptKey(c);
		assertNotEquals(key, withTool);
		c.tools.clear();
		c.tools.add(StandIn.tool("ge_price"));
		assertNotEquals(withTool, ChatApi.promptKey(c));
		c.tools.clear();
		c.system = "Other";
		assertNotEquals(key, ChatApi.promptKey(c));
	}

	@Test
	public void toolsRunTogetherAndAnswerInOrderWhateverHappens() throws Exception
	{
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<List<ChatApi.ToolResult>> results = new AtomicReference<>();
		List<String> ran = new ArrayList<>();
		CountDownLatch secondIn = new CountDownLatch(1);
		ChatApi.ToolRunner runner = (name, input, answer) ->
		{
			ran.add(name);
			switch (name)
			{
				case "slow":
					// Answers on another thread, after the one asked for after it.
					new Thread(() ->
					{
						StandIn.await(secondIn);
						answer.accept(ChatApi.ToolResult.ok("slow " + input.get("n")));
					}).start();
					break;
				case "fast":
					answer.accept(ChatApi.ToolResult.ok("fast"));
					// A second answer is ignored.
					answer.accept(ChatApi.ToolResult.error("again"));
					secondIn.countDown();
					break;
				default:
					throw new IllegalStateException("broken tool");
			}
		};
		JsonObject n = new JsonObject();
		n.addProperty("n", 1);
		ChatApi.runTools(runner, Arrays.asList("slow", "fast", "broken", "unread"), Arrays.asList(n, new JsonObject(), new JsonObject(), null), r ->
		{
			results.set(r);
			done.countDown();
		});
		assertTrue(done.await(10, TimeUnit.SECONDS));
		List<ChatApi.ToolResult> r = results.get();
		assertEquals("unreadable arguments aren't run", List.of("slow", "fast", "broken"), ran);
		assertEquals("slow 1", r.get(0).content);
		assertEquals("fast", r.get(1).content);
		assertFalse(r.get(1).error);
		assertTrue(r.get(2).error);
		assertTrue(r.get(2).content.contains("broken"));
		assertTrue(r.get(3).error);
		assertTrue(r.get(3).content.startsWith("Arguments weren't valid JSON"));

		// No runner (a request without tools that got a call anyway), and no calls at all.
		AtomicReference<List<ChatApi.ToolResult>> none = new AtomicReference<>();
		ChatApi.runTools(null, List.of("wiki_search"), List.of(new JsonObject()), none::set);
		assertTrue(none.get().get(0).error);
		ChatApi.runTools(runner, List.of(), List.of(), none::set);
		assertTrue(none.get().isEmpty());
	}

	@Test
	public void roundsAreJoinedIntoOneReply()
	{
		assertEquals("Let me check.\n\nIt's 1.5m.", ChatApi.joinRounds(List.of(" Let me check. ", ""), "It's 1.5m.\n"));
		assertEquals("Only", ChatApi.joinRounds(List.of(), "Only"));
		assertEquals("", ChatApi.joinRounds(List.of(""), " "));
	}
}
