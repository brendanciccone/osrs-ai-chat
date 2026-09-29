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

To try it without paying for API use, run a local model with [Ollama](https://ollama.com) (`ollama pull llama3.2`),
then choose **OpenAI-compatible** with URL `http://localhost:11434/v1` and model `llama3.2`.

The tests run both provider clients against a stand-in server on 127.0.0.1 (`ChatApiTest`, no real API calls) and
check what saved chats keep (`ChatStoreTest`).

Code: `AiChatPlugin` (lifecycle, chats, chatbox command, game chat, notifications), `AiChatPanel` (the sidebar),
`AnthropicApi` and `OpenAiApi` (the providers, behind `ChatApi`), `CharacterInfo` (the optional character details),
`ChatStore` (what "Remember chats" saves), `AiChatConfig`.

Plugin Hub rules this code keeps to: Java 11, no processes, reflection or new HTTP/JSON clients (the injected
`OkHttpClient` and `Gson` only), file access only in the plugin's own folder through `getPluginDirectory()` and off
the Swing and client threads, nothing blocking the client thread.
