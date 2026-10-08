package com.aichat;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.Icon;

/**
 * The panel's small icons, drawn with shapes rather than font characters (which not every font has). Each fits a square
 * of {@code size} pixels, in its own colour or the component's text colour, faded while the component is disabled.
 */
final class Glyph implements Icon
{
	enum Shape
	{
		/** An arrow pointing up: Send. */
		SEND,
		/** A square: Stop. */
		STOP,
		/** An arrow pointing down: jump to the latest message. */
		DOWN,
		PLUS,
		/** Three dots: more actions. */
		MORE,
		COPY,
		/** A circling arrow: Retry. */
		RETRY,
		/** A cross: close. */
		CLOSE
	}

	private final Shape shape;
	private final int size;
	/** Null: the component's own text colour. */
	private final Color color;

	Glyph(Shape shape, int size)
	{
		this(shape, size, null);
	}

	Glyph(Shape shape, int size, Color color)
	{
		this.shape = shape;
		this.size = size;
		this.color = color;
	}

	@Override
	public int getIconWidth()
	{
		return size;
	}

	@Override
	public int getIconHeight()
	{
		return size;
	}

	@Override
	public void paintIcon(Component c, Graphics g, int x, int y)
	{
		Graphics2D g2 = PanelStyle.smooth(g);
		try
		{
			g2.translate(x, y);
			Color base = color != null ? color : c.getForeground();
			g2.setColor(c.isEnabled() ? base : new Color(base.getRed(), base.getGreen(), base.getBlue(), 100));
			g2.setStroke(new BasicStroke(Math.max(1.5f, size / 9f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			draw(g2, size);
		}
		finally
		{
			g2.dispose();
		}
	}

	/** Draws the shape in a square of side {@code s}, positions given as fractions of it. */
	private void draw(Graphics2D g, float s)
	{
		switch (shape)
		{
			case SEND:
				g.draw(line(s, 0.5, 0.82, 0.5, 0.2));
				g.draw(path(s, 0.24, 0.46, 0.5, 0.2, 0.76, 0.46));
				break;
			case DOWN:
				g.draw(line(s, 0.5, 0.18, 0.5, 0.8));
				g.draw(path(s, 0.24, 0.54, 0.5, 0.8, 0.76, 0.54));
				break;
			case STOP:
				g.fill(new RoundRectangle2D.Double(0.27 * s, 0.27 * s, 0.46 * s, 0.46 * s, 0.14 * s, 0.14 * s));
				break;
			case PLUS:
				g.draw(line(s, 0.5, 0.18, 0.5, 0.82));
				g.draw(line(s, 0.18, 0.5, 0.82, 0.5));
				break;
			case MORE:
				for (double cx : new double[]{0.2, 0.5, 0.8})
				{
					double r = Math.max(1.2, 0.08 * s);
					g.fill(new Ellipse2D.Double(cx * s - r, 0.5 * s - r, 2 * r, 2 * r));
				}
				break;
			case COPY:
				// The sheet behind shows only where the front one doesn't cover it.
				g.draw(path(s, 0.34, 0.3, 0.34, 0.14, 0.86, 0.14, 0.86, 0.66, 0.7, 0.66));
				g.draw(new RoundRectangle2D.Double(0.14 * s, 0.34 * s, 0.52 * s, 0.52 * s, 0.12 * s, 0.12 * s));
				break;
			case RETRY:
				retry(g, s);
				break;
			default:
				g.draw(line(s, 0.28, 0.28, 0.72, 0.72));
				g.draw(line(s, 0.72, 0.28, 0.28, 0.72));
				break;
		}
	}

	/** Most of a circle, anticlockwise from the upper right round to the lower right, with an arrowhead at its end. */
	private static void retry(Graphics2D g, float s)
	{
		double r = 0.32 * s;
		double cx = 0.5 * s;
		double cy = 0.5 * s;
		g.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, 40, 290, Arc2D.OPEN));
		double end = Math.toRadians(330);
		double ex = cx + r * Math.cos(end);
		double ey = cy - r * Math.sin(end);
		// The way the arc is heading at its end (anticlockwise on screen), and across it.
		double dx = -Math.sin(end);
		double dy = -Math.cos(end);
		double head = 0.2 * s;
		Path2D.Double arrow = new Path2D.Double();
		arrow.moveTo(ex + dx * head, ey + dy * head);
		arrow.lineTo(ex - dy * head * 0.6, ey + dx * head * 0.6);
		arrow.lineTo(ex + dy * head * 0.6, ey - dx * head * 0.6);
		arrow.closePath();
		g.fill(arrow);
	}

	private static Line2D line(float s, double x1, double y1, double x2, double y2)
	{
		return new Line2D.Double(x1 * s, y1 * s, x2 * s, y2 * s);
	}

	/** An open line through the points, given as x, y pairs. */
	private static Path2D path(float s, double... xy)
	{
		Path2D.Double p = new Path2D.Double();
		p.moveTo(xy[0] * s, xy[1] * s);
		for (int i = 2; i < xy.length; i += 2)
		{
			p.lineTo(xy[i] * s, xy[i + 1] * s);
		}
		return p;
	}
}
