package com.aichat;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** What the assistant is told about AI Chat's settings: their names as the settings panel shows them. */
public class SystemPromptTest
{
	@Test
	public void theSettingsItSuggestsAreNamedAsThePlayerSeesThem()
	{
		String items = SettingName.of("shareItems");
		String character = SettingName.of("sendCharacter");
		assertTrue(AiChatPlugin.SYSTEM_PROMPT.contains("(\"" + items + "\" or \"" + character + "\")"));
		assertFalse("the old name", AiChatPlugin.SYSTEM_PROMPT.contains("Send character info"));
	}

	@Test
	public void itKnowsThePanelShowsTables()
	{
		// The panel draws tables (and turns wide ones into cards): the assistant mustn't refuse to make one.
		assertFalse(AiChatPlugin.SYSTEM_PROMPT.contains("don't use tables"));
		assertTrue(AiChatPlugin.SYSTEM_PROMPT.contains("small tables are fine"));
	}
}
