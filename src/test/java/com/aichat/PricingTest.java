package com.aichat;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Dollar estimates for Claude replies: known models only, cache reads and writes priced as Anthropic does. */
public class PricingTest
{
	private static ChatApi.Usage usage(long input, long cacheRead, long cacheWrite, long output)
	{
		ChatApi.Usage u = new ChatApi.Usage();
		u.input = input;
		u.cacheRead = cacheRead;
		u.cacheWrite = cacheWrite;
		u.output = output;
		return u;
	}

	@Test
	public void knownModelsArePriced()
	{
		// A million of each: $4 in, $0.20 cache read, $5 cache write (1.25x), $20 out.
		assertEquals(29.20, Pricing.dollars("claude-opus-5-5", usage(1_000_000, 1_000_000, 1_000_000, 1_000_000)), 1e-9);
		assertEquals(0.25, Pricing.dollars("claude-fable-5-1", usage(0, 1_000_000, 0, 0)), 1e-9);
		assertEquals(1.00, Pricing.dollars("claude-fable-5", usage(0, 1_000_000, 0, 0)), 1e-9);
		assertEquals(50, Pricing.dollars("claude-fable-5-1", usage(0, 0, 0, 1_000_000)), 1e-9);
		assertEquals(6.25, Pricing.dollars("claude-opus-4-8", usage(0, 0, 1_000_000, 0)), 1e-9);
		assertEquals(0.30, Pricing.dollars("claude-sonnet-4-6", usage(0, 1_000_000, 0, 0)), 1e-9);
		assertEquals(10, Pricing.dollars("claude-sonnet-5", usage(0, 0, 0, 1_000_000)), 1e-9);
		// A typical reply: 1,204 in, 3,410 cached, 352 out on Claude Opus 5.5 is about a cent.
		assertEquals(0.012538, Pricing.dollars("claude-opus-5-5", usage(1204, 3410, 0, 352)), 1e-9);
	}

	@Test
	public void datedIdsArePricedLikeTheirModel()
	{
		assertEquals(Pricing.dollars("claude-haiku-4-5", usage(10, 20, 30, 40)), Pricing.dollars("claude-haiku-4-5-20251001", usage(10, 20, 30, 40)));
	}

	@Test
	public void unknownModelsGetNoEstimate()
	{
		assertNull(Pricing.dollars("gpt-6-luna", usage(1, 1, 1, 1)));
		assertNull(Pricing.dollars("claude-opus-9", usage(1, 1, 1, 1)));
		assertNull(Pricing.dollars("claude-opus-5-5-preview", usage(1, 1, 1, 1)));
		assertNull(Pricing.dollars("claude-opus-5-5-2026", usage(1, 1, 1, 1)));
		assertNull(Pricing.dollars(null, usage(1, 1, 1, 1)));
		assertNull(Pricing.dollars("claude-opus-5-5", null));
	}
}
