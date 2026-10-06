package com.aichat;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * The tools that go with one request, and the runner that answers the model's calls to them: the look-ups first
 * (wiki_search and wiki_page with "Wiki look-ups", ge_price always), then the game-data tools the sharing settings allow
 * (get_equipment, get_inventory, get_bank, get_slayer_task, get_achievement_diaries). Always in that order: the tools
 * are part of the prompt the provider caches, and changing them sends earlier replies as plain text.
 */
@Slf4j
final class ToolBox implements ChatApi.ToolRunner
{
	private final LookupTools lookups;
	private final GameDataTools gameData;
	/** Told when a call starts, on the provider's thread, so the panel can say it's looking things up. */
	private final Runnable started;

	ToolBox(LookupTools lookups, GameDataTools gameData, Runnable started)
	{
		this.lookups = lookups;
		this.gameData = gameData;
		this.started = started;
	}

	List<ChatApi.ToolSpec> specs()
	{
		List<ChatApi.ToolSpec> specs = new ArrayList<>(lookups.specs());
		specs.addAll(gameData.specs());
		return specs;
	}

	/** Offers the tools with {@code conversation}, and answers its calls to them. */
	void addTo(ChatApi.Conversation conversation)
	{
		conversation.tools.addAll(specs());
		conversation.toolRunner = this;
	}

	@Override
	public void run(String name, JsonObject input, Consumer<ChatApi.ToolResult> done)
	{
		try
		{
			started.run();
		}
		catch (RuntimeException e)
		{
			// Only the status line is lost.
			log.warn("look-up start not shown", e);
		}
		// Each runner checks its own settings again, so a call to a tool that wasn't offered is turned down there.
		if (LookupTools.handles(name))
		{
			lookups.run(name, input, done);
		}
		else if (GameDataTools.handles(name))
		{
			gameData.run(name, input, done);
		}
		else
		{
			done.accept(ChatApi.ToolResult.error("There's no tool called " + name + "."));
		}
	}
}
