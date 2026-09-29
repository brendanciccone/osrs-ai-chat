package com.aichat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Both provider clients against a stand-in server on 127.0.0.1: what they send and how they read the answers. */
public class ChatApiTest
{
	private final Gson gson = new Gson();
	private final OkHttpClient http = new OkHttpClient();
	private HttpServer server;
	/** Canned answers by path, served in order; the last one repeats. */
	private final Map<String, List<String[]>> answers = new ConcurrentHashMap<>();
	private final List<JsonObject> bodies = new CopyOnWriteArrayList<>();
	private final List<Map<String, List<String>>> headers = new CopyOnWriteArrayList<>();

	@Before
	public void start() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange ->
		{
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			bodies.add(gson.fromJson(body, JsonObject.class));
			headers.add(exchange.getRequestHeaders());
			List<String[]> queue = answers.getOrDefault(exchange.getRequestURI().getPath(), Collections.singletonList(new String[]{"404", "{}"}));
			String[] answer = queue.size() > 1 ? queue.remove(0) : queue.get(0);
			byte[] out = answer[1].getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(Integer.parseInt(answer[0]), out.length);
			try (OutputStream os = exchange.getResponseBody())
			{
				os.write(out);
			}
		});
		server.start();
	}

	@After
	public void stop()
	{
		server.stop(0);
	}

	private HttpUrl url(String path)
	{
		return HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort() + path);
	}

	private void answer(String path, String... codeAndBodyPairs)
	{
		List<String[]> queue = new ArrayList<>();
		for (int i = 0; i < codeAndBodyPairs.length; i += 2)
		{
			queue.add(new String[]{codeAndBodyPairs[i], codeAndBodyPairs[i + 1]});
		}
		answers.put(path, Collections.synchronizedList(queue));
	}

	/** Sends and waits: [reply, error]. */
	private Object[] send(ChatApi api, ChatApi.Conversation c) throws InterruptedException
	{
		Object[] result = new Object[2];
		CountDownLatch done = new CountDownLatch(1);
		api.send(c, new ChatApi.Listener()
		{
			@Override
			public void onReply(ChatApi.Reply reply)
			{
				result[0] = reply;
				done.countDown();
			}

			@Override
			public void onError(String message)
			{
				result[1] = message;
				done.countDown();
			}
		});
		assertTrue("no answer", done.await(10, TimeUnit.SECONDS));
		return result;
	}

	private static ChatApi.Conversation conversation(String model, String... texts)
	{
		ChatApi.Conversation c = new ChatApi.Conversation();
		c.model = model;
		c.system = "Be brief.";
		for (int i = 0; i < texts.length; i++)
		{
			c.turns.add(new ChatApi.Turn(i % 2 == 0, texts[i]));
		}
		return c;
	}

	private static final String CLAUDE_OK = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
		+ "\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"},{\"type\":\"text\",\"text\":\"Train Agility.\"}],"
		+ "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";

	@Test
	public void claudeRequestsAndReplies() throws Exception
	{
		answer("/v1/messages", "200", CLAUDE_OK);
		AnthropicApi api = new AnthropicApi(http, gson, url("/v1/messages"), "sk-test");
		Object[] r = send(api, conversation("claude-opus-5-5", "What should I train?"));

		ChatApi.Reply reply = (ChatApi.Reply) r[0];
		assertNotNull(String.valueOf(r[1]), reply);
		assertEquals("Train Agility.", reply.text);
		assertEquals("claude-opus-5-5", reply.model);
		assertEquals(2, reply.rawContent.size());
		assertFalse(reply.cutShort);

		JsonObject body = bodies.get(0);
		assertEquals("claude-opus-5-5", body.get("model").getAsString());
		assertEquals("Be brief.", body.get("system").getAsString());
		assertEquals("default", body.get("fallbacks").getAsString());
		assertTrue(body.get("max_tokens").getAsInt() > 1000);
		assertEquals("What should I train?", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
		assertEquals("sk-test", headers.get(0).get("X-api-key").get(0));
		assertEquals("2023-06-01", headers.get(0).get("Anthropic-version").get(0));
		assertEquals("server-side-fallback-2026-07-01", headers.get(0).get("Anthropic-beta").get(0));
	}

	@Test
	public void claudeFallbackOnlyForModelsThatTakeIt() throws Exception
	{
		answer("/v1/messages", "200", CLAUDE_OK);
		send(new AnthropicApi(http, gson, url("/v1/messages"), "k"), conversation("claude-haiku-4-5", "hi"));
		assertFalse(bodies.get(0).has("fallbacks"));
		assertNull(headers.get(0).get("Anthropic-beta"));
	}

	@Test
	public void earlierClaudeRepliesGoBackUnchangedOnlyWhenNothingChanged()
	{
		AnthropicApi api = new AnthropicApi(http, gson, url("/v1/messages"), "k");
		JsonArray raw = gson.fromJson("[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s\"},{\"type\":\"text\",\"text\":\"A\"}]", JsonArray.class);
		ChatApi.Conversation c = conversation("claude-opus-5-5", "Q1", "A", "Q2");
		ChatApi.Turn answer = c.turns.get(1);
		answer.rawContent = raw;
		answer.rawModel = "claude-opus-5-5";
		answer.rawSystem = "Be brief.";

		JsonArray sent = api.body(c, true, true).getAsJsonArray("messages");
		assertEquals(raw, sent.get(1).getAsJsonObject().get("content"));

		// Different instructions, a different model, or the retry without reasoning: text only.
		c.system = "Be very brief.";
		assertEquals("A", api.body(c, true, true).getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
		c.system = "Be brief.";
		c.model = "claude-sonnet-5-5";
		assertEquals("A", api.body(c, true, true).getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
		c.model = "claude-opus-5-5";
		assertEquals("A", api.body(c, false, true).getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
	}

	@Test
	public void claudeRetriesOnceWithoutReasoningWhenHistoryChanged() throws Exception
	{
		answer("/v1/messages",
			"400", "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"messages.1.content.0: Invalid `signature` in `thinking` block. The block is bound to a different conversation.\"}}",
			"200", CLAUDE_OK);
		ChatApi.Conversation c = conversation("claude-opus-5-5", "Q1", "A", "Q2");
		c.turns.get(1).rawContent = gson.fromJson("[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s\"},{\"type\":\"text\",\"text\":\"A\"}]", JsonArray.class);
		c.turns.get(1).rawModel = "claude-opus-5-5";
		c.turns.get(1).rawSystem = "Be brief.";
		Object[] r = send(new AnthropicApi(http, gson, url("/v1/messages"), "k"), c);
		assertNotNull(String.valueOf(r[1]), r[0]);
		assertEquals(2, bodies.size());
		assertTrue(bodies.get(0).getAsJsonArray("messages").get(1).getAsJsonObject().get("content").isJsonArray());
		assertEquals("A", bodies.get(1).getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
		// The plugin is told, so it keeps sending earlier replies as text from now on.
		assertTrue(((ChatApi.Reply) r[0]).historyAsText);
	}

	@Test
	public void claudeDoesWithoutFallbackIfTheAccountCantUseIt() throws Exception
	{
		answer("/v1/messages",
			"400", "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Unexpected value(s) `server-side-fallback-2026-07-01` for the `anthropic-beta` header.\"}}",
			"200", CLAUDE_OK);
		Object[] r = send(new AnthropicApi(http, gson, url("/v1/messages"), "k"), conversation("claude-opus-5-5", "hi"));
		assertNotNull(String.valueOf(r[1]), r[0]);
		assertFalse(((ChatApi.Reply) r[0]).historyAsText);
		assertEquals(2, bodies.size());
		assertTrue(bodies.get(0).has("fallbacks"));
		assertFalse(bodies.get(1).has("fallbacks"));
		assertNull(headers.get(1).get("Anthropic-beta"));
	}

	@Test
	public void claudeProblemsAreExplained() throws Exception
	{
		AnthropicApi api = new AnthropicApi(http, gson, url("/v1/messages"), "bad");
		answer("/v1/messages", "401", "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
		String unauthorized = (String) send(api, conversation("claude-opus-5-5", "hi"))[1];
		assertTrue(unauthorized, unauthorized.contains("Claude API key"));
		answer("/v1/messages", "404", "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"model: claude-nope\"}}");
		assertTrue(((String) send(api, conversation("claude-nope", "hi"))[1]).contains("claude-nope"));
		answer("/v1/messages", "200", "{\"model\":\"claude-opus-5-5\",\"content\":[],\"stop_reason\":\"refusal\",\"stop_details\":{\"type\":\"refusal\",\"category\":null}}");
		assertTrue(((String) send(api, conversation("claude-opus-5-5", "hi"))[1]).contains("declined"));
		answer("/v1/messages", "200", "{\"model\":\"claude-opus-5-5\",\"content\":[{\"type\":\"text\",\"text\":\"Long\"}],\"stop_reason\":\"max_tokens\"}");
		assertTrue(((ChatApi.Reply) send(api, conversation("claude-opus-5-5", "hi"))[0]).cutShort);
	}

	@Test
	public void openAiRequestsAndReplies() throws Exception
	{
		answer("/v1/chat/completions", "200", "{\"id\":\"c1\",\"model\":\"gpt-x\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Do Monkey Madness.\",\"refusal\":null},\"finish_reason\":\"stop\"}]}");
		OpenAiApi api = new OpenAiApi(http, gson, url("/v1/"), "sk-o", "ChatGPT", true, "low", new ConcurrentHashMap<>());
		Object[] r = send(api, conversation("gpt-x", "Next quest?", "Dragon Slayer.", "After that?"));
		assertEquals("Do Monkey Madness.", ((ChatApi.Reply) r[0]).text);

		JsonObject body = bodies.get(0);
		JsonArray messages = body.getAsJsonArray("messages");
		assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
		assertEquals("Be brief.", messages.get(0).getAsJsonObject().get("content").getAsString());
		assertEquals("assistant", messages.get(2).getAsJsonObject().get("role").getAsString());
		assertEquals("After that?", messages.get(3).getAsJsonObject().get("content").getAsString());
		assertTrue(body.has("max_completion_tokens"));
		assertFalse(body.has("max_tokens"));
		assertEquals("low", body.get("reasoning_effort").getAsString());
		assertFalse(body.get("store").getAsBoolean());
		assertFalse(body.has("temperature"));
		assertEquals("Bearer sk-o", headers.get(0).get("Authorization").get(0));
	}

	@Test
	public void aModelThatRefusesAnOptionalSettingIsAskedAgainWithoutIt() throws Exception
	{
		answer("/v1/chat/completions",
			"400", "{\"error\":{\"message\":\"Unrecognized request argument supplied: reasoning_effort\",\"type\":\"invalid_request_error\"}}",
			"200", "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"},\"finish_reason\":\"stop\"}]}");
		Object[] r = send(new OpenAiApi(http, gson, url("/v1/"), "k", "ChatGPT", true, "low", new ConcurrentHashMap<>()), conversation("gpt-4o-mini", "hi"));
		assertEquals("Hi", ((ChatApi.Reply) r[0]).text);
		assertTrue(bodies.get(0).has("reasoning_effort"));
		assertFalse(bodies.get(1).has("reasoning_effort"));
		assertTrue(bodies.get(1).has("store"));
	}

	@Test
	public void compatibleServicesGetNoKeyUnlessGivenAndNoOpenAiOnlyFields() throws Exception
	{
		answer("/v1/chat/completions", "200", "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Hi\"}]},\"finish_reason\":\"length\"}]}");
		OpenAiApi api = new OpenAiApi(http, gson, OpenAiApi.parseBaseUrl(url("/v1").toString()), "", "llama3.2", false, "low", new ConcurrentHashMap<>());
		ChatApi.Reply reply = (ChatApi.Reply) send(api, conversation("llama3.2", "hi"))[0];
		assertEquals("Hi", reply.text);
		assertTrue(reply.cutShort);
		assertNull(headers.get(0).get("Authorization"));
		assertFalse(bodies.get(0).has("max_completion_tokens"));
		assertEquals("low", bodies.get(0).get("reasoning_effort").getAsString());
		assertFalse(bodies.get(0).has("store"));
		assertTrue(bodies.get(0).has("max_tokens"));
	}

	@Test
	public void servicesThatDontThinkAreAskedAgainWithoutReasoningEffort() throws Exception
	{
		String ok = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"},\"finish_reason\":\"stop\"}]}";
		OpenAiApi api = new OpenAiApi(http, gson, url("/v1/"), "", "llama3.2", false, "low", new ConcurrentHashMap<>());
		// Ollama, for a model that can't think.
		answer("/v1/chat/completions", "400", "{\"error\":{\"message\":\"\\\"llama3.2\\\" does not support thinking\",\"type\":\"api_error\"}}", "200", ok);
		assertEquals("Hi", ((ChatApi.Reply) send(api, conversation("llama3.2", "hi"))[0]).text);
		assertTrue(bodies.get(0).has("reasoning_effort"));
		assertFalse(bodies.get(1).has("reasoning_effort"));
		assertTrue("the rest is unchanged", bodies.get(1).has("max_tokens"));

		// Mistral: a 422 listing the field it doesn't accept.
		bodies.clear();
		answer("/v1/chat/completions", "422", "{\"detail\":[{\"type\":\"extra_forbidden\",\"loc\":[\"body\",\"reasoning_effort\"],\"msg\":\"Extra inputs are not permitted\"}]}", "200", ok);
		assertEquals("Hi", ((ChatApi.Reply) send(api, conversation("mistral-small-latest", "hi"))[0]).text);
		assertFalse(bodies.get(1).has("reasoning_effort"));

		// Ollama 0.11.8-0.17.6, for a thinking model other than gpt-oss.
		bodies.clear();
		answer("/v1/chat/completions", "400", "{\"error\":{\"message\":\"think value \\\"low\\\" is not supported for this model\",\"type\":\"invalid_request_error\",\"param\":null,\"code\":null}}", "200", ok);
		assertEquals("Hi", ((ChatApi.Reply) send(api, conversation("qwen3:8b", "hi"))[0]).text);
		assertFalse(bodies.get(1).has("reasoning_effort"));

		// Any other problem is reported, not retried.
		bodies.clear();
		answer("/v1/chat/completions", "400", "{\"error\":{\"message\":\"context length exceeded\"}}");
		assertTrue(((String) send(api, conversation("m", "hi"))[1]).contains("context length exceeded"));
		assertEquals(1, bodies.size());
	}

	@Test
	public void aRefusedSettingIsLeftOutNextTimeButOnlyOnceItsCertain() throws Exception
	{
		String ok = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"},\"finish_reason\":\"stop\"}]}";
		String refusal = "{\"error\":{\"message\":\"\\\"llama3.2\\\" does not support thinking\"}}";
		java.util.Map<String, java.util.Set<String>> refused = new ConcurrentHashMap<>();

		// A retry that fails too proves nothing: nothing is remembered.
		answer("/v1/chat/completions", "400", refusal, "500", "{\"error\":{\"message\":\"boom\"}}");
		send(new OpenAiApi(http, gson, url("/v1/"), "", "llama3.2", false, "low", refused), conversation("llama3.2", "hi"));
		assertTrue(refused.isEmpty());

		// Refused, then answered without it: remembered, so the next message (a new client object, as the plugin
		// makes per message) leaves it out from the start. Three requests for two messages, not four.
		bodies.clear();
		answer("/v1/chat/completions", "400", refusal, "200", ok);
		send(new OpenAiApi(http, gson, url("/v1/"), "", "llama3.2", false, "low", refused), conversation("llama3.2", "one"));
		send(new OpenAiApi(http, gson, url("/v1/"), "", "llama3.2", false, "low", refused), conversation("llama3.2", "two"));
		assertEquals(3, bodies.size());
		assertFalse(bodies.get(2).has("reasoning_effort"));
		assertTrue(bodies.get(2).has("max_tokens"));

		// Another model on the same service is asked normally.
		bodies.clear();
		answer("/v1/chat/completions", "200", ok);
		send(new OpenAiApi(http, gson, url("/v1/"), "", "qwen3:8b", false, "low", refused), conversation("qwen3:8b", "hi"));
		assertEquals("low", bodies.get(0).get("reasoning_effort").getAsString());
	}

	@Test
	public void modelDefaultThinkingSendsNoEffort() throws Exception
	{
		answer("/v1/chat/completions", "200", "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"},\"finish_reason\":\"stop\"}]}");
		send(new OpenAiApi(http, gson, url("/v1/"), "", "qwen/qwen3.8-27b", false, AiChatConfig.Thinking.DEFAULT.effort, new ConcurrentHashMap<>()),
			conversation("qwen/qwen3.8-27b", "hi"));
		assertFalse(bodies.get(0).has("reasoning_effort"));
		assertEquals("low", AiChatConfig.Thinking.SHORT.effort);
	}

	@Test
	public void compatibleQuirks() throws Exception
	{
		OpenAiApi api = new OpenAiApi(http, gson, url("/v1/"), "k", "qwen", false, "low", new ConcurrentHashMap<>());
		// Reasoning inside the reply is dropped.
		answer("/v1/chat/completions", "200", "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"<think>\\nhmm\\n</think>\\n\\nGo to Varrock.\"},\"finish_reason\":\"stop\"}]}");
		assertEquals("Go to Varrock.", ((ChatApi.Reply) send(api, conversation("qwen", "hi"))[0]).text);
		// Gemini: a bad key is a 400 with the error in a list.
		answer("/v1/chat/completions", "400", "[{\"error\":{\"code\":400,\"message\":\"Please pass a valid API key\",\"status\":\"INVALID_ARGUMENT\"}}]");
		assertTrue(((String) send(api, conversation("qwen", "hi"))[1]).contains("didn't accept the API key"));
		// OpenRouter: a failure can come back as a 200 with only an error.
		answer("/v1/chat/completions", "200", "{\"error\":{\"code\":502,\"message\":\"Provider returned error\"}}");
		assertTrue(((String) send(api, conversation("qwen", "hi"))[1]).contains("Provider returned error"));
		// Mistral: {"detail": ...}.
		answer("/v1/chat/completions", "401", "{\"detail\":\"Invalid API Key\"}");
		assertTrue(((String) send(api, conversation("qwen", "hi"))[1]).contains("Invalid API Key"));
		// Thinking parts of a list are skipped.
		answer("/v1/chat/completions", "200", "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":[{\"type\":\"text\",\"text\":\"hmm\"}]},{\"type\":\"text\",\"text\":\"Answer\"}]},\"finish_reason\":\"stop\"}]}");
		assertEquals("Answer", ((ChatApi.Reply) send(api, conversation("qwen", "hi"))[0]).text);
		// Reasoning that ran out of room before its closing tag isn't an answer.
		answer("/v1/chat/completions", "200", "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"<think>\\nOkay, the user wants\"},\"finish_reason\":\"length\"}]}");
		assertTrue(((String) send(api, conversation("qwen", "hi"))[1]).contains("reply length"));
		// Out of room before saying anything.
		answer("/v1/chat/completions", "200", "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"length\"}]}");
		assertTrue(((String) send(api, conversation("qwen", "hi"))[1]).contains("reply length"));
	}

	@Test
	public void openAiProblemsAreExplained() throws Exception
	{
		OpenAiApi api = new OpenAiApi(http, gson, url("/v1/"), "sk-bad", "ChatGPT", true, "low", new ConcurrentHashMap<>());
		answer("/v1/chat/completions", "401", "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}");
		assertTrue(((String) send(api, conversation("gpt-x", "hi"))[1]).contains("API key"));
		answer("/v1/chat/completions", "429", "{\"error\":{\"message\":\"You exceeded your current quota\",\"type\":\"insufficient_quota\"}}");
		assertTrue(((String) send(api, conversation("gpt-x", "hi"))[1]).contains("quota"));
		answer("/v1/chat/completions", "200", "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"refusal\":\"I can't help with that.\"},\"finish_reason\":\"stop\"}]}");
		assertTrue(((String) send(api, conversation("gpt-x", "hi"))[1]).contains("declined"));

		// Nothing listening: says so, and asks whether a local service is running.
		OpenAiApi down = new OpenAiApi(http, gson, HttpUrl.get("http://127.0.0.1:1/v1/"), "", "m", false, "low", new ConcurrentHashMap<>());
		assertTrue(((String) send(down, conversation("m", "hi"))[1]).contains("running"));
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
		JsonObject body = gson.fromJson("{\"model\":\"m\",\"store\":false,\"reasoning_effort\":\"low\",\"max_completion_tokens\":8000}", JsonObject.class);
		assertEquals("reasoning_effort", OpenAiApi.rejectedOption(gson, body,
			"{\"error\":{\"message\":\"Unsupported value\",\"param\":\"reasoning_effort\"}}"));
		assertNull(OpenAiApi.rejectedOption(gson, body, "{\"error\":{\"message\":\"store is not supported\",\"param\":\"store\"}}"));
		assertNull(OpenAiApi.rejectedOption(gson, body, "{\"error\":{\"message\":\"Conversation could not be restored\"}}"));
		assertEquals("max_completion_tokens", OpenAiApi.rejectedOption(gson, body,
			"{\"error\":{\"message\":\"Unsupported parameter: 'max_completion_tokens'\"}}"));
	}

	@Test
	public void pastedKeysAreCleanedAndChecked()
	{
		assertEquals("sk-ant-abc", ChatApi.cleanKey(" sk-ant-\u200babc\u00a0\n"));
		assertTrue(ChatApi.sendableKey(ChatApi.cleanKey(" sk-ant-abc ")));
		assertFalse(ChatApi.sendableKey(ChatApi.cleanKey("sk-ant-abc\u2026")));
		assertFalse(ChatApi.sendableKey(ChatApi.cleanKey("\u201csk-ant-abc\u201d")));
	}

	@Test
	public void repliesBecomeOneGameChatMessagePerParagraphOrListItem()
	{
		String h = "<colHIGHLIGHT>";
		String n = "<colNORMAL>";
		assertEquals(List.of(
				h + "Claude: " + n + "Right now, actually. Training Attack gets you two things at once:",
				h + "- " + n + "The abyssal whip at 70 Attack",
				h + "2. " + n + "Warriors' Guild access",
				h + "Claude: " + n + "You can do it on Slayer tasks."),
			AiChatPlugin.echoMessages("Claude", null,
				"Right now, actually. Training Attack gets you two things at once:\r\n\n"
					+ "\u2022 The abyssal whip at 70 Attack\n2. Warriors\u2019 Guild access\n\n  You can do it on **Slayer** tasks.  ",
				500));
		// The model's text is escaped and never highlighted; a named chat shows its name once.
		List<String> named = AiChatPlugin.echoMessages("Claude", "Bossing", "<col=ef1020>You have been banned.\n- next", 500);
		assertEquals(h + "Claude (Bossing): " + n + "<lt>col=ef1020<gt>You have been banned.", named.get(0));
		assertEquals(h + "- " + n + "next", named.get(1));
		// A list item first still says who it's from.
		assertEquals(h + "Claude: - " + n + "first", AiChatPlugin.echoMessages("Claude", null, "* first", 500).get(0));
		for (String m : named)
		{
			assertFalse(m, m.contains("<br>"));
		}
		// Markdown that's only layout is left out; any kind of line break starts a new message.
		assertEquals(List.of(h + "Claude: " + n + "Top", h + "- " + n + "item", h + "Claude: " + n + "end"),
			AiChatPlugin.echoMessages("Claude", null, "Top\n---\n```java\n|---|---|\n***\r- item\u2028end\n```", 500));
	}

	@Test
	public void longRepliesAreCutWithANoteAboutThePanel()
	{
		String note = "<colHIGHLIGHT>AI Chat: the full reply is in the side panel.";
		// Cut at a word once the length setting is used up.
		List<String> cut = AiChatPlugin.echoMessages("Claude", null, "one two three four five six seven eight nine ten", 20);
		assertEquals(List.of("<colHIGHLIGHT>Claude: <colNORMAL>one two three four ...", note), cut);
		// A new paragraph isn't started as a stub when the setting is nearly used up.
		String first = "x".repeat(40) + " " + "y".repeat(40);
		assertEquals(List.of("<colHIGHLIGHT>Claude: <colNORMAL>" + first, note),
			AiChatPlugin.echoMessages("Claude", null, first + "\nI think you should go to Ardougne next.", 90));
		// At most a few messages, however short.
		List<String> many = AiChatPlugin.echoMessages("Claude", null, "a\nb\nc\nd\ne\nf\ng\nh\ni\nj", 500);
		assertEquals(AiChatPlugin.ECHO_MAX_MESSAGES + 1, many.size());
		assertEquals(note, many.get(many.size() - 1));
		// Words the game couldn't wrap are shortened; the panel has the whole thing.
		String link = "https://oldschool.runescape.wiki/w/Dragon_defender?some=long&query=string&more=1";
		String shown = AiChatPlugin.echoMessages("Claude", null, "See " + link, 500).get(0);
		assertTrue(shown, shown.endsWith(link.substring(0, AiChatPlugin.ECHO_MAX_WORD - 3) + "..."));
		// Nothing to show still says something.
		assertEquals(List.of("<colHIGHLIGHT>AI Chat (error): (empty reply)"), AiChatPlugin.echoMessages("AI Chat (error)", null, " \n ", 500));
	}

	@Test
	public void stoppingARequestMeansNoAnswer() throws Exception
	{
		CountDownLatch release = new CountDownLatch(1);
		server.createContext("/slow", exchange ->
		{
			try
			{
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(500, -1);
			exchange.close();
		});
		OpenAiApi api = new OpenAiApi(http, gson, url("/slow/"), "", "m", false, "low", new ConcurrentHashMap<>());
		CountDownLatch heard = new CountDownLatch(1);
		ChatApi.Pending p = api.send(conversation("m", "hi"), new ChatApi.Listener()
		{
			@Override
			public void onReply(ChatApi.Reply reply)
			{
				heard.countDown();
			}

			@Override
			public void onError(String message)
			{
				heard.countDown();
			}
		});
		p.cancel();
		release.countDown();
		assertFalse(heard.await(1, TimeUnit.SECONDS));
	}

	@Test
	public void characterInfoReadsLikeANote()
	{
		String s = CharacterInfo.format("Zezima", 126, 2277, List.of("Attack 99", "Sailing 1"), 300,
			List.of("Dragon Slayer I"), Collections.emptyList());
		assertTrue(s, s.startsWith("[Character: Zezima, combat level 126, total level 2277. Levels: Attack 99, Sailing 1."));
		assertTrue(s, s.contains("Quests completed (1): Dragon Slayer I."));
		assertTrue(s, s.endsWith("Quests in progress: none.]"));
	}
}
