package com.aichat;

import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** What the settings say about the chosen provider: what's missing, what answers, and what "Test" belongs to. */
public class ProviderSetupTest
{
	/** The settings, with the defaults of AiChatConfig unless a test changes them. */
	private static final class Settings implements AiChatConfig
	{
		boolean aiRequests = true;
		Provider provider = Provider.CLAUDE;
		String claudeKey = "sk-ant-abc";
		String claudeModel = "claude-opus-5-5";
		String openaiKey = "";
		String url = "";
		String compatibleKey = "";
		String compatibleModel = "";

		@Override
		public boolean aiRequests()
		{
			return aiRequests;
		}

		@Override
		public Provider provider()
		{
			return provider;
		}

		@Override
		public String claudeApiKey()
		{
			return claudeKey;
		}

		@Override
		public String claudeModel()
		{
			return claudeModel;
		}

		@Override
		public String openaiApiKey()
		{
			return openaiKey;
		}

		@Override
		public String compatibleUrl()
		{
			return url;
		}

		@Override
		public String compatibleApiKey()
		{
			return compatibleKey;
		}

		@Override
		public String compatibleModel()
		{
			return compatibleModel;
		}
	}

	private final Settings settings = new Settings();
	private final ProviderSetup setup = new ProviderSetup(settings);

	@Test
	public void nothingIsSetUpWhileAiRequestsAreOff()
	{
		settings.aiRequests = false;
		assertTrue(setup.problem().startsWith("Turn on \"Enable AI requests\""));
		assertNull(setup.api(null, null, null, new HashMap<>()));
	}

	@Test
	public void claude()
	{
		assertNull(setup.problem());
		assertEquals("claudeModel", setup.modelKey());
		assertEquals("Anthropic", setup.service());
		assertTrue(setup.keyed());
		assertTrue("Anthropic's list needs the key", !setup.keyUnchecked());

		settings.claudeModel = "  claude-haiku-4-5 ";
		assertEquals("claude-haiku-4-5", setup.model());
		settings.claudeKey = " ";
		assertTrue(setup.problem().startsWith("Add your Claude API key"));
		settings.claudeKey = "“sk-ant-abc”";
		assertEquals(ProviderSetup.BAD_KEY, setup.problem());
		settings.claudeKey = "sk-ant-abc";
		settings.claudeModel = "";
		assertEquals("Pick a Claude model next to Send, or set one in the Claude section of the AI Chat settings.",
			setup.problem());
	}

	@Test
	public void chatGpt()
	{
		settings.provider = AiChatConfig.Provider.CHATGPT;
		assertTrue(setup.problem().startsWith("Add your OpenAI API key"));
		settings.openaiKey = "sk-proj-1";
		assertNull(setup.problem());
		assertEquals("openaiModel", setup.modelKey());
		assertEquals("OpenAI", setup.service());
	}

	@Test
	public void openAiCompatible()
	{
		settings.provider = AiChatConfig.Provider.OPENAI_COMPATIBLE;
		assertTrue(setup.problem().startsWith("Set the Base URL"));
		settings.url = "http://localhost:11434/v1";
		assertEquals("Pick a model next to Send, or set one in the Other (OpenAI-compatible) section of the AI Chat "
			+ "settings.",
			setup.problem());
		settings.compatibleModel = "llama3.2";
		assertNull(setup.problem());
		assertEquals("compatibleModel", setup.modelKey());
		assertEquals("localhost:11434", setup.service());
		assertTrue(!setup.keyed());
		assertTrue("no key to check", !setup.keyUnchecked());

		// A key over plain http only to this computer or the home network.
		settings.compatibleKey = "sk-or-1";
		assertNull(setup.problem());
		assertTrue("its model list may not need the key", setup.keyUnchecked());
		settings.url = "http://openrouter.ai/api/v1";
		assertEquals("Use an https:// URL for openrouter.ai: with http:// your API key would cross the internet unencrypted.",
			setup.problem());
		settings.url = "https://openrouter.ai/api/v1";
		assertNull(setup.problem());
	}

	@Test
	public void theApiFollowsTheProvider()
	{
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
		try
		{
			ChatApi claude = setup.api(new okhttp3.OkHttpClient(), new com.google.gson.Gson(), scheduler, new HashMap<>());
			assertNotNull(claude);
			assertEquals("Claude", claude.displayName());
			settings.provider = AiChatConfig.Provider.OPENAI_COMPATIBLE;
			settings.url = "http://localhost:11434/v1";
			settings.compatibleModel = " llama3.2 ";
			assertEquals("llama3.2", setup.api(new okhttp3.OkHttpClient(), new com.google.gson.Gson(), scheduler, new HashMap<>()).displayName());
		}
		finally
		{
			scheduler.shutdownNow();
		}
	}

	@Test
	public void testNeedsNoModel()
	{
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
		try
		{
			// A new Ollama user who doesn't know the model's name yet: Test is how they find it.
			settings.provider = AiChatConfig.Provider.OPENAI_COMPATIBLE;
			settings.url = "http://localhost:11434/v1";
			assertTrue(setup.problem().startsWith("Pick a model"));
			assertNull(setup.connectionProblem());
			assertNull(setup.api(new okhttp3.OkHttpClient(), new com.google.gson.Gson(), scheduler, new HashMap<>()));
			assertNotNull(setup.testApi(new okhttp3.OkHttpClient(), new com.google.gson.Gson(), scheduler, new HashMap<>()));

			// But it does need what it takes to reach the provider, and AI requests on.
			settings.url = "";
			assertTrue(setup.connectionProblem().startsWith("Set the Base URL"));
			assertNull(setup.testApi(new okhttp3.OkHttpClient(), new com.google.gson.Gson(), scheduler, new HashMap<>()));
			settings.url = "http://localhost:11434/v1";
			settings.aiRequests = false;
			assertTrue(setup.connectionProblem().startsWith("Turn on \"Enable AI requests\""));
			assertNull(setup.testApi(new okhttp3.OkHttpClient(), new com.google.gson.Gson(), scheduler, new HashMap<>()));
		}
		finally
		{
			scheduler.shutdownNow();
		}
	}

	@Test
	public void aTestBelongsToTheProviderAddressAndKeyButNotTheModel()
	{
		String before = setup.connection();
		settings.claudeModel = "claude-haiku-4-5";
		assertEquals(before, setup.connection());
		// A pasted key with a stray space is the same key.
		settings.claudeKey = " sk-ant-abc\n";
		assertEquals(before, setup.connection());
		settings.claudeKey = "sk-ant-other";
		assertNotEquals(before, setup.connection());
	}
}
