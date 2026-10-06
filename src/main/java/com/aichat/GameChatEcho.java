package com.aichat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;

/**
 * Replies as game chat: the start of each one, in the chat font, as a heads-up while playing. The side panel has the
 * whole reply, so this keeps to plain sentences and drops what only makes sense there (Markdown, long links).
 */
final class GameChatEcho
{
	/** Game chat messages per reply (the game wraps each one), not counting the note that it was cut. */
	static final int MAX_MESSAGES = 8;
	/** Longer words (links) are shortened in game chat: the game wraps only at spaces and hyphens. */
	static final int MAX_WORD = 60;
	/** A paragraph isn't started with less than this much of the length setting left: it'd be a stub. */
	private static final int MIN_PART = 20;

	/** "- item", "* item", "+ item", bullet, "1. item", "2) item". */
	private static final Pattern LIST_ITEM = Pattern.compile("([-*+\u2022]|\\d{1,3}[.)])\\s+(.+)");
	/** Markdown that's only layout (rules, table borders, leftover markers): left out of game chat. */
	private static final Pattern LAYOUT_ONLY = Pattern.compile("[-*_=#|`~:+ ]+");
	/** The first and last line of a code block. */
	private static final Pattern FENCE = Pattern.compile("(```|~~~).*");
	/** Block quote markers, nested ones too: "> ", ">> ", "> > ". */
	private static final Pattern QUOTE = Pattern.compile("^(?:> ?)+");
	/** [text](url) and ![picture](url). Wiki addresses can have brackets in them, like Coins_(item). */
	private static final Pattern LINK = Pattern.compile("!?\\[([^\\[\\]]*)]\\(((?:[^()\\s]|\\([^()\\s]*\\))*)\\)");
	/** <https://...>: the game would show the angle brackets. */
	private static final Pattern BRACKETED_URL = Pattern.compile("<(https?://[^<>\\s]+)>");
	/**
	 * __bold__, _italic_ and *italic*. Never inside a word, where underscores are part of names and Wiki addresses
	 * (Abyssal_whip), and never around spaces, where a star is more likely a sum (2 * 3 * 4).
	 */
	private static final Pattern UNDERSCORE_BOLD = Pattern.compile("(?<![\\p{L}\\p{N}_])__([^_\\s](?:[^_]*[^_\\s])?)__(?![\\p{L}\\p{N}_])");
	private static final Pattern UNDERSCORE_ITALIC = Pattern.compile("(?<![\\p{L}\\p{N}_])_([^_\\s](?:[^_]*[^_\\s])?)_(?![\\p{L}\\p{N}_])");
	private static final Pattern STAR_ITALIC = Pattern.compile("(?<![\\p{L}\\p{N}*])\\*([^*\\s](?:[^*]*[^*\\s])?)\\*(?![\\p{L}\\p{N}*])");

	private GameChatEcho()
	{
	}

	/**
	 * A reply as game chat: one message per paragraph or list item, which the game wraps to the chatbox's width.
	 * Each starts with a marker drawn in the highlight colour (who it's from, or the list marker), so only the game's
	 * own wrapped rows go unmarked. The model's text is escaped and never in the highlight colour: it can't draw a
	 * marker, or pass for one of the game's red warnings.
	 */
	static List<String> echoMessages(String label, String chatName, String text, int maxChars)
	{
		List<String> messages = new ArrayList<>();
		int left = maxChars;
		boolean cut = false;
		for (String raw : text.split("\\r\\n|[\\n\\r\u2028\u2029]"))
		{
			String line = chatText(raw);
			if (line.isEmpty() || LAYOUT_ONLY.matcher(line).matches())
			{
				continue;
			}
			if (messages.size() == MAX_MESSAGES || left <= 0)
			{
				cut = true;
				break;
			}
			String marker = null;
			Matcher item = LIST_ITEM.matcher(line);
			if (item.matches())
			{
				marker = Character.isDigit(line.charAt(0)) ? item.group(1) : "-";
				line = item.group(2);
			}
			if (line.length() > left && left < MIN_PART && !messages.isEmpty())
			{
				cut = true;
				break;
			}
			if (line.length() > left)
			{
				line = cutAtSpace(line, left) + " ...";
				cut = true;
			}
			left -= line.length();
			String lead;
			if (messages.isEmpty())
			{
				lead = label + (chatName == null ? "" : " (" + ChatApi.shorten(chatName, 30) + ")") + ":"
					+ (marker == null ? "" : " " + marker);
			}
			else
			{
				lead = marker != null ? marker : label + ":";
			}
			messages.add(new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT).append(lead + " ")
				.append(ChatColorType.NORMAL).append(line)
				.build());
			if (cut)
			{
				break;
			}
		}
		if (messages.isEmpty())
		{
			messages.add(highlighted(label + ": (empty reply)"));
		}
		if (cut)
		{
			messages.add(highlighted("AI Chat: the full reply is in the side panel."));
		}
		return messages;
	}

	/**
	 * A line of a reply for the chat font: plain punctuation, no Markdown (the panel shows that), no overlong words.
	 * Links keep only their text: the game can't open them, and the panel has them.
	 */
	static String chatText(String raw)
	{
		String s = raw
			.replace('\u2018', '\'').replace('\u2019', '\'')
			.replace('\u201C', '"').replace('\u201D', '"')
			.replace('\u2013', '-').replace('\u2014', '-')
			.replace("\u2026", "...").replace("**", "")
			.replaceAll("[\\s\\p{Z}\\p{Cc}]+", " ").trim();
		if (FENCE.matcher(s).matches())
		{
			return "";
		}
		s = QUOTE.matcher(s).replaceFirst("").replaceFirst("^#{1,6} ", "");
		s = links(s);
		s = BRACKETED_URL.matcher(s).replaceAll("$1").replace("`", "");
		s = UNDERSCORE_BOLD.matcher(s).replaceAll("$1");
		s = UNDERSCORE_ITALIC.matcher(s).replaceAll("$1");
		s = STAR_ITALIC.matcher(s).replaceAll("$1");
		StringBuilder out = new StringBuilder();
		for (String word : s.trim().split(" "))
		{
			out.append(out.length() == 0 ? "" : " ")
				.append(word.length() <= MAX_WORD ? word : word.substring(0, MAX_WORD - 3) + "...");
		}
		return out.toString();
	}

	/** [text](url) as its text, or as the address when there's no text. */
	private static String links(String s)
	{
		Matcher m = LINK.matcher(s);
		StringBuffer out = new StringBuffer();
		while (m.find())
		{
			String text = m.group(1).trim();
			m.appendReplacement(out, Matcher.quoteReplacement(text.isEmpty() ? m.group(2) : text));
		}
		m.appendTail(out);
		return out.toString();
	}

	/** {@code s} cut to at most {@code max} characters, at a space if there's one in the second half. */
	static String cutAtSpace(String s, int max)
	{
		int space = s.lastIndexOf(' ', max);
		return (space > max / 2 ? s.substring(0, space) : s.substring(0, max)).trim();
	}

	/** A message all in the highlight colour, for AI Chat's own notes. */
	static String highlighted(String text)
	{
		return new ChatMessageBuilder().append(ChatColorType.HIGHLIGHT).append(text).build();
	}
}
