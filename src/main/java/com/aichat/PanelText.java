package com.aichat;

/** What the panel's status line says while a reply is on its way. Pure, so it's tested without Swing. */
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
}
