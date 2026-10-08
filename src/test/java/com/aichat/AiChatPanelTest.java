package com.aichat;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.SwingUtilities;
import javax.swing.text.StyleConstants;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The panel with a stand-in plugin: the composer's button, the starters, Retry and the actions under replies, the
 * banner, the player's bubbles, the reply on its way, when the transcript follows the chat down, and the lines that show
 * more when clicked. (The drawing itself isn't tested here.) Runs headless.
 */
public class AiChatPanelTest
{
	/** The plugin, as far as the panel can tell: settings and chats the test sets, and a record of what was asked. */
	private static final class FakeHost implements AiChatPanel.Host
	{
		final List<Chat> chats = new ArrayList<>();
		Chat current;
		String problem;
		ConnectionCheck.Note note;
		boolean dismissed;
		String model = "claude-opus-5-5";
		List<String> choices = Arrays.asList("claude-opus-5-5", "claude-haiku-4-5");
		final List<String> sent = new ArrayList<>();
		final List<Chat.Message> retried = new ArrayList<>();
		final List<String> chosen = new ArrayList<>();
		int stops;
		boolean accept = true;

		FakeHost()
		{
			current = new Chat("Chat 1");
			chats.add(current);
		}

		@Override
		public List<Chat> chats()
		{
			return chats;
		}

		@Override
		public Chat currentChat()
		{
			return current;
		}

		@Override
		public String setupProblem()
		{
			return problem;
		}

		@Override
		public String testProblem()
		{
			return problem;
		}

		@Override
		public ConnectionCheck.Note connectionNote()
		{
			return dismissed ? null : note;
		}

		@Override
		public void dismissNote()
		{
			dismissed = true;
		}

		@Override
		public String model()
		{
			return model;
		}

		@Override
		public List<String> modelChoices()
		{
			return choices;
		}

		@Override
		public String modelTip()
		{
			return "The model new messages go to.";
		}

		@Override
		public boolean send(String text)
		{
			sent.add(text);
			return accept;
		}

		@Override
		public void stop()
		{
			stops++;
		}

		@Override
		public void retry(Chat chat, Chat.Message m)
		{
			retried.add(m);
		}

		@Override
		public void newChat()
		{
		}

		@Override
		public void selectChat(Chat chat)
		{
		}

		@Override
		public void renameChat(Chat chat, String name)
		{
		}

		@Override
		public void clearChat(Chat chat)
		{
		}

		@Override
		public void deleteChat(Chat chat)
		{
		}

		@Override
		public void testConnection()
		{
		}

		@Override
		public void chooseModel(String model)
		{
			chosen.add(model);
		}
	}

	private final FakeHost host = new FakeHost();

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

	/** Lays out {@code c} and everything in it, as showing it would: headless, nothing is shown. */
	private static void layOut(Component c)
	{
		if (c instanceof Container)
		{
			((Container) c).doLayout();
			for (Component child : ((Container) c).getComponents())
			{
				layOut(child);
			}
		}
	}

	private Chat.Message add(Chat.Role role, String text)
	{
		Chat.Message m = new Chat.Message(role, text);
		host.current.messages.add(m);
		return m;
	}

	@Test
	public void theButtonSendsStopsOrSkips() throws Throwable
	{
		onEdt(() ->
		{
			AiChatPanel panel = new AiChatPanel(host);
			Composer.ActionButton button = panel.composer.action;
			assertEquals(Composer.Mode.SEND, button.mode);
			assertEquals("Send (Enter)", button.getToolTipText());
			assertFalse("nothing to send", button.isEnabled());

			panel.composer.input.setText("  ");
			assertFalse("only spaces", button.isEnabled());
			panel.composer.input.setText("What drops a whip?");
			assertTrue(button.isEnabled());
			button.doClick();
			assertEquals(Arrays.asList("What drops a whip?"), host.sent);
			assertEquals("sent: the box is cleared", "", panel.composer.input.getText());

			// A message that can't go yet stays in the box.
			host.accept = false;
			panel.composer.input.setText("And a dragon pickaxe?");
			button.doClick();
			assertEquals("And a dragon pickaxe?", panel.composer.input.getText());

			// A reply on its way: Stop, whatever's in the box.
			host.current.messages.add(new Chat.Message(Chat.Role.USER, "q"));
			host.current.pending = new ChatApi.Pending();
			panel.refreshAll();
			assertEquals(Composer.Mode.STOP, button.mode);
			assertEquals("Stop", button.getToolTipText());
			assertTrue(button.isEnabled());
			panel.composer.input.setText("");
			assertTrue("Stop has nothing to send", button.isEnabled());
			int sent = host.sent.size();
			button.doClick();
			assertEquals(1, host.stops);
			assertEquals(sent, host.sent.size());

			// A long chat's summary first: Skip, with its own tooltip.
			host.current.skipSummary = () ->
			{
			};
			panel.refreshAll();
			assertEquals(Composer.Mode.SKIP, button.mode);
			assertEquals("Skip", button.getText());
			assertEquals(Composer.SKIP_TIP, button.getToolTipText());
			button.doClick();
			assertEquals(2, host.stops);

			host.current.skipSummary = null;
			host.current.pending = null;
			panel.refreshAll();
			assertEquals(Composer.Mode.SEND, button.mode);
			assertFalse(button.isEnabled());
		});
	}

	@Test
	public void theChatsTitleIsAsWideAsItIsWithItsArrowRightAfterIt() throws Throwable
	{
		onEdt(() ->
		{
			AiChatPanel panel = new AiChatPanel(host);
			panel.setSize(225, 600);
			layOut(panel);
			JComponent title = (JComponent) panel.chatSelect.getParent();
			int room = title.getWidth();
			int shown = panel.chatSelect.getWidth();
			assertTrue("not stretched to the buttons", shown < room);

			// The list holds a longer name: the title doesn't make room for it.
			Chat other = new Chat("Plans for the whole Desert Treasure II quest line, start to end");
			host.chats.add(other);
			panel.refreshAll();
			layOut(panel);
			assertEquals(shown, panel.chatSelect.getWidth());

			// Shown, it's cut to the room beside the buttons.
			host.current = other;
			panel.refreshAll();
			layOut(panel);
			assertEquals(room, panel.chatSelect.getWidth());
			assertTrue(title.getX() + title.getWidth() <= panel.newChat.getParent().getX());
		});
	}

	@Test
	public void jumpToTheLatestShowsWhileThePlayerIsntReadingTheEnd() throws Throwable
	{
		AiChatPanel[] made = new AiChatPanel[1];
		onEdt(() ->
		{
			for (int i = 0; i < 12; i++)
			{
				add(Chat.Role.USER, "Question " + i + ", long enough to wrap onto a second line in the narrow panel");
				add(Chat.Role.ASSISTANT, "Answer " + i + ", long enough to wrap onto a second line in the narrow panel, "
					+ "and onto a third one too.");
			}
			made[0] = new AiChatPanel(host);
			made[0].setSize(225, 400);
			layOut(made[0]);
			layOut(made[0]);
		});
		AiChatPanel panel = made[0];
		JScrollBar bar = panel.transcriptScroll.getVerticalScrollBar();
		// A chat opens at its end.
		onEdt(() ->
		{
			assertTrue("the chat is longer than the panel", bar.getMaximum() > 2 * bar.getVisibleAmount());
			assertEquals(bar.getMaximum(), bar.getValue() + bar.getVisibleAmount());
			assertFalse(panel.jump.isVisible());

			bar.setValue(0);
			assertTrue("scrolled up", panel.jump.isVisible());
			bar.setValue(bar.getMaximum());
			assertFalse("back at the end", panel.jump.isVisible());

			bar.setValue(0);
			panel.jump.doClick();
		});
		onEdt(() ->
		{
			assertEquals("taken to the end", bar.getMaximum(), bar.getValue() + bar.getVisibleAmount());
			assertFalse(panel.jump.isVisible());

			// A question sent from the end: the transcript grows before it's followed down, and that isn't the player
			// scrolling up.
			add(Chat.Role.USER, "And how long does the Slayer task take, roughly, if I bring a cannon along with me?");
			host.current.pending = new ChatApi.Pending();
			host.current.runStartedAt = System.currentTimeMillis();
			panel.refreshAll();
			layOut(panel);
			assertTrue(bar.getValue() + bar.getVisibleAmount() < bar.getMaximum() - 24);
			assertFalse("no flash", panel.jump.isVisible());
		});
		onEdt(() ->
		{
			assertEquals("followed", bar.getMaximum(), bar.getValue() + bar.getVisibleAmount());
			assertFalse(panel.jump.isVisible());
		});
	}

	@Test
	public void theInputBoxSaysWhatItsFor() throws Throwable
	{
		onEdt(() ->
		{
			AiChatPanel panel = new AiChatPanel(host);
			assertEquals("Ask anything…", Composer.PLACEHOLDER);
			// Painted in the box, not a tooltip: drawn while it's empty, and gone once there's text.
			panel.composer.input.setSize(200, 40);
			BufferedImage empty = paint(panel.composer.input);
			panel.composer.input.setText(" ");
			BufferedImage typed = paint(panel.composer.input);
			assertTrue("the placeholder draws something", differs(empty, typed));
		});
	}

	private static BufferedImage paint(JComponent c)
	{
		BufferedImage image = new BufferedImage(c.getWidth(), c.getHeight(),
			BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		c.paint(g);
		g.dispose();
		return image;
	}

	private static boolean differs(BufferedImage a, BufferedImage b)
	{
		for (int x = 0; x < a.getWidth(); x++)
		{
			for (int y = 0; y < a.getHeight(); y++)
			{
				if (a.getRGB(x, y) != b.getRGB(x, y))
				{
					return true;
				}
			}
		}
		return false;
	}

	@Test
	public void startersFillTheInputAndNeverSend() throws Throwable
	{
		onEdt(() ->
		{
			AiChatPanel panel = new AiChatPanel(host);
			assertTrue("an empty chat welcomes the player", panel.showsEmpty());
			assertEquals(4, panel.empty.chips.size());
			for (int i = 0; i < EmptyChat.STARTERS.size(); i++)
			{
				EmptyChat.Starter starter = EmptyChat.STARTERS.get(i);
				assertEquals(starter.label, panel.empty.chips.get(i).getText());
				panel.empty.chips.get(i).doClick();
				assertEquals(starter.text, panel.composer.input.getText());
				assertEquals("the caret waits at the end", starter.text.length(), panel.composer.input.getCaretPosition());
			}
			assertTrue("nothing is sent: that costs money", host.sent.isEmpty());
			assertEquals("How much is ", EmptyChat.STARTERS.get(1).text);
			assertTrue(panel.composer.action.isEnabled());

			add(Chat.Role.USER, "q");
			panel.refreshAll();
			assertFalse(panel.showsEmpty());
		});
	}

	@Test
	public void retryWritesAgainOnlyTheLatestReply() throws Throwable
	{
		onEdt(() ->
		{
			add(Chat.Role.USER, "Q1");
			Chat.Message a1 = add(Chat.Role.ASSISTANT, "A1");
			add(Chat.Role.USER, "Q2");
			Chat.Message a2 = add(Chat.Role.ASSISTANT, "A2");
			AiChatPanel panel = new AiChatPanel(host);
			assertTrue(panel.row(a2).offersRetry());
			assertFalse("never an older reply", panel.row(a1).offersRetry());

			MessageRow.Reply latest = (MessageRow.Reply) panel.row(a2);
			latest.actions.retry.doClick();
			assertEquals(Arrays.asList(a2), host.retried);

			// A question after it that failed: Retry moves to the error, which sends that question again.
			Chat.Message q3 = add(Chat.Role.USER, "Q3");
			q3.unanswered = true;
			Chat.Message error = add(Chat.Role.ERROR, "Couldn't reach Anthropic.");
			panel.refreshAll();
			assertFalse(panel.row(a2).offersRetry());
			assertTrue(panel.row(error).offersRetry());
			((MessageRow.Problem) panel.row(error)).retry.doClick();
			assertSame(error, host.retried.get(1));

			// Stopped: Retry goes on the note.
			host.current.messages.remove(error);
			Chat.Message stopped = add(Chat.Role.NOTE, "Stopped.");
			panel.refreshAll();
			assertTrue(panel.row(stopped).offersRetry());

			// Nothing while a reply is on its way.
			host.current.messages.remove(stopped);
			host.current.messages.remove(q3);
			host.current.pending = new ChatApi.Pending();
			panel.refreshAll();
			assertFalse(panel.row(a2).offersRetry());
		});
	}

	@Test
	public void theLatestRepliesActionsAlwaysShowAndOlderOnesOnHover() throws Throwable
	{
		onEdt(() ->
		{
			add(Chat.Role.USER, "Q1");
			Chat.Message a1 = add(Chat.Role.ASSISTANT, "A1 **bold**");
			add(Chat.Role.USER, "Q2");
			Chat.Message a2 = add(Chat.Role.ASSISTANT, "A2");
			AiChatPanel panel = new AiChatPanel(host);
			MessageRow.Reply older = (MessageRow.Reply) panel.row(a1);
			MessageRow.Reply latest = (MessageRow.Reply) panel.row(a2);
			assertTrue(latest.actions.isShown());
			assertTrue(latest.actions.copy.isVisible());
			assertFalse(older.actions.isShown());
			int height = older.actions.getPreferredSize().height;
			assertTrue("its place is kept", height > 0);

			older.setSize(200, 100);
			older.dispatchEvent(new MouseEvent(older, MouseEvent.MOUSE_ENTERED, 0, 0, 10, 10, 0, false));
			assertTrue(older.actions.isShown());
			assertTrue(older.actions.copy.isVisible());
			assertFalse("Copy, but no Retry on an older reply", older.actions.retry.isVisible());
			assertEquals("the transcript doesn't jump", height, older.actions.getPreferredSize().height);
			// Over a part of the row is still over the row.
			older.body.setBounds(0, 0, 200, 20);
			older.body.dispatchEvent(new MouseEvent(older.body, MouseEvent.MOUSE_EXITED, 0, 0, 10, 30, 0, false));
			assertTrue(older.actions.isShown());
			older.dispatchEvent(new MouseEvent(older, MouseEvent.MOUSE_EXITED, 0, 0, 10, 300, 0, false));
			assertFalse(older.actions.isShown());

			// Who answered and when, in the tooltip rather than a header line.
			assertEquals(PanelText.tooltip(a1), older.body.getToolTipText());
			assertTrue(older.body.getToolTipText().startsWith("Assistant · "));
			// Copy is the whole reply as plain text (what's copied; the clipboard itself isn't touched here).
			assertEquals("A1 bold", older.body.plainText());
		});
	}

	@Test
	public void theBannerShowsProblemsUntilFixedAndTestResultsUntilClosed() throws Throwable
	{
		onEdt(() ->
		{
			host.problem = "Turn on \"Enable AI requests\" in the AI Chat settings, then choose a provider and add your API key.";
			AiChatPanel panel = new AiChatPanel(host);
			assertTrue(panel.banner.isVisible());
			assertEquals(host.problem + " Open RuneLite's settings (the wrench) and search for AI Chat.",
				panel.banner.text.getText());
			assertFalse("only fixing it makes it go", panel.banner.close.isVisible());

			host.problem = null;
			panel.refreshAll();
			assertFalse("nothing to say", panel.banner.isVisible());

			host.note = new ConnectionCheck.Note(ConnectionCheck.Kind.OK, "Connected to Anthropic. claude-opus-5-5 is available.");
			panel.refreshAll();
			assertTrue(panel.banner.isVisible());
			assertEquals(host.note.text, panel.banner.text.getText());
			assertEquals(PanelStyle.OK_COLOR, panel.banner.text.getForeground());
			assertTrue(panel.banner.close.isVisible());
			panel.banner.close.doClick();
			assertTrue(host.dismissed);
			assertFalse(panel.banner.isVisible());

			host.dismissed = false;
			host.note = new ConnectionCheck.Note(ConnectionCheck.Kind.ERROR, "Anthropic didn't accept your API key.");
			panel.refreshAll();
			assertEquals(PanelStyle.ERROR_COLOR, panel.banner.text.getForeground());
		});
	}

	@Test
	public void thePlayersBubblesHugShortTextOnTheRight() throws Throwable
	{
		onEdt(() ->
		{
			Chat.Message shortOne = add(Chat.Role.USER, "hi");
			Chat.Message longOne = add(Chat.Role.USER, "How do I get an abyssal whip, and what Slayer level do I need for "
				+ "abyssal demons in the catacombs?");
			AiChatPanel panel = new AiChatPanel(host);
			MessageRow.Question row = (MessageRow.Question) panel.row(shortOne);
			MessageRow.Bubble bubble = row.bubble;
			int padding = bubble.getInsets().left + bubble.getInsets().right;
			assertTrue("hugs the text", bubble.fittedWidth(200) < 60);
			assertTrue(bubble.fittedWidth(200) >= bubble.body.naturalWidth() + padding);
			assertEquals("at most 85% of the row", 170, ((MessageRow.Question) panel.row(longOne)).bubble.fittedWidth(200));

			// Laid out on the right, and as tall as the wrapped text inside it needs.
			JPanel holder = new JPanel(new StackLayout(0));
			holder.add(panel.row(longOne));
			holder.setSize(200, 1000);
			holder.doLayout();
			panel.row(longOne).doLayout();
			MessageRow.Bubble big = ((MessageRow.Question) panel.row(longOne)).bubble;
			assertEquals(170, big.getWidth());
			assertEquals(200, big.getX() + big.getWidth());
			assertEquals(StackLayout.heightFor(big.body, 170 - padding) + big.getInsets().top + big.getInsets().bottom,
				big.getHeight());
		});
	}

	@Test
	public void theReplyOnItsWayThinksThenWrites() throws Throwable
	{
		onEdt(() ->
		{
			add(Chat.Role.USER, "q");
			host.current.pending = new ChatApi.Pending();
			host.current.runStartedAt = System.currentTimeMillis();
			host.current.answering = "Claude";
			AiChatPanel panel = new AiChatPanel(host);
			MessageRow.Reply live = panel.liveRow();
			assertNotNull(live);
			assertTrue(live.status.isVisible());
			assertTrue(live.status.getSource(), live.status.getSource().startsWith("Thinking"));
			assertFalse(live.body.isVisible());
			assertFalse("nothing to copy yet", live.actions.isVisible());

			host.current.liveText = "Abyssal demons drop it";
			host.current.liveActivity.add("Read the Wiki page \"Abyssal whip\"");
			panel.refreshLive(host.current);
			assertSame("updated in place", live, panel.liveRow());
			assertFalse(live.status.isVisible());
			assertEquals("Abyssal demons drop it", live.body.getSource());
			assertEquals("Looked up: Abyssal whip (Wiki)", live.activity.lookups.line.getSource());

			host.current.lookingUp = true;
			panel.refreshLive(host.current);
			assertEquals("Looking things up…", live.status.getSource());
		});
	}

	@Test
	public void notesAreShortAndASummaryIsAClickAway() throws Throwable
	{
		onEdt(() ->
		{
			Chat.Message note = add(Chat.Role.NOTE, "Summary of the 24 earlier messages, sent instead of them:\n\nThe "
				+ "player is training Agility.");
			add(Chat.Role.USER, "q");
			AiChatPanel panel = new AiChatPanel(host);
			ShowMore text = ((MessageRow.Note) panel.row(note)).text;
			assertEquals("Summary of 24 earlier messages (show)", text.line.getSource());
			click(text);
			assertEquals("The player is training Agility.", text.more.getSource());
			assertTrue(text.more.isVisible());
		});
	}

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

	private static void click(ShowMore s)
	{
		s.line.dispatchEvent(new MouseEvent(s.line, MouseEvent.MOUSE_CLICKED, 0, InputEvent.BUTTON1_DOWN_MASK, 5, 5, 1,
			false, MouseEvent.BUTTON1));
	}

	@Test
	public void theFullListOfLookUpsIsAClickAwayWhenTheLineLeavesSomeOut() throws Throwable
	{
		onEdt(() ->
		{
			ShowMore s = new ShowMore("Click to see everything", StyleConstants.ALIGN_LEFT);
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
			// One kind per line, wrapped in the narrow panel, and never read as HTML.
			assertEquals("Wiki pages: Abyssal whip\nWiki searches: \"abyssal whip\"\nGE prices: Dragon bones",
				s.more.getText());
			assertEquals(Boolean.TRUE, s.more.getClientProperty("html.disable"));
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
			ShowMore s = new ShowMore("Click to see everything", StyleConstants.ALIGN_LEFT);
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
	public void theCharacterDetailsOpenTheSameWayUnderTheBubble() throws Throwable
	{
		onEdt(() ->
		{
			Chat.Message q = add(Chat.Role.USER, "What should I train?");
			q.context = "[Character: Zezima]\nCombat level: 126";
			AiChatPanel panel = new AiChatPanel(host);
			ShowMore s = ((MessageRow.Question) panel.row(q)).shared;
			assertEquals("Sent your character details (show)", s.line.getText());
			click(s);
			assertEquals("Sent your character details (hide)", s.line.getText());
			assertEquals("[Character: Zezima]\nCombat level: 126", s.more.getText());
			assertTrue(s.more.isVisible());
		});
	}
}
