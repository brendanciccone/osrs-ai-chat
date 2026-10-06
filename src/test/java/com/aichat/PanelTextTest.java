package com.aichat;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The panel's words for a reply on its way, a reply's tokens, and a chat's totals. */
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

	private static ChatApi.Usage usage(long input, long cacheRead, long cacheWrite, long output)
	{
		ChatApi.Usage u = new ChatApi.Usage();
		u.input = input;
		u.cacheRead = cacheRead;
		u.cacheWrite = cacheWrite;
		u.output = output;
		return u;
	}

	private static Chat.Message reply(ChatApi.Usage usage, String model)
	{
		Chat.Message m = new Chat.Message(Chat.Role.ASSISTANT, "reply");
		m.usage = usage;
		m.model = model;
		return m;
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
	public void aRepliesTokensAndCost()
	{
		assertEquals("claude-opus-5-5: 1,204 in · 3,410 cached · 352 out · about $0.01",
			PanelText.usage(usage(1_004, 3_410, 200, 352), "claude-opus-5-5"));
		// No cache, no known price: just the counts.
		assertEquals("gpt-6-luna: 900 in · 120 out", PanelText.usage(usage(900, 0, 0, 120), "gpt-6-luna"));
		assertEquals("claude-haiku-4-5: 12 in · 3 out · less than $0.01", PanelText.usage(usage(12, 0, 0, 3), "claude-haiku-4-5"));
		assertEquals("12 in · 3 out", PanelText.usage(usage(12, 0, 0, 3), null));
	}

	@Test
	public void chatTotalsShowTheCostOnlyWhenEveryPriceIsKnown()
	{
		List<Chat.Message> messages = new ArrayList<>();
		assertNull(PanelText.chatTotals(messages));
		messages.add(new Chat.Message(Chat.Role.USER, "q"));
		messages.add(reply(usage(10_000, 0, 0, 2_000), "claude-opus-5-5"));
		messages.add(new Chat.Message(Chat.Role.USER, "q"));
		messages.add(reply(usage(1_000, 5_000, 0, 200), "claude-opus-5-5-20261001"));
		// 10,000 * 4 + 2,000 * 20 + 1,000 * 4 + 5,000 * 0.2 + 200 * 20 = 89,000 millionths.
		assertEquals("This chat: 18.2k tokens · about $0.09", PanelText.chatTotals(messages));

		// The summary's tokens count too.
		Chat.Message summary = new Chat.Message(Chat.Role.NOTE, "Summary of ...");
		summary.usage = usage(100_000, 0, 0, 1_000);
		summary.model = "claude-opus-5-5";
		messages.add(0, summary);
		assertEquals("This chat: 119.2k tokens · about $0.51", PanelText.chatTotals(messages));

		// One model without a known price: no cost at all, rather than part of it.
		messages.add(reply(usage(100, 0, 0, 10), "llama3.2"));
		assertEquals("This chat: 119.3k tokens", PanelText.chatTotals(messages));
	}

	@Test
	public void repliesWithoutCountsMakeTheTotalAMinimum()
	{
		List<Chat.Message> messages = new ArrayList<>();
		// From a chat saved before AI Chat counted tokens.
		messages.add(new Chat.Message(Chat.Role.ASSISTANT, "old reply"));
		assertNull(PanelText.chatTotals(messages));
		messages.add(reply(usage(1_500, 0, 0, 500), "claude-opus-5-5"));
		assertEquals("This chat: at least 2k tokens", PanelText.chatTotals(messages));
		// Errors and notes have no counts of their own: they don't make it a minimum.
		messages.remove(0);
		messages.add(new Chat.Message(Chat.Role.ERROR, "Anthropic is busy"));
		messages.add(new Chat.Message(Chat.Role.NOTE, "Stopped."));
		// Nor does what was shown of a reply that didn't finish: its counts go with the error or note after it.
		Chat.Message unfinished = new Chat.Message(Chat.Role.ASSISTANT, "Half a");
		unfinished.unfinished = true;
		messages.add(unfinished);
		assertEquals("This chat: 2k tokens · about $0.02", PanelText.chatTotals(messages));
	}

	@Test
	public void numbersAreShort()
	{
		assertEquals("950", PanelText.tokens(950));
		assertEquals("1k", PanelText.tokens(1_000));
		assertEquals("18.2k", PanelText.tokens(18_234));
		assertEquals("999.9k", PanelText.tokens(999_900));
		assertEquals("1M", PanelText.tokens(999_990));
		assertEquals("1.4M", PanelText.tokens(1_420_000));
		assertEquals("less than $0.01", PanelText.dollars(0.004));
		assertEquals("about $0.01", PanelText.dollars(0.005));
		assertEquals("about $12.35", PanelText.dollars(12.3456));
		assertEquals("0s", PanelText.elapsed(-5));
		assertEquals("59s", PanelText.elapsed(59_999));
		assertEquals("1m 0s", PanelText.elapsed(60_000));
	}
}
