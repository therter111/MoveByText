"""Reads the Rasa training data for spaCy, so both models learn from the same examples.

rasa/generate_nlu.py writes rasa/data/nlu.yml with labels in Rasa's markup:
    spawn a [small](size) [pirate](faction) fleet
    a [big]{"entity": "size", "group": "1"} [hegemony]{"entity": "faction", "group": "1"} fleet and ...
This turns each example into plain text, its intent and its labelled character spans. A group goes into the label
after a "#" ("faction#1"), so spaCy's entity recognizer learns which fleet a word belongs to; server.py splits it
back off.
"""

import collections
import json
import os
import random
import re

HERE = os.path.dirname(os.path.abspath(__file__))
RASA = os.path.join(os.path.dirname(HERE), "rasa")
DEFAULT_NLU = os.path.join(RASA, "data", "nlu.yml")
GROUP_MARK = "#"

# [text](entity) or [text]{"entity": "...", "group": "..."}
LABEL = re.compile(r"\[([^\]]+)\](?:\(([^)]*)\)|(\{[^}]*\}))")

Example = collections.namedtuple("Example", "text intent spans")


def parse_example(marked):
    """'spawn a [small](size) fleet' -> ('spawn a small fleet', [(8, 13, 'size')])."""
    out, spans, last = [], [], 0
    length = 0
    for m in LABEL.finditer(marked):
        before = marked[last:m.start()]
        out.append(before)
        length += len(before)
        if m.group(2) is not None:
            label = m.group(2)
        else:
            meta = json.loads(m.group(3))
            label = meta["entity"] + (GROUP_MARK + str(meta["group"]) if meta.get("group") else "")
        value = m.group(1)
        spans.append((length, length + len(value), label))
        out.append(value)
        length += len(value)
        last = m.end()
    out.append(marked[last:])
    return "".join(out), spans


def read(path):
    """Every example of a Rasa nlu.yml as an Example, in file order. Lookup tables and other blocks are skipped."""
    examples, intent = [], None
    for line in open(path, encoding="utf-8").read().split("\n"):
        m = re.match(r"^- (\w+): (.+)$", line)
        if m:
            intent = m.group(2).strip() if m.group(1) == "intent" else None
        elif line.startswith("    - ") and intent:
            text, spans = parse_example(line[len("    - "):])
            examples.append(Example(text, intent, spans))
    return examples


def split_label(label):
    """'faction#1' -> ('faction', '1'); 'size' -> ('size', '')."""
    entity, _, group = label.partition(GROUP_MARK)
    return entity, group


def stratified_split(examples, fraction, seed):
    """(rest, held out): about `fraction` of each intent's examples held out, at least one of each."""
    rng = random.Random(seed)
    by_intent = collections.defaultdict(list)
    for e in examples:
        by_intent[e.intent].append(e)
    rest, held = [], []
    for intent in sorted(by_intent):
        items = by_intent[intent][:]
        rng.shuffle(items)
        n = max(1, int(round(len(items) * fraction))) if len(items) > 1 else 0
        held += items[:n]
        rest += items[n:]
    return rest, held


def aligned_spans(doc, spans):
    """The spaCy spans for (start, end, label) character spans, widened to whole tokens.

    Returns (spans, dropped): a span that can't be placed, or that overlaps an earlier one after widening, is dropped.
    """
    from spacy.util import filter_spans

    placed, dropped = [], 0
    for start, end, label in spans:
        span = doc.char_span(start, end, label=label, alignment_mode="expand")
        if span is None:
            dropped += 1
        else:
            placed.append(span)
    kept = filter_spans(placed)
    return kept, dropped + len(placed) - len(kept)


REQUEST_INTENTS = {"spawn_fleet", "trade_convoy", "request_help"}


def is_request(intent):
    """Like CommandBuilder.isRequest (Java): "spawn_fleet+trade_convoy" is a request too."""
    return any(part in REQUEST_INTENTS for part in intent.split("+"))


def to_docbin(nlp, examples, intents):
    """A DocBin with the intent as doc.cats (1.0 for the intent, 0.0 for the rest) and the spans as doc.ents.

    Only requests get entity labels. In the other sentences (stories, opinions, cancel) the entities are left
    unannotated instead of "none here": the mod never reads them there, and "none here" for "could my fleet beat
    a big hegemony fleet?" taught the entity recognizer to skip "a big hegemony fleet" in requests too.
    """
    from spacy.tokens import DocBin

    db, dropped = DocBin(), 0
    for e in examples:
        doc = nlp.make_doc(e.text)
        if is_request(e.intent):
            ents, lost = aligned_spans(doc, e.spans)
            dropped += lost
            doc.ents = ents
        doc.cats = {i: 1.0 if i == e.intent else 0.0 for i in intents}
        db.add(doc)
    return db, dropped
