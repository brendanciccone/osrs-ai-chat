# Developing AI Chat

Building needs a JDK 11 to 23 (17 or 21 recommended): the Gradle 8.10 wrapper, the same as RuneLite's plugin
template, doesn't run on newer ones.

```bash
./gradlew build   # compile and test (Windows: gradlew.bat build)
./gradlew run     # RuneLite's developer client with the plugin loaded (Windows: gradlew.bat run)
```

The developer client is RuneLite's own (see the
[example-plugin](https://github.com/runelite/example-plugin) template this repo follows). To log in with a Jagex
account there, follow RuneLite's [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).

`./gradlew run` logs at debug level, and AI Chat writes one line there for each request that ends: the model and the
tokens it used, never what was said. For example
`reply from claude-opus-5-5: 1204 input, 3410 from cache, 0 written to cache, 352 output tokens` (or `summary from`
for a long chat's summary, `failed reply from` for one that ended in an error). That's how to check that Claude's
prompt caching works: a message writes the chat so far to the cache, and the next one, sent within a few minutes,
reads it back (a very short chat can be under Claude's minimum and isn't cached). ChatGPT and OpenAI-compatible
services only say what they read from their own cache.

To try it without paying for API use, run a local model with [Ollama](https://ollama.com) (`ollama pull llama3.2`),
then set **Provider** to **OpenAI-compatible** and, in the **Other (OpenAI-compatible)** section, set **Base URL**
`http://localhost:11434/v1` and **Model** `llama3.2`.

## Tests

`./gradlew build` runs them all. No test calls a real AI provider or the Wiki: the network tests use a stand-in
server on 127.0.0.1 (`StandIn`), which serves canned answers (streams included) and records each request.

- `AnthropicApiTest`, `OpenAiApiTest`: the two providers against `StandIn`: streaming, tool rounds, retries,
  token counts, model lists, and the settings a service may refuse. `ChatApiTest`: what they share (keys, retry
  waits, running tools). `SseTest`: the event-stream reader.
- `RequestRunnerTest`: a message's way out and its answer's way back, with a stand-in provider and EDT: summary
  first, the reply as it streams in, look-ups, Stop (what was shown stays), Retry (an unanswered question, or the
  latest reply written again on the same history), a chat too long for its model, the line each request logs, and
  answers that come too late to count.
  `ThrottleTest`: redrawing a streaming reply at most ~15 times a second. `ToolBoxTest`: which tools go with a
  request, in what order, and which runner answers each call.
- `ConversationBuilderTest`: what a request sends (history, character notes, summaries, replaying Claude's replies).
  `ChatStoreTest`: what "Save chat history" saves and loads, files from older versions included. `ChatSaverTest`: when
  the saved chats are opened, brought back, saved and deleted, with a stand-in disk.
- `LookupToolsTest`: the Wiki and GE price tools, against a stand-in Wiki and canned prices. `GameDataTest` and
  `GameDataToolsTest`: writing up the player's items, Slayer task and diaries, and when the game-data tools share.
  `CharacterInfoTest`: the character note.
- `MarkdownTest` and `MessageViewTest`: reading replies' Markdown, and drawing it off screen (headless): alignment,
  the width a bubble hugs, links and tooltips, tables (drawn as tables or cards, copied as text). `TableViewTest`: how
  a table's columns share the width, and when it turns into cards. `StackLayoutTest`: the transcript's layout, bubbles that hug their text
  included. `GlyphTest`: the drawn icons.
  `GameChatEchoTest`: replies as game chat. `PanelTextTest`: the line under a reply on its way ("Thinking..."), each
  message's tooltip, summary notes, and the one line over a reply saying what was shared (always named in full) and
  looked up (named, or counted to fit the width), with every line behind it.
  `ConnectionCheckTest` and `ProviderSetupTest`: the model list (what the picker offers, what Test says, the picker's
  tooltip) and what the settings say about the provider. `ConnectionTesterTest`: which answers count, and when the
  picker's list is asked for (once per provider, key and URL, and on Refresh list; never while AI requests are off).
  `AiChatPanelTest`: the panel with a stand-in plugin (headless): Send, Stop and Skip (and a click as the button
  changes, which is ignored), the chat's title, the starters (they fill the box, never send), Retry only on the last
  message and the latest reply's Copy and Retry, the banner (and the note that points to it), the player's bubbles,
  the reply on its way, when the transcript follows the chat down, Jump to the latest, the lines that open with a
  chevron (and the one over a reply, fitted to its width), and the input box growing with its text.
  `ModelPickerTest`: the model picker's menu (the models, the one set ticked, Type a model name…, Refresh list), what
  it saves and what it doesn't, and a long name cut at its end.
  `SystemPromptTest`: the settings the assistant is told about, by the names `AiChatConfig` gives them (`SettingName`
  reads those for the tests). `PrefixTest`: the `::ai` command.

## Code

- `AiChatPlugin`: the plugin's lifecycle, threads and wiring: settings, the chatbox command and hotkey, chats, game
  chat and notifications.
- `RequestRunner`: sends a chat's messages and puts the answers in it (summary, character details, tools, the
  streaming reply, Stop, Retry). `ConversationBuilder`: what each request sends. `Throttle`: paces the redraws of a
  streaming reply.
- `ChatApi` (shared types and helpers), `AnthropicApi`, `OpenAiApi`, `Sse`: the providers. `ProviderSetup`: the chosen
  provider as the settings describe it. `ConnectionTester`: asking for the provider's model list, for "Test
  connection" and for the model picker. `ConnectionCheck`: what the list says, and what the picker offers.
- `ToolBox`: the tools that go with a request. `LookupTools` and `WikiClient`: Wiki search and pages, GE prices.
  `GameDataTools` and `GameData`: the player's equipment, inventory, bank, Slayer task and diaries.
  `CharacterInfo`: the character note.
- `AiChatPanel` (the sidebar: header, banner, transcript, composer, kept in step with the plugin through its `Host`),
  `MessageRow` (one message: the player's bubble, a reply with Copy and Retry, a note, an error), `Composer` (the input
  box, model picker and Send/Stop button), `ModelPicker` (the model's name next to Send, and its menu), `Banner`,
  `EmptyChat` (a new chat's welcome and starters), `ShowMore` (a line with a chevron that shows more when clicked),
  `MessageView`, `TableView` and `Markdown` (formatted text, tables), `PanelText` (the panel's words: the line under
  a reply on its way, tooltips, notes, the line over a reply saying what was shared and looked up), `StackLayout`, and
  the small drawn parts: `Glyph` (icons), `FlatButton`, `RoundBox`, `PanelStyle` (fonts and colours).
  `GameChatEcho`: replies as game chat.
- `Chat` (a conversation), `ChatSaver` ("Save chat history": when to load, save and delete), `ChatStore` (what's
  saved), `ChatFile` (the file itself), `AiChatConfig`.

## Plugin Hub

Rules this code keeps to: Java 11, no processes, reflection or new HTTP/JSON clients (the injected `OkHttpClient` and
`Gson` only), no `Thread.sleep`, links only through `LinkBrowser.browse`, file access only in the plugin's own folder
through `getPluginDirectory()` and off the Swing and client threads, game data read only on the client thread, and
nothing blocking the client thread or the EDT. Nothing is sent anywhere while "Enable AI requests" is off, and
provider, model and Wiki text is never rendered as HTML.

AI Chat is on the Plugin Hub as [osrs-ai-chat](https://runelite.net/plugin-hub/show/osrs-ai-chat). Its manifest,
[`plugins/osrs-ai-chat`](https://github.com/runelite/plugin-hub/blob/master/plugins/osrs-ai-chat) in the plugin-hub
repository, names the commit the Hub builds and carries the Hub's own warning:

```
warning=This plugin submits your IP address, and may submit various account data, to a 3rd-party server not controlled or verified by Runelite developers.
```

That warning already covers what later versions can share (character details, items and gear, Wiki look-ups), so it
needn't change. To release a new version: bump `version` in `runelite-plugin.properties`, merge to `main`, then open a
pull request on runelite/plugin-hub that changes only `commit=` in that manifest to the new commit on `main` (see the
plugin-hub README's "Updating a plugin"). The Hub's reviewers read every changed line, so a large update takes longer.
