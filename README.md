# MoveByText

A Starsector mod (game version 0.98a) that turns text the player types into fleets. Open the "Fleet commands"
panel on the campaign screen (or press F8), write what you want ("send a small trade convoy from Jangala to
Sindria", "have the hegemony defend Chicomoztoc") and press Send. What happened, or why nothing did, shows up in the
panel and in the campaign messages.

Three things can be asked for:

| Request | What the game does |
|---|---|
| A war fleet (`SPAWN`) | A fleet of the named faction appears next to the player and patrols. |
| A trade convoy (`TRADE`) | Traders travel to the named place, from the player's location or a named origin. |
| Help for a place (`HELP`) | A relief fleet travels to the named place and defends it for a while. |

## The panel

| Key or button | What it does |
|---|---|
| F8 | Opens and closes the editor. Another key can be set with `"panelKey"` in `llmdata.json`. |
| Enter, Send | Sends the message (Ctrl+Enter too). |
| Shift+Enter | Starts a new line of the message. |
| Up, Down | Bring back earlier messages, to send again or change. |
| Tab | Completes a faction, star system, market or station name; Tab again gives the next one that fits. |
| Esc | Closes the editor; what was typed stays for when it opens again. |
| Spawn, Discard | Spawns the fleets of the last answer, or drops them. Without `"confirmBeforeSpawn": false` in `llmdata.json`, fleets wait for this. |
| Cancel | Stops waiting for the answer; it is ignored when it comes. |
| Forget chat | Starts a new conversation: the language model no longer sees the earlier messages. |
| The grip right of the button | Drag it to move the panel. `"panelPosition"` in `llmdata.json` sets where it starts. |

One message is sent at a time. While its answer is awaited, Send is off and the panel shows how long it has
taken. The editor stays open, and the conversation and earlier messages stay available, when another save is
loaded. The panel is hidden while the campaign UI is (H), and while a dialog, menu or tab such as the map is open.

The settings are read again on every game load. A change to `llmdata.json` or `systemPrompt.txt` shows after
loading a save; the game doesn't have to be restarted.

## Providers

Something has to read the text. `"provider"` in `data/config/llmdata.json` picks one of four:

| Provider | What it is | Setup |
|---|---|---|
| `gemini` | Google's Gemini API | An API key in `llmdata.json` |
| `koboldcpp` | A language model on your own PC or network | [nlp/koboldcpp/README.md](nlp/koboldcpp/README.md) |
| `rasa` | A Rasa NLU server on this PC, no language model | [nlp/rasa/README.md](nlp/rasa/README.md) |
| `spacy` | A spaCy NLU server on this PC, no language model | [nlp/spacy/README.md](nlp/spacy/README.md) |

Every provider answers with the same command tags, e.g. `<SPAWN:pirates:40>`, so the game side doesn't care which
one is used.

The language models (Gemini, KoboldCpp) get their instructions from `data/config/systemPrompt.txt`. A
`"systemPrompt"` in `llmdata.json` wins over the file, for trying out a variant without changing it.

## Factions from other mods

The prompt and the word lists know the 11 vanilla factions. On every game load the mod also reads the factions
that other mods add, the ones shown in the intel tab. It also reads the names of the sector's systems, markets
and stations.

- Gemini and KoboldCpp get the mod factions listed in the prompt, at the `{MOD_FACTIONS}` line of
  `systemPrompt.txt`. Without mod factions that line disappears and the prompt is the vanilla one.
- Rasa and spaCy were trained on the vanilla names only, so they rarely mark a mod faction as a faction. When they
  do, `FactionNames` finds it. For spaCy, the `faction` rule also looks for it in short spawn commands.
- A vanilla name always wins over a mod faction's name that contains it.
- How well the language models handle mod factions has not been measured: none of the `LlmPromptTest` cases use
  them.

## Install

1. Install RolflectionLib (mod id `rolflection_lib`), the only mod this one requires.
2. Put this folder in `Starsector/mods/` and enable the mod in the launcher.
3. Copy `data/config/llmdata.json.example` to `data/config/llmdata.json` and fill it in. The example explains
   every setting. Git ignores the copy, so a key in it is never committed.

## Code layout

| Folder | What is in it |
|---|---|
| `src/KirizsKeve/MoveByText/` | The mod entry point (`MoveByTextImpl`), the editor panel and `CommandConsole`. |
| `src/.../command/` | The three commands and `CommandParser`, which reads their tags out of a reply. |
| `src/.../game/` | Finding places by name, spawning fleets and reading the sector's names (`SectorNames`). Game thread only. |
| `src/.../nlp/` | The providers, the settings and the prompt. Nothing here touches Starsector objects, so it runs outside the game. |
| `test/` | `AllOfflineTests` and the suites it runs, which need no game or network, plus `LlmPromptTest`, which does. `test/data/` holds tables shared with the Python side. |
| `nlp/` | Setup, training and evaluation scripts for the KoboldCpp, Rasa and spaCy providers. |
| `build.py` | Builds the jar and runs the offline tests without IntelliJ. |

## Building and testing

`jars/MoveByText.jar` is the file the game loads, so rebuild it after changing the code. Either:

- run `python build.py` in this folder (Python 3, JDK 17 or newer). It compiles `src/`, writes the jar and runs
  the offline tests. `--no-test` skips the tests, `--test-only` leaves the jar alone, and `--filter WORD` runs only
  the tests whose name contains WORD; or
- build the IntelliJ project (JDK 17), whose "Create .jar" artifact writes the same jar.

Every `build.py` run, even with `--no-test`, also runs `SandboxCheck`. Starsector loads mod code through its own
class loader, which refuses most of `java.io` (`File`, `ByteArrayOutputStream`...), `java.lang.reflect`,
`java.nio.file.File*`, `javax.script` and `java.util.prefs`. javac doesn't know that, and the game only says so when
it runs that code, so a build that uses one of these fails right away.

The offline tests need no game, network or API key. Where code asks `Global` for the sector or the settings,
`FakeGame` stands in for them; `FakeUi` does the same for the panel's UI objects. Fleet creation itself is swapped out
through `FleetSpawner`'s `FleetMaker`. `AllOfflineTests` runs every suite, and each also has its own `main()` for
IntelliJ, with the mod folder as the working directory:

| Suite | What it checks |
|---|---|
| `CommandParserTest`, `CommandBuilderTest` | Reading command tags, and turning NLU answers into them |
| `CommandConsoleTest`, `ComposerTest`, `PanelTest` | One message at a time, Cancel, swapping the backend; the panel: toggle and hotkey, typing, Send/Cancel while busy, the waiting line, Up/Down, Forget chat |
| `SettingsTest`, `GameLoadTest` | Where the prompt comes from, mod factions in it, the settings read again on every game load, `panelKey` |
| `PlaceFinderTest`, `SectorNamesTest` | Which place a name means; which factions and places of the sector reach the NLP side |
| `CommandFailureTest`, `FleetSpawnerTest` | Every way a command fails; what SPAWN, TRADE and HELP ask the game for, where the fleet goes, its orders and flags, which market sends help |
| `ConsistencyTest` | The prompt (faction ids, examples, size scale) and the training data in `generate_nlu.py` (factions, places, intents, entity types, every size/multiplier/offset/count word, `number_words`) against the Java code; `distance()` against `test/data/distance_cases.txt` |
| `ProjectFilesTest` | The game's class rules, `mod_info.json` against the jar, `llmdata.json.example`, the `LlmPromptTest` cases and `canonical()`; `check_overlap.py`, `test_handwritten.py` and `compare_runs.py` against the Java side |
| `CodeRulesTest` | In the compiled code: `nlp/` never touches the game (it runs on the worker thread), nor does other code run there; no `System.exit` or `Thread.sleep`; every suite registered; every script a `.bat` starts exists |
| `GeminiClientTest`, `KoboldCppClientTest`, `RasaClientTest` | The providers, against fake servers on localhost |

Some of these run Python, with the same interpreter as `build.py` (from IntelliJ: `python` on the PATH). `build.py`
also runs the Python tests in `test/python/` (`python -m unittest discover -s test/python`): the training data
generator, `nlu_data.py`, the evaluation scripts' helpers, a syntax check of every `.py` file, and
`nlp/spacy/test_distance.py`, which checks the Python copy of `distance()` against the same table as Java.
`LlmPromptTest` sends real requests to the configured provider.

Still only testable in the game: `FleetFactoryV3` filling a fleet with ships, what the panel draws and real keyboard
focus, and whether the game caches `llmdata.json` and `systemPrompt.txt` between loads.
