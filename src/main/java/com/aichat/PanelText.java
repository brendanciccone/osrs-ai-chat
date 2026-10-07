package com.aichat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the panel's status line says while a reply is on its way, and the lines under each message saying what was
 * looked up or shared for it. Pure, so it's tested without Swing.
 */
final class PanelText
{
	/** Names of each kind the look-up line shows; the others are counted, and listed in full a click away. */
	static final int NAMES_SHOWN = 3;
	/** The panel is narrow: a longer name is cut on the look-up line, and kept whole in the full list. */
	static final int NAME_CHARS = 30;

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

	/** A message's activity lines, as the panel shows them. */
	static final class Activity
	{
		/** "Looked up: Abyssal whip (Wiki) · Dragon bones (GE price)"; null when nothing was. */
		final String lookups;
		/**
		 * Everything that was looked up, in full and one kind per line ("Wiki pages: ...", "Wiki searches: ...", "GE
		 * prices: ..."), for the panel to show under the look-up line when it's clicked. Null when that line already
		 * names it all, or when there's no such line.
		 */
		final String full;
		/** The other lines, as they were and in order: what was shared, and what was skipped or went wrong. */
		final List<String> rest;

		private Activity(String lookups, String full, List<String> rest)
		{
			this.lookups = lookups;
			this.full = full;
			this.rest = rest;
		}

		/** Every line shown, the look-up line first. */
		List<String> lines()
		{
			List<String> all = new ArrayList<>();
			if (lookups != null)
			{
				all.add(lookups);
			}
			all.addAll(rest);
			return all;
		}
	}

	/**
	 * Folds the Wiki and GE price look-ups among {@code lines} (a message's {@link Chat.Message#activity}; null for
	 * none) into one line: the Wiki pages read (or, when none was, what was searched for), then the GE prices checked,
	 * each named once. A line per look-up was too much to read under every reply. When the line leaves something out
	 * (names past the first few, a long name cut, or the searches behind the pages read), the full list comes with it:
	 * it's the only place some of what was sent, such as the words searched for, can be read. What the player shared
	 * of their own, and look-ups that were skipped or went wrong, keep their own lines. Done only when shown: the saved
	 * lines keep every look-up, and chats saved by earlier versions look the same.
	 */
	static Activity activity(List<String> lines)
	{
		List<String> pages = new ArrayList<>();
		List<String> searches = new ArrayList<>();
		List<String> prices = new ArrayList<>();
		List<String> rest = new ArrayList<>();
		for (String line : lines == null ? Collections.<String>emptyList() : lines)
		{
			if (line != null && !fold(line, pages, searches, prices))
			{
				rest.add(line);
			}
		}
		if (pages.isEmpty() && searches.isEmpty() && prices.isEmpty())
		{
			return new Activity(null, null, rest);
		}
		List<String> shown = new ArrayList<>();
		List<String> full = new ArrayList<>();
		// The pages say what the searches found; the searches still go in the full list, as they went to the Wiki too.
		boolean more = !pages.isEmpty() && !searches.isEmpty();
		if (!pages.isEmpty())
		{
			shown.add(names(pages, false) + " (Wiki)");
			full.add("Wiki pages: " + String.join(", ", pages));
			more |= !allNamed(pages);
		}
		else if (!searches.isEmpty())
		{
			shown.add(names(searches, true) + (searches.size() == 1 ? " (Wiki search)" : " (Wiki searches)"));
			more |= !allNamed(searches);
		}
		if (!searches.isEmpty())
		{
			full.add("Wiki searches: " + quoted(searches));
		}
		if (!prices.isEmpty())
		{
			shown.add(names(prices, false) + (prices.size() == 1 ? " (GE price)" : " (GE prices)"));
			full.add("GE prices: " + String.join(", ", prices));
			more |= !allNamed(prices);
		}
		return new Activity("Looked up: " + String.join(" · ", shown), more ? String.join("\n", full) : null, rest);
	}

	/**
	 * For a line {@link LookupTools} wrote for a look-up that went through: adds what was looked up to its list, once.
	 * False for any other line, which is shown as it is.
	 */
	private static boolean fold(String line, List<String> pages, List<String> searches, List<String> prices)
	{
		if (line.startsWith(LookupTools.SEARCHED_WIKI))
		{
			return addOnce(searches, unquote(line.substring(LookupTools.SEARCHED_WIKI.length())));
		}
		if (line.startsWith(LookupTools.READ_WIKI_PAGE))
		{
			return addOnce(pages, pageTitle(line.substring(LookupTools.READ_WIKI_PAGE.length())));
		}
		if (line.startsWith(LookupTools.CHECKED_GE_PRICE))
		{
			String item = line.substring(LookupTools.CHECKED_GE_PRICE.length()).trim();
			return addOnce(prices, item.isEmpty() ? null : item);
		}
		return false;
	}

	/** False when there's no {@code name}: the line wasn't one the look-ups make after all. */
	private static boolean addOnce(List<String> names, String name)
	{
		if (name == null)
		{
			return false;
		}
		if (!names.contains(name))
		{
			names.add(name);
		}
		return true;
	}

	/** {@code Abyssal whip} from {@code "Abyssal whip"}; null for anything else. */
	private static String unquote(String s)
	{
		return s.length() > 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : null;
	}

	/** The title from {@code "Abyssal whip"}, or from {@code "Abyssal whip" (Drops)} when one section was read. */
	private static String pageTitle(String s)
	{
		int end = s.indexOf("\" (");
		return unquote(end > 0 && s.endsWith(")") ? s.substring(0, end + 1) : s);
	}

	/** The first {@link #NAMES_SHOWN} names, cut to fit the narrow panel, then how many more there are. */
	private static String names(List<String> names, boolean quoted)
	{
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < names.size() && i < NAMES_SHOWN; i++)
		{
			String name = ChatApi.shorten(names.get(i), NAME_CHARS);
			out.append(i > 0 ? ", " : "").append(quoted ? '"' + name + '"' : name);
		}
		if (names.size() > NAMES_SHOWN)
		{
			out.append(" +").append(names.size() - NAMES_SHOWN).append(" more");
		}
		return out.toString();
	}

	/** Whether {@link #names} names each of {@code names}, whole. */
	private static boolean allNamed(List<String> names)
	{
		if (names.size() > NAMES_SHOWN)
		{
			return false;
		}
		for (String name : names)
		{
			if (!ChatApi.shorten(name, NAME_CHARS).equals(name))
			{
				return false;
			}
		}
		return true;
	}

	private static String quoted(List<String> names)
	{
		List<String> out = new ArrayList<>();
		for (String name : names)
		{
			out.add('"' + name + '"');
		}
		return String.join(", ", out);
	}
}
