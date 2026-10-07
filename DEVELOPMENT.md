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
then choose **OpenAI-compatible** with URL `http://localhost:11434/v1` and model `llama3.2`.

## Tests

`./gradlew build` runs them all. No test calls a real AI provider or the Wiki: the network tests use a stand-in
server on 127.0.0.1 (`StandIn`), which serves canned answers (streams included) and records each request.

- `AnthropicApiTest`, `OpenAiApiTest`: the two providers against `StandIn`: streaming, tool rounds, retries,
  token counts, model lists, and the settings a service may refuse. `ChatApiTest`: what they share (keys, retry
  waits, running tools). `SseTest`: the event-stream reader.
- `RequestRunnerTest`: a message's way out and its answer's way back, with a stand-in provider and EDT: summary
  first, the reply as it streams in, look-ups, Stop (what was shown stays), Retry, a chat too long for its model, the
  line each request logs, and answers that come too late to count.
  `ThrottleTest`: redrawing a streaming reply at most ~15 times a second. `ToolBoxTest`: which tools go with a
  request, in what order, and which runner answers each call.
- `ConversationBuilderTest`: what a request sends (history, character notes, summaries, replaying Claude's replies).
  `ChatStoreTest`: what "Remember chats" saves and loads, files from older versions included. `ChatSaverTest`: when
  the saved chats are opened, brought back, saved and deleted, with a stand-in disk.
- `LookupToolsTest`: the Wiki and GE price tools, against a stand-in Wiki and canned prices. `GameDataTest` and
  `GameDataToolsTest`: writing up the player's items, Slayer task and diaries, and when the game-data tools share.
  `CharacterInfoTest`: the character note.
- `MarkdownTest` and `MessageViewTest`: reading replies' Markdown, and drawing it off screen (headless).
  `StackLayoutTest`: the transcript's layout.
  `GameChatEchoTest`: replies as game chat. `PanelTextTest`: the status line, and the Wiki and GE price look-ups
  folded into one line under a reply, with the full list when that line leaves some out.
  `ConnectionCheckTest` and `ProviderSetupTest`: "Test" and what the settings say about the provider.
  `ConnectionTesterTest`: which answers to "Test" count. `AiChatPanelTest`: when the transcript follows the chat
  down, and the lines under a message that show more when clicked (headless). `PrefixTest`: the `::ai` command.

## Code

- `AiChatPlugin`: the plugin's lifecycle, threads and wiring: settings, the chatbox command and hotkey, chats, game
  chat and notifications.
- `RequestRunner`: sends a chat's messages and puts the answers in it (summary, character details, tools, the
  streaming reply, Stop, Retry). `ConversationBuilder`: what each request sends. `Throttle`: paces the redraws of a
  streaming reply.
- `ChatApi` (shared types and helpers), `AnthropicApi`, `OpenAiApi`, `Sse`: the providers. `ProviderSetup`: the chosen
  provider as the settings describe it. `ConnectionTester`: "Test" while it runs. `ConnectionCheck`: what its answer
  says, and "Choose model".
- `ToolBox`: the tools that go with a request. `LookupTools` and `WikiClient`: Wiki search and pages, GE prices.
  `GameDataTools` and `GameData`: the player's equipment, inventory, bank, Slayer task and diaries.
  `CharacterInfo`: the character note.
- `AiChatPanel` (the sidebar), `MessageView` and `Markdown` (formatted messages), `PanelText` (the panel's status
  line, and the lines under each message), `StackLayout`. `GameChatEcho`: replies as game chat.
- `Chat` (a conversation), `ChatSaver` ("Remember chats": when to load, save and delete), `ChatStore` (what's saved),
  `ChatFile` (the file itself), `AiChatConfig`.

## Plugin Hub

Rules this code keeps to: Java 11, no processes, reflection or new HTTP/JSON clients (the injected `OkHttpClient` and
`Gson` only), no `Thread.sleep`, links only through `LinkBrowser.browse`, file access only in the plugin's own folder
through `getPluginDirectory()` and off the Swing and client threads, game data read only on the client thread, and
nothing blocking the client thread or the EDT. Nothing is sent anywhere while "Enable AI requests" is off, and
provider, model and Wiki text is never rendered as HTML.

Suggested `warning` for the plugin's Plugin Hub manifest:

```
warning=This plugin sends your messages, and any character or item details you choose to share, to the AI provider you configure (a 3rd-party server not controlled or verified by the RuneLite developers), and looks things up on the OSRS Wiki.
```
