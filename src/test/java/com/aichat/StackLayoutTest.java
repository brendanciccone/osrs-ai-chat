package com.aichat;

import java.awt.Dimension;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The transcript's layout: children stacked at full width, and only the ones that changed measured again. */
public class StackLayoutTest
{
	/**
	 * A child that counts how often it's measured. Off screen nothing counts as laid out (that takes a window), so it
	 * keeps track of that itself, the way it would on screen.
	 */
	private static final class Counted extends JComponent
	{
		int height;
		int measured;
		private boolean laidOut;

		Counted(int height)
		{
			this.height = height;
		}

		@Override
		public Dimension getPreferredSize()
		{
			measured++;
			return new Dimension(10, height);
		}

		@Override
		public boolean isValid()
		{
			return laidOut;
		}

		@Override
		public void invalidate()
		{
			laidOut = false;
			super.invalidate();
		}

		@Override
		protected void validateTree()
		{
			super.validateTree();
			laidOut = true;
		}
	}

	/** Lays itself out the way Swing does on screen, which marks what was laid out as valid. */
	private static final class Root extends JPanel
	{
		Root()
		{
			super(new StackLayout(2));
		}

		void layOut()
		{
			synchronized (getTreeLock())
			{
				validateTree();
			}
		}
	}

	@Test
	public void onlyWhatChangedIsMeasuredAgain()
	{
		Root root = new Root();
		Counted first = new Counted(10);
		Counted live = new Counted(20);
		root.add(first);
		root.add(live);
		root.setSize(100, 500);
		root.layOut();
		assertEquals(100, first.getWidth());
		assertEquals(10, first.getHeight());
		assertEquals(12, live.getY());
		assertEquals(20, live.getHeight());

		// The reply streaming in grows: only it is measured again.
		first.measured = 0;
		live.measured = 0;
		live.height = 35;
		live.invalidate();
		root.layOut();
		assertEquals(0, first.measured);
		assertTrue(live.measured > 0);
		assertEquals(35, live.getHeight());
		assertEquals(10 + 2 + 35, root.getPreferredSize().height);

		// Another width: everything is measured again.
		root.setSize(80, 500);
		root.layOut();
		assertTrue(first.measured > 0);
		assertEquals(80, first.getWidth());
	}
}
