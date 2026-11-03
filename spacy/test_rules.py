"""Checks the server rules in rules.py on fixed sentences. No trained model needed, only en_core_web_md.

Run in the spaCy environment, in the spacy folder:  python test_rules.py
"""

import sys

import rules

R = rules.Rules(rules.ALL)
failed = 0


def check(name, condition, detail=""):
    global failed
    print(("PASS   " if condition else "FAIL   ") + name + ("" if condition else "\n       " + detail))
    failed += not condition


def answer(text, intent, *entities):
    """A model answer: entities as (type, words in the text)."""
    ents = []
    for kind, words in entities:
        start = text.index(words)
        ents.append({"entity": kind, "value": words, "start": start, "end": start + len(words)})
    return {"text": text, "intent": {"name": intent, "confidence": 0.99}, "entities": ents}


def intent_after(text, intent="spawn_fleet", *entities):
    return R.after(answer(text, intent, *entities))["intent"]["name"]


# typos
fixed, changes = R.before("spwan a hegmony fleet")
check("typos: words with no vector near a template word are fixed", fixed == "spawn a hegemony fleet", fixed)
check("typos: the corrections are listed", changes == ["spwan -> spawn", "hegmony -> hegemony"], str(changes))
check("typos: capitals are kept", R.before("SPWAN pirates")[0] == "SPAWN pirates", R.before("SPWAN pirates")[0])
check("typos: place names and known words stay", R.before("send a convoy to Jangala")[0] == "send a convoy to Jangala")
check("typos: words with a vector stay, even if rare", R.before("big pirat flet")[0] == "big pirat flet")

# cues
check("cues: reported speech in a long sentence is not a request",
      intent_after("Yesterday my wingman told me to spawn a huge pirate fleet just to see what happens") == "out_of_scope")
check("cues: a long story with no request is not a request",
      intent_after("My friend says the Luddic Path are the hardest enemies in the game, I say the Remnants are")
      == "out_of_scope")
check("cues: an imperative in a long sentence is kept",
      intent_after("The pathers are bombing Fikenhild again right now, send defenders", "request_help") == "request_help")
check("cues: 'I want' in a long sentence is kept",
      intent_after("I want a big pirate fleet near me so that I can test my new loadout") == "spawn_fleet")
check("cues: short sentences need no cue", intent_after("a gigantic tri-tachyon fleet") == "spawn_fleet")

# negation
check("negation: a negated command is not a request", intent_after("don't spawn any pirates") == "out_of_scope")
check("negation: with an alternative it stays", intent_after("don't send pirates, send the hegemony instead") == "spawn_fleet")

# negated_size
a = R.after(answer("spawn a pirate fleet but please don't make it a big one", "spawn_fleet", ("size", "big")))
check("negated_size: 'don't make it a big one' drops the size", not any(e["entity"] == "size" for e in a["entities"]),
      str(a["entities"]))
a = R.after(answer("I don't care, give me a huge pirate fleet", "spawn_fleet", ("size", "huge")))
check("negated_size: a negation elsewhere keeps the size", any(e["entity"] == "size" for e in a["entities"]),
      str(a["entities"]))
a = R.after(answer("a pirate fleet, nothing huge", "spawn_fleet", ("size", "huge")))
check("negated_size: 'nothing huge' drops the size", not any(e["entity"] == "size" for e in a["entities"]))

# cues: the request patterns found on the design half
for text in ("Send a convoy to Askonia, I've got 5000 credits riding on this trade with a guy at the bar",
             "The hegemony should blockade Askonia with a huge fleet, the diktat has been getting way too comfortable",
             "What I actually want is a big hegemony fleet near me right now"):
    check("cues: kept: " + text[:40], intent_after(text) == "spawn_fleet")
check("cues: 'I should ...' is no request cue",
      intent_after("I should probably go to bed soon because it is really late here") == "out_of_scope")

# plain_word
a = R.after(answer("Context: send help to Jangala", "request_help", ("target", "Context"), ("target", "Jangala")))
flags = {e["value"]: e.get("plain_word") for e in a["entities"]}
check("plain_word: an ordinary word is marked, a Starsector name isn't",
      flags == {"Context": True, "Jangala": False}, str(flags))

print()
print("%s" % ("all passed" if not failed else "%d failed" % failed))
sys.exit(1 if failed else 0)
