package com.aichat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static com.aichat.StandIn.conversation;
import static com.aichat.StandIn.events;
import static com.aichat.StandIn.json;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Claude against a stand-in for Anthropic's API on 127.0.0.1: what's sent, and how the streamed answers are read. */
public class AnthropicApiTest
{
	private static final String PATH = "/v1/messages";
	private final Gson gson = new Gson();
	private final OkHttpClient http = new OkHttpClient();
	private final Map<String, Set<String>> refused = new ConcurrentHashMap<>();
	private Scheduler scheduler;
	private StandIn server;

	/** The plugin's scheduler, with a look at the waits put on it. */
	static final class Scheduler extends ScheduledThreadPoolExecutor
	{
		final CountDownLatch scheduled = new CountDownLatch(1);
		volatile ScheduledFuture<?> last;

		Scheduler()
		{
			super(1);
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)
		{
			ScheduledFuture<?> f = super.schedule(command, delay, unit);
			last = f;
			scheduled.countDown();
			return f;
		}

		/** Whether the last wait was cancelled, waiting a moment for that to happen. */
		boolean lastCancelled() throws Exception
		{
			assertTrue("nothing was scheduled", scheduled.await(10, TimeUnit.SECONDS));
			try
			{
				last.get(5, TimeUnit.SECONDS);
				return false;
			}
			catch (CancellationException e)
			{
				return true;
			}
		}
	}

	@Before
	public void start() throws IOException
	{
		server = new StandIn();
		scheduler = new Scheduler();
	}

	@After
	public void stop()
	{
		server.stop();
		scheduler.shutdownNow();
	}

	private AnthropicApi api()
	{
		return new AnthropicApi(http, gson, server.url(PATH), "sk-test", scheduler, refused);
	}

	private static StandIn.Heard send(ChatApi api, ChatApi.Conversation c)
	{
		StandIn.Heard heard = new StandIn.Heard();
		api.send(c, heard);
		return heard;
	}

	// ------------------------------------------------------------------
	// Streamed answers, written out as Anthropic sends them
	// ------------------------------------------------------------------

	private static String event(String type, String data)
	{
		return "event: " + type + "\ndata: " + data + "\n\n";
	}

	private static String begin(String model)
	{
		return event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
			+ "\"model\":\"" + model + "\",\"content\":[],\"stop_reason\":null,\"usage\":{\"input_tokens\":10,"
			+ "\"cache_read_input_tokens\":200,\"cache_creation_input_tokens\":30,\"output_tokens\":1}}}");
	}

	private static String block(int index, String json)
	{
		return event("content_block_start", "{\"type\":\"content_block_start\",\"index\":" + index + ",\"content_block\":" + json + "}");
	}

	private static String delta(int index, String json)
	{
		return event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":" + index + ",\"delta\":" + json + "}");
	}

	private static String blockStop(int index)
	{
		return event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":" + index + "}");
	}

	private static String text(int index, String... pieces)
	{
		StringBuilder sb = new StringBuilder(block(index, "{\"type\":\"text\",\"text\":\"\"}"));
		for (String p : pieces)
		{
			JsonObject d = new JsonObject();
			d.addProperty("type", "text_delta");
			d.addProperty("text", p);
			sb.append(delta(index, d.toString()));
		}
		return sb.append(blockStop(index)).toString();
	}

	private static String thinking(int index, String thinking, String signature)
	{
		return block(index, "{\"type\":\"thinking\",\"thinking\":\"\"}")
			+ (thinking.isEmpty() ? "" : delta(index, "{\"type\":\"thinking_delta\",\"thinking\":\"" + thinking + "\"}"))
			+ delta(index, "{\"type\":\"signature_delta\",\"signature\":\"" + signature + "\"}")
			+ blockStop(index);
	}

	private static String toolUse(int index, String id, String name, String... jsonPieces)
	{
		StringBuilder sb = new StringBuilder(block(index, "{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"input\":{}}"));
		for (String p : jsonPieces)
		{
			JsonObject d = new JsonObject();
			d.addProperty("type", "input_json_delta");
			d.addProperty("partial_json", p);
			sb.append(delta(index, d.toString()));
		}
		return sb.append(blockStop(index)).toString();
	}

	private static String end(String stopReason, int outputTokens)
	{
		return event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null},"
			+ "\"usage\":{\"output_tokens\":" + outputTokens + "}}")
			+ event("message_stop", "{\"type\":\"message_stop\"}");
	}

	private static String ping()
	{
		return event("ping", "{\"type\": \"ping\"}");
	}

	private static String error(String type)
	{
		return event("error", "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"Overloaded\"}}");
	}

	private static StandIn.Answer answer(String text)
	{
		return events(begin("claude-opus-5-5"), text(0, text), end("end_turn", 5));
	}

	private static final String CLAUDE_OK = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
		+ "\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"},{\"type\":\"text\",\"text\":\"Train Agility.\"}],"
		+ "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";

	private JsonArray messages(int request)
	{
		return server.bodies.get(request).getAsJsonArray("messages");
	}

	private JsonObject message(int request, int index)
	{
		return messages(request).get(index).getAsJsonObject();
	}

	// ------------------------------------------------------------------
	// Tests
	// ------------------------------------------------------------------

	@Test
	public void streamsAReplyAndAsksForCachingAndFallback() throws Exception
	{
		server.answer(PATH, events(begin("claude-opus-5-5"), ping(), thinking(0, "Weighing options.", "sig-1"),
			text(1, "Train ", "Agility."), end("end_turn", 5)));
		ChatApi.Conversation c = conversation("claude-opus-5-5", "What should I train?");
		StandIn.Heard heard = send(api(), c);
		ChatApi.Reply reply = heard.reply();

		assertEquals("Train Agility.", reply.text);
		assertEquals("claude-opus-5-5", reply.model);
		assertFalse(reply.cutShort);
		assertFalse(reply.historyAsText);
		assertEquals(List.of("Train", "Train Agility."), heard.partials);
		// Claude's turn exactly as it was made, reasoning and its signature included, to go back next time.
		assertEquals(1, reply.rawMessages.size());
		assertEquals(gson.fromJson("{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"Weighing options.\","
			+ "\"signature\":\"sig-1\"},{\"type\":\"text\",\"text\":\"Train Agility.\"}]}", JsonObject.class), reply.rawMessages.get(0));
		assertEquals(ChatApi.promptKey(c), reply.rawKey);
		assertEquals(10, reply.usage.input);
		assertEquals(200, reply.usage.cacheRead);
		assertEquals(30, reply.usage.cacheWrite);
		assertEquals(5, reply.usage.output);

		JsonObject body = server.bodies.get(0);
		assertEquals("claude-opus-5-5", body.get("model").getAsString());
		assertEquals("Be brief.", body.get("system").getAsString());
		assertTrue(body.get("stream").getAsBoolean());
		assertEquals("ephemeral", body.getAsJsonObject("cache_control").get("type").getAsString());
		assertEquals("default", body.get("fallbacks").getAsString());
		assertEquals(16000, body.get("max_tokens").getAsInt());
		assertFalse("no tools, no tools field", body.has("tools"));
		assertEquals("What should I train?", message(0, 0).get("content").getAsString());
		assertEquals("sk-test", server.headers.get(0).getFirst("x-api-key"));
		assertEquals("2023-06-01", server.headers.get(0).getFirst("anthropic-version"));
		assertEquals("server-side-fallback-2026-07-01", server.headers.get(0).getFirst("anthropic-beta"));
	}

	@Test
	public void everyModelIsAskedForFallbackAndALowerLimitIsKept() throws Exception
	{
		server.answer(PATH, answer("Hi"));
		ChatApi.Conversation c = conversation("claude-haiku-4-5", "hi");
		c.maxTokens = 4000;
		send(api(), c).reply();
		assertEquals("default", server.bodies.get(0).get("fallbacks").getAsString());
		assertEquals("server-side-fallback-2026-07-01", server.headers.get(0).getFirst("anthropic-beta"));
		assertEquals(4000, server.bodies.get(0).get("max_tokens").getAsInt());
	}

	@Test
	public void earlierClaudeRepliesGoBackUnchangedOnlyUnderTheSamePrompt()
	{
		AnthropicApi api = api();
		ChatApi.Conversation c = conversation("claude-opus-5-5", "Q1", "A", "Q2");
		c.tools.add(StandIn.tool("wiki_search"));
		JsonArray raw = gson.fromJson("[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s\"},"
			+ "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"wiki_search\",\"input\":{\"query\":\"q\"}}]},"
			+ "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"r\"}]},"
			+ "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"A\"}]}]", JsonArray.class);
		ChatApi.Turn answer = c.turns.get(1);
		answer.rawMessages = raw;
		answer.rawKey = ChatApi.promptKey(c);

		JsonArray sent = api.body(c, true, true).getAsJsonArray("messages");
		assertEquals("Q1, the reply's three messages, Q2", 5, sent.size());
		for (int i = 0; i < 3; i++)
		{
			assertEquals(raw.get(i), sent.get(i + 1));
		}
		assertEquals("Q2", sent.get(4).getAsJsonObject().get("content").getAsString());
		assertEquals("wiki_search", api.body(c, true, true).getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString());
		assertTrue(api.body(c, true, true).getAsJsonArray("tools").get(0).getAsJsonObject().has("input_schema"));

		// Different instructions, a different model, different tools, or the retry without reasoning: text only.
		c.system = "Be very brief.";
		assertTextOnly(api.body(c, true, true));
		c.system = "Be brief.";
		c.model = "claude-sonnet-5-5";
		assertTextOnly(api.body(c, true, true));
		c.model = "claude-opus-5-5";
		c.tools.add(StandIn.tool("ge_price"));
		assertTextOnly(api.body(c, true, true));
		c.tools.remove(1);
		assertEquals(5, api.body(c, true, true).getAsJsonArray("messages").size());
		assertTextOnly(api.body(c, false, true));
	}

	private static void assertTextOnly(JsonObject body)
	{
		JsonArray messages = body.getAsJsonArray("messages");
		assertEquals(3, messages.size());
		assertEquals("A", messages.get(1).getAsJsonObject().get("content").getAsString());
	}

	@Test
	public void claudeRetriesOnceWithoutReasoningWhenHistoryChanged() throws Exception
	{
		// Anthropic's whole message, which also names the anthropic-beta header: not a refusal of fallback.
		server.answer(PATH,
			json(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"messages.1.content.0: "
				+ "Invalid `signature` in `thinking` block. The block is bound to a different conversation. Remove the block, "
				+ "or set `thinking.block_binding.prefix_mismatch_behavior` to \\\"drop_block\\\". That setting requires the "
				+ "`thinking-binding-controls-2026-08-01` value in the `anthropic-beta` header.\"}}"),
			answer("OK"));
		ChatApi.Conversation c = conversation("claude-opus-5-5", "Q1", "A", "Q2");
		c.turns.get(1).rawMessages = gson.fromJson("[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s\"},{\"type\":\"text\",\"text\":\"A\"}]}]", JsonArray.class);
		c.turns.get(1).rawKey = ChatApi.promptKey(c);
		ChatApi.Reply reply = send(api(), c).reply();
		assertEquals(2, server.bodies.size());
		assertTrue(message(0, 1).get("content").isJsonArray());
		assertEquals("A", message(1, 1).get("content").getAsString());
		// The plugin is told, so it keeps sending earlier replies as text from now on.
		assertTrue(reply.historyAsText);
		// Fallback is still asked for, and not remembered as refused.
		assertEquals("default", server.bodies.get(1).get("fallbacks").getAsString());
		assertTrue(refused.isEmpty());
	}

	@Test
	public void aMentionOfThinkingIsntRetriedWhenNoReasoningWasSent() throws Exception
	{
		server.answer(PATH, json(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"thinking: something else\"}}"));
		assertTrue(send(api(), conversation("claude-opus-5-5", "hi")).error().contains("thinking: something else"));
		assertEquals(1, server.bodies.size());
	}

	@Test
	public void aToolRoundSendsClaudesTurnBackVerbatimWithTheResults() throws Exception
	{
		server.answer(PATH,
			events(begin("claude-opus-5-5"), text(0, "Let me check the Wiki."),
				toolUse(1, "toolu_1", "wiki_search", "{\"query\":", " \"abyssal", " whip\"}"), end("tool_use", 20)),
			events(begin("claude-opus-5-5"), text(0, "It's ", "1.5m."), end("end_turn", 7)));
		ChatApi.Conversation c = conversation("claude-opus-5-5", "How much is a whip?");
		c.tools.add(StandIn.tool("wiki_search"));
		StandIn.Tools tools = new StandIn.Tools();
		c.toolRunner = tools;
		StandIn.Heard heard = send(api(), c);
		ChatApi.Reply reply = heard.reply();

		assertEquals(List.of("wiki_search {\"query\":\"abyssal whip\"}"), tools.calls);
		assertEquals("Let me check the Wiki.\n\nIt's 1.5m.", reply.text);
		assertEquals(reply.text, heard.partials.get(heard.partials.size() - 1));
		assertTrue(heard.partials.contains("Let me check the Wiki.\n\nIt's"));

		JsonObject tool = server.bodies.get(0).getAsJsonArray("tools").get(0).getAsJsonObject();
		assertEquals("wiki_search", tool.get("name").getAsString());
		assertEquals("Looks up wiki_search.", tool.get("description").getAsString());
		assertEquals(StandIn.tool("wiki_search").inputSchema, tool.get("input_schema"));

		// Round 2: the question, Claude's turn exactly as it came, and one message with the result.
		assertEquals(3, messages(1).size());
		assertEquals("How much is a whip?", message(1, 0).get("content").getAsString());
		JsonObject turn = gson.fromJson("{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Let me check the Wiki.\"},"
			+ "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"wiki_search\",\"input\":{\"query\":\"abyssal whip\"}}]}", JsonObject.class);
		assertEquals(turn, message(1, 1));
		assertEquals(gson.fromJson("{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\","
			+ "\"content\":\"result of wiki_search\"}]}", JsonObject.class), message(1, 2));
		assertEquals("the same tools every round", server.bodies.get(0).get("tools"), server.bodies.get(1).get("tools"));

		// The whole exchange goes back next time, final answer last.
		assertEquals(3, reply.rawMessages.size());
		assertEquals(turn, reply.rawMessages.get(0));
		assertEquals("It's 1.5m.", reply.rawMessages.get(2).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
		// Usage adds up over both rounds.
		assertEquals(20, reply.usage.input);
		assertEquals(27, reply.usage.output);
		assertEquals(400, reply.usage.cacheRead);
	}

	@Test
	public void toolsRunTogetherAndTheirResultsGoBackInOrder() throws Exception
	{
		server.answer(PATH,
			events(begin("claude-opus-5-5"), toolUse(0, "t_bad", "wiki_page", "{\"title\": "), toolUse(1, "t_slow", "wiki_page", "{\"title\":\"Vorkath\"}"),
				toolUse(2, "t_fast", "ge_price", "{\"item\":\"whip\"}"), end("tool_use", 20)),
			answer("Done."));
		ChatApi.Conversation c = conversation("claude-opus-5-5", "q");
		c.tools.add(StandIn.tool("wiki_page"));
		c.tools.add(StandIn.tool("ge_price"));
		// The slow page answers on another thread, after the price is in.
		CountDownLatch priceIn = new CountDownLatch(1);
		StandIn.Tools tools = new StandIn.Tools()
			.on("wiki_page", done -> new Thread(() ->
			{
				StandIn.await(priceIn);
				done.accept(ChatApi.ToolResult.error("The Wiki has no page called Vorkath."));
			}).start())
			.on("ge_price", done ->
			{
				done.accept(ChatApi.ToolResult.ok("1,500,000 coins"));
				priceIn.countDown();
			});
		c.toolRunner = tools;
		assertEquals("Done.", send(api(), c).reply().text);

		assertEquals("the call with unreadable input isn't run", 2, tools.calls.size());
		JsonArray results = message(1, 2).getAsJsonArray("content");
		assertEquals(3, results.size());
		JsonObject bad = results.get(0).getAsJsonObject();
		assertEquals("t_bad", bad.get("tool_use_id").getAsString());
		assertTrue(bad.get("content").getAsString().startsWith("Arguments weren't valid JSON"));
		assertTrue(bad.get("is_error").getAsBoolean());
		assertEquals("t_slow", results.get(1).getAsJsonObject().get("tool_use_id").getAsString());
		assertTrue(results.get(1).getAsJsonObject().get("is_error").getAsBoolean());
		assertEquals("t_fast", results.get(2).getAsJsonObject().get("tool_use_id").getAsString());
		assertEquals("1,500,000 coins", results.get(2).getAsJsonObject().get("content").getAsString());
		assertFalse(results.get(2).getAsJsonObject().has("is_error"));
	}

	@Test
	public void aReplyThatKeepsLookingThingsUpIsStopped() throws Exception
	{
		server.answer(PATH, events(begin("claude-opus-5-5"), toolUse(0, "t", "wiki_search", "{\"query\":\"q\"}"), end("tool_use", 3)));
		ChatApi.Conversation c = conversation("claude-opus-5-5", "q");
		c.tools.add(StandIn.tool("wiki_search"));
		StandIn.Tools tools = new StandIn.Tools();
		c.toolRunner = tools;
		StandIn.Heard heard = send(api(), c);
		assertEquals(ChatApi.TOO_MANY_ROUNDS, heard.error());
		assertEquals(ChatApi.MAX_TOOL_ROUNDS, tools.calls.size());
		assertEquals(ChatApi.MAX_TOOL_ROUNDS + 1, server.bodies.size());
		// Every round was billed, answer or not: 10 in, 200 cached, 30 written to the cache and 3 out, each time.
		ChatApi.Usage used = heard.failure.usage;
		assertEquals((ChatApi.MAX_TOOL_ROUNDS + 1) * 10, used.input);
		assertEquals((ChatApi.MAX_TOOL_ROUNDS + 1) * 200, used.cacheRead);
		assertEquals((ChatApi.MAX_TOOL_ROUNDS + 1) * 30, used.cacheWrite);
		assertEquals((ChatApi.MAX_TOOL_ROUNDS + 1) * 3, used.output);
		assertFalse(used.incomplete);
		assertEquals("claude-opus-5-5", heard.failure.model);
	}

	@Test
	public void whatAFailedReplyUsedIsReported() throws Exception
	{
		// Cut off part way: what was counted so far, and a sign that more went uncounted.
		server.answer(PATH, events(begin("claude-opus-5-5"), text(0, "Half")));
		StandIn.Heard heard = send(api(), conversation("claude-opus-5-5", "hi"));
		assertEquals(ChatApi.CUT_OFF, heard.error());
		assertEquals(241, heard.failure.usage.total());
		assertTrue(heard.failure.usage.incomplete);

		// Declined part way: billed, and counted in full.
		server.answer(PATH, events(begin("claude-opus-5-5"), text(0, "Sure, here"), end("refusal", 3)));
		heard = send(api(), conversation("claude-opus-5-5", "hi"));
		assertEquals("Claude declined to answer that.", heard.error());
		assertEquals(243, heard.failure.usage.total());
		assertFalse(heard.failure.usage.incomplete);

		// Turned away before anything was answered: nothing used.
		server.answer(PATH, json(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"));
		heard = send(api(), conversation("claude-opus-5-5", "hi"));
		assertTrue(heard.error().contains("Claude API key"));
		assertEquals(0, heard.failure.usage.total());
		assertFalse(heard.failure.usage.incomplete);
	}

	@Test
	public void aBusyAnthropicIsAskedAgain() throws Exception
	{
		server.answer(PATH,
			json(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}").header("retry-after", "0"),
			answer("Hi"));
		StandIn.Heard heard = send(api(), conversation("claude-opus-5-5", "hi"));
		assertEquals("Hi", heard.reply().text);
		assertEquals(List.of("Anthropic is busy 0"), heard.retries);
		assertEquals(2, server.bodies.size());

		// Twice at most, then the player is told.
		server.clear();
		server.answer(PATH, json(429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Number of requests has exceeded your rate limit\"}}")
			.header("retry-after-ms", "0"));
		heard = send(api(), conversation("claude-opus-5-5", "hi"));
		assertTrue(heard.error(), heard.error.contains("rate limit"));
		assertEquals(List.of("Anthropic's rate limit was hit 0", "Anthropic's rate limit was hit 0"), heard.retries);
		assertEquals(3, server.bodies.size());

		// Asked to wait more than a minute: an error with the time, at once.
		server.clear();
		server.answer(PATH, json(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}").header("retry-after", "300"));
		heard = send(api(), conversation("claude-opus-5-5", "hi"));
		assertEquals("Anthropic is busy; it asked to wait 5 minutes. Try again then.", heard.error());
		assertTrue(heard.retries.isEmpty());
		assertEquals(1, server.bodies.size());
	}

	@Test
	public void anOverloadedStreamIsRetriedOnlyBeforeItShowsText() throws Exception
	{
		server.answer(PATH, events(begin("claude-opus-5-5"), thinking(0, "", "s"), error("overloaded_error")), answer("Hi"));
		StandIn.Heard heard = send(api(), conversation("claude-opus-5-5", "hi"));
		assertEquals("Hi", heard.reply().text);
		assertEquals(1, heard.retries.size());
		assertEquals(2, server.bodies.size());

		// Text already shown is never quietly swapped for another reply.
		server.clear();
		server.answer(PATH, events(begin("claude-opus-5-5"), text(0, "Half an "), error("overloaded_error")), answer("Hi"));
		heard = send(api(), conversation("claude-opus-5-5", "hi"));
		assertTrue(heard.error(), heard.error.contains("overloaded"));
		assertEquals(List.of("Half an"), heard.partials);
		assertTrue(heard.retries.isEmpty());
		assertEquals(1, server.bodies.size());
	}

	@Test
	public void aRefusalIsAnErrorEvenAfterSomeText() throws Exception
	{
		server.answer(PATH, events(begin("claude-opus-5-5"), text(0, "Sure, here"), end("refusal", 3)));
		StandIn.Heard heard = send(api(), conversation("claude-opus-5-5", "hi"));
		assertEquals("Claude declined to answer that.", heard.error());
		assertEquals(List.of("Sure, here"), heard.partials);
		assertTrue("what it wrote isn't kept", heard.failure.withdrawn);

		// Any other failure leaves what was shown where it is.
		server.answer(PATH, events(begin("claude-opus-5-5"), text(0, "Half")));
		heard = send(api(), conversation("claude-opus-5-5", "hi"));
		assertEquals(ChatApi.CUT_OFF, heard.error());
		assertFalse(heard.failure.withdrawn);
	}

	@Test
	public void fallbackRefusedIsLeftOutAndRemembered() throws Exception
	{
		String refusal = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Unexpected value(s) `server-side-fallback-2026-07-01` for the `anthropic-beta` header.\"}}";
		// A retry that fails too proves nothing: nothing is remembered.
		server.answer(PATH, json(400, refusal), json(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"));
		send(api(), conversation("claude-opus-5-5", "hi")).error();
		assertTrue(refused.isEmpty());

		server.clear();
		server.answer(PATH, json(400, refusal), answer("Hi"));
		ChatApi.Reply reply = send(api(), conversation("claude-opus-5-5", "hi")).reply();
		assertFalse(reply.historyAsText);
		assertEquals(2, server.bodies.size());
		assertTrue(server.bodies.get(0).has("fallbacks"));
		assertFalse(server.bodies.get(1).has("fallbacks"));
		assertNull(server.headers.get(1).getFirst("anthropic-beta"));
		assertEquals(Set.of("fallbacks"), refused.get("anthropic claude-opus-5-5"));

		// The next message (a new client object, as the plugin makes per message) leaves it out from the start.
		send(api(), conversation("claude-opus-5-5", "again")).reply();
		assertEquals(3, server.bodies.size());
		assertFalse(server.bodies.get(2).has("fallbacks"));
		// Another model is still asked.
		send(api(), conversation("claude-sonnet-5-5", "hi")).reply();
		assertTrue(server.bodies.get(3).has("fallbacks"));
	}

	@Test
	public void afterAFallbackOnlyTheTextFromBeforeItGoesBack() throws Exception
	{
		server.answer(PATH, events(begin("claude-opus-5-5"), thinking(0, "hmm", "sig-a"), text(1, "Part one. "),
			toolUse(2, "t", "wiki_search", "{\"query\":\"x\"}"),
			block(3, "{\"type\":\"fallback\",\"from\":{\"model\":\"claude-opus-5-5\"},\"to\":{\"model\":\"claude-opus-4-8\"}}"), blockStop(3),
			thinking(4, "", "sig-b"), text(5, "Part two."), end("end_turn", 9)));
		ChatApi.Conversation c = conversation("claude-opus-5-5", "q");
		StandIn.Tools tools = new StandIn.Tools();
		c.toolRunner = tools;
		ChatApi.Reply reply = send(api(), c).reply();
		assertEquals("Part one. Part two.", reply.text);
		assertEquals("the model that took over", "claude-opus-4-8", reply.model);
		JsonArray content = reply.rawMessages.get(0).getAsJsonObject().getAsJsonArray("content");
		assertEquals(4, content.size());
		assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
		assertEquals("fallback", content.get(1).getAsJsonObject().get("type").getAsString());
		assertEquals("sig-b", content.get(2).getAsJsonObject().get("signature").getAsString());
		assertEquals("Part two.", content.get(3).getAsJsonObject().get("text").getAsString());
		assertTrue("the declined model's tool call isn't run", tools.calls.isEmpty());
	}

	@Test
	public void aStreamThatEndsEarlyIsAnError() throws Exception
	{
		server.answer(PATH, events(begin("claude-opus-5-5"), text(0, "Half")));
		assertEquals(ChatApi.CUT_OFF, send(api(), conversation("claude-opus-5-5", "hi")).error());
	}

	@Test
	public void aPlainJsonAnswerToAStreamedRequestStillWorks() throws Exception
	{
		server.answer(PATH, json(200, CLAUDE_OK));
		ChatApi.Reply reply = send(api(), conversation("claude-opus-5-5", "What should I train?")).reply();
		assertEquals("Train Agility.", reply.text);
		assertEquals("claude-opus-5-5", reply.model);
		assertEquals(2, reply.rawMessages.get(0).getAsJsonObject().getAsJsonArray("content").size());
		assertEquals(10, reply.usage.input);
		assertEquals(5, reply.usage.output);
	}

	@Test
	public void claudeProblemsAreExplained() throws Exception
	{
		AnthropicApi api = new AnthropicApi(http, gson, server.url(PATH), "bad", scheduler, refused);
		server.answer(PATH, json(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"));
		String unauthorized = send(api, conversation("claude-opus-5-5", "hi")).error();
		assertTrue(unauthorized, unauthorized.contains("Claude API key"));
		assertFalse("the key is never in a message", unauthorized.contains("bad"));
		server.answer(PATH, json(404, "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"model: claude-nope\"}}"));
		assertTrue(send(api, conversation("claude-nope", "hi")).error().contains("claude-nope"));
		server.answer(PATH, json(200, "{\"model\":\"claude-opus-5-5\",\"content\":[],\"stop_reason\":\"refusal\",\"stop_details\":{\"type\":\"refusal\",\"category\":null}}"));
		assertTrue(send(api, conversation("claude-opus-5-5", "hi")).error().contains("declined"));
		server.answer(PATH, json(200, "{\"model\":\"claude-opus-5-5\",\"content\":[{\"type\":\"text\",\"text\":\"Long\"}],\"stop_reason\":\"max_tokens\"}"));
		assertTrue(send(api, conversation("claude-opus-5-5", "hi")).reply().cutShort);
		server.answer(PATH, events(begin("claude-opus-5-5"), text(0, "Long"), end("max_tokens", 16000)));
		assertTrue(send(api, conversation("claude-opus-5-5", "hi")).reply().cutShort);
	}

	@Test
	public void aChatTooLongForTheModelSaysSo() throws Exception
	{
		server.answer(PATH, json(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"prompt is too long: 208310 tokens > 200000 maximum\"}}"));
		StandIn.Heard heard = send(api(), conversation("claude-haiku-4-5", "hi"));
		assertEquals("This chat is too long for claude-haiku-4-5. Start a new chat, or choose a model that can take more.", heard.error());
		assertTrue(heard.failure.tooLong);
		assertEquals("not asked again as it is", 1, server.bodies.size());

		server.answer(PATH, json(413, "{\"type\":\"error\",\"error\":{\"type\":\"request_too_large\",\"message\":\"Request exceeds the maximum allowed number of bytes.\"}}"));
		assertTrue(send(api(), conversation("claude-haiku-4-5", "hi")).await().failure.tooLong);

		// Other mistakes in a request aren't about its length.
		server.answer(PATH, json(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens: Field required\"}}"));
		assertFalse(send(api(), conversation("claude-haiku-4-5", "hi")).await().failure.tooLong);
	}

	@Test
	public void stoppingDuringARetryWaitMeansNoAnswer() throws Exception
	{
		server.answer(PATH, json(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}").header("retry-after", "30"),
			answer("Hi"));
		StandIn.Heard heard = new StandIn.Heard();
		ChatApi.Pending pending = api().send(conversation("claude-opus-5-5", "hi"), heard);
		heard.awaitRetrying();
		assertEquals(List.of("Anthropic is busy 30"), heard.retries);
		pending.cancel();
		assertTrue("Stop cancels the wait on the scheduler", scheduler.lastCancelled());
		assertFalse(heard.answeredWithin(300));
		assertEquals(1, server.bodies.size());
	}

	@Test
	public void aConnectionThatCantBeMadeIsTriedAgain() throws Exception
	{
		AnthropicApi down = new AnthropicApi(http, gson, HttpUrl.get("http://127.0.0.1:1/v1/messages"), "k", scheduler, refused);
		StandIn.Heard heard = new StandIn.Heard();
		ChatApi.Pending pending = down.send(conversation("claude-opus-5-5", "hi"), heard);
		heard.awaitRetrying();
		assertEquals(List.of("Couldn't reach Anthropic 2"), heard.retries);
		pending.cancel();
		assertTrue(scheduler.lastCancelled());
		assertFalse(heard.answeredWithin(300));
	}

	@Test
	public void earlierReasoningIsDroppedFromTheWholeReplyWhenRefusedPartWay() throws Exception
	{
		String bound = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"messages.1.content.0: Invalid `signature` in `thinking` block.\"}}";
		server.answer(PATH,
			events(begin("claude-opus-5-5"), thinking(0, "", "s-new"), toolUse(1, "t1", "wiki_search", "{\"query\":\"q\"}"), end("tool_use", 4)),
			json(400, bound),
			answer("Done."));
		ChatApi.Conversation c = conversation("claude-opus-5-5", "Q1", "A", "Q2");
		c.tools.add(StandIn.tool("wiki_search"));
		c.toolRunner = new StandIn.Tools();
		c.turns.get(1).rawMessages = gson.fromJson("[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s-old\"},{\"type\":\"text\",\"text\":\"A\"}]}]", JsonArray.class);
		c.turns.get(1).rawKey = ChatApi.promptKey(c);
		ChatApi.Reply reply = send(api(), c).reply();

		assertEquals(3, server.bodies.size());
		assertTrue("round 2 replayed the earlier reply", message(1, 1).get("content").isJsonArray());
		assertEquals("thinking", message(1, 3).getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
		// Asked again: earlier replies as text, and this reply's own reasoning (built on them) left out too.
		assertEquals("A", message(2, 1).get("content").getAsString());
		JsonArray turn = message(2, 3).getAsJsonArray("content");
		assertEquals(1, turn.size());
		assertEquals("tool_use", turn.get(0).getAsJsonObject().get("type").getAsString());
		assertTrue(reply.historyAsText);
		assertEquals(1, reply.rawMessages.get(0).getAsJsonObject().getAsJsonArray("content").size());
	}

	@Test
	public void listsModelsPageByPage() throws Exception
	{
		server.answer("/v1/models",
			json(200, "{\"data\":[{\"type\":\"model\",\"id\":\"claude-opus-5-5\"},{\"type\":\"model\",\"id\":\"claude-haiku-4-5\"}],\"has_more\":true,\"first_id\":\"claude-opus-5-5\",\"last_id\":\"claude-haiku-4-5\"}"),
			json(200, "{\"data\":[{\"type\":\"model\",\"id\":\"claude-sonnet-5-5\"}],\"has_more\":false,\"last_id\":\"claude-sonnet-5-5\"}"));
		Models models = new Models();
		api().listModels(models);
		assertEquals(List.of("claude-opus-5-5", "claude-haiku-4-5", "claude-sonnet-5-5"), models.await().ids);
		assertEquals("limit=1000", server.uris.get(0).getQuery());
		assertEquals("limit=1000&after_id=claude-haiku-4-5", server.uris.get(1).getQuery());
		assertEquals("sk-test", server.headers.get(1).getFirst("x-api-key"));
		assertEquals("2023-06-01", server.headers.get(1).getFirst("anthropic-version"));

		server.answer("/v1/models", json(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"));
		models = new Models();
		api().listModels(models);
		assertTrue(models.await().error.contains("Claude API key"));
	}

	/** What a model list request heard. */
	static final class Models implements ChatApi.ModelsListener
	{
		private final CountDownLatch done = new CountDownLatch(1);
		volatile List<String> ids;
		volatile String error;

		@Override
		public void onModels(List<String> ids)
		{
			this.ids = ids;
			done.countDown();
		}

		@Override
		public void onError(String message)
		{
			error = message;
			done.countDown();
		}

		Models await() throws InterruptedException
		{
			assertTrue("no answer", done.await(10, TimeUnit.SECONDS));
			return this;
		}
	}
}
