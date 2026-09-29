package com.aichat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class PrefixTest
{
	@Test
	public void recognisesOurCommand()
	{
		assertEquals("fix the build", AiChatPlugin.stripPrefix("::ai fix the build"));
		assertEquals("fix the build", AiChatPlugin.stripPrefix("  ::AI   fix the build "));
		assertEquals("", AiChatPlugin.stripPrefix("::ai"));
	}

	@Test
	public void leavesOtherChatAlone()
	{
		// Including other plugins' commands, like Claude Chat's ::claude.
		assertNull(AiChatPlugin.stripPrefix("::claude hi"));
		assertNull(AiChatPlugin.stripPrefix("::aix"));
		assertNull(AiChatPlugin.stripPrefix("ai fix it"));
		assertNull(AiChatPlugin.stripPrefix("hello ::ai"));
		assertNull(AiChatPlugin.stripPrefix(""));
		assertNull(AiChatPlugin.stripPrefix(null));
	}
}
