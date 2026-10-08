package com.aichat;

import java.awt.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.JToolTip;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.BasicComboBoxEditor;

/**
 * The model picker next to Send: the models the provider offers, with the one that's set always there and selected.
 * Choosing one from the list, or typing a name and pressing Enter (or leaving the box), sets the provider's model as
 * the settings would. Typing alone saves nothing, and neither does moving through the open list: only closing it on a
 * model does. Changes the panel makes itself (a model changed in the settings, a list that came in) are never taken
 * for the player's. Plain text only, list and box alike: model names come from the provider. Swing EDT only.
 */
final class ModelPicker extends JComboBox<String>
{
	private final DefaultComboBoxModel<String> items;
	/** Hears the player's choice, a moment later on the EDT (it changes the settings, which redraws the panel). */
	private final Consumer<String> chosen;
	/** The panel is filling the list in: what that selects isn't the player's choice. */
	private boolean updating;
	/** The list is open: moving through it isn't a choice until it closes. */
	private boolean open;
	/** It's closing with Escape or a click elsewhere: whatever it was on isn't a choice. */
	private boolean cancelled;
	/** The model that's set, as far as the picker knows: what it shows when nothing's being chosen. */
	private String current = "";
	private List<String> shown = Collections.emptyList();

	ModelPicker(Consumer<String> chosen)
	{
		super(new DefaultComboBoxModel<>());
		this.items = (DefaultComboBoxModel<String>) getModel();
		this.chosen = chosen;
		setEditor(new PlainEditor());
		setRenderer(new PlainRenderer());
		setEditable(true);
		setFont(PanelStyle.SMALL_FONT);
		setBackground(PanelStyle.FIELD_COLOR);
		setForeground(PanelStyle.TEXT_COLOR);
		setMaximumRowCount(12);
		addActionListener(e ->
		{
			// Enter in the box, or the box left with something new typed in it, comes as "comboBoxEdited".
			if (!updating && (!open || "comboBoxEdited".equals(e.getActionCommand())))
			{
				choose(getSelectedItem());
			}
		});
		addPopupMenuListener(new PopupMenuListener()
		{
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e)
			{
				open = true;
				cancelled = false;
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e)
			{
				open = false;
				if (updating)
				{
					return;
				}
				if (cancelled)
				{
					select(current);
				}
				else
				{
					choose(getSelectedItem());
				}
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e)
			{
				cancelled = true;
			}
		});
	}

	/**
	 * Shows {@code model}, the one that's set, among {@code choices} (see {@link ConnectionCheck#choices}), with
	 * {@code tip} saying how to choose. Leaves the box alone when nothing changed, so a redraw of the panel doesn't wipe
	 * out a name being typed. The look and feel passes the tooltip on to the box and the arrow button, whose tooltips
	 * can't be told not to read HTML: it's always AI Chat's own sentence, starting with its own words, so they never do
	 * (see {@link ConnectionCheck#pickerTip}).
	 */
	void show(String model, List<String> choices, String tip)
	{
		setToolTipText(tip);
		String m = model == null ? "" : model.trim();
		if (m.equals(current) && choices.equals(shown))
		{
			return;
		}
		current = m;
		shown = new ArrayList<>(choices);
		String typing = typing();
		updating = true;
		try
		{
			items.removeAllElements();
			for (String c : choices)
			{
				items.addElement(c);
			}
			select(m);
			if (typing != null)
			{
				getEditor().setItem(typing);
			}
		}
		finally
		{
			updating = false;
		}
	}

	/** What the player is typing in the box right now, or null when they aren't. */
	private String typing()
	{
		Component box = getEditor().getEditorComponent();
		Object text = getEditor().getItem();
		return box.isFocusOwner() && text != null && !text.toString().equals(current) ? text.toString() : null;
	}

	private void select(String model)
	{
		boolean was = updating;
		updating = true;
		try
		{
			items.setSelectedItem(model.isEmpty() ? null : model);
			getEditor().setItem(model);
		}
		finally
		{
			updating = was;
		}
	}

	/** The player chose {@code item}: the provider's model becomes it, unless it's blank or already set. */
	private void choose(Object item)
	{
		String model = item == null ? "" : item.toString().trim();
		if (model.isEmpty())
		{
			// Nothing to set: back to the model that is.
			select(current);
			return;
		}
		if (model.equals(current))
		{
			return;
		}
		current = model;
		// Not from inside the combo box's own event: setting the model redraws the panel, this picker included.
		SwingUtilities.invokeLater(() -> chosen.accept(model));
	}

	@Override
	public JToolTip createToolTip()
	{
		JToolTip tip = super.createToolTip();
		tip.putClientProperty("html.disable", Boolean.TRUE);
		return tip;
	}

	/** The box: a text field, which shows text as it is, as does its tooltip. */
	private static final class PlainEditor extends BasicComboBoxEditor
	{
		@Override
		protected JTextField createEditorComponent()
		{
			JTextField field = new JTextField("", 9)
			{
				@Override
				public JToolTip createToolTip()
				{
					JToolTip tip = super.createToolTip();
					tip.putClientProperty("html.disable", Boolean.TRUE);
					return tip;
				}
			};
			field.putClientProperty("html.disable", Boolean.TRUE);
			field.setBorder(null);
			field.setBackground(PanelStyle.FIELD_COLOR);
			field.setForeground(PanelStyle.TEXT_COLOR);
			field.setCaretColor(PanelStyle.TEXT_COLOR);
			return field;
		}
	}

	/** The list's rows: labels that never render a model's name as HTML. */
	private static final class PlainRenderer extends DefaultListCellRenderer
	{
		PlainRenderer()
		{
			// Before any text is set: the HTML renderer is picked when it is.
			putClientProperty("html.disable", Boolean.TRUE);
		}

		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus)
		{
			super.getListCellRendererComponent(list, value, index, selected, focus);
			putClientProperty("html.disable", Boolean.TRUE);
			return this;
		}
	}
}
