package com.aichat;

import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** When the transcript follows the chat down to its end. (The Swing drawing itself isn't tested here.) */
public class AiChatPanelTest
{
	@Test
	public void aQuestionJustSentIsFollowedButANoteAboveItIsNot()
	{
		Chat chat = new Chat("x");
		Chat.Message question = new Chat.Message(Chat.Role.USER, "q");
		chat.messages.add(question);
		Map<Chat.Message, Object> shown = new IdentityHashMap<>();
		assertTrue("just sent", AiChatPanel.follow(chat, false, false, shown));

		// The summary comes back while the player has scrolled up: its note goes in above the question, already shown.
		shown.put(question, new Object());
		chat.messages.add(0, new Chat.Message(Chat.Role.NOTE, "Summary of the 40 earlier messages, sent instead of them:"));
		assertFalse("scrolled up", AiChatPanel.follow(chat, false, false, shown));
		assertTrue("reading the end", AiChatPanel.follow(chat, false, true, shown));
		assertTrue("another chat", AiChatPanel.follow(chat, true, false, shown));
		assertFalse(AiChatPanel.follow(new Chat("empty"), false, false, shown));
	}
}
