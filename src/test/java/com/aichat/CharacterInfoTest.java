package com.aichat;

import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/** The character note sent with a message when the player shares their character. */
public class CharacterInfoTest
{
	@Test
	public void characterInfoReadsLikeANote()
	{
		String s = CharacterInfo.format("Zezima", 126, 2277, List.of("Attack 99", "Sailing 1"), 300,
			List.of("Dragon Slayer I"), Collections.emptyList());
		assertTrue(s, s.startsWith("[Character: Zezima, combat level 126, total level 2277. Levels: Attack 99, Sailing 1."));
		assertTrue(s, s.contains("Quests completed (1): Dragon Slayer I."));
		assertTrue(s, s.endsWith("Quests in progress: none.]"));
	}
}
