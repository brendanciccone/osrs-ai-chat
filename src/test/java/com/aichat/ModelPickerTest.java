package com.aichat;

import java.awt.Component;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The model picker: what the player chooses is saved, once, and nothing else is: not typing, not moving through the
 * open list, not what the panel shows itself. Runs headless.
 */
public class ModelPickerTest
{
	private static final List<String> CLAUDE = Arrays.asList("claude-opus-5-5", "claude-haiku-4-5", "claude-sonnet-5-5");

	private final List<String> chosen = new ArrayList<>();

	/** Runs {@code test} on the EDT, then what it left queued there (the picker hands choices on a moment later). */
	private static void onEdt(Runnable test) throws Throwable
	{
		try
		{
			SwingUtilities.invokeAndWait(test);
			SwingUtilities.invokeAndWait(() ->
			{
			});
		}
		catch (InvocationTargetException e)
		{
			throw e.getCause();
		}
	}

	private ModelPicker picker() throws Throwable
	{
		ModelPicker[] made = new ModelPicker[1];
		onEdt(() ->
		{
			made[0] = new ModelPicker(chosen::add);
			made[0].show("claude-opus-5-5", CLAUDE, "The model new messages go to.");
		});
		return made[0];
	}

	private static JTextField box(ModelPicker p)
	{
		return (JTextField) p.getEditor().getEditorComponent();
	}

	@Test
	public void whatThePanelShowsIsNeverTakenForAChoice() throws Throwable
	{
		ModelPicker p = picker();
		onEdt(() ->
		{
			assertEquals("claude-opus-5-5", p.getSelectedItem());
			assertEquals(3, p.getItemCount());
			assertEquals("claude-opus-5-5", box(p).getText());
			// A model changed in the settings, or a list that came in.
			p.show("claude-sonnet-5-5", CLAUDE, "The model new messages go to.");
			p.show("claude-sonnet-5-5", Arrays.asList("claude-sonnet-5-5", "claude-opus-5-5"), "Still the same.");
			assertEquals("claude-sonnet-5-5", p.getSelectedItem());
			assertEquals("Still the same.", p.getToolTipText());
			// No model set yet: the list is offered, and nothing in it is picked for the player.
			p.show("", CLAUDE, "The model new messages go to.");
			assertEquals(null, p.getSelectedItem());
			assertEquals("", box(p).getText());
		});
		assertTrue(chosen.isEmpty());
	}

	@Test
	public void choosingFromTheListSavesIt() throws Throwable
	{
		ModelPicker p = picker();
		onEdt(() -> p.setSelectedItem("claude-haiku-4-5"));
		assertEquals(Arrays.asList("claude-haiku-4-5"), chosen);
		// The settings change, and the panel shows it: no second save.
		onEdt(() -> p.show("claude-haiku-4-5", CLAUDE, "The model new messages go to."));
		onEdt(() -> p.setSelectedItem("claude-haiku-4-5"));
		assertEquals(Arrays.asList("claude-haiku-4-5"), chosen);
	}

	@Test
	public void movingThroughTheOpenListIsntAChoiceUntilItCloses() throws Throwable
	{
		ModelPicker p = picker();
		onEdt(() ->
		{
			p.firePopupMenuWillBecomeVisible();
			p.setSelectedItem("claude-haiku-4-5");
			p.setSelectedItem("claude-sonnet-5-5");
		});
		assertTrue(chosen.isEmpty());
		onEdt(p::firePopupMenuWillBecomeInvisible);
		assertEquals(Arrays.asList("claude-sonnet-5-5"), chosen);

		// Closed with Escape: back to the model that's set, nothing saved.
		onEdt(() ->
		{
			p.show("claude-sonnet-5-5", CLAUDE, "tip");
			p.firePopupMenuWillBecomeVisible();
			p.setSelectedItem("claude-opus-5-5");
			p.firePopupMenuCanceled();
			p.firePopupMenuWillBecomeInvisible();
			assertEquals("claude-sonnet-5-5", p.getSelectedItem());
		});
		assertEquals(Arrays.asList("claude-sonnet-5-5"), chosen);
	}

	@Test
	public void aTypedNameIsSavedOnEnterNotWhileTyping() throws Throwable
	{
		ModelPicker p = picker();
		onEdt(() ->
		{
			box(p).setText("my-fine");
			box(p).setText("my-fine-tune");
		});
		assertTrue("typing saves nothing", chosen.isEmpty());
		// Enter in the box (leaving it does the same: Swing turns both into this).
		onEdt(() -> box(p).postActionEvent());
		assertEquals(Arrays.asList("my-fine-tune"), chosen);

		// Nothing typed: nothing to set, and the box shows the model that's set again.
		onEdt(() ->
		{
			box(p).setText("   ");
			box(p).postActionEvent();
			assertEquals("my-fine-tune", box(p).getText());
		});
		assertEquals(Arrays.asList("my-fine-tune"), chosen);
	}

	@Test
	public void aLongNameShowsFromItsStart() throws Throwable
	{
		String model = "anthropic/claude-sonnet-5-5:thinking-extended-preview";
		ModelPicker p = picker();
		onEdt(() ->
		{
			p.setSize(150, 24);
			p.doLayout();
			p.show(model, Arrays.asList(model), "tip");
		});
		onEdt(() ->
		{
			assertEquals(model, box(p).getText());
			int width = box(p).getWidth();
			assertTrue("it doesn't fit", width > 0 && box(p).getFontMetrics(box(p).getFont()).stringWidth(model) > width);
			assertEquals(0, box(p).getCaretPosition());
			assertEquals("the box shows its start", 0, box(p).getScrollOffset());

			// Typed in, then left: back to the start too.
			box(p).setCaretPosition(model.length());
			for (FocusListener l : box(p).getFocusListeners())
			{
				l.focusLost(new FocusEvent(box(p), FocusEvent.FOCUS_LOST));
			}
			assertEquals(0, box(p).getCaretPosition());
		});
		assertTrue("nothing new to save", chosen.isEmpty());
	}

	@Test
	public void namesAreNeverHtml() throws Throwable
	{
		ModelPicker p = picker();
		onEdt(() ->
		{
			assertEquals(Boolean.TRUE, box(p).getClientProperty("html.disable"));
			JList<String> list = new JList<>();
			Component row = p.getRenderer().getListCellRendererComponent(list, "<html><b>x", 0, false, false);
			assertEquals(Boolean.TRUE, ((JComponent) row).getClientProperty("html.disable"));
			assertEquals(Boolean.TRUE, p.createToolTip().getClientProperty("html.disable"));
		});
	}
}
