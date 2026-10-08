package com.aichat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The panel's words for a reply on its way, a message's tooltip, notes, and what was looked up or shared. */
public class PanelTextTest
{
	private static final long NOW = 1_000_000;

	private static Chat running()
	{
		Chat chat = new Chat("x");
		chat.pending = new ChatApi.Pending();
		chat.runStartedAt = NOW - 12_400;
		return chat;
	}

	@Test
	public void theLineUnderTheReplySaysWhatItsDoingUntilItWrites()
	{
		Chat chat = running();
		assertEquals("Thinking… 12s", PanelText.live(chat, NOW));

		chat.liveText = "Vorkath is";
		assertNull("the words say it all", PanelText.live(chat, NOW));

		// What it looked up is listed above the reply, as each look-up comes back: this line doesn't say it again.
		chat.lookingUp = true;
		chat.liveActivity.add("Read the Wiki page \"Vorkath\"");
		assertEquals("Looking things up… 12s", PanelText.live(chat, NOW));

		chat.retryWhy = "Anthropic is busy";
		chat.retryAt = NOW + 5_200;
		assertEquals("Anthropic is busy; trying again in 6s", PanelText.live(chat, NOW));
		// Once the wait is over, the request is on its way again.
		assertEquals("Looking things up… 18s", PanelText.live(chat, NOW + 6_000));
	}

	@Test
	public void thinkingCountsDotsThenSeconds()
	{
		assertEquals("Thinking.", PanelText.thinking(0));
		assertEquals("Thinking..", PanelText.thinking(1_200));
		assertEquals("Thinking...", PanelText.thinking(2_999));
		assertEquals("Thinking.", PanelText.thinking(3_000));
		// A model that's slow to start: the seconds show it's still waiting.
		assertEquals("Thinking… 5s", PanelText.thinking(5_000));
		assertEquals("Thinking… 1m 2s", PanelText.thinking(62_000));
	}

	@Test
	public void summarisingHasItsOwnLine()
	{
		Chat chat = running();
		chat.skipSummary = () ->
		{
		};
		assertEquals("Summarising earlier messages… 12s", PanelText.live(chat, NOW));
		chat.runStartedAt = NOW - 75_000;
		assertEquals("Summarising earlier messages… 1m 15s", PanelText.live(chat, NOW));
	}

	@Test
	public void aMessagesTooltipSaysWhenAndWho()
	{
		long time = 1_700_000_000_000L;
		String hhmm = new SimpleDateFormat("HH:mm").format(new Date(time));
		Chat.Message question = new Chat.Message(Chat.Role.USER, "q", time);
		assertEquals(hhmm, PanelText.tooltip(question));
		question.unanswered = true;
		assertEquals(hhmm + " · not answered", PanelText.tooltip(question));

		Chat.Message reply = new Chat.Message(Chat.Role.ASSISTANT, "a", time);
		assertEquals("Assistant · " + hhmm, PanelText.tooltip(reply));
		reply.who = "Claude";
		reply.unfinished = true;
		reply.summarized = true;
		assertEquals("Claude · " + hhmm + " · didn't finish · summarised: the summary further down is sent "
			+ "instead", PanelText.tooltip(reply));
		assertEquals(hhmm, PanelText.tooltip(new Chat.Message(Chat.Role.NOTE, "Stopped.", time)));
	}

	@Test
	public void aSummarysNoteIsOneLineWithTheSummaryAClickAway()
	{
		Chat chat = new Chat("x");
		List<Chat.Message> old = new ArrayList<>();
		for (int i = 0; i < 24; i++)
		{
			Chat.Message m = new Chat.Message(i % 2 == 0 ? Chat.Role.USER : Chat.Role.ASSISTANT, "m" + i);
			chat.messages.add(m);
			old.add(m);
		}
		Chat.Message note = ConversationBuilder.applySummary(chat, old, "The player is training Agility.", null);
		PanelText.Note shown = PanelText.note(note.text);
		assertEquals("Summary of 24 earlier messages", shown.line);
		assertEquals("The player is training Agility.", shown.details);

		Chat one = new Chat("y");
		Chat.Message only = new Chat.Message(Chat.Role.USER, "q");
		one.messages.add(only);
		PanelText.Note single = PanelText.note(ConversationBuilder.applySummary(one, Collections.singletonList(only),
			"One question.\n\nOn two lines.", null).text);
		assertEquals("Summary of 1 earlier message", single.line);
		assertEquals("One question.\n\nOn two lines.", single.details);

		// Any other note is shown as it is.
		PanelText.Note stopped = PanelText.note("Stopped.");
		assertEquals("Stopped.", stopped.line);
		assertNull(stopped.details);
		assertEquals("Summary of the plan: none", PanelText.note("Summary of the plan: none").line);
	}

	@Test
	public void timesAreShort()
	{
		assertEquals("0s", PanelText.elapsed(-5));
		assertEquals("59s", PanelText.elapsed(59_999));
		assertEquals("1m 0s", PanelText.elapsed(60_000));
	}

	private static PanelText.Activity fold(String... lines)
	{
		return PanelText.activity(Arrays.asList(lines));
	}

	@Test
	public void wikiPagesReadAreListedByTitle()
	{
		PanelText.Activity shown = fold(
			"Searched the Wiki for \"abyssal whip\"",
			"Read the Wiki page \"Abyssal whip\"",
			"Searched the Wiki for \"what drops the abyssal whip\"",
			"Read the Wiki page \"Abyssal demon\" (Drops)");
		assertEquals("Looked up: Abyssal whip, Abyssal demon (Wiki)", shown.lookups);
		// The searches only found the pages, so they're left off the line; the full list still has them.
		assertEquals("Wiki pages: Abyssal whip, Abyssal demon\n"
			+ "Wiki searches: \"abyssal whip\", \"what drops the abyssal whip\"", shown.full);
		assertEquals(Collections.emptyList(), shown.rest);
		assertEquals(Collections.singletonList("Looked up: Abyssal whip, Abyssal demon (Wiki)"), shown.lines());

		// A title with quotes or brackets of its own, with and without a section.
		assertEquals("Looked up: \"Ernest\" the Chicken, Dragon (disambiguation) (Wiki)", fold(
			"Read the Wiki page \"\"Ernest\" the Chicken\" (introduction)",
			"Read the Wiki page \"Dragon (disambiguation)\"").lookups);
	}

	@Test
	public void searchesAreListedWhenNoPageWasRead()
	{
		PanelText.Activity one = fold("Searched the Wiki for \"abyssal whip drop rate\"");
		assertEquals("Looked up: \"abyssal whip drop rate\" (Wiki search)", one.lookups);
		// The line already says it all.
		assertNull(one.full);

		PanelText.Activity two = fold("Searched the Wiki for \"whip\"", "Searched the Wiki for \"abyssal whip\"");
		assertEquals("Looked up: \"whip\", \"abyssal whip\" (Wiki searches)", two.lookups);
		assertNull(two.full);
	}

	@Test
	public void wikiAndGePricesShareTheLine()
	{
		PanelText.Activity shown = fold(
			"Searched the Wiki for \"abyssal whip\"",
			"Read the Wiki page \"Abyssal whip\"",
			"Read the Wiki page \"Abyssal demon\"",
			"Checked the GE price of Dragon bones");
		assertEquals("Looked up: Abyssal whip, Abyssal demon (Wiki) · Dragon bones (GE price)", shown.lookups);
		assertEquals("Wiki pages: Abyssal whip, Abyssal demon\nWiki searches: \"abyssal whip\"\nGE prices: Dragon bones",
			shown.full);

		PanelText.Activity searched = fold(
			"Checked the GE price of Dragon bones",
			"Searched the Wiki for \"dragon bones\"",
			"Checked the GE price of Big bones");
		assertEquals("Looked up: \"dragon bones\" (Wiki search) · Dragon bones, Big bones (GE prices)", searched.lookups);
		assertNull(searched.full);
		PanelText.Activity prices = fold("Checked the GE price of Dragon bones");
		assertEquals("Looked up: Dragon bones (GE price)", prices.lookups);
		assertNull(prices.full);
	}

	@Test
	public void eachThingIsNamedOnce()
	{
		PanelText.Activity shown = fold(
			"Read the Wiki page \"Vorkath\"",
			"Read the Wiki page \"Vorkath\" (Drops)",
			"Read the Wiki page \"Vorkath\" (Strategy)",
			"Checked the GE price of Dragon bones",
			"Checked the GE price of Dragon bones",
			"Searched the Wiki for \"vorkath\"",
			"Searched the Wiki for \"vorkath\"");
		assertEquals("Looked up: Vorkath (Wiki) · Dragon bones (GE price)", shown.lookups);
		assertEquals("Wiki pages: Vorkath\nWiki searches: \"vorkath\"\nGE prices: Dragon bones", shown.full);
		assertEquals("Looked up: \"vorkath\" (Wiki search)",
			fold("Searched the Wiki for \"vorkath\"", "Searched the Wiki for \"vorkath\"").lookups);
	}

	@Test
	public void aLongListShowsThreeOfEachAndTheFullListHasThemAll()
	{
		PanelText.Activity shown = fold(
			"Read the Wiki page \"Abyssal whip\"",
			"Read the Wiki page \"Abyssal demon\"",
			"Read the Wiki page \"Abyssal Sire\"",
			"Read the Wiki page \"Abyssal tentacle\"",
			"Read the Wiki page \"Kraken\"",
			"Checked the GE price of Abyssal whip",
			"Checked the GE price of Kraken tentacle",
			"Checked the GE price of Abyssal dagger",
			"Checked the GE price of Abyssal bludgeon");
		assertEquals("Looked up: Abyssal whip, Abyssal demon, Abyssal Sire +2 more (Wiki) · "
			+ "Abyssal whip, Kraken tentacle, Abyssal dagger +1 more (GE prices)", shown.lookups);
		assertEquals("Wiki pages: Abyssal whip, Abyssal demon, Abyssal Sire, Abyssal tentacle, Kraken\n"
			+ "GE prices: Abyssal whip, Kraken tentacle, Abyssal dagger, Abyssal bludgeon", shown.full);
		// Too many of one kind is enough for the full list.
		assertEquals("Wiki pages: Abyssal whip, Abyssal demon, Abyssal Sire, Abyssal tentacle\nGE prices: Kraken tentacle",
			fold(
				"Read the Wiki page \"Abyssal whip\"",
				"Read the Wiki page \"Abyssal demon\"",
				"Read the Wiki page \"Abyssal Sire\"",
				"Read the Wiki page \"Abyssal tentacle\"",
				"Checked the GE price of Kraken tentacle").full);
		// Three of each is still all of them.
		assertNull(fold(
			"Read the Wiki page \"Abyssal whip\"",
			"Read the Wiki page \"Abyssal demon\"",
			"Read the Wiki page \"Abyssal Sire\"",
			"Checked the GE price of Abyssal whip",
			"Checked the GE price of Kraken tentacle",
			"Checked the GE price of Abyssal dagger").full);

		// A long name is cut on the line, and whole in the full list.
		String search = "how much does an abyssal whip cost to imbue";
		PanelText.Activity cut = fold("Searched the Wiki for \"" + search + "\"");
		assertEquals("Looked up: \"how much does an abyssal whip...\" (Wiki search)", cut.lookups);
		assertEquals("Wiki searches: \"" + search + "\"", cut.full);
		String item = "Abyssal whip (or) ornament kit, noted";
		PanelText.Activity price = fold("Checked the GE price of " + item);
		assertEquals("Looked up: Abyssal whip (or) ornament kit... (GE price)", price.lookups);
		assertEquals("GE prices: " + item, price.full);
		// Exactly as long as fits isn't cut.
		String fits = "Abyssal whip (or) ornament kit";
		assertEquals(PanelText.NAME_CHARS, fits.length());
		assertNull(fold("Checked the GE price of " + fits).full);
	}

	@Test
	public void whatWasSharedFollowsTheLookUpLineInOrder()
	{
		PanelText.Activity shown = fold(
			"Shared your equipment",
			"Read the Wiki page \"Vorkath\"",
			"Searched your bank for \"rune\"",
			"Couldn't search the Wiki for \"vorkath\"",
			"Checked the GE price of Rune platebody",
			"Didn't share your inventory: \"Share items and gear\" is off",
			"Found no Wiki page called \"Vorkath (monster)\"",
			"Read your bank, but didn't share it: the request had stopped",
			"Found no GE price for \"vorki\"",
			"Skipped a Wiki search: Wiki look-ups are off");
		assertEquals(Arrays.asList(
			"Looked up: Vorkath (Wiki) · Rune platebody (GE price)",
			"Shared your equipment",
			"Searched your bank for \"rune\"",
			"Couldn't search the Wiki for \"vorkath\"",
			"Didn't share your inventory: \"Share items and gear\" is off",
			"Found no Wiki page called \"Vorkath (monster)\"",
			"Read your bank, but didn't share it: the request had stopped",
			"Found no GE price for \"vorki\"",
			"Skipped a Wiki search: Wiki look-ups are off"), shown.lines());
	}

	@Test
	public void otherLinesAreShownAsTheyAre()
	{
		List<String> lines = Arrays.asList(
			RequestRunner.NO_LOOKUPS,
			"Shared your Slayer task",
			"Something a later version writes",
			"Read the Wiki page Vorkath",
			"Read the Wiki page \"\"",
			"Searched the Wiki for vorkath",
			"Checked the GE price of ",
			"Couldn't finish a look-up (wiki_page)");
		PanelText.Activity shown = PanelText.activity(lines);
		assertNull(shown.lookups);
		assertNull(shown.full);
		assertEquals(lines, shown.rest);
		assertEquals(lines, shown.lines());
	}

	@Test
	public void nothingListedShowsNothing()
	{
		for (List<String> none : Arrays.asList(null, Collections.<String>emptyList(), Collections.<String>singletonList(null)))
		{
			PanelText.Activity shown = PanelText.activity(none);
			assertNull(shown.lookups);
			assertNull(shown.full);
			assertTrue(shown.lines().isEmpty());
		}
	}
}
