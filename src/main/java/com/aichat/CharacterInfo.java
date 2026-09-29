package com.aichat;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;

/** The character details sent with a message when the player turns that on. */
final class CharacterInfo
{
	private CharacterInfo()
	{
	}

	/** Client thread only: reading quest states runs a game script. Null when there's no character to describe. */
	static String describe(Client client)
	{
		Player me = client.getLocalPlayer();
		if (me == null || me.getName() == null)
		{
			return null;
		}
		List<String> levels = new ArrayList<>();
		for (Skill s : Skill.values())
		{
			// Skill also lists the deprecated total-level pseudo-skill; the total is reported on its own below.
			if (!"OVERALL".equals(s.name()))
			{
				levels.add(s.getName() + " " + client.getRealSkillLevel(s));
			}
		}
		List<String> done = new ArrayList<>();
		List<String> started = new ArrayList<>();
		for (Quest q : Quest.values())
		{
			QuestState state = q.getState(client);
			if (state == QuestState.FINISHED)
			{
				done.add(q.getName());
			}
			else if (state == QuestState.IN_PROGRESS)
			{
				started.add(q.getName());
			}
		}
		return format(me.getName(), me.getCombatLevel(), client.getTotalLevel(), levels,
			client.getVarpValue(VarPlayerID.QP), done, started);
	}

	static String format(String name, int combat, int total, List<String> levels, int questPoints, List<String> done, List<String> started)
	{
		return "[Character: " + name + ", combat level " + combat + ", total level " + total + ". "
			+ "Levels: " + String.join(", ", levels) + ". "
			+ "Quest points: " + questPoints + ". "
			+ "Quests completed (" + done.size() + "): " + (done.isEmpty() ? "none" : String.join(", ", done)) + ". "
			+ "Quests in progress: " + (started.isEmpty() ? "none" : String.join(", ", started)) + ".]";
	}
}
