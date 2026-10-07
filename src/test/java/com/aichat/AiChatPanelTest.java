package com.aichat;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * When the transcript follows the chat down to its end, and the lines under a message that show more when clicked.
 * (The Swing drawing itself isn't tested here.) Runs headless.
 */
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

	/** Swing components belong on the EDT, in tests too. */
	private static void onEdt(Runnable test) throws Throwable
	{
		try
		{
			SwingUtilities.invokeAndWait(test);
		}
		catch (InvocationTargetException e)
		{
			throw e.getCause();
		}
	}

	private static void click(AiChatPanel.ShowMore s)
	{
		s.line.dispatchEvent(new MouseEvent(s.line, MouseEvent.MOUSE_CLICKED, 0, InputEvent.BUTTON1_DOWN_MASK, 5, 5, 1,
			false, MouseEvent.BUTTON1));
	}

	@Test
	public void theFullListOfLookUpsIsAClickAwayWhenTheLineLeavesSomeOut() throws Throwable
	{
		onEdt(() ->
		{
			AiChatPanel.ShowMore s = new AiChatPanel.ShowMore("Click to see everything");
			assertFalse("nothing to show yet", s.isVisible());

			PanelText.Activity shown = PanelText.activity(Arrays.asList(
				"Searched the Wiki for \"abyssal whip\"",
				"Read the Wiki page \"Abyssal whip\"",
				"Checked the GE price of Dragon bones"));
			s.show(shown.lookups, shown.full);
			assertTrue(s.isVisible());
			assertEquals("Looked up: Abyssal whip (Wiki) · Dragon bones (GE price) (show)", s.line.getText());
			assertFalse(s.more.isVisible());
			assertEquals("Click to see everything", s.line.getToolTipText());
			int closed = StackLayout.heightFor(s, 220);

			click(s);
			assertTrue(s.more.isVisible());
			assertEquals("Looked up: Abyssal whip (Wiki) · Dragon bones (GE price) (hide)", s.line.getText());
			// One kind per line, wrapped in the narrow panel, and never read as HTML (a text area can't be).
			assertEquals("Wiki pages: Abyssal whip\nWiki searches: \"abyssal whip\"\nGE prices: Dragon bones",
				s.more.getText());
			assertTrue(s.more.getLineWrap());
			assertTrue("taller when open", StackLayout.heightFor(s, 220) > closed);

			// More look-ups for a reply still on its way: the open list takes them in and stays open.
			PanelText.Activity more = PanelText.activity(Arrays.asList(
				"Searched the Wiki for \"abyssal whip\"",
				"Read the Wiki page \"Abyssal whip\"",
				"Checked the GE price of Dragon bones",
				"Searched the Wiki for \"whip price\""));
			s.show(more.lookups, more.full);
			assertTrue(s.more.isVisible());
			assertEquals("Wiki pages: Abyssal whip\nWiki searches: \"abyssal whip\", \"whip price\"\n"
				+ "GE prices: Dragon bones", s.more.getText());

			click(s);
			assertFalse(s.more.isVisible());
			assertEquals("Looked up: Abyssal whip (Wiki) · Dragon bones (GE price) (show)", s.line.getText());
			assertEquals(closed, StackLayout.heightFor(s, 220));
		});
	}

	@Test
	public void aLineThatSaysItAllHasNothingToClick() throws Throwable
	{
		onEdt(() ->
		{
			AiChatPanel.ShowMore s = new AiChatPanel.ShowMore("Click to see everything");
			s.show("Looked up: Dragon bones (GE price)", "GE prices: Dragon bones");
			click(s);
			assertTrue(s.more.isVisible());

			// The same line, now with nothing more to it: the open list goes, and a click does nothing.
			s.show("Looked up: Dragon bones (GE price)", null);
			assertEquals("Looked up: Dragon bones (GE price)", s.line.getText());
			assertFalse(s.more.isVisible());
			assertNull(s.line.getToolTipText());
			click(s);
			assertFalse(s.more.isVisible());
			assertEquals("Looked up: Dragon bones (GE price)", s.line.getText());

			s.show(null, "GE prices: Dragon bones");
			assertFalse(s.isVisible());
		});
	}

	@Test
	public void theCharacterDetailsOpenTheSameWay() throws Throwable
	{
		onEdt(() ->
		{
			AiChatPanel.ShowMore s = new AiChatPanel.ShowMore("Click to see what was sent with this message");
			s.show("Sent your character details", "[Character: Zezima]\nCombat level: 126");
			assertEquals("Sent your character details (show)", s.line.getText());
			click(s);
			assertEquals("Sent your character details (hide)", s.line.getText());
			assertEquals("[Character: Zezima]\nCombat level: 126", s.more.getText());
			assertTrue(s.more.isVisible());
		});
	}
}
