package com.aichat;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import javax.swing.JTextArea;
import net.runelite.client.ui.PluginPanel;

/**
 * Stacks visible children top to bottom at the container's full width. Unlike BoxLayout, each child's height is
 * asked for at that width, so wrapping text areas get the right height on the first layout instead of one line.
 */
class StackLayout implements LayoutManager
{
	/** Width to assume before the sidebar has been laid out: the panel minus AiChatPanel's border. */
	private static final int FALLBACK_WIDTH = PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH - 16;

	private final int gap;

	StackLayout(int gap)
	{
		this.gap = gap;
	}

	@Override
	public void addLayoutComponent(String name, Component comp)
	{
	}

	@Override
	public void removeLayoutComponent(Component comp)
	{
	}

	@Override
	public Dimension preferredLayoutSize(Container parent)
	{
		Insets in = parent.getInsets();
		int outer = parent.getWidth() > 0 ? parent.getWidth() : FALLBACK_WIDTH;
		int w = Math.max(0, outer - in.left - in.right);
		int h = in.top + in.bottom;
		int n = 0;
		for (Component c : parent.getComponents())
		{
			if (c.isVisible())
			{
				h += heightFor(c, w);
				n++;
			}
		}
		if (n > 1)
		{
			h += gap * (n - 1);
		}
		return new Dimension(outer, h);
	}

	@Override
	public Dimension minimumLayoutSize(Container parent)
	{
		return preferredLayoutSize(parent);
	}

	@Override
	public void layoutContainer(Container parent)
	{
		Insets in = parent.getInsets();
		int w = Math.max(0, parent.getWidth() - in.left - in.right);
		int y = in.top;
		for (Component c : parent.getComponents())
		{
			if (!c.isVisible())
			{
				continue;
			}
			int h = heightFor(c, w);
			c.setBounds(in.left, y, w, h);
			y += h + gap;
		}
	}

	/** Preferred height of {@code c} when it is {@code w} wide. */
	static int heightFor(Component c, int w)
	{
		if (c instanceof JTextArea)
		{
			// A wrapped text area computes its preferred height from its current size.
			c.setSize(w, Short.MAX_VALUE);
		}
		else if (c instanceof Container)
		{
			// Lay the children out at this width first, so nested text areas know theirs.
			c.setSize(w, Short.MAX_VALUE);
			((Container) c).doLayout();
		}
		return c.getPreferredSize().height;
	}
}
