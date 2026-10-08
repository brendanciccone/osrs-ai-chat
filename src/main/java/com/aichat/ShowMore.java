package com.aichat;

import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.text.StyleConstants;

/**
 * A muted line about a message with more to it than fits there, such as "Sent your character details (show)" or
 * "Summary of 24 earlier messages (show)": a click on the line shows the rest in a block under it, and another click
 * hides it again. Both are plain text, which wraps in the narrow panel and never renders HTML: they can hold words the
 * model chose. The line sits left, centred or right, like what it's about; the block is always left-aligned, set in a
 * little.
 */
final class ShowMore extends JPanel
{
	final MessageView line = new MessageView();
	final MessageView more = new MessageView();
	/** For the line, while it has more to show. */
	private final String tip;
	/** For the line while it hasn't, or null. */
	private String plainTip;
	private String text = "";

	/** {@code alignment}: where the line sits, StyleConstants.ALIGN_LEFT, ALIGN_CENTER or ALIGN_RIGHT. */
	ShowMore(String tip, int alignment)
	{
		super(new StackLayout(0));
		this.tip = tip;
		setOpaque(false);
		setVisible(false);
		for (MessageView v : new MessageView[]{line, more})
		{
			v.setTextFont(PanelStyle.SMALL_FONT);
			v.setTextColor(PanelStyle.MUTED_COLOR);
			add(v);
		}
		line.setAlignment(alignment);
		// Set in a little, so it reads as the line's own and not as what comes after it.
		more.setBorder(new EmptyBorder(2, 8, 2, alignment == StyleConstants.ALIGN_LEFT ? 0 : 8));
		more.setVisible(false);
		line.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e) && hasMore())
				{
					more.setVisible(!more.isVisible());
					showLine();
					// Taller or shorter now: measured again, with the transcript around it.
					revalidate();
					repaint();
				}
			}
		});
	}

	/**
	 * Shows {@code text} (null: nothing at all), with {@code details} a click away (null: just the line). Shown or
	 * hidden, the details stay as they were when they change: a list still growing over a reply on its way doesn't
	 * close on the player.
	 */
	void show(String text, String details)
	{
		this.text = text == null ? "" : text;
		String d = this.text.isEmpty() || details == null ? "" : details;
		more.setPlainText(d);
		if (d.isEmpty())
		{
			more.setVisible(false);
		}
		setVisible(!this.text.isEmpty());
		showLine();
	}

	/** The line's tooltip while it has nothing more to show, such as when its message was sent. */
	void setPlainTip(String tip)
	{
		plainTip = tip;
		showLine();
	}

	private boolean hasMore()
	{
		return !more.getSource().isEmpty();
	}

	private void showLine()
	{
		boolean any = hasMore();
		line.setPlainText(any ? text + (more.isVisible() ? " (hide)" : " (show)") : text);
		line.setCursor(Cursor.getPredefinedCursor(any ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
		line.setToolTipText(any ? tip : plainTip);
	}
}
