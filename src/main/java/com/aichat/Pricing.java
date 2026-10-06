package com.aichat;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Roughly what a Claude reply cost, from Anthropic's published prices, for the panel's usage line. Only for models
 * whose price is known here: anything else (every other provider's model included) gets no estimate, rather than a
 * wrong one.
 */
final class Pricing
{
	/**
	 * Dollars per million tokens, as published on 2026-09-25: input, output, and reading from the prompt cache (a tenth
	 * of input, except where Anthropic made it cheaper). Writing to the cache costs a quarter more than input.
	 */
	private static final Map<String, double[]> PRICES = Map.ofEntries(
		Map.entry("claude-fable-5-1", new double[]{10, 50, 0.25}),
		Map.entry("claude-mythos-5-1", new double[]{10, 50, 0.25}),
		Map.entry("claude-fable-5", new double[]{10, 50, 1}),
		Map.entry("claude-mythos-5", new double[]{10, 50, 1}),
		Map.entry("claude-opus-5-5", new double[]{4, 20, 0.20}),
		Map.entry("claude-opus-5", new double[]{5, 25, 0.50}),
		Map.entry("claude-opus-4-8", new double[]{5, 25, 0.50}),
		Map.entry("claude-opus-4-7", new double[]{5, 25, 0.50}),
		Map.entry("claude-opus-4-6", new double[]{5, 25, 0.50}),
		Map.entry("claude-sonnet-5-5", new double[]{2, 10, 0.20}),
		Map.entry("claude-sonnet-5", new double[]{2, 10, 0.20}),
		Map.entry("claude-sonnet-4-6", new double[]{3, 15, 0.30}),
		Map.entry("claude-haiku-4-5", new double[]{1, 5, 0.10}));
	private static final double CACHE_WRITE = 1.25;
	/** A model id with a date on the end, like claude-haiku-4-5-20251001. */
	private static final Pattern DATED = Pattern.compile("(.+)-\\d{8}");

	private Pricing()
	{
	}

	/** The estimated cost in dollars, or null when the model's price isn't known. */
	static Double dollars(String model, ChatApi.Usage usage)
	{
		if (model == null || usage == null)
		{
			return null;
		}
		double[] price = PRICES.get(model);
		Matcher dated = DATED.matcher(model);
		if (price == null && dated.matches())
		{
			price = PRICES.get(dated.group(1));
		}
		if (price == null)
		{
			return null;
		}
		return (usage.input * price[0] + usage.cacheWrite * price[0] * CACHE_WRITE + usage.cacheRead * price[2]
			+ usage.output * price[1]) / 1_000_000;
	}
}
