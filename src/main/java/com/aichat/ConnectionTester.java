package com.aichat;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * "Test" while it runs and after: asks the provider which models the key can use, and keeps the latest answer for the
 * provider, address and key it was asked with (see {@link ConnectionCheck}). Runs on the Swing EDT; the answer comes
 * back through {@code edt}, and counts only if it's for the latest Test and that wasn't stopped since.
 */
final class ConnectionTester
{
	/** Runs a task on the EDT later, never right away: SwingUtilities::invokeLater. */
	private final Executor edt;
	/** The result is in: show it. */
	private final Runnable changed;
	/** The latest Test, or null. */
	private ConnectionCheck check;
	/** The Test request in flight, or null. */
	private ChatApi.Pending testing;

	ConnectionTester(Executor edt, Runnable changed)
	{
		this.edt = edt;
		this.changed = changed;
	}

	/**
	 * Asks {@code api} for its models, instead of any Test still running. {@code setup}: the provider, address and key
	 * it's for ({@link ProviderSetup#connection}); {@code sorted}: put the models in alphabetical order.
	 */
	void start(ChatApi api, String setup, boolean sorted)
	{
		stop();
		check = ConnectionCheck.testing(setup);
		// Set before any answer can be handled: answers are handled on this (the EDT) thread, after this method.
		ChatApi.Pending[] request = new ChatApi.Pending[1];
		try
		{
			request[0] = api.listModels(new ChatApi.ModelsListener()
			{
				@Override
				public void onModels(List<String> ids)
				{
					edt.execute(() -> tested(request[0], ConnectionCheck.listed(setup, ids, sorted)));
				}

				@Override
				public void onError(String message)
				{
					edt.execute(() -> tested(request[0], ConnectionCheck.failed(setup, message)));
				}
			});
		}
		catch (RuntimeException e)
		{
			// As with sending: the exception's message can contain the API key.
			check = ConnectionCheck.failed(setup, RequestRunner.COULDNT_SEND);
		}
		testing = request[0];
	}

	/** The answer to {@code request}: kept only if that's still the Test in flight. */
	private void tested(ChatApi.Pending request, ConnectionCheck result)
	{
		if (request == null || testing != request || request.isCancelled())
		{
			return;
		}
		testing = null;
		check = result;
		changed.run();
	}

	/** Stops a Test in flight, if any, and forgets the last result. */
	void stop()
	{
		if (testing != null)
		{
			testing.cancel();
			testing = null;
		}
		check = null;
	}

	/**
	 * What the latest Test says about the setup as it is now, or null when there's nothing to say: none yet, or it was
	 * for another provider, address or key ({@code setup}). A different model is fine: the list is read against it.
	 */
	ConnectionCheck.Note note(String setup, String service, String model, boolean keyed)
	{
		if (check == null || !check.setup.equals(setup))
		{
			return null;
		}
		return check.note(service, model, keyed);
	}
}
