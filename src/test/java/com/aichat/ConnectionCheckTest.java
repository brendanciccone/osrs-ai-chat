package com.aichat;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** "Test" in the panel: which models are offered, and what the result says about the model that's set. */
public class ConnectionCheckTest
{
	private static final String SETUP = ConnectionCheck.setupKey(AiChatConfig.Provider.CLAUDE, null, "sk-ant-1");

	@Test
	public void onlyChatModelsAreOffered()
	{
		List<String> ids = Arrays.asList("gpt-6-luna", "text-embedding-3-small", "tts-1-hd", "whisper-1", "dall-e-3",
			"omni-moderation-latest", "gpt-image-1", "gpt-4o-audio-preview", "gpt-4o-transcribe", "gpt-realtime",
			"gpt-4o-search-preview", "nomic-embed-text", "GPT-6.1-sol", "gpt-6-luna", "", null);
		assertEquals(Arrays.asList("gpt-6-luna", "GPT-6.1-sol"), ConnectionCheck.chatModels(ids, true));
		// Anthropic's own order (newest first) is kept.
		assertEquals(Arrays.asList("claude-opus-5-5", "claude-haiku-4-5"),
			ConnectionCheck.chatModels(Arrays.asList("claude-opus-5-5", "claude-haiku-4-5"), false));
	}

	@Test
	public void theResultIsReadAgainstTheModelSetNow()
	{
		ConnectionCheck check = ConnectionCheck.listed(SETUP, Arrays.asList("claude-opus-5-5", "claude-haiku-4-5"), false);

		ConnectionCheck.Note ok = check.note("Anthropic", "claude-opus-5-5", true, false);
		assertEquals(ConnectionCheck.Kind.OK, ok.kind);
		assertEquals("Connected to Anthropic. claude-opus-5-5 is available.", ok.text);
		assertEquals(Arrays.asList("claude-opus-5-5", "claude-haiku-4-5"), ok.models);

		ConnectionCheck.Note missing = check.note("Anthropic", "claude-opus-9", true, false);
		assertEquals(ConnectionCheck.Kind.WARNING, missing.kind);
		assertEquals("Connected, but claude-opus-9 isn't in the list your key can use.", missing.text);
		assertEquals(2, missing.models.size());
	}

	@Test
	public void compatibleServicesNameTheirModelsTheirOwnWay()
	{
		ConnectionCheck check = ConnectionCheck.listed(SETUP, Arrays.asList("llama3.2:latest", "qwen3:8b"), true);
		assertEquals(ConnectionCheck.Kind.OK, check.note("localhost:11434", "llama3.2", false, false).kind);
		assertEquals("Connected to localhost:11434, but qwen3 isn't one of its models.",
			check.note("localhost:11434", "qwen3", false, false).text);
		assertEquals("Connected to localhost:11434, but it listed no models to chat with.",
			ConnectionCheck.listed(SETUP, Arrays.asList("nomic-embed-text"), true).note("localhost:11434", "x", false, false).text);
	}

	@Test
	public void problemsAndServicesWithoutAList()
	{
		ConnectionCheck.Note testing = ConnectionCheck.testing(SETUP).note("Anthropic", "claude-opus-5-5", true, false);
		assertEquals(ConnectionCheck.Kind.TESTING, testing.kind);
		assertEquals("Testing the connection...", testing.text);

		String bad = "Anthropic didn't accept your API key. Check the API key in the Claude section of the AI Chat settings.";
		ConnectionCheck.Note error = ConnectionCheck.failed(SETUP, bad).note("Anthropic", "claude-opus-5-5", true, false);
		assertEquals(ConnectionCheck.Kind.ERROR, error.kind);
		assertEquals(bad, error.text);
		assertTrue(error.models.isEmpty());

		// Any web server can say "not found": that's no sign the URL is right.
		ConnectionCheck.Note noList = ConnectionCheck.failed(SETUP, OpenAiApi.NO_MODEL_LIST).note("localhost:11434", "m", false, false);
		assertEquals(ConnectionCheck.Kind.WARNING, noList.kind);
		assertEquals("localhost:11434 answered, but not with a list of models, so Test can't check the URL or the model "
			+ "name. Check both on the service's website.", noList.text);
		// Without a model set, it still says where one goes.
		ConnectionCheck.Note noListNoModel = ConnectionCheck.failed(SETUP, OpenAiApi.NO_MODEL_LIST).note("localhost:11434", " ", false, false);
		assertEquals(ConnectionCheck.Kind.WARNING, noListNoModel.kind);
		assertEquals("localhost:11434 answered, but not with a list of models, so Test can't check the URL or offer a "
			+ "model. Set the model in the Other (OpenAI-compatible) section of the AI Chat settings, with its name from the "
			+ "service's website.", noListNoModel.text);
	}

	@Test
	public void aListAnyoneCanReadVouchesForNoKey()
	{
		// OpenRouter lists its models without looking at the key: a green note mustn't suggest the key was checked.
		ConnectionCheck check = ConnectionCheck.listed(SETUP, Arrays.asList("openai/gpt-6-luna", "qwen/qwen3-32b"), true);
		ConnectionCheck.Note listed = check.note("openrouter.ai", "qwen/qwen3-32b", false, true);
		assertEquals(ConnectionCheck.Kind.OK, listed.kind);
		assertEquals("Connected to openrouter.ai. qwen/qwen3-32b is available. Your key is checked when you send: some "
			+ "services list their models for anyone.", listed.text);
		assertEquals("Connected to openrouter.ai. Your key is checked when you send: some services list their models for "
			+ "anyone. Choose a model:", check.note("openrouter.ai", "", false, true).text);
		// Without a key there's nothing to say about one.
		assertEquals("Connected to openrouter.ai. qwen/qwen3-32b is available.", check.note("openrouter.ai", "qwen/qwen3-32b", false, false).text);
	}

	@Test
	public void withoutAModelTheListIsOffered()
	{
		ConnectionCheck check = ConnectionCheck.listed(SETUP, Arrays.asList("llama3.2:latest", "qwen3:8b"), true);
		ConnectionCheck.Note note = check.note("localhost:11434", "", false, false);
		assertEquals(ConnectionCheck.Kind.OK, note.kind);
		assertEquals("Connected to localhost:11434. Choose a model:", note.text);
		assertEquals(Arrays.asList("llama3.2:latest", "qwen3:8b"), note.models);
	}

	@Test
	public void anAliasIsFoundAsTheDatedModelItPointsTo()
	{
		// Anthropic lists claude-haiku-4-5 only by its full name.
		ConnectionCheck check = ConnectionCheck.listed(SETUP, Arrays.asList("claude-opus-5-5", "claude-haiku-4-5-20251001"), false);
		ConnectionCheck.Note note = check.note("Anthropic", "claude-haiku-4-5", true, false);
		assertEquals(ConnectionCheck.Kind.OK, note.kind);
		assertEquals("Connected to Anthropic. claude-haiku-4-5 is available.", note.text);
		// Only a date: another model that starts the same way isn't it.
		assertEquals(ConnectionCheck.Kind.WARNING, ConnectionCheck.listed(SETUP, Arrays.asList("claude-haiku-4-5-latest-x",
			"claude-haiku-4-5-2025100"), false).note("Anthropic", "claude-haiku-4-5", true, false).kind);
	}

	@Test
	public void aResultBelongsToItsProviderAddressAndKey()
	{
		String claude = ConnectionCheck.setupKey(AiChatConfig.Provider.CLAUDE, null, "sk-ant-1");
		assertEquals(SETUP, claude);
		assertNotEquals(claude, ConnectionCheck.setupKey(AiChatConfig.Provider.CLAUDE, null, "sk-ant-2"));
		assertNotEquals(claude, ConnectionCheck.setupKey(AiChatConfig.Provider.CHATGPT, null, "sk-ant-1"));
		assertNotEquals(ConnectionCheck.setupKey(AiChatConfig.Provider.OPENAI_COMPATIBLE, "http://localhost:11434/v1", ""),
			ConnectionCheck.setupKey(AiChatConfig.Provider.OPENAI_COMPATIBLE, "http://localhost:1234/v1", ""));
		// The key itself isn't kept.
		assertTrue(!claude.contains("sk-ant-1"));
	}
}
