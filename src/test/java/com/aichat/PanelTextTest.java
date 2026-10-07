package com.aichat;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** The panel's words for a reply on its way. */
public class PanelTextTest
{
	private static final long NOW = 1_000_000;

	private static Chat running()
	{
		Chat chat = new Chat("x");
		chat.pending = new ChatApi.Pending();
		chat.runStartedAt = NOW - 12_400;
		return chat;
	}

	@Test
	public void theStatusLineFollowsTheReply()
	{
		Chat chat = running();
		assertEquals("Waiting for a reply... 12s", PanelText.status(chat, NOW));

		chat.liveText = "Vorkath is";
		assertEquals("Writing... 12s", PanelText.status(chat, NOW));

		chat.lookingUp = true;
		assertEquals("Looking things up...", PanelText.status(chat, NOW));
		chat.lookupLine = "Read the Wiki page \"Vorkath\"";
		assertEquals("Looking things up: Read the Wiki page \"Vorkath\"", PanelText.status(chat, NOW));

		chat.retryWhy = "Anthropic is busy";
		chat.retryAt = NOW + 5_200;
		assertEquals("Anthropic is busy; trying again in 6s", PanelText.status(chat, NOW));
		// Once the wait is over, the request is on its way again.
		assertEquals("Looking things up: Read the Wiki page \"Vorkath\"", PanelText.status(chat, NOW + 6_000));
	}

	@Test
	public void summarisingHasItsOwnLine()
	{
		Chat chat = running();
		chat.skipSummary = () ->
		{
		};
		assertEquals("Summarising earlier messages... 12s", PanelText.status(chat, NOW));
		chat.runStartedAt = NOW - 75_000;
		assertEquals("Summarising earlier messages... 1m 15s", PanelText.status(chat, NOW));
	}

	@Test
	public void timesAreShort()
	{
		assertEquals("0s", PanelText.elapsed(-5));
		assertEquals("59s", PanelText.elapsed(59_999));
		assertEquals("1m 0s", PanelText.elapsed(60_000));
	}
}
