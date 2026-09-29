<img src="assets/logo.svg" width="96" alt="AI Chat logo: a rune stone with a speech bubble carved in">

# AI Chat

Chat with Claude, ChatGPT or any OpenAI-compatible model without leaving Old School RuneScape: ask from a side panel
or the chatbox, keep playing, and get a game chat message and a notification when the answer is in. Uses your own API
key. Free and open source.

![AI Chat in RuneLite: a conversation with Claude in the side panel, with the reply also shown in the game chatbox](assets/screenshot.png)

## Features

- A chat panel in the RuneLite sidebar, with several chats side by side
- `::ai <message>` in the chatbox to ask without opening the panel (or `::ai` alone, or a hotkey you pick in the
  settings, to open an "Ask:" box); the line is handled by the plugin and never sent to the game
- Replies in the panel, echoed into the game chat, plus a RuneLite notification
- Pick your provider: **Claude** (Anthropic), **ChatGPT** (OpenAI), or any service with an **OpenAI-compatible** API,
  such as OpenRouter, Groq, or a free model running on your own computer with Ollama or LM Studio
- Optional, off by default: share your character's name, levels and quest progress for answers that fit your account

## Setup

1. Get an API key from your provider:
   - Claude: [console.anthropic.com](https://console.anthropic.com)
   - ChatGPT: [platform.openai.com](https://platform.openai.com)
   - OpenAI-compatible: from that service, or none for local ones like [Ollama](https://ollama.com) and
     [LM Studio](https://lmstudio.ai)

   API use is billed per message by the provider. A Claude or ChatGPT subscription doesn't include API access.
2. In RuneLite's settings, open **AI Chat**, turn on **Enable AI requests** and choose the **AI provider**. Then open
   that provider's section (Claude, ChatGPT or OpenAI-compatible) and paste your key. For an OpenAI-compatible
   service, also set its URL (for example `http://localhost:11434/v1` for Ollama) and model.
3. Open the AI Chat panel (the rune icon in the sidebar) and ask away.

## Privacy

Nothing is sent until you turn on **Enable AI requests**. Then everything goes straight from RuneLite to the provider
you chose; there's no server in between.

- **Sent with every message:** what you type, the earlier messages of that chat, short instructions about the plugin
  (plus your own extra instructions, if you set any), and your IP address, as with any internet request. The chat so
  far goes to whichever provider is selected when you send, so switching providers mid-chat sends it to the new one.
- **Only while "Send character info" is on:** your character name, combat and total level, skill levels, quest
  points, and which quests you've completed or started. Added to your first message in a chat and again when
  something changed, and sent along with the rest of that chat; turning the setting off leaves it out again.
- **Never sent:** your account or login details, your location, inventory or bank, or anything about other players.
- **Your API key** is only sent to the provider it belongs to (never over plain `http://` to another computer on the
  internet). RuneLite keeps it with your other settings, unencrypted on your computer, and on RuneLite's servers if
  you use profile sync.
- **Chats** are kept on this computer in `.runelite/plugin-data/ai-chat/chats.json` (the latest 200 messages of each),
  so they're still there next time, including any character info that went with them. Clear and Delete remove them
  from there too. Turn off **Remember chats** to keep chats only while RuneLite is open; that also deletes the saved
  copy. API keys are never saved there. With several RuneLite windows open, the first one remembers its chats and the
  others keep theirs only while open. Uninstalling AI Chat leaves the file: turn off **Remember chats** first, or
  delete the `.runelite/plugin-data/ai-chat` folder afterwards.
- **Notifications** say which assistant replied, not what you asked.

The provider's own privacy policy applies to what you send them.

## Settings

Each provider's settings are in its own section; only the one you picked is used.

| Setting | Default | |
|---|---|---|
| Enable AI requests | off | Nothing is sent until this is on |
| AI provider | Claude | Claude, ChatGPT, or OpenAI-compatible |
| Extra instructions | | Added to what the assistant is told, for example "I'm an ironman" |
| Claude API key / model | / `claude-opus-5-5` | Any Claude model, for example `claude-sonnet-5-5` or `claude-haiku-4-5` |
| ChatGPT API key / model | / `gpt-6-luna` | Any OpenAI chat model, for example `gpt-6.1-sol` |
| Compatible API URL / key / model | | The service's base URL (usually ending in `/v1`), key if it needs one, and model name |
| Thinking (OpenAI-compatible) | Short (faster) | How long a reasoning model thinks first; *Model default* leaves it to the service |
| Send character info | off | See Privacy above |
| Notify on reply | on | RuneLite notification when a reply arrives (enable *send when focused* to get it while playing) |
| Show replies in game chat | on | Echo replies into the chatbox |
| Chat reply length | 500 | Longer replies are cut short in the chatbox; the panel has everything |
| Ask hotkey | none | Opens an "Ask:" prompt in the chatbox |
| Remember chats | on | Keep chats when RuneLite closes (saved on this computer) |

With a newer Claude model, if Anthropic's safety filter declines a request, Anthropic retries it on another Claude model
(its server-side fallback) instead of refusing outright. ChatGPT and OpenAI-compatible models are asked to keep their
reasoning short (reasoning effort "low"), which makes most thinking models answer much faster; a model that doesn't
support that is asked normally. A few models that don't think by default will think a little; for those, set
*Thinking* in the OpenAI-compatible section to *Model default*.

Independent project, not affiliated with or endorsed by Anthropic, OpenAI, Jagex or RuneLite. The assistant can't see
or control the game. BSD-2-Clause.
