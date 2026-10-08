<img src="assets/logo.svg" width="96" alt="AI Chat logo: a rune stone with a speech bubble carved in">

# AI Chat

Chat with Claude, ChatGPT or any OpenAI-compatible model without leaving Old School RuneScape: ask from a side panel
or the chatbox, keep playing, and get a game chat message and a notification when the answer is in. The assistant can
look things up on the OSRS Wiki while it answers. Uses your own API key. Free and open source.

![AI Chat in RuneLite: a conversation with Claude in the side panel, with the reply also shown in the game chatbox](assets/screenshot.png)

## Features

- A chat panel in the RuneLite sidebar that works like the AI chat apps you know: your messages in bubbles on the
  right, replies across the panel, "Ask anything..." at the bottom, and a few ideas to start a new chat with. Keep
  several chats: the chat's title at the top switches between them, **+** starts a new one, and the **⋯** menu
  renames, clears or deletes it
- `::ai <message>` in the chatbox to ask without opening the panel (or `::ai` alone, or a hotkey you pick in the
  settings, to open an "Ask:" box); the line is handled by the plugin and isn't sent to the game
- Replies appear in the panel as they're written, with light formatting (lists, bold, links you can click). Under each
  reply, **Copy** copies it as plain text and **Retry** (on the latest reply) asks the same question again for a new
  answer. Once a reply is complete, it's echoed into the game chat, with a RuneLite notification
- **Wiki look-ups:** the assistant can search and read the OSRS Wiki and check Grand Exchange prices (from RuneLite's
  own price data) while it answers, and links the pages it used. What it looked up is listed above each reply.
  Look-ups need a model that can use tools; a model that can't (some that run on your own computer) answers from
  memory, and says so with its reply
- Pick your provider: **Claude** (Anthropic), **ChatGPT** (OpenAI), or any service with an **OpenAI-compatible** API,
  such as OpenRouter, Groq, or a free model running on your own computer with Ollama or LM Studio
- **Pick a model next to Send**, from the ones your key can use (or type any name), and check your setup with
  **Test connection** in the **⋯** menu
- Optional, off by default: share your character (name, account type, levels and quests, and your Slayer task and
  achievement diaries when a question needs them), and your equipment, inventory and bank, for answers that fit your
  account
- A busy provider is asked again automatically; a question that failed or was stopped can be sent again with
  **Retry**, and what was shown of its reply stays for you to read
- Long chats are summarised instead of cut short (see [Long chats](#long-chats))

## Setup

1. Install AI Chat from the Plugin Hub: in RuneLite, open the Plugin Hub and search for **AI Chat**
   ([its page on runelite.net](https://runelite.net/plugin-hub/show/osrs-ai-chat)).
2. Get an API key from your provider:
   - Claude: [console.anthropic.com](https://console.anthropic.com)
   - ChatGPT: [platform.openai.com](https://platform.openai.com)
   - OpenAI-compatible: from that service, or none for local ones like [Ollama](https://ollama.com) and
     [LM Studio](https://lmstudio.ai)

   API use is billed per message by the provider. A Claude or ChatGPT subscription doesn't include API access.
3. In RuneLite's settings, open **AI Chat**. Under **General**, turn on **Enable AI requests** and choose the
   **Provider**. Then open that provider's section (**Claude**, **ChatGPT** or **Other (OpenAI-compatible)**) and paste
   your **API key**. For an OpenAI-compatible service, also set its **Base URL** (for example
   `http://localhost:11434/v1` for Ollama).
4. Open the AI Chat panel (the rune icon in the sidebar). The model picker next to Send lists the models your key can
   use: pick one, or type a model's name and press Enter. To check your key and URL, choose **Test connection** from
   the **⋯** menu (some OpenAI-compatible services, such as OpenRouter, list their models for any key, so there only
   the URL is checked). Then ask away.

## Privacy

Nothing is sent anywhere until you turn on **Enable AI requests**, and turning it off stops anything already on its
way. Then everything goes straight from RuneLite to the provider you chose, and Wiki look-ups to the OSRS Wiki;
there's no server in between.

- **Sent to your AI provider with every message:** what you type, the earlier messages of that chat (in a long chat,
  a summary instead of the oldest ones; see [Long chats](#long-chats)), short instructions about the plugin (plus
  your **Custom instructions**, if you set any), and your IP address, as with any internet request. The chat so far
  goes to whichever provider is selected when you send, so switching providers mid-chat sends it to the new one.
- **What the assistant looks up** (Wiki pages, GE prices, and anything below that you share) is sent to the provider
  as part of the reply it's for. Claude also gets the look-ups of earlier replies again with each new message in that
  chat, while RuneLite stays open. Every look-up is listed above the reply it was for, so you can always see what was
  sent: the Wiki pages and GE prices on one line, such as "Looked up: Vorkath (Wiki) · Dragon bones (GE price)", and
  anything of yours on a line of its own, such as "Shared your bank". When that first line can't name everything (a
  long list, or the words searched for on the Wiki), it ends in "(show)": click it for the full list.
- **Only while "Share character details" is on:** your character name, account type (a regular account or which kind of
  ironman), combat and total level, skill levels, quest points, and which quests you've completed or started. Added to
  your first message in a chat and again when something changed (that message says "Sent your character details"; click
  it to see them), and sent along with the rest of that chat; turning the setting off leaves it out again. The assistant
  can also look up your current Slayer task (with your Slayer points and task streak) and which achievement diaries
  you've completed, when a question needs them.
- **Only while "Share items and gear" is on:** the assistant can look at your worn equipment, your inventory, and
  your bank as it was the last time you had it open while this setting was on (on the account you're logged in to),
  with Grand Exchange prices, when a question needs them. AI Chat keeps that last look at your bank in memory only,
  and only while this setting and AI Chat itself are on.
- Character, Slayer, diary and item look-ups only work while you're logged in.
- **Sent to the OSRS Wiki while "Wiki look-ups" is on (it is by default):** the search words and page titles the
  assistant looks up, and your IP address, go to oldschool.runescape.wiki. AI Chat adds nothing about your account,
  but the assistant writes the search words itself, from your question and anything you've shared with it, such as
  an item from your bank. Nothing from the Wiki is stored on your computer. GE prices come from the price list
  RuneLite already keeps, so checking one sends nothing anywhere.
- **The model list:** to fill in the model picker, AI Chat asks your provider which models your API key can use when
  the panel opens and when you change the provider, key or URL (only while **Enable AI requests** is on), and again
  when you choose **Test connection**. That sends the key and your IP address, nothing else, and the list is only
  kept in memory.
- **Never sent:** your account or login details, where you are in the game, what's around you, and anything about
  other players.
- **Your API key** is only sent to the provider it belongs to (never over plain `http://` to another computer on the
  internet). RuneLite keeps it with your other settings, unencrypted on your computer, and on RuneLite's servers if
  you use profile sync.
- **Chats** are kept on this computer in `.runelite/plugin-data/osrs-ai-chat/chats.json` (the latest 200 messages of
  each, and any older ones still sent to the assistant; see [Long chats](#long-chats)), so they're still there next
  time, including any character info that went with them and the list of what was looked up for each reply (the
  look-ups' contents aren't kept). Clear and Delete remove them from there too. Turn off **Save chat history** to keep
  chats only while RuneLite is open; that also deletes the saved copy. API keys are never saved there. With
  several RuneLite windows open, the first one remembers its chats and the others keep theirs only while open.
  Uninstalling AI Chat leaves the file: turn off **Save chat history** first, or delete the
  `.runelite/plugin-data/osrs-ai-chat` folder afterwards.
- **Notifications** say which assistant replied, not what you asked.

The provider's own privacy policy applies to what you send them, and the OSRS Wiki's to what goes there.

## Long chats

AI Chat never quietly drops the start of a chat. Once a chat would send more than 40 messages, it first asks the same
provider and model for a short summary of the oldest ones (one extra request, shown as "Summarising earlier
messages..." where the reply will appear), then sends that summary instead of them, along with the newest 16 or so. A
note just before your question says "Summary of 24 earlier messages (show)": click it to read the summary. The
messages it covers are drawn fainter, and their tooltip says they're summarised. If it couldn't be made, or you skip
it (the button next to the model picker says Skip while it's being made), a note says so and the whole chat is sent
that time; the next message tries again.

A chat can also be too long for the model with fewer messages: long replies and the Wiki pages read for them add up,
and smaller models take in less. When the provider says so, AI Chat summarises everything before your question and
asks once more; if it's still too long, start a new chat or choose a model that can take more. Ollama doesn't say so:
past the model's context size it quietly forgets the start of the chat, so raise that in Ollama for long chats.

**Save chat history** saves the latest 200 messages of each chat. Older ones are left out of the saved copy only once
they're no longer sent (the summary covers them, or they're notes and errors), and a note at the start of the chat
says how many are missing.

## Settings

Each provider's settings are in its own section; only the one you picked under **Provider** is used. You can also
pick the model in the AI Chat panel, next to Send.

| Section | Setting | Default | |
|---|---|---|---|
| General | Enable AI requests | off | Nothing is sent until this is on |
| | Provider | Claude | Claude, ChatGPT, or an OpenAI-compatible service |
| | Custom instructions | | Added to what the assistant is told, for example "I'm an ironman" |
| | Wiki look-ups | on | Lets the assistant search and read the OSRS Wiki while it answers (see Privacy). GE prices come from RuneLite's own price data and work with this off |
| Claude | API key / Model | / `claude-opus-5-5` | Any Claude model, for example `claude-sonnet-5-5` or `claude-haiku-4-5` |
| ChatGPT | API key / Model | / `gpt-6-luna` | Any OpenAI chat model, for example `gpt-6.1-sol` |
| Other (OpenAI-compatible) | Base URL / API key / Model | | The service's base URL (usually ending in `/v1`), key if it needs one, and model name |
| | Thinking | Short (faster) | How long a reasoning model thinks first; *Model default* leaves it to the service |
| Data & privacy | Share character details | off | Name, account type, levels and quests with your messages; Slayer task and diaries when needed (see Privacy) |
| | Share items and gear | off | Equipment, inventory and bank (as last seen) when needed (see Privacy) |
| | Save chat history | on | Keep chats when RuneLite closes (saved on this computer) |
| Notifications & game chat | Notify on reply | on | RuneLite notification when a reply arrives (enable *send when focused* to get it while playing) |
| | Show replies in game chat | on | Echo replies into the chatbox |
| | Game chat reply length | 500 | Longer replies are cut short in the chatbox; the panel has everything |
| | Ask hotkey | none | Opens an "Ask:" prompt in the chatbox |

Claude requests use Anthropic's prompt caching: the earlier part of a chat, sent again with every message, is read
from the cache at a fraction of the price. If Anthropic's safety filter declines a request, Anthropic retries it on
another Claude model (its server-side fallback) instead of refusing outright, where the model offers that. ChatGPT
and OpenAI-compatible models are asked to keep their reasoning short (reasoning effort "low"), which makes most
thinking models answer much faster; a model that doesn't support that is asked normally. A few models that don't
think by default will think a little; for those, set *Thinking* in the Other (OpenAI-compatible) section to *Model
default*.

Independent project, not affiliated with or endorsed by Anthropic, OpenAI, Jagex or RuneLite. The assistant can't
control the game, and sees only what you choose to share (see [Privacy](#privacy)). BSD-2-Clause.
