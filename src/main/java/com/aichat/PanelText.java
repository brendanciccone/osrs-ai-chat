package com.aichat;

import java.util.List;
import java.util.Locale;

/**
 * What the panel says about a chat, in words: the status line while a reply is on its way, a reply's token counts and
 * cost, and the chat's totals. Pure, so it's tested without Swing.
 */
final class PanelText
{
	private PanelText()
	{
	}

	/** The status line of a chat waiting for its reply, at {@code now} (milliseconds). */
	static String status(Chat chat, long now)
	{
		if (chat.retryWhy != null && chat.retryAt > now)
		{
			return chat.retryWhy + "; trying again in " + ChatApi.seconds(chat.retryAt - now) + "s";
		}
		String elapsed = elapsed(now - chat.runStartedAt);
		if (chat.isSummarizing())
		{
			return "Summarising earlier messages... " + elapsed;
		}
		if (chat.lookingUp)
		{
			return chat.lookupLine == null ? "Looking things up..." : "Looking things up: " + chat.lookupLine;
		}
		return (chat.liveText != null ? "Writing... " : "Waiting for a reply... ") + elapsed;
	}

	/** "12s", "3m 5s". */
	static String elapsed(long ms)
	{
		long secs = Math.max(0, ms / 1000);
		return secs < 60 ? secs + "s" : (secs / 60) + "m " + (secs % 60) + "s";
	}

	/**
	 * A reply's tokens, for the tooltip on its header: "claude-opus-5-5: 1,204 in · 3,410 cached · 352 out · about
	 * $0.01". Input written to the prompt cache counts as "in" (it's new to the provider); "cached" is what was read
	 * back from it, at a fraction of the price. The cost only for models whose price is known.
	 */
	static String usage(ChatApi.Usage u, String model)
	{
		StringBuilder s = new StringBuilder();
		if (model != null && !model.isEmpty())
		{
			s.append(model).append(": ");
		}
		s.append(number(u.input + u.cacheWrite)).append(" in");
		if (u.cacheRead > 0)
		{
			s.append(" · ").append(number(u.cacheRead)).append(" cached");
		}
		s.append(" · ").append(number(u.output)).append(" out");
		Double cost = Pricing.dollars(model, u);
		if (cost != null)
		{
			s.append(" · ").append(dollars(cost));
		}
		return s.toString();
	}

	/**
	 * The chat's tokens so far, for the status line when nothing is on its way: "This chat: 18.2k tokens · about
	 * $0.09"; null before there are any. The cost only when every reply's price is known, and "at least" when some
	 * replies' tokens aren't (chats from before AI Chat counted them, or services that don't say).
	 */
	static String chatTotals(List<Chat.Message> messages)
	{
		long tokens = 0;
		double cost = 0;
		boolean priced = true;
		boolean complete = true;
		for (Chat.Message m : messages)
		{
			if (m.usage == null)
			{
				// What an unfinished reply used goes with the note or error after it.
				complete &= m.role != Chat.Role.ASSISTANT || m.unfinished;
				continue;
			}
			tokens += m.usage.total();
			Double d = Pricing.dollars(m.model, m.usage);
			priced &= d != null;
			cost += d == null ? 0 : d;
		}
		if (tokens == 0)
		{
			return null;
		}
		return "This chat: " + (complete ? "" : "at least ") + tokens(tokens) + " tokens"
			+ (priced && complete ? " · " + dollars(cost) : "");
	}

	/** A token count in a few characters: "950", "18.2k", "1.4M". */
	static String tokens(long n)
	{
		if (n < 1000)
		{
			return String.valueOf(n);
		}
		String k = oneDecimal(n / 1000.0);
		if (!k.equals("1000") && n < 1_000_000)
		{
			return k + "k";
		}
		return oneDecimal(n / 1_000_000.0) + "M";
	}

	/** "about $0.09", or "less than $0.01" for what would round to nothing. */
	static String dollars(double d)
	{
		return d < 0.005 ? "less than $0.01" : String.format(Locale.ROOT, "about $%.2f", d);
	}

	/** 1,204. */
	static String number(long n)
	{
		return String.format(Locale.ROOT, "%,d", n);
	}

	private static String oneDecimal(double d)
	{
		String s = String.format(Locale.ROOT, "%.1f", d);
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}
}
