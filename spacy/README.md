# spaCy NLU parser for MoveByText

The spaCy version of the Rasa parser in `../rasa`: a fourth way to read the player's text, with **no language
model**. A spaCy text classifier picks the intent (spawn a fleet, send a convoy, send help, cancel, or not a
request) and a spaCy entity recognizer marks the faction, size, places and so on. Both learn from **the same
examples as the Rasa model** (`rasa/generate_nlu.py`), but start from pretrained English word vectors
(`en_core_web_md`), so words and phrasings the templates never use are less foreign to them.

`server.py` answers in Rasa's `/model/parse` format, so the mod reads it like Rasa: `SpacyClient.java` sends the
message one sentence at a time and `RasaCommandBuilder.java` turns the answers into command tags. In between,
rules fix the model's common mistakes without retraining it (see "The rules" below): some in the server
(`rules.py`, with spaCy's pretrained English parser), the rest in `SpacyClient.java`.

| File | What it does |
|---|---|
| `nlu_data.py` | Reads the Rasa training data (`rasa/data/nlu.yml`) into plain text, intent and labelled spans. |
| `config.cfg` | The spaCy pipeline: word vectors, a shared CNN, text classifier, entity recognizer. |
| `train.py`, `train.bat` | Generates the data with the Rasa scripts, removes test overlaps, trains a model into `models/`. |
| `server.py`, `start-server.bat` | Starts the server on `127.0.0.1:5007` (this PC only). |
| `rules.py`, `test_rules.py` | The server's rules, and checks for them on fixed sentences. |
| `evaluate.py` | Scores a model on labelled examples (used by the cross-validation). |
| `cross-validate.bat`, `cross_validate.py` | 10-fold cross-validation, results in `results/cv-<date>-<time>/`. |
| `test_handwritten.py` | Runs the hand-written `LlmPromptTest` cases against every model of a cross-validation run. |
| `compare_runs.py` | Compares two such runs (e.g. with and without a rule) on the two halves of the test cases. |
| `requirements.txt` | Exact Python package versions for the install. |

## Setup (once)
1. Python 3.10, the same as for Rasa (`winget install --id Python.Python.3.10 --scope user`). spaCy also runs on
   newer versions, but `requirements.txt` was made with 3.10.
2. Make an environment outside the mod folder (about 500 MB) and install spaCy and the word vectors. Run this in the
   `spacy` folder:
   ```
   py -3.10 -m venv "%USERPROFILE%\.venvs\movebytext-spacy"
   "%USERPROFILE%\.venvs\movebytext-spacy\Scripts\python" -m pip install --upgrade pip
   "%USERPROFILE%\.venvs\movebytext-spacy\Scripts\pip" install -r requirements.txt
   ```
   It is a separate environment from Rasa's: Rasa 3.6 needs older numpy and pydantic versions than spaCy 3.8.
   Elsewhere? Set the `SPACY_VENV` environment variable to the environment's folder; the scripts use it.
3. Run `train.bat` (about 15 minutes; spaCy trains on one CPU core).

## Every time you play
1. Run `start-server.bat` and wait for "spaCy server is up and running" (a few seconds). Keep the window open; it
   prints one line for every sentence it reads, and what the rules changed.
2. In `data/config/llmdata.json` set `"provider": "spacy"`. The `spacy` block is optional; see the example file.

Check it by hand:
```
curl.exe -X POST http://127.0.0.1:5007/model/parse -d "{\"text\":\"spawn a small pirate fleet\"}"
```
This shows the answer after the server's rules; the rules in `SpacyClient.java` are applied after it, in the game.

## Changing what it understands
The templates are shared with Rasa: edit `rasa/generate_nlu.py`, then run `train.bat` here (and `rasa/train.bat`
for the Rasa model) and restart the server. Faction nicknames and size words are also listed in
`RasaCommandBuilder.java` (`FactionNames`, `SizeWords`), which does the final mapping for both.

How it learns is in `config.cfg`. Messages with several fleets are labelled like `faction#1`, `size#2`, so the
entity recognizer learns which fleet a word belongs to; `server.py` gives that back as Rasa's `group`.

To see how well a model does, run `LlmPromptTest` with the provider set to `spacy`. It writes
`test/logs/llm-prompt-test_spacy_<date>.log`, the same report the other providers get.

To see how well the pipeline learns the training data itself, run `cross-validate.bat`. It uses the same code and
seed as `rasa/cross_validate.py`, so a run made from the same data has exactly the same 10 parts as a Rasa run, and
the two can be compared part by part. It takes about 2 hours. Each run gets a folder `results/cv-<date>-<time>/`
(about 600 MB: each part's model is 60 MB, mostly the word vectors) with `full.log`, `summary.md` and a `part-01` to
`part-10` folder each holding that part's data, model, reports and log. A stopped run continues with
`cross-validate.bat --resume results\cv-<date>-<time>`.

Entities are scored as whole spans here: a found entity counts only when its start, end and type are all right.
"Entity+group F1" also needs the right fleet. Rasa's reports score each word on its own, so only the intent scores
compare directly between the two `summary.md` files.

To see how the same 10 models do on the hand-written cases, run `python test_handwritten.py` in the spaCy
environment (about 12 minutes, needs a JDK 17). It tests every part's model and the newest model in `models/` with
`LlmPromptTest`, using its own config and port 5008, so `data/config/llmdata.json`, a running `start-server.bat`
and the Rasa version (port 5006) are left alone. It writes `handwritten-summary.md` into the run folder.
`--rules` and `--server-rules` pick the rules (names, `all` or `none`), `--name X` keeps that run's results apart
(`handwritten-X`), and `--port` lets several runs go at once. `python compare_runs.py A B` then compares two runs.

## How well it does
On the 629 cases of `LlmPromptTest` (November 2026), averaged over the 10 models of the cross-validation run
`results/cv-20261103-120931` (the same 10 parts as Rasa's October run):

| | spaCy, all rules | spaCy, no rules | Rasa |
|---|---|---|---|
| Template data, intent accuracy | 0.995 ± 0.003 | 0.995 ± 0.003 | 0.996 ± 0.002 |
| Hand-written, basic (129 short, direct requests) | 84.7% ± 1.9 | 78.8% ± 2.5 | 86.3% ± 1.1 |
| Hand-written, advanced (200) | 46.0% ± 2.9 | 35.2% ± 3.5 | 33.3% ± 3.4 |
| Hand-written, extensive (300 long ones) | 39.9% ± 1.9 | 28.0% ± 2.4 | 30.1% ± 1.6 |
| Hand-written, total | **51.0% ± 1.7** | 40.7% ± 2.2 | 42.7% ± 1.0 |
| Hand-written, check half only (see below) | **47.3%** | 39.1% | 41.4% |

The rules were designed by looking at test failures, so the total flatters them. The **check half** is the honest
number: the 629 cases alternate between a design half and a check half within each test group, all rules after
the first two were designed and chosen on the design half only, and the check half was only read at the end. On
it, spaCy with the rules passes 47.3% against Rasa's 41.4%.

The model `train.bat` makes passes **338 (53.7%)**: 113 basic, 99 advanced, 126 extensive. Rasa's full model passes
262 (41.7%). spaCy answers in well under a second (all 629 cases take about a minute) and needs no GPU.

### The rules
They run around the trained model, without retraining it, and apply to spaCy only; Rasa reads its answers as
before. Each has a name: `server.py --rules` picks the server's (default all), `"rules"` in the `spacy` block of
`llmdata.json` picks `SpacyClient`'s (default all). The gains are on the design half, mean over the 10 models,
each rule added on its own to the first two:

| Rule | Where | What it does | Gain | Models better / worse |
|---|---|---|---|---|
| `cancel` | Java | A sentence only counts as "cancel" when it says so (never mind, cancel, forget, don't, ...). The model read story sentences as "never mind", wiping out the request. | +4.8 with `faction` | 10 / 0 |
| `faction` | Java | In a command of up to 8 words with no faction marked, the faction is looked up in the text (`FactionNames`). Lowercase words marked as a faction that aren't one ("a nearby **faction**") are dropped; made-up names like "Klingon" are kept, so they still give `<NONE>`. | (with `cancel`) | |
| `cues` | server | A sentence of more than 8 words read as a request needs a request cue, or it is read as not a request: an imperative ("send defenders", also as the first word), please / I want / I'd like / can you / let me / have the..., "X should...", or no verb at all (a bare noun phrase). Stories, plans and reported speech ("my wingman told me to spawn...") have none. | **+4.3** | 10 / 0 |
| `places` + `plain_word` | both | The server marks places whose every word has a word vector (ordinary English words: "Context", "Someone", "Her"); Java drops those that aren't known Starsector places. | +0.9 | 10 / 0 |
| `split` | Java | "spawn X and send Y" is sent as two pieces, one command each. | +0.8 | 10 / 0 |
| `size` | Java | In a command of up to 8 words with no size marked, the size word is looked up ("a gigantic tri-tachyon fleet" -> 300). | +0.6 | 9 / 0 |
| `roles` | Java | With a single intent, the place's role follows it: a help request defends its place ("backup for Chicomoztoc"), a war fleet has none. | +0.4 | 7 / 2 |
| `negation` | server | A negated command with no alternative ("don't spawn any pirates") is not a request. | +0.3 | 7 / 0 |
| `negated_size` | server | A size word in a negated phrase ("don't make it a big one", "nothing huge") is dropped, so 100 applies. | +0.1 (+0.25 with the rest) | 4 / 1 (8 / 0) |
| `count` | Java | A count above 10 is a misread size: "SPAWN PIRATES 200" is one 200-point fleet, not ten. | +0.06 | 2 / 0 |
| `typos` | server | Before the model reads a sentence, a word with no word vector that is one edit (two for 8+ letters) from exactly one template word becomes that word: spwan, hegmony, tritachion, and Hungarian endings like Askoniába -> Askonia. Typos that happen to be words ("pirat", "flet") have vectors and stay. | +0.06 | 2 / 0 |

All together they add 7.6 points on the design half and **4.5 on the check half** over the first two rules (all
10 models better on both), and 8.2 points on the check half over no rules. `count` and `typos` gain little on this
test set but never made a model worse, and fix real mistakes, so they are kept.

The first version of `cues` (without the first-word imperative, "should" and noun phrases) gained only 1.0 and
was worse on 3 models; those three were added after looking at what it broke on the design half. Tried and left
out: the faction lookup in sentences of any length (it picked up enemies that are only mentioned), and adding
numbers the English number finder sees before "points" (no gain). What `cues` still gets wrong most: a context
sentence that carries a detail ("My fleet is about 160 fleet points.") is dropped with its detail.

To measure a change: `python test_handwritten.py --name mine --rules ... --server-rules ...`, then
`python compare_runs.py final-negsize mine` (design half; `--check` adds the check half, `--flips` lists what
changed, `--failures mine` lists the design-half failures to work on).

Choices behind `config.cfg`, tried side by side on the same data:
- The CNN of spaCy's "accuracy" template (8 layers of 256) needed about 85 minutes to train and was behind at every
  step; 4 layers of 128 train in 10-14 minutes.
- Entities are learned only from request sentences (`nlu_data.to_docbin`). Labelling "no entities" in stories made
  the model skip "a big hegemony fleet" in requests. Over three seeds each, both ways passed about the same number
  of cases in total (263 against 262 on average), but this one did better on basic requests (103 against 100) and
  varied less between seeds.
- Different seeds of the same config passed between 249 and 276 cases, so a difference of a few cases between two
  single models says little; the cross-validation's 10 models give a steadier picture.

## Limits
- English only; the word vectors are English.
- Typos: the model sees words through their vectors, first letter, last three letters and shape. The `typos` rule
  fixes typos that have no vector, but not those that happen to be words ("pirat", "flet").
- It reads each message on its own, with no memory of earlier messages.
- It learns only what the templates show. The word vectors help with new words that mean the same thing, not with
  new kinds of requests or complicated size math.
- Rasa's lookup tables (`rasa/data/lookups.yml`) are not used.
