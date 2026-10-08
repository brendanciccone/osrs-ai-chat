package com.aichat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.StyleConstants;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The message view off screen: the text and attributes it builds, the height it asks for in the transcript, and where
 * its links are. Runs headless.
 */
public class MessageViewTest
{
	private static final String WIKI = "https://oldschool.runescape.wiki/w/Abyssal_whip";

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

	private static String text(MessageView v)
	{
		Document d = v.getDocument();
		try
		{
			return d.getText(0, d.getLength());
		}
		catch (BadLocationException e)
		{
			throw new AssertionError(e);
		}
	}

	/** The attributes of the first character of {@code word}. */
	private static AttributeSet at(MessageView v, String word)
	{
		int i = text(v).indexOf(word);
		assertTrue(word, i >= 0);
		return v.getStyledDocument().getCharacterElement(i).getAttributes();
	}

	@Test
	public void showsTheTextWithoutTheMarks() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			assertEquals("", text(v));
			v.setMarkdown("# Title\n\nHello **world**\n\n- one\n- two\n  - nested\n\n3. third\n\n> quote\n\n```\ncode\n  indented\n```\n\n"
				+ "---\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nend");
			assertEquals("Title\nHello world\n\u2022\tone\n\u2022\ttwo\n\u2022\tnested\n3.\tthird\nquote\ncode\n  indented\n\n"
				+ "a | b\n--+--\n1 | 2\nend", text(v));
		});
	}

	@Test
	public void stylesAndLinks() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("Hi **bold** *it* ***both*** `code` [Wiki](" + WIKI + ") [bad](javascript:alert(1)) https://x.com\n\n"
				+ "> quoted\n\n## Head");
			assertFalse(StyleConstants.isBold(at(v, "Hi")));
			assertEquals(Color.WHITE, StyleConstants.getForeground(at(v, "Hi")));
			assertTrue(StyleConstants.isBold(at(v, "bold")));
			assertFalse(StyleConstants.isItalic(at(v, "bold")));
			assertTrue(StyleConstants.isItalic(at(v, "it")));
			assertTrue(StyleConstants.isBold(at(v, "both")) && StyleConstants.isItalic(at(v, "both")));
			assertEquals(Font.MONOSPACED, StyleConstants.getFontFamily(at(v, "code")));
			assertEquals(WIKI, at(v, "Wiki").getAttribute(MessageView.LINK));
			assertTrue(StyleConstants.isUnderline(at(v, "Wiki")));
			assertEquals("https://x.com", at(v, "https://x.com").getAttribute(MessageView.LINK));
			// Not a web address: plain text, nothing to click.
			assertNull(at(v, "bad").getAttribute(MessageView.LINK));
			assertFalse(StyleConstants.isUnderline(at(v, "bad")));
			assertNull(at(v, "Hi").getAttribute(MessageView.LINK));
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, StyleConstants.getForeground(at(v, "quoted")));
			assertTrue(StyleConstants.isBold(at(v, "Head")));
			assertTrue(StyleConstants.getFontSize(at(v, "Head")) > StyleConstants.getFontSize(at(v, "Hi")));

			v.setTextColor(Color.RED);
			assertEquals(Color.RED, StyleConstants.getForeground(at(v, "Hi")));
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, StyleConstants.getForeground(at(v, "quoted")));
			v.setTextFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
			assertEquals(16, StyleConstants.getFontSize(at(v, "Hi")));
		});
	}

	@Test
	public void htmlIsShownAsText() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("<b>not bold</b> <a href=\"https://x.com\">x</a>");
			assertEquals("<b>not bold</b> <a href=\"https://x.com\">x</a>", text(v));
			assertFalse(StyleConstants.isBold(at(v, "not bold")));
			assertEquals("text/plain", v.getContentType());
		});
	}

	@Test
	public void plainTextIsNotMarkdown() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setPlainText("**what's** 5*3*2? See https://x.com.\n- not a list");
			assertEquals("**what's** 5*3*2? See https://x.com.\n- not a list", text(v));
			assertEquals("https://x.com", at(v, "https").getAttribute(MessageView.LINK));
			assertFalse(StyleConstants.isBold(at(v, "what")));
			assertEquals("**what's** 5*3*2? See https://x.com.\n- not a list", v.plainText());
			// Switching back to Markdown with the same text renders it as Markdown.
			v.setMarkdown("**what's**");
			assertEquals("what's", text(v));
		});
	}

	@Test
	public void plainTextForCopying() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("Read [the Wiki](" + WIKI + ").\n\n- **one**\n- two");
			assertEquals("Read the Wiki (" + WIKI + ").\n\n- one\n- two", v.plainText());
			assertEquals("Read [the Wiki](" + WIKI + ").\n\n- **one**\n- two", v.getSource());
		});
	}

	@Test
	public void theSameTextIsntRenderedAgain() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("Hello **there**");
			Document first = v.getDocument();
			v.setMarkdown("Hello **there**");
			assertSame(first, v.getDocument());
			v.setTextColor(Color.WHITE);
			assertSame(first, v.getDocument());
			v.setMarkdown("Hello **there**, more");
			assertNotSame(first, v.getDocument());
		});
	}

	@Test
	public void heightFollowsTheWidth() throws Throwable
	{
		onEdt(() ->
		{
			StringBuilder reply = new StringBuilder();
			for (int i = 0; i < 20; i++)
			{
				reply.append("Some words that will have to wrap in a narrow panel. ");
			}
			reply.append("\n\n- a list item that is long enough to wrap onto a second line in the sidebar\n\n```\ncode\n```");
			MessageView v = new MessageView();
			v.setMarkdown(reply.toString());
			int wide = StackLayout.heightFor(v, 600);
			int narrow = StackLayout.heightFor(v, 150);
			assertTrue(wide > 0);
			assertTrue(narrow + " vs " + wide, narrow > wide * 2);
			// Nothing left over from the last measurement.
			assertEquals(wide, StackLayout.heightFor(v, 600));
			// It never asks to be wider than it's given.
			assertEquals(600, v.getPreferredSize().width);
		});
	}

	@Test
	public void longWordsWrapInsteadOfWidening() throws Throwable
	{
		onEdt(() ->
		{
			MessageView one = new MessageView();
			one.setMarkdown("x");
			int line = StackLayout.heightFor(one, 150);
			assertTrue(line > 0);

			MessageView v = new MessageView();
			v.setMarkdown("https://oldschool.runescape.wiki/w/" + Markdown.repeat('A', 300));
			assertTrue(StackLayout.heightFor(v, 150) >= 3 * line);
			assertEquals(150, v.getPreferredSize().width);
		});
	}

	@Test
	public void heightInsideABubble() throws Throwable
	{
		onEdt(() ->
		{
			// As the panel uses it: in a bordered bubble, in the transcript's StackLayout.
			MessageView v = new MessageView();
			v.setMarkdown("**Abyssal whip**: about 1.5m on the GE. It's a good upgrade from a rune scimitar, and the "
				+ "[Wiki](" + WIKI + ") has more.\n\n1. Buy it\n2. Wield it");
			JPanel bubble = new JPanel(new BorderLayout());
			bubble.setBorder(new EmptyBorder(4, 6, 6, 6));
			bubble.add(v, BorderLayout.CENTER);
			int height = StackLayout.heightFor(bubble, 220);
			assertEquals(StackLayout.heightFor(v, 208) + 10, height);

			JPanel transcript = new JPanel(new StackLayout(6));
			transcript.add(bubble);
			transcript.setSize(220, 1000);
			transcript.doLayout();
			bubble.doLayout();
			assertEquals(208, v.getWidth());
			assertEquals(height - 10, v.getHeight());
		});
	}

	@Test
	public void emptyMessagesStillHaveALine() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			assertTrue(StackLayout.heightFor(v, 200) > 0);
			v.setMarkdown("");
			assertTrue(StackLayout.heightFor(v, 200) > 0);
			v.setPlainText(null);
			assertTrue(StackLayout.heightFor(v, 200) > 0);
		});
	}

	@Test
	public void everyPrefixOfAReplyRenders() throws Throwable
	{
		onEdt(() ->
		{
			// The live bubble while a reply streams in.
			String reply = "## Fire cape\n\n1. *High* Ranged, see [Fight Caves](https://oldschool.runescape.wiki/w/TzHaar_Fight_Cave).\n"
				+ "2. Prayer potions\n   - about `12`\n\n> Jad's attacks\n\n```\nflick\n```\n\n---\n\n| Item | Price |\n|---|--:|\n| Pot | 9k |\n\nDone";
			MessageView v = new MessageView();
			BufferedImage image = new BufferedImage(220, 600, BufferedImage.TYPE_INT_ARGB);
			for (int i = 0; i <= reply.length(); i++)
			{
				v.setMarkdown(reply.substring(0, i));
				int h = StackLayout.heightFor(v, 220);
				assertTrue(h > 0);
				v.setSize(220, h);
				Graphics2D g = image.createGraphics();
				v.print(g);
				g.dispose();
			}
		});
	}

	@Test
	public void drawsCodeBoxesQuoteBarsAndRules() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("> quoted\n\n```\ncode\n```\n\n---\n\nend");
			int h = StackLayout.heightFor(v, 220);
			v.setSize(220, h);
			BufferedImage image = new BufferedImage(220, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			v.print(g);
			g.dispose();
			try
			{
				int quoteY = (int) v.modelToView2D(text(v).indexOf("quoted")).getCenterY();
				int codeY = (int) v.modelToView2D(text(v).indexOf("code")).getCenterY();
				// The quote's bar at the far left; the code's box across the whole width; nothing between them.
				assertTrue(alpha(image, 0, quoteY) > 0);
				assertEquals(0, alpha(image, 5, quoteY));
				assertTrue(alpha(image, 1, codeY) > 0);
				assertTrue(alpha(image, 219, codeY) > 0);
				assertEquals(0, alpha(image, 219, quoteY));
				// The rule: some row between the code and "end" is drawn across the width.
				int endY = (int) v.modelToView2D(text(v).indexOf("end")).getY();
				boolean rule = false;
				for (int y = codeY + 12; y < endY; y++)
				{
					rule |= alpha(image, 200, y) > 0 && alpha(image, 200, y - 2) == 0;
				}
				assertTrue(rule);
			}
			catch (BadLocationException e)
			{
				throw new AssertionError(e);
			}
		});
	}

	private static int alpha(BufferedImage image, int x, int y)
	{
		return image.getRGB(x, y) >>> 24;
	}

	@Test
	public void linksAreFoundUnderTheMouse() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("Read [the Wiki](" + WIKI + ") first");
			v.setSize(400, StackLayout.heightFor(v, 400));
			try
			{
				Rectangle2D wiki = v.modelToView2D(text(v).indexOf("Wiki"));
				Point onLink = new Point((int) wiki.getX() + 2, (int) wiki.getCenterY());
				assertEquals(WIKI, v.linkAt(onLink));
				assertEquals(WIKI, v.getToolTipText(new MouseEvent(v, MouseEvent.MOUSE_MOVED, 0, 0, onLink.x, onLink.y, 0, false)));

				Rectangle2D read = v.modelToView2D(text(v).indexOf("Read"));
				assertNull(v.linkAt(new Point((int) read.getX() + 2, (int) read.getCenterY())));
				// Past the end of the line, and below the text.
				assertNull(v.linkAt(new Point(395, (int) wiki.getCenterY())));
				assertNull(v.linkAt(new Point((int) wiki.getX() + 2, v.getHeight() + 50)));
			}
			catch (BadLocationException e)
			{
				throw new AssertionError(e);
			}
		});
	}

	@Test
	public void aLinkEndingALineIsOnlyALinkWhereItIs() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("[Wiki](" + WIKI + ")\n\nnext");
			v.setSize(400, StackLayout.heightFor(v, 400));
			try
			{
				Rectangle2D last = v.modelToView2D(text(v).indexOf("i\n"));
				assertEquals(WIKI, v.linkAt(new Point((int) last.getX() + 1, (int) last.getCenterY())));
				assertNull(v.linkAt(new Point((int) last.getX() + 60, (int) last.getCenterY())));
			}
			catch (BadLocationException e)
			{
				throw new AssertionError(e);
			}
		});
	}

	@Test
	public void linesCanSitCentredOrRight() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setPlainText("Stopped.");
			assertEquals(StyleConstants.ALIGN_LEFT, StyleConstants.getAlignment(paragraph(v)));
			v.setAlignment(StyleConstants.ALIGN_CENTER);
			assertEquals(StyleConstants.ALIGN_CENTER, StyleConstants.getAlignment(paragraph(v)));
			v.setAlignment(StyleConstants.ALIGN_RIGHT);
			v.setMarkdown("one\n\ntwo");
			assertEquals(StyleConstants.ALIGN_RIGHT, StyleConstants.getAlignment(paragraph(v)));

			// Drawn at the right edge: nothing in the left half.
			v.setSize(300, StackLayout.heightFor(v, 300));
			try
			{
				assertTrue(v.modelToView2D(0).getX() > 150);
			}
			catch (BadLocationException e)
			{
				throw new AssertionError(e);
			}
		});
	}

	private static AttributeSet paragraph(MessageView v)
	{
		return v.getStyledDocument().getParagraphElement(0).getAttributes();
	}

	@Test
	public void itKnowsHowWideItIsUnwrapped() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setPlainText("hi");
			int small = v.naturalWidth();
			assertTrue(small > 0 && small < 40);
			v.setPlainText("A question long enough to wrap in the narrow panel, and then some more words");
			int big = v.naturalWidth();
			assertTrue(big > 300);
			int line = StackLayout.heightFor(one("x"), 400);
			// Its longest line, whatever width it was last laid out at.
			assertTrue(StackLayout.heightFor(v, 150) > line);
			assertEquals(big, v.naturalWidth());
			v.setPlainText("short\na much longer second line");
			assertEquals("the longest line", one("a much longer second line").naturalWidth(), v.naturalWidth());
			// A box that wide holds it without a wrap.
			v.setPlainText("How do I get a whip?");
			assertEquals(line, StackLayout.heightFor(v, v.naturalWidth()));
		});
	}

	private static MessageView one(String text)
	{
		MessageView v = new MessageView();
		v.setPlainText(text);
		return v;
	}

	@Test
	public void theMessagesOwnTooltipShowsAwayFromLinks() throws Throwable
	{
		onEdt(() ->
		{
			MessageView v = new MessageView();
			v.setMarkdown("[Wiki](" + WIKI + ") and some more words after it");
			v.setToolTipText("Claude \u00b7 14:02");
			v.setSize(400, StackLayout.heightFor(v, 400));
			try
			{
				Rectangle2D wiki = v.modelToView2D(1);
				assertEquals(WIKI, v.getToolTipText(move(v, (int) wiki.getX() + 1, (int) wiki.getCenterY())));
				Rectangle2D after = v.modelToView2D(text(v).indexOf("after"));
				assertEquals("Claude \u00b7 14:02", v.getToolTipText(move(v, (int) after.getX() + 1, (int) after.getCenterY())));
			}
			catch (BadLocationException e)
			{
				throw new AssertionError(e);
			}
		});
	}

	private static MouseEvent move(MessageView v, int x, int y)
	{
		return new MouseEvent(v, MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false);
	}
}
