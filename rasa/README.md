# Rasa NLU parser for MoveByText

A third way to read the player's text, next to Gemini and KoboldCpp, with **no language model**. Rasa's DIET
classifier, trained only on the examples in this folder, picks the intent (spawn a fleet, send a convoy, send help,
or not a request) and marks the faction, size, places and so on. `RasaCommandBuilder.java` turns that into the same
command tags the LLMs write, so the game and the tests treat every provider the same way.

| File | What it does |
|---|---|
| `generate_nlu.py` | Writes the training data (`data/nlu.yml`, `data/lookups.yml`) from templates. Edit the templates here. |
| `check_overlap.py` | Removes training examples identical to a test message, so the tests stay a fair check. |
| `config.yml` | The NLU pipeline: tokenizer, word and character features, DIET, fallback. |
| `train.bat` | Generates the data, removes overlaps, trains a model into `models/`. |
| `start-server.bat` | Starts the server on `127.0.0.1:5005` (this PC only). |
| `requirements.txt` | Exact Python package versions for the install. |

## Setup (once)
1. Install Python 3.10. Rasa 3.6 doesn't run on 3.11 or newer.
   `winget install --id Python.Python.3.10 --scope user`
2. Make an environment outside the mod folder (it's about 2 GB with TensorFlow) and install Rasa. Run this in the
   `rasa` folder:
   ```
   py -3.10 -m venv "%USERPROFILE%\.venvs\movebytext-rasa"
   "%USERPROFILE%\.venvs\movebytext-rasa\Scripts\python" -m pip install --upgrade pip
   "%USERPROFILE%\.venvs\movebytext-rasa\Scripts\pip" install -r requirements.txt
   ```
   `requirements.txt` lists the exact versions of all 167 packages from a setup that works, so a later release of
   one of them can't break the install. Elsewhere? Set the `RASA_VENV` environment variable to the environment's
   folder; both scripts use it.
3. Run `train.bat` (a few minutes).

## Every time you play
1. Run `start-server.bat` and wait for "Rasa server is up and running" (20-40 seconds). Keep the window open.
2. In `data/config/llmdata.json` set `"provider": "rasa"`. The `rasa` block is optional; see the example file.

Check it by hand:
```
curl.exe -X POST http://127.0.0.1:5005/model/parse -d "{\"text\":\"spawn a small pirate fleet\"}"
```

## Changing what it understands
Edit the templates or word lists in `generate_nlu.py`, run `train.bat`, restart the server. Faction nicknames and
size words are also listed in `RasaCommandBuilder.java` (`FactionNames`, `SizeWords`), which does the final mapping;
add new ones in both places.

To see how well a model does, run `LlmPromptTest` with the provider set to `rasa`. It writes
`test/logs/llm-prompt-test_rasa_<date>.log`, the same report the LLMs get.

## How well it does
On the 629 cases of `LlmPromptTest` (October 2026), the Rasa model passes about **42%**. Qwen 3.5 9B with the system
prompt passes about 91% of the first 329 and 88% of the 300 long ones. Rasa is strong on short, direct requests
(about 85% of the 129 basic cases), sizes, numbers and the "not a request" cases. It is weak on long messages where the
request depends on context, on who sends help, on lore descriptions, and on several fleets in one sentence.
It answers in well under a second and needs no GPU.

## Limits
- English only. Messages in other languages are mostly read as "not a request".
- It reads each message on its own, with no memory of earlier messages.
- Anything the templates don't cover (unusual lore descriptions, complicated size math) is likely to be missed.
  The fix is more templates, not a bigger model.
- Rasa Open Source 3.6.21 (January 2025) is the last open-source release; newer Rasa versions need a license.
