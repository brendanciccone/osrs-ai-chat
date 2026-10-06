package com.aichat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * "Test" while it runs: only the answer to the latest Test counts, a stopped one says nothing, and a result belongs to
 * the setup it was made with. The provider is a stand-in that answers when the test says so.
 */
public class ConnectionTesterTest
{
	private static final String SETUP = ConnectionCheck.setupKey(AiChatConfig.Provider.CLAUDE, null, "sk-ant-1");
	/** Stands in for the EDT: tasks wait here until the test runs them, as invokeLater's do. */
	private final BlockingQueue<Runnable> edt = new LinkedBlockingQueue<>();
	private int changes;
	private final ConnectionTester tester = new ConnectionTester(edt::add, () -> changes++);
	private final FakeApi api = new FakeApi();

	/** A provider that keeps every model list request, and answers only when the test calls its listener. */
	private static final class FakeApi implements ChatApi
	{
		final List<ModelsListener> listeners = new ArrayList<>();
		final List<Pending> requests = new ArrayList<>();
		RuntimeException failure;

		@Override
		public Pending send(Conversation conversation, Listener listener)
		{
			throw new AssertionError("Test sends no messages");
		}

		@Override
		public String displayName()
		{
			return "Claude";
		}

		@Override
		public Pending listModels(ModelsListener listener)
		{
			if (failure != null)
			{
				throw failure;
			}
			listeners.add(listener);
			Pending p = new Pending();
			requests.add(p);
			return p;
		}
	}

	private void runEdt()
	{
		Runnable r;
		while ((r = edt.poll()) != null)
		{
			r.run();
		}
	}

	private ConnectionCheck.Note note()
	{
		return tester.note(SETUP, "Anthropic", "claude-opus-5-5", true);
	}

	@Test
	public void aTestShowsWhatTheProviderListed()
	{
		assertNull("nothing yet", note());
		tester.start(api, SETUP, false);
		assertEquals(ConnectionCheck.Kind.TESTING, note().kind);

		api.listeners.get(0).onModels(List.of("claude-opus-5-5", "claude-haiku-4-5"));
		assertEquals("not until the EDT runs it", ConnectionCheck.Kind.TESTING, note().kind);
		runEdt();
		assertEquals(ConnectionCheck.Kind.OK, note().kind);
		assertEquals(List.of("claude-opus-5-5", "claude-haiku-4-5"), note().models);
		assertEquals(1, changes);
	}

	@Test
	public void onlyTheLatestTestCounts()
	{
		tester.start(api, SETUP, false);
		ChatApi.ModelsListener first = api.listeners.get(0);
		tester.start(api, SETUP, false);
		assertTrue("the first one is stopped", api.requests.get(0).isCancelled());

		first.onModels(List.of("claude-opus-5-5"));
		runEdt();
		assertEquals(ConnectionCheck.Kind.TESTING, note().kind);
		assertEquals(0, changes);

		api.listeners.get(1).onError("Anthropic didn't accept your API key. Check \"Claude API key\" in the AI Chat settings.");
		runEdt();
		assertEquals(ConnectionCheck.Kind.ERROR, note().kind);
		assertEquals(1, changes);
	}

	@Test
	public void aStoppedTestSaysNothing()
	{
		tester.start(api, SETUP, false);
		tester.stop();
		assertTrue(api.requests.get(0).isCancelled());
		assertNull(note());
		api.listeners.get(0).onModels(List.of("claude-opus-5-5"));
		runEdt();
		assertNull(note());
		assertEquals(0, changes);
	}

	@Test
	public void aResultBelongsToTheSetupItWasMadeWith()
	{
		tester.start(api, SETUP, false);
		api.listeners.get(0).onModels(List.of("claude-opus-5-5"));
		runEdt();
		String otherKey = ConnectionCheck.setupKey(AiChatConfig.Provider.CLAUDE, null, "sk-ant-2");
		assertNull(tester.note(otherKey, "Anthropic", "claude-opus-5-5", true));
		// Another model is fine: the list is read against it.
		assertEquals(ConnectionCheck.Kind.WARNING, tester.note(SETUP, "Anthropic", "claude-opus-9", true).kind);
	}

	@Test
	public void aTestThatCantBeSentSaysSoWithoutTheKey()
	{
		api.failure = new IllegalArgumentException("Unexpected char in header value: sk-ant-secret");
		tester.start(api, SETUP, false);
		ConnectionCheck.Note note = note();
		assertEquals(ConnectionCheck.Kind.ERROR, note.kind);
		assertEquals(RequestRunner.COULDNT_SEND, note.text);
		assertFalse(note.text.contains("sk-ant"));
	}
}
