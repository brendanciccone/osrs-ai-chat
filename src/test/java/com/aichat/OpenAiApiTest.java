package com.aichat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

/** ChatGPT and OpenAI-compatible services against a stand-in server on 127.0.0.1: what's sent, and how answers are read. */
public class OpenAiApiTest
{
	private static final String PATH = "/v1/chat/completions";
	private static final String OK = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"},\"finish_reason\":\"stop\"}]}";
	private final Gson gson = new Gson();
	private final OkHttpClient http = new OkHttpClient();
	private ScheduledThreadPoolExecutor scheduler;
	private StandIn server;

	@Before
	public void start() throws IOException
	{
		server = new StandIn();
		scheduler = new ScheduledThreadPoolExecutor(1);
		scheduler.setRemoveOnCancelPolicy(true);
	}

	@After
	public void stop()
	{
		server.stop();
		scheduler.shutdownNow();
	}

	private OpenAiApi openai(String key, Map<String, Set<String>> refused)
	{
		return new OpenAiApi(http, gson, server.url("/v1/"), key, "ChatGPT", true, "low", scheduler, refused);
	}

	private OpenAiApi compatible(String name, String effort, Map<String, Set<String>> refused)
	{
		return new OpenAiApi(http, gson, server.url("/v1/"), "", name, false, effort, scheduler, refused);
	}

	private OpenAiApi compatible(String name)
	{
		return compatible(name, "low", new ConcurrentHashMap<>());
	}

	private static StandIn.Heard send(ChatApi api, ChatApi.Conversation c)
	{
		StandIn.Heard heard = new StandIn.Heard();
		api.send(c, heard);
		return heard;
	}

	/** A streamed piece: "data: {...}" and its blank line. */
	private static String chunk(String json)
	{
		return "data: " + json + "\n\n";
	}

	private static String content(String text)
	{
		JsonObject delta = new JsonObject();
		delta.addProperty("content", text);
		return chunk("{\"id\":\"c1\",\"model\":\"gpt-x\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":null}]}");
	}

	private static String finish(String reason)
	{
		return chunk("{\"id\":\"c1\",\"model\":\"gpt-x\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + reason + "\"}]}");
	}

	private static String toolCalls(String json)
	{
		return chunk("{\"id\":\"c1\",\"model\":\"gpt-x\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":" + json + "},\"finish_reason\":null}]}");
	}

	private static final String DONE = "data: [DONE]\n\n";

	@Test
	public void openAiRequestsAndReplies() throws Exception
	{
		server.answer(PATH, json(200, "{\"id\":\"c1\",\"model\":\"gpt-x\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Do Monkey Madness.\",\"refusal\":null},\"finish_reason\":\"stop\"}],"
			+ "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":8,\"prompt_tokens_details\":{\"cached_tokens\":0}}}"));
		ChatApi.Reply reply = send(openai("sk-o", new ConcurrentHashMap<>()), conversation("gpt-x", "Next quest?", "Dragon Slayer.", "After that?")).reply();
		assertEquals("Do Monkey Madness.", reply.text);
		assertEquals("gpt-x", reply.model);
		assertEquals(50, reply.usage.input);
		assertEquals(8, reply.usage.output);
		assertNull("no replay for these services", reply.rawMessages);

		JsonObject body = server.bodies.get(0);
		JsonArray messages = body.getAsJsonArray("messages");
		assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
		assertEquals("Be brief.", messages.get(0).getAsJsonObject().get("content").getAsString());
		assertEquals("assistant", messages.get(2).getAsJsonObject().get("role").getAsString());
		assertEquals("After that?", messages.get(3).getAsJsonObject().get("content").getAsString());
		assertTrue(body.get("stream").getAsBoolean());
		assertTrue(body.getAsJsonObject("stream_options").get("include_usage").getAsBoolean());
		assertTrue(body.has("max_completion_tokens"));
		assertFalse(body.has("max_tokens"));
		assertFalse("no tools, no tools field", body.has("tools"));
		assertEquals("low", body.get("reasoning_effort").getAsString());
		assertFalse(body.get("store").getAsBoolean());
		assertFalse(body.has("temperature"));
		assertEquals("Bearer sk-o", server.headers.get(0).getFirst("Authorization"));
	}

	@Test
	public void streamsAReplyAndCountsItsTokens() throws Exception
	{
		server.answer(PATH, events(chunk("{\"id\":\"c1\",\"model\":\"gpt-x\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}"),
			": keep-alive\n\n",
			chunk("{\"id\":\"c1\",\"model\":\"gpt-x\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"hidden\"},\"finish_reason\":null}]}"),
			content("Do "), content("Monkey Madness."), finish("stop"),
			chunk("{\"id\":\"c1\",\"model\":\"gpt-x\",\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"prompt_tokens_details\":{\"cached_tokens\":60}}}"),
			DONE));
		StandIn.Heard heard = send(openai("k", new ConcurrentHashMap<>()), conversation("gpt-x", "Next quest?"));
		ChatApi.Reply reply = heard.reply();
		assertEquals("Do Monkey Madness.", reply.text);
		assertEquals(List.of("Do", "Do Monkey Madness."), heard.partials);
		assertEquals("gpt-x", reply.model);
		assertEquals(40, reply.usage.input);
		assertEquals(60, reply.usage.cacheRead);
		assertEquals(0, reply.usage.cacheWrite);
		assertEquals(20, reply.usage.output);
		assertFalse(reply.cutShort);
	}

	@Test
	public void reasoningWrittenIntoTheStreamIsNeverShown() throws Exception
	{
		server.answer(PATH, events(content("<thi"), content("nk>\nhmm, the player"), content(" wants Varrock</think>\n\n"),
			content("Go to "), content("Varrock."), finish("stop"), DONE));
		StandIn.Heard heard = send(compatible("qwen"), conversation("qwen", "hi"));
		assertEquals("Go to Varrock.", heard.reply().text);
		assertEquals(List.of("Go to", "Go to Varrock."), heard.partials);
	}

	@Test
	public void toolCallsStreamedInPiecesAreRunAndAnswered() throws Exception
	{
		server.answer(PATH,
			events(toolCalls("[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"wiki_search\",\"arguments\":\"\"}}]"),
				toolCalls("[{\"index\":0,\"function\":{\"arguments\":\"{\\\"query\\\":\"}}]"),
				toolCalls("[{\"index\":1,\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"ge_price\",\"arguments\":\"{\\\"query\\\"\"}}]"),
				toolCalls("[{\"index\":0,\"function\":{\"arguments\":\"\\\"whip\\\"}\"}},{\"index\":1,\"function\":{\"arguments\":\":\\\"whip\\\"}\"}}]"),
				toolCalls("[{\"index\":2,\"id\":\"call_c\",\"type\":\"function\",\"function\":{\"name\":\"wiki_page\",\"arguments\":\"{oops\"}}]"),
				finish("tool_calls"), DONE),
			events(content("About 1.5m."), finish("stop"), DONE));
		ChatApi.Conversation c = conversation("gpt-x", "Whip price?");
		c.tools.add(StandIn.tool("wiki_search"));
		c.tools.add(StandIn.tool("ge_price"));
		c.tools.add(StandIn.tool("wiki_page"));
		StandIn.Tools tools = new StandIn.Tools();
		c.toolRunner = tools;
		ChatApi.Reply reply = send(openai("k", new ConcurrentHashMap<>()), c).reply();
		assertEquals("About 1.5m.", reply.text);
		assertEquals("the call with broken arguments isn't run",
			List.of("wiki_search {\"query\":\"whip\"}", "ge_price {\"query\":\"whip\"}"), tools.calls);

		JsonObject tool = server.bodies.get(0).getAsJsonArray("tools").get(0).getAsJsonObject();
		assertEquals("function", tool.get("type").getAsString());
		assertEquals("wiki_search", tool.getAsJsonObject("function").get("name").getAsString());
		assertEquals(StandIn.tool("wiki_search").inputSchema, tool.getAsJsonObject("function").get("parameters"));

		JsonArray messages = server.bodies.get(1).getAsJsonArray("messages");
		assertEquals("system, question, the calls, three results", 6, messages.size());
		JsonObject asked = messages.get(2).getAsJsonObject();
		assertEquals("assistant", asked.get("role").getAsString());
		assertEquals("", asked.get("content").getAsString());
		assertEquals(gson.fromJson("[{\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"wiki_search\",\"arguments\":\"{\\\"query\\\":\\\"whip\\\"}\"}},"
			+ "{\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"ge_price\",\"arguments\":\"{\\\"query\\\":\\\"whip\\\"}\"}},"
			+ "{\"id\":\"call_c\",\"type\":\"function\",\"function\":{\"name\":\"wiki_page\",\"arguments\":\"{oops\"}}]", JsonArray.class),
			asked.getAsJsonArray("tool_calls"));
		assertEquals(gson.fromJson("{\"role\":\"tool\",\"tool_call_id\":\"call_a\",\"content\":\"result of wiki_search\"}", JsonObject.class), messages.get(3));
		assertEquals("call_b", messages.get(4).getAsJsonObject().get("tool_call_id").getAsString());
		JsonObject broken = messages.get(5).getAsJsonObject();
		assertEquals("call_c", broken.get("tool_call_id").getAsString());
		assertTrue(broken.get("content").getAsString().startsWith("Arguments weren't valid JSON"));
	}

	@Test
	public void toolCallsWithoutIndexOrIdStillPairUp() throws Exception
	{
		// Some local services leave out "index": a new id is a new call, and a piece without one adds to the last call.
		server.answer(PATH,
			events(toolCalls("[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"wiki_search\",\"arguments\":\"{\\\"query\\\":\"}}]"),
				toolCalls("[{\"function\":{\"arguments\":\"\\\"x\\\"}\"}}]"),
				toolCalls("[{\"id\":\"b\",\"type\":\"function\",\"function\":{\"name\":\"ge_price\",\"arguments\":\"{\\\"item\\\":\"}}]"),
				// Others repeat the id on every piece: the same id is the same call.
				toolCalls("[{\"id\":\"b\",\"function\":{\"arguments\":\"\\\"y\\\"}\"}}]"),
				finish("tool_calls"), DONE),
			// And some leave out the id too: the results must still say which call they answer.
			events(toolCalls("[{\"type\":\"function\",\"function\":{\"name\":\"ge_price\",\"arguments\":\"{}\"}}]"),
				finish("tool_calls"), DONE),
			events(content("Done."), finish("stop"), DONE));
		ChatApi.Conversation c = conversation("m", "q");
		c.tools.add(StandIn.tool("wiki_search"));
		c.tools.add(StandIn.tool("ge_price"));
		StandIn.Tools tools = new StandIn.Tools();
		c.toolRunner = tools;
		assertEquals("Done.", send(compatible("m"), c).reply().text);
		assertEquals(List.of("wiki_search {\"query\":\"x\"}", "ge_price {\"item\":\"y\"}", "ge_price {}"), tools.calls);

		JsonArray round2 = server.bodies.get(1).getAsJsonArray("messages");
		JsonArray asked = round2.get(2).getAsJsonObject().getAsJsonArray("tool_calls");
		assertEquals("a", asked.get(0).getAsJsonObject().get("id").getAsString());
		assertEquals("b", asked.get(1).getAsJsonObject().get("id").getAsString());
		assertEquals("a", round2.get(3).getAsJsonObject().get("tool_call_id").getAsString());
		assertEquals("b", round2.get(4).getAsJsonObject().get("tool_call_id").getAsString());

		JsonArray round3 = server.bodies.get(2).getAsJsonArray("messages");
		assertEquals("call_2_0", round3.get(5).getAsJsonObject().getAsJsonArray("tool_calls").get(0).getAsJsonObject().get("id").getAsString());
		assertEquals("call_2_0", round3.get(6).getAsJsonObject().get("tool_call_id").getAsString());
	}

	@Test
	public void geminisSignaturesGoBackWithItsToolCalls() throws Exception
	{
		String signed = "[{\"index\":0,\"id\":\"function-call-1\",\"type\":\"function\",\"function\":{\"name\":\"ge_price\","
			+ "\"arguments\":\"{\\\"item\\\":\\\"whip\\\"}\"},\"extra_content\":{\"google\":{\"thought_signature\":\"SIG_A\"}}}]";
		server.answer(PATH, events(toolCalls(signed), finish("tool_calls"), DONE), events(content("1.5m."), finish("stop"), DONE));
		ChatApi.Conversation c = conversation("gemini-3-pro", "Whip price?");
		c.tools.add(StandIn.tool("ge_price"));
		c.toolRunner = new StandIn.Tools();
		assertEquals("1.5m.", send(compatible("gemini-3-pro"), c).reply().text);
		JsonObject call = server.bodies.get(1).getAsJsonArray("messages").get(2).getAsJsonObject()
			.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
		assertEquals("SIG_A", call.getAsJsonObject("extra_content").getAsJsonObject("google").get("thought_signature").getAsString());

		// An error about tools after the model has called them isn't a model that can't use them.
		server.clear();
		server.answer(PATH, events(toolCalls(signed), finish("tool_calls"), DONE),
			json(400, "[{\"error\":{\"code\":400,\"message\":\"Function call is missing a thought_signature in functionCall parts. "
				+ "This is required for tools to work correctly.\",\"status\":\"INVALID_ARGUMENT\"}}]"));
		Map<String, Set<String>> refused = new ConcurrentHashMap<>();
		assertTrue(send(compatible("gemini-3-pro", "low", refused), c).error().contains("thought_signature"));
		assertEquals(2, server.bodies.size());
		assertTrue(refused.isEmpty());
	}

	@Test
	public void reasoningBehindToolCallsGoesBackWithThem() throws Exception
	{
		server.answer(PATH,
			events(chunk("{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"The player wants \"},\"finish_reason\":null}]}"),
				chunk("{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"a price.\"},\"finish_reason\":null}]}"),
				toolCalls("[{\"index\":0,\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"ge_price\",\"arguments\":\"{}\"}}]"),
				finish("tool_calls"), DONE),
			events(content("1.5m."), finish("stop"), DONE));
		ChatApi.Conversation c = conversation("deepseek-reasoner", "Whip price?");
		c.tools.add(StandIn.tool("ge_price"));
		c.toolRunner = new StandIn.Tools();
		assertEquals("1.5m.", send(compatible("deepseek-reasoner"), c).reply().text);
		JsonObject asked = server.bodies.get(1).getAsJsonArray("messages").get(2).getAsJsonObject();
		assertEquals("The player wants a price.", asked.get("reasoning_content").getAsString());

		// Not from a service that didn't send any.
		server.clear();
		server.answer(PATH, events(toolCalls("[{\"index\":0,\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"ge_price\",\"arguments\":\"{}\"}}]"),
			finish("tool_calls"), DONE), events(content("1.5m."), finish("stop"), DONE));
		send(compatible("m"), c).reply();
		assertFalse(server.bodies.get(1).getAsJsonArray("messages").get(2).getAsJsonObject().has("reasoning_content"));
	}

	@Test
	public void toolsAreLeftOutForModelsThatCantUseThem() throws Exception
	{
		Map<String, Set<String>> refused = new ConcurrentHashMap<>();
		server.answer(PATH, json(400, "{\"error\":{\"message\":\"registry.ollama.ai/library/llama3:latest does not support tools\",\"type\":\"api_error\"}}"),
			events(content("Hi"), finish("stop"), DONE));
		ChatApi.Conversation c = conversation("llama3", "hi");
		c.tools.add(StandIn.tool("wiki_search"));
		c.toolRunner = new StandIn.Tools();
		ChatApi.Reply reply = send(compatible("llama3", "low", refused), c).reply();
		assertEquals("Hi", reply.text);
		assertTrue("the panel is told", reply.toolsUnavailable);
		assertTrue(server.bodies.get(0).has("tools"));
		assertFalse(server.bodies.get(1).has("tools"));
		assertTrue("the rest is unchanged", server.bodies.get(1).has("reasoning_effort"));
		assertTrue(refused.values().iterator().next().contains("tools"));
		// The model is told it has none, so it doesn't send the player to settings that are already on.
		assertEquals("Be brief.", systemOf(0));
		assertEquals("Be brief." + OpenAiApi.NO_TOOLS, systemOf(1));

		assertTrue(send(compatible("llama3", "low", refused), c).reply().toolsUnavailable);
		assertFalse("remembered", server.bodies.get(2).has("tools"));
		assertEquals("Be brief." + OpenAiApi.NO_TOOLS, systemOf(2));

		// A request without tools has none to miss.
		assertFalse(send(compatible("llama3", "low", refused), conversation("llama3", "hi")).reply().toolsUnavailable);
		assertEquals("Be brief.", systemOf(3));
	}

	@Test
	public void servicesThatRefuseToolsInTheirOwnWordsGetNone() throws Exception
	{
		String[][] refusals = {
			{"404", "{\"error\":{\"message\":\"No endpoints found that support tool use. Try disabling \\\"wiki_search\\\". "
				+ "To learn more about provider routing, visit: https://openrouter.ai/docs/provider-routing\",\"code\":404}}"},
			{"400", "{\"error\":{\"message\":\"\\\"auto\\\" tool choice requires --enable-auto-tool-choice and "
				+ "--tool-call-parser to be set\",\"type\":\"BadRequestError\",\"param\":null,\"code\":400}}"},
			{"400", "{\"object\":\"error\",\"message\":\"\\\"auto\\\" tool choice requires --enable-auto-tool-choice and "
				+ "--tool-call-parser to be set\",\"type\":\"BadRequestError\",\"param\":null,\"code\":400}"},
		};
		for (String[] refusal : refusals)
		{
			server.clear();
			server.answer(PATH, json(Integer.parseInt(refusal[0]), refusal[1]), events(content("Hi"), finish("stop"), DONE));
			ChatApi.Conversation c = conversation("m", "hi");
			c.tools.add(StandIn.tool("wiki_search"));
			c.toolRunner = new StandIn.Tools();
			Map<String, Set<String>> refused = new ConcurrentHashMap<>();
			ChatApi.Reply reply = send(compatible("m", "low", refused), c).reply();
			assertTrue(refusal[1], reply.toolsUnavailable);
			assertFalse(server.bodies.get(1).has("tools"));
			assertEquals(Set.of("tools"), refused.values().iterator().next());
		}

		// A model that isn't there is still explained as one.
		server.clear();
		server.answer(PATH, json(404, "{\"error\":{\"message\":\"The model `m` does not exist\"}}"));
		ChatApi.Conversation c = conversation("m", "hi");
		c.tools.add(StandIn.tool("wiki_search"));
		assertTrue(send(compatible("m"), c).error().startsWith("Not found at"));
		assertEquals(1, server.bodies.size());
	}

	private String systemOf(int request)
	{
		return server.bodies.get(request).getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
	}

	@Test
	public void streamOptionsAreLeftOutWhenRefused() throws Exception
	{
		Map<String, Set<String>> refused = new ConcurrentHashMap<>();
		server.answer(PATH, json(400, "{\"error\":{\"message\":\"Unrecognized request argument supplied: stream_options\",\"type\":\"invalid_request_error\"}}"),
			events(content("Hi"), finish("stop"), DONE));
		assertEquals("Hi", send(compatible("m", "low", refused), conversation("m", "hi")).reply().text);
		assertTrue(server.bodies.get(0).has("stream_options"));
		assertFalse(server.bodies.get(1).has("stream_options"));
		assertTrue(server.bodies.get(1).get("stream").getAsBoolean());
		send(compatible("m", "low", refused), conversation("m", "again")).reply();
		assertFalse(server.bodies.get(2).has("stream_options"));
	}

	@Test
	public void eachToolRoundGetsItsOwnRetries() throws Exception
	{
		String busy = "{\"error\":{\"message\":\"Bad gateway\"}}";
		server.answer(PATH, json(502, busy).header("retry-after", "0"), json(502, busy).header("retry-after", "0"),
			events(toolCalls("[{\"index\":0,\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"ge_price\",\"arguments\":\"{}\"}}]"),
				finish("tool_calls"), DONE),
			json(502, busy).header("retry-after", "0"), events(content("Hi"), finish("stop"), DONE));
		ChatApi.Conversation c = conversation("m", "q");
		c.tools.add(StandIn.tool("ge_price"));
		c.toolRunner = new StandIn.Tools();
		StandIn.Heard heard = send(compatible("m"), c);
		// The first round used both its retries; the next still has its own.
		assertEquals("Hi", heard.reply().text);
		assertEquals(3, heard.retries.size());
		assertEquals(5, server.bodies.size());
	}

	@Test
	public void aBusyServiceIsAskedAgainButAnEmptyAccountIsNot() throws Exception
	{
		// Not a 503: OkHttp itself asks again at once when one says "Retry-After: 0".
		server.answer(PATH, json(502, "{\"error\":{\"message\":\"Bad gateway\"}}").header("retry-after", "0"), json(200, OK));
		StandIn.Heard heard = send(openai("k", new ConcurrentHashMap<>()), conversation("gpt-x", "hi"));
		assertEquals("Hi", heard.reply().text);
		assertEquals(List.of(server.url("/").host() + ":" + server.url("/").port() + " is busy 0"), heard.retries);
		assertEquals(2, server.bodies.size());

		server.clear();
		server.answer(PATH, json(429, "{\"error\":{\"message\":\"You exceeded your current quota, please check your plan and billing details.\",\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}")
			.header("retry-after", "0"));
		heard = send(openai("k", new ConcurrentHashMap<>()), conversation("gpt-x", "hi"));
		assertTrue(heard.error(), heard.error.contains("out of credits"));
		assertTrue(heard.retries.isEmpty());
		assertEquals(1, server.bodies.size());

		// A rate limit that only links to the billing page is waited out.
		server.clear();
		server.answer(PATH, json(429, ChatApiTest.GROQ_RATE_LIMIT).header("retry-after", "0"), json(200, OK));
		heard = send(compatible("llama-3.3-70b-versatile"), conversation("llama-3.3-70b-versatile", "hi"));
		assertEquals("Hi", heard.reply().text);
		assertEquals(1, heard.retries.size());

		// And explained as one when it isn't.
		server.clear();
		server.answer(PATH, json(429, ChatApiTest.GROQ_RATE_LIMIT).header("retry-after", "0"));
		heard = send(compatible("llama-3.3-70b-versatile"), conversation("llama-3.3-70b-versatile", "hi"));
		assertTrue(heard.error(), heard.error.contains("rate limit was hit"));
	}

	@Test
	public void aStreamThatStopsWithoutFinishingIsAnError() throws Exception
	{
		server.answer(PATH, events(content("Half a")));
		StandIn.Heard heard = send(compatible("m"), conversation("m", "hi"));
		assertEquals(ChatApi.CUT_OFF, heard.error());
		assertEquals(List.of("Half a"), heard.partials);

		// A finish reason without [DONE] is a whole answer: some services stop there.
		server.answer(PATH, events(content("Whole"), finish("stop")));
		assertEquals("Whole", send(compatible("m"), conversation("m", "hi")).reply().text);
	}

	@Test
	public void aConnectionLostAfterTheReplyEndedKeepsTheReply() throws Exception
	{
		// The finish reason came, then the connection closed before the stream's own end.
		OpenAiApi api = new OpenAiApi(http, gson, answerThenDrop(content("Whole answer."), finish("stop")), "", "m", false,
			"low", scheduler, new ConcurrentHashMap<>());
		ChatApi.Reply reply = send(api, conversation("m", "hi")).reply();
		assertEquals("Whole answer.", reply.text);
		assertTrue("its counts never came", reply.usage.incomplete);

		// Before it: cut off.
		api = new OpenAiApi(http, gson, answerThenDrop(content("Half a")), "", "m", false, "low", scheduler,
			new ConcurrentHashMap<>());
		assertEquals(ChatApi.CUT_OFF, send(api, conversation("m", "hi")).error());
	}

	/**
	 * A server for one request that streams {@code events}, then closes the connection short of the length it promised
	 * (the stand-in server would keep it open). Its base URL.
	 */
	private static HttpUrl answerThenDrop(String... events) throws IOException
	{
		ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		Thread serve = new Thread(() ->
		{
			try (ServerSocket s = socket; Socket c = s.accept())
			{
				// The whole request first, or closing would reset the connection before the answer is read.
				InputStream in = c.getInputStream();
				StringBuilder head = new StringBuilder();
				while (!head.toString().endsWith("\r\n\r\n"))
				{
					head.append((char) in.read());
				}
				java.util.regex.Matcher length = java.util.regex.Pattern.compile("(?i)content-length: *(\\d+)").matcher(head);
				for (int left = length.find() ? Integer.parseInt(length.group(1)) : 0; left > 0; left--)
				{
					in.read();
				}
				byte[] body = String.join("", events).getBytes(StandardCharsets.UTF_8);
				OutputStream out = c.getOutputStream();
				out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " + (body.length + 100)
					+ "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
				out.write(body);
				out.flush();
			}
			catch (IOException e)
			{
				// The test fails on what the client heard.
			}
		});
		serve.setDaemon(true);
		serve.start();
		return HttpUrl.get("http://127.0.0.1:" + socket.getLocalPort() + "/v1/");
	}

	@Test
	public void aTimeoutAfterTheRequestWentIsntRetried() throws Exception
	{
		// Holds every request until the test is over.
		AtomicInteger hits = new AtomicInteger();
		CountDownLatch release = new CountDownLatch(1);
		server.server().createContext("/slow/", exchange ->
		{
			hits.incrementAndGet();
			StandIn.await(release);
			exchange.close();
		});
		try
		{
			// A service on the internet, as far as AI Chat can tell (one on this computer is never asked again anyway),
			// though it's the stand-in on 127.0.0.1.
			OkHttpClient impatient = http.newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).proxy(Proxy.NO_PROXY)
				.dns(host -> Collections.singletonList(InetAddress.getLoopbackAddress())).build();
			int port = server.url("/").port();
			OpenAiApi slow = new OpenAiApi(impatient, gson, HttpUrl.get("http://ai.example:" + port + "/slow/v1/"), "k", "m",
				false, "low", scheduler, new ConcurrentHashMap<>());
			StandIn.Heard heard = send(slow, conversation("m", "q"));
			assertEquals("ai.example:" + port + " took too long to answer. Try again.", heard.error());
			// The service had the request and may have been answering it, and billing it: it isn't sent again.
			assertTrue(heard.retries.isEmpty());
			assertEquals(1, hits.get());
			assertTrue(heard.failure.usage.incomplete);
		}
		finally
		{
			release.countDown();
		}
	}

	@Test
	public void jsonNestedTooDeepDoesntStallTheReply() throws Exception
	{
		// A model stuck writing "[": the call isn't run, and the model is told why.
		JsonObject function = new JsonObject();
		function.addProperty("name", "wiki_search");
		function.addProperty("arguments", "{\"query\":" + "[".repeat(5000) + "]".repeat(5000) + "}");
		JsonObject call = new JsonObject();
		call.addProperty("index", 0);
		call.addProperty("id", "c");
		call.addProperty("type", "function");
		call.add("function", function);
		JsonArray calls = new JsonArray();
		calls.add(call);
		server.answer(PATH, events(toolCalls(calls.toString()), finish("tool_calls"), DONE), events(content("Sorry."), finish("stop"), DONE));
		ChatApi.Conversation c = conversation("m", "q");
		c.tools.add(StandIn.tool("wiki_search"));
		StandIn.Tools tools = new StandIn.Tools();
		c.toolRunner = tools;
		assertEquals("Sorry.", send(compatible("m"), c).reply().text);
		assertTrue(tools.calls.isEmpty());
		JsonObject result = server.bodies.get(1).getAsJsonArray("messages").get(3).getAsJsonObject();
		assertTrue(result.get("content").getAsString().startsWith("Arguments weren't valid JSON"));

		// A piece of the stream too deep to read ends the reply with an error, not silence.
		server.clear();
		server.answer(PATH, events(content("Hi"), chunk("[".repeat(100_000) + "]".repeat(100_000))));
		assertTrue(send(compatible("m"), conversation("m", "q")).error().endsWith("sent an answer AI Chat couldn't read."));
	}

	@Test
	public void anErrorPartWayThroughTheStreamIsReported() throws Exception
	{
		server.answer(PATH, events(content("Partly"), chunk("{\"error\":{\"code\":502,\"message\":\"Provider returned error\"}}"), DONE));
		assertTrue(send(compatible("m"), conversation("m", "hi")).error().contains("Provider returned error"));
		server.answer(PATH, events(chunk("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"error\",\"error\":{\"message\":\"Upstream timed out\"}}]}"), DONE));
		assertTrue(send(compatible("m"), conversation("m", "hi")).error().contains("Upstream timed out"));
		server.answer(PATH, events(content("Some"), finish("content_filter"), DONE));
		StandIn.Heard filtered = send(compatible("m"), conversation("m", "hi"));
		assertTrue(filtered.error().contains("content filter"));
		assertTrue("what it wrote isn't kept", filtered.failure.withdrawn);
	}

	@Test
	public void aReplyThatKeepsCallingToolsIsStopped() throws Exception
	{
		server.answer(PATH, events(toolCalls("[{\"index\":0,\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"wiki_search\",\"arguments\":\"{}\"}}]"),
			finish("tool_calls"), DONE));
		ChatApi.Conversation c = conversation("m", "q");
		c.tools.add(StandIn.tool("wiki_search"));
		c.toolRunner = new StandIn.Tools();
		StandIn.Heard heard = send(compatible("m"), c);
		assertEquals(ChatApi.TOO_MANY_ROUNDS, heard.error());
		assertEquals(ChatApi.MAX_TOOL_ROUNDS + 1, server.bodies.size());
		// This service sent no token counts, so whatever was used went uncounted.
		assertEquals(0, heard.failure.usage.total());
		assertTrue(heard.failure.usage.incomplete);

		// One that does: every round counts.
		server.answer(PATH, events(toolCalls("[{\"index\":0,\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"wiki_search\",\"arguments\":\"{}\"}}]"),
			finish("tool_calls"), chunk("{\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5}}"), DONE));
		heard = send(compatible("m"), c);
		assertEquals(ChatApi.TOO_MANY_ROUNDS, heard.error());
		assertEquals((ChatApi.MAX_TOOL_ROUNDS + 1) * 105, heard.failure.usage.total());
		assertFalse(heard.failure.usage.incomplete);
	}

	@Test
	public void aLowerLengthLimitIsKept() throws Exception
	{
		server.answer(PATH, json(200, OK));
		ChatApi.Conversation c = conversation("m", "hi");
		c.maxTokens = 4000;
		send(openai("k", new ConcurrentHashMap<>()), c).reply();
		send(compatible("m"), c).reply();
		assertEquals(4000, server.bodies.get(0).get("max_completion_tokens").getAsInt());
		assertEquals(4000, server.bodies.get(1).get("max_tokens").getAsInt());
	}

	@Test
	public void listsModels() throws Exception
	{
		server.answer("/v1/models", json(200, "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-x\",\"object\":\"model\"},{\"id\":\"text-embedding-3-small\",\"object\":\"model\"}]}"));
		AnthropicApiTest.Models models = new AnthropicApiTest.Models();
		openai("sk-o", new ConcurrentHashMap<>()).listModels(models);
		assertEquals(List.of("gpt-x", "text-embedding-3-small"), models.await().ids);
		assertEquals("Bearer sk-o", server.headers.get(0).getFirst("Authorization"));

		// A service without a model list.
		server.answer("/v1/models", json(404, "{\"error\":\"not found\"}"));
		models = new AnthropicApiTest.Models();
		compatible("m").listModels(models);
		assertEquals(OpenAiApi.NO_MODEL_LIST, models.await().error);
		assertNull("no key, no header", server.headers.get(1).getFirst("Authorization"));

		server.answer("/v1/models", json(401, "{\"error\":{\"message\":\"Incorrect API key provided\"}}"));
		models = new AnthropicApiTest.Models();
		openai("sk-bad", new ConcurrentHashMap<>()).listModels(models);
		assertTrue(models.await().error.contains("didn't accept the API key"));
	}

	@Test
	public void aUrlWithoutV1IsCaughtByTest() throws Exception
	{
		// Ollama's address as the player typed it, without the /v1 its chats need.
		OpenAiApi bare = new OpenAiApi(http, gson, server.url("/"), "", "llama3.2", false, "low", scheduler, new ConcurrentHashMap<>());
		server.answer("/v1/models", json(200, "{\"object\":\"list\",\"data\":[{\"id\":\"llama3.2:latest\"}]}"));
		AnthropicApiTest.Models models = new AnthropicApiTest.Models();
		bare.listModels(models);
		assertEquals("The URL is missing /v1: set the Compatible API URL to " + server.url("/v1") + " in the AI Chat settings.",
			models.await().error);
		assertEquals("/models", server.uris.get(0).getPath());
		assertEquals("/v1/models", server.uris.get(1).getPath());

		// Not there either: a service without a list, as far as anyone can tell.
		server.clear();
		server.answer("/v1/models", json(404, "{}"));
		models = new AnthropicApiTest.Models();
		bare.listModels(models);
		assertEquals(OpenAiApi.NO_MODEL_LIST, models.await().error);
		assertEquals(2, server.uris.size());
	}

	@Test
	public void aTestThatGetsNoAnswerEnds() throws Exception
	{
		// Answers nothing until the test is over.
		CountDownLatch release = new CountDownLatch(1);
		server.server().createContext("/slow/", exchange ->
		{
			StandIn.await(release);
			exchange.close();
		});
		try
		{
			OkHttpClient impatient = http.newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build();
			OpenAiApi slow = new OpenAiApi(impatient, gson, server.url("/slow/v1/"), "", "m", false, "low", scheduler, new ConcurrentHashMap<>());
			AnthropicApiTest.Models models = new AnthropicApiTest.Models();
			slow.listModels(models);
			assertEquals("127.0.0.1:" + server.url("/").port() + " didn't answer in time. Try again in a moment.", models.await().error);

			AnthropicApi claude = new AnthropicApi(impatient, gson, server.url("/slow/v1/messages"), "k", scheduler, new ConcurrentHashMap<>());
			models = new AnthropicApiTest.Models();
			claude.listModels(models);
			assertEquals("Anthropic didn't answer in time. Try again in a moment.", models.await().error);
		}
		finally
		{
			release.countDown();
		}
	}

	@Test
	public void aModelThatRefusesAnOptionalSettingIsAskedAgainWithoutIt() throws Exception
	{
		server.answer(PATH,
			json(400, "{\"error\":{\"message\":\"Unrecognized request argument supplied: reasoning_effort\",\"type\":\"invalid_request_error\"}}"),
			json(200, OK));
		ChatApi.Reply reply = send(openai("k", new ConcurrentHashMap<>()), conversation("gpt-4o-mini", "hi")).reply();
		assertEquals("Hi", reply.text);
		assertTrue(server.bodies.get(0).has("reasoning_effort"));
		assertFalse(server.bodies.get(1).has("reasoning_effort"));
		assertTrue(server.bodies.get(1).has("store"));
	}

	@Test
	public void compatibleServicesGetNoKeyUnlessGivenAndNoOpenAiOnlyFields() throws Exception
	{
		server.answer(PATH, json(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Hi\"}]},\"finish_reason\":\"length\"}]}"));
		OpenAiApi api = new OpenAiApi(http, gson, OpenAiApi.parseBaseUrl(server.url("/v1").toString()), "", "llama3.2", false, "low", scheduler, new ConcurrentHashMap<>());
		ChatApi.Reply reply = send(api, conversation("llama3.2", "hi")).reply();
		assertEquals("Hi", reply.text);
		assertTrue(reply.cutShort);
		assertNull(server.headers.get(0).getFirst("Authorization"));
		assertFalse(server.bodies.get(0).has("max_completion_tokens"));
		assertEquals("low", server.bodies.get(0).get("reasoning_effort").getAsString());
		assertFalse(server.bodies.get(0).has("store"));
		assertTrue(server.bodies.get(0).has("max_tokens"));
	}

	@Test
	public void servicesThatDontThinkAreAskedAgainWithoutReasoningEffort() throws Exception
	{
		OpenAiApi api = compatible("llama3.2");
		// Ollama, for a model that can't think.
		server.answer(PATH, json(400, "{\"error\":{\"message\":\"\\\"llama3.2\\\" does not support thinking\",\"type\":\"api_error\"}}"), json(200, OK));
		assertEquals("Hi", send(api, conversation("llama3.2", "hi")).reply().text);
		assertTrue(server.bodies.get(0).has("reasoning_effort"));
		assertFalse(server.bodies.get(1).has("reasoning_effort"));
		assertTrue("the rest is unchanged", server.bodies.get(1).has("max_tokens"));

		// Mistral: a 422 listing the field it doesn't accept.
		server.clear();
		server.answer(PATH, json(422, "{\"detail\":[{\"type\":\"extra_forbidden\",\"loc\":[\"body\",\"reasoning_effort\"],\"msg\":\"Extra inputs are not permitted\"}]}"), json(200, OK));
		assertEquals("Hi", send(api, conversation("mistral-small-latest", "hi")).reply().text);
		assertFalse(server.bodies.get(1).has("reasoning_effort"));

		// Ollama 0.11.8-0.17.6, for a thinking model other than gpt-oss.
		server.clear();
		server.answer(PATH, json(400, "{\"error\":{\"message\":\"think value \\\"low\\\" is not supported for this model\",\"type\":\"invalid_request_error\",\"param\":null,\"code\":null}}"), json(200, OK));
		assertEquals("Hi", send(api, conversation("qwen3:8b", "hi")).reply().text);
		assertFalse(server.bodies.get(1).has("reasoning_effort"));

		// Any other problem is reported, not retried.
		server.clear();
		server.answer(PATH, json(400, "{\"error\":{\"message\":\"messages: roles must alternate\"}}"));
		assertTrue(send(api, conversation("m", "hi")).error().contains("roles must alternate"));
		assertEquals(1, server.bodies.size());
	}

	@Test
	public void aRefusedSettingIsLeftOutNextTimeButOnlyOnceItsCertain() throws Exception
	{
		String refusal = "{\"error\":{\"message\":\"\\\"llama3.2\\\" does not support thinking\"}}";
		Map<String, Set<String>> refused = new ConcurrentHashMap<>();

		// A retry that fails too proves nothing: nothing is remembered.
		server.answer(PATH, json(400, refusal), json(500, "{\"error\":{\"message\":\"boom\"}}").header("retry-after", "0"));
		send(compatible("llama3.2", "low", refused), conversation("llama3.2", "hi")).error();
		assertTrue(refused.isEmpty());

		// Refused, then answered without it: remembered, so the next message (a new client object, as the plugin
		// makes per message) leaves it out from the start. Three requests for two messages, not four.
		server.clear();
		server.answer(PATH, json(400, refusal), json(200, OK));
		send(compatible("llama3.2", "low", refused), conversation("llama3.2", "one")).reply();
		send(compatible("llama3.2", "low", refused), conversation("llama3.2", "two")).reply();
		assertEquals(3, server.bodies.size());
		assertFalse(server.bodies.get(2).has("reasoning_effort"));
		assertTrue(server.bodies.get(2).has("max_tokens"));

		// Another model on the same service is asked normally.
		server.clear();
		server.answer(PATH, json(200, OK));
		send(compatible("qwen3:8b", "low", refused), conversation("qwen3:8b", "hi")).reply();
		assertEquals("low", server.bodies.get(0).get("reasoning_effort").getAsString());
	}

	@Test
	public void modelDefaultThinkingSendsNoEffort() throws Exception
	{
		server.answer(PATH, json(200, OK));
		send(compatible("qwen/qwen3.8-27b", AiChatConfig.Thinking.DEFAULT.effort, new ConcurrentHashMap<>()), conversation("qwen/qwen3.8-27b", "hi")).reply();
		assertFalse(server.bodies.get(0).has("reasoning_effort"));
		assertEquals("low", AiChatConfig.Thinking.SHORT.effort);
	}

	@Test
	public void compatibleQuirks() throws Exception
	{
		OpenAiApi api = new OpenAiApi(http, gson, server.url("/v1/"), "k", "qwen", false, "low", scheduler, new ConcurrentHashMap<>());
		// Reasoning inside the reply is dropped.
		server.answer(PATH, json(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"<think>\\nhmm\\n</think>\\n\\nGo to Varrock.\"},\"finish_reason\":\"stop\"}]}"));
		assertEquals("Go to Varrock.", send(api, conversation("qwen", "hi")).reply().text);
		// Gemini: a bad key is a 400 with the error in a list.
		server.answer(PATH, json(400, "[{\"error\":{\"code\":400,\"message\":\"Please pass a valid API key\",\"status\":\"INVALID_ARGUMENT\"}}]"));
		assertTrue(send(api, conversation("qwen", "hi")).error().contains("didn't accept the API key"));
		// OpenRouter: a failure can come back as a 200 with only an error.
		server.answer(PATH, json(200, "{\"error\":{\"code\":502,\"message\":\"Provider returned error\"}}"));
		assertTrue(send(api, conversation("qwen", "hi")).error().contains("Provider returned error"));
		// Mistral: {"detail": ...}.
		server.answer(PATH, json(401, "{\"detail\":\"Invalid API Key\"}"));
		assertTrue(send(api, conversation("qwen", "hi")).error().contains("Invalid API Key"));
		// Thinking parts of a list are skipped.
		server.answer(PATH, json(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":[{\"type\":\"text\",\"text\":\"hmm\"}]},{\"type\":\"text\",\"text\":\"Answer\"}]},\"finish_reason\":\"stop\"}]}"));
		assertEquals("Answer", send(api, conversation("qwen", "hi")).reply().text);
		// Reasoning that ran out of room before its closing tag isn't an answer.
		server.answer(PATH, json(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"<think>\\nOkay, the user wants\"},\"finish_reason\":\"length\"}]}"));
		assertTrue(send(api, conversation("qwen", "hi")).error().contains("reply length"));
		// Out of room before saying anything.
		server.answer(PATH, json(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"length\"}]}"));
		assertTrue(send(api, conversation("qwen", "hi")).error().contains("reply length"));
	}

	@Test
	public void openAiProblemsAreExplained() throws Exception
	{
		OpenAiApi api = openai("sk-bad", new ConcurrentHashMap<>());
		server.answer(PATH, json(401, "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}"));
		assertTrue(send(api, conversation("gpt-x", "hi")).error().contains("API key"));
		server.answer(PATH, json(429, "{\"error\":{\"message\":\"You exceeded your current quota\",\"type\":\"insufficient_quota\"}}"));
		assertTrue(send(api, conversation("gpt-x", "hi")).error().contains("quota"));
		server.answer(PATH, json(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"refusal\":\"I can't help with that.\"},\"finish_reason\":\"stop\"}]}"));
		assertTrue(send(api, conversation("gpt-x", "hi")).error().contains("declined"));
		server.answer(PATH, events(chunk("{\"choices\":[{\"index\":0,\"delta\":{\"refusal\":\"I can't \"},\"finish_reason\":null}]}"),
			chunk("{\"choices\":[{\"index\":0,\"delta\":{\"refusal\":\"help with that.\"},\"finish_reason\":\"stop\"}]}"), DONE));
		assertEquals("ChatGPT declined: I can't help with that.", send(api, conversation("gpt-x", "hi")).error());

		// Nothing listening on this computer: says so at once, and asks whether the service is running.
		OpenAiApi down = new OpenAiApi(http, gson, HttpUrl.get("http://127.0.0.1:1/v1/"), "", "m", false, "low", scheduler, new ConcurrentHashMap<>());
		StandIn.Heard heard = send(down, conversation("m", "hi"));
		assertTrue(heard.error().contains("running"));
		assertTrue(heard.retries.isEmpty());
	}

	@Test
	public void aChatTooLongForTheModelSaysSo() throws Exception
	{
		server.answer(PATH, json(400, "{\"error\":{\"message\":\"This model's maximum context length is 128000 tokens. However, your messages resulted in 130211 tokens. Please reduce the length of the messages.\",\"type\":\"invalid_request_error\",\"param\":\"messages\",\"code\":\"context_length_exceeded\"}}"));
		StandIn.Heard heard = send(openai("k", new ConcurrentHashMap<>()), conversation("gpt-x", "hi"));
		assertEquals("This chat is too long for gpt-x. Start a new chat, or choose a model that can take more.", heard.error());
		assertTrue(heard.failure.tooLong);

		// A model on this computer: its own context size may be the limit.
		server.answer(PATH, json(400, "{\"error\":{\"code\":400,\"message\":\"the request exceeds the available context size, try increasing it\",\"type\":\"exceed_context_size_error\"}}"));
		heard = send(compatible("m"), conversation("m", "hi"));
		assertTrue(heard.error(), heard.error.contains("bigger context size"));
		assertTrue(heard.failure.tooLong);

		// A reply length limit bigger than a small model's context: leaving it out is enough.
		server.clear();
		server.answer(PATH, json(400, "{\"error\":{\"message\":\"'max_tokens' is too large: 16000. This model's maximum context length is 8192 tokens and your request has 50 input tokens (16000 > 8192 - 50).\"}}"),
			json(200, OK));
		assertEquals("Hi", send(compatible("m"), conversation("m", "hi")).reply().text);
		assertFalse(server.bodies.get(1).has("max_tokens"));
	}

	@Test
	public void baseUrls()
	{
		assertEquals("http://localhost:11434/v1/", OpenAiApi.parseBaseUrl(" http://localhost:11434/v1 ").toString());
		assertEquals("https://openrouter.ai/api/v1/", OpenAiApi.parseBaseUrl("https://openrouter.ai/api/v1/chat/completions").toString());
		assertEquals("https://openrouter.ai/api/v1/chat/completions", OpenAiApi.parseBaseUrl("https://openrouter.ai/api/v1/").resolve("chat/completions").toString());
		assertNull(OpenAiApi.parseBaseUrl(""));
		assertNull(OpenAiApi.parseBaseUrl("localhost:11434"));
		assertEquals("localhost:11434", OpenAiApi.describeUrl("http://localhost:11434/v1"));
		assertEquals("openrouter.ai", OpenAiApi.describeUrl("https://openrouter.ai/api/v1"));
		assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/",
			OpenAiApi.parseBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai/").toString());
	}

	@Test
	public void plainHttpOnlyStaysOnThisComputerOrNetwork()
	{
		for (String host : new String[]{"localhost", "127.0.0.1", "192.168.1.20", "10.0.0.5", "172.16.0.1", "172.31.255.1",
			"169.254.1.1", "studio.local", "[::1]", "::1", "fd12:3456::1", "fe80::1"})
		{
			assertTrue(host, OpenAiApi.isPrivate(host));
		}
		// Names that only look like private addresses could point anywhere.
		for (String host : new String[]{"openrouter.ai", "172.32.0.1", "8.8.8.8", "api.example.com", "10.attacker.example",
			"192.168.evil.com", "10.0.0.1.evil.com", "999.1.1.1", "2001:db8::1"})
		{
			assertFalse(host, OpenAiApi.isPrivate(host));
		}
	}

	@Test
	public void thinkingIsRemovedHoweverItsWritten()
	{
		assertEquals("Answer", OpenAiApi.withoutThinking("<think>a</think>Answer"));
		assertEquals("", OpenAiApi.withoutThinking("<think>\nstill thinking when it ran out"));
		assertEquals("Answer", OpenAiApi.withoutThinking("reasoning from a prompt that opened the tag</think>\nAnswer"));
		assertEquals("Plain answer", OpenAiApi.withoutThinking(" Plain answer "));
	}

	@Test
	public void onlyTheRefusedSettingIsDroppedAndNeverStore()
	{
		JsonObject body = gson.fromJson("{\"model\":\"m\",\"store\":false,\"reasoning_effort\":\"low\",\"max_completion_tokens\":8000,"
			+ "\"stream\":true,\"stream_options\":{\"include_usage\":true},\"tools\":[]}", JsonObject.class);
		assertEquals("reasoning_effort", OpenAiApi.rejectedOption(gson, body,
			"{\"error\":{\"message\":\"Unsupported value\",\"param\":\"reasoning_effort\"}}"));
		assertNull(OpenAiApi.rejectedOption(gson, body, "{\"error\":{\"message\":\"store is not supported\",\"param\":\"store\"}}"));
		assertNull(OpenAiApi.rejectedOption(gson, body, "{\"error\":{\"message\":\"Conversation could not be restored\"}}"));
		assertNull("stream itself isn't optional", OpenAiApi.rejectedOption(gson, body, "{\"error\":{\"message\":\"stream is not supported\"}}"));
		assertEquals("max_completion_tokens", OpenAiApi.rejectedOption(gson, body,
			"{\"error\":{\"message\":\"Unsupported parameter: 'max_completion_tokens'\"}}"));
		assertEquals("stream_options", OpenAiApi.rejectedOption(gson, body,
			"{\"error\":{\"message\":\"Unrecognized request argument supplied: stream_options\"}}"));
		assertEquals("tools", OpenAiApi.rejectedOption(gson, body,
			"{\"error\":{\"message\":\"registry.ollama.ai/library/gemma:2b does not support tools\"}}"));
	}

	@Test
	public void modelListsAreRead()
	{
		assertEquals(List.of("a", "b"), OpenAiApi.modelIds(gson, "{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}"));
		assertEquals(List.of("a"), OpenAiApi.modelIds(gson, "[{\"id\":\"a\"}]"));
		assertNull(OpenAiApi.modelIds(gson, "<html>"));
		assertNull(OpenAiApi.modelIds(gson, "{\"models\":[]}"));
	}

	@Test
	public void stoppingARequestMeansNoAnswer() throws Exception
	{
		CountDownLatch release = new CountDownLatch(1);
		server.server().createContext("/slow", exchange ->
		{
			StandIn.await(release);
			exchange.sendResponseHeaders(500, -1);
			exchange.close();
		});
		OpenAiApi api = new OpenAiApi(http, gson, server.url("/slow/"), "", "m", false, "low", scheduler, new ConcurrentHashMap<>());
		StandIn.Heard heard = new StandIn.Heard();
		ChatApi.Pending p = api.send(conversation("m", "hi"), heard);
		p.cancel();
		release.countDown();
		assertFalse(heard.answeredWithin(1000));
	}

	@Test
	public void stoppingPartWayThroughAStreamMeansNoAnswer() throws Exception
	{
		CountDownLatch sent = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		server.server().createContext("/stream", exchange ->
		{
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			try (java.io.OutputStream os = exchange.getResponseBody())
			{
				os.write(content("Partly").getBytes(java.nio.charset.StandardCharsets.UTF_8));
				os.flush();
				sent.countDown();
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException | IOException e)
			{
				// The client went away.
			}
		});
		OpenAiApi api = new OpenAiApi(http, gson, server.url("/stream/"), "", "m", false, "low", scheduler, new ConcurrentHashMap<>());
		StandIn.Heard heard = new StandIn.Heard();
		ChatApi.Pending p = api.send(conversation("m", "hi"), heard);
		assertTrue(sent.await(10, TimeUnit.SECONDS));
		p.cancel();
		release.countDown();
		assertFalse(heard.answeredWithin(1000));
	}
}
