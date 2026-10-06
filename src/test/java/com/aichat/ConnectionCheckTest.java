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

		ConnectionCheck.Note ok = check.note("Anthropic", "claude-opus-5-5", true);
		assertEquals(ConnectionCheck.Kind.OK, ok.kind);
		assertEquals("Connected to Anthropic. claude-opus-5-5 is available.", ok.text);
		assertEquals(Arrays.asList("claude-opus-5-5", "claude-haiku-4-5"), ok.models);

		ConnectionCheck.Note missing = check.note("Anthropic", "claude-opus-9", true);
		assertEquals(ConnectionCheck.Kind.WARNING, missing.kind);
		assertEquals("Connected, but claude-opus-9 isn't in the list your key can use.", missing.text);
		assertEquals(2, missing.models.size());
	}

	@Test
	public void compatibleServicesNameTheirModelsTheirOwnWay()
	{
		ConnectionCheck check = ConnectionCheck.listed(SETUP, Arrays.asList("llama3.2:latest", "qwen3:8b"), true);
		assertEquals(ConnectionCheck.Kind.OK, check.note("localhost:11434", "llama3.2", false).kind);
		assertEquals("Connected to localhost:11434, but qwen3 isn't one of its models.",
			check.note("localhost:11434", "qwen3", false).text);
		assertEquals("Connected to localhost:11434, but it listed no models to chat with.",
			ConnectionCheck.listed(SETUP, Arrays.asList("nomic-embed-text"), true).note("localhost:11434", "x", false).text);
	}

	@Test
	public void problemsAndServicesWithoutAList()
	{
		ConnectionCheck.Note testing = ConnectionCheck.testing(SETUP).note("Anthropic", "claude-opus-5-5", true);
		assertEquals(ConnectionCheck.Kind.TESTING, testing.kind);
		assertEquals("Testing the connection...", testing.text);

		String bad = "Anthropic didn't accept your API key. Check \"Claude API key\" in the AI Chat settings.";
		ConnectionCheck.Note error = ConnectionCheck.failed(SETUP, bad).note("Anthropic", "claude-opus-5-5", true);
		assertEquals(ConnectionCheck.Kind.ERROR, error.kind);
		assertEquals(bad, error.text);
		assertTrue(error.models.isEmpty());

		ConnectionCheck.Note noList = ConnectionCheck.failed(SETUP, OpenAiApi.NO_MODEL_LIST).note("api.example.com", "m", false);
		assertEquals(ConnectionCheck.Kind.OK, noList.kind);
		assertEquals("Connected to api.example.com. It doesn't list its models, so check the model name on its website.", noList.text);
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
