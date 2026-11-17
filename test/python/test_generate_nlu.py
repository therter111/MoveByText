"""Tests of nlp/rasa/generate_nlu.py, the Rasa/spaCy training data generator. Standard library only; build.py runs
them:  python -m unittest discover -s test/python
"""

import importlib
import os
import re
import sys
import tempfile
import unittest

MOD = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(MOD, "nlp", "rasa"))
sys.path.insert(0, os.path.join(MOD, "nlp", "spacy"))

import generate_nlu as g  # noqa: E402
import nlu_data  # noqa: E402

# The template lists whose {slots} fill() fills (the JOINS_* use {c}/{r} with str.format instead)
TEMPLATE_LISTS = ["SPAWN", "SPAWN_COUNT", "SPAWN_MULTI", "TRADE", "TRADE_COUNT", "TRADE_MULTI", "HELP", "HELP_NO_PLACE",
                  "HELP_MULTI", "CANCEL", "OUT_OF_SCOPE", "STORIES", "CLAUSES"]
# The entity types CommandBuilder (Java) reads; Java's ConsistencyTest checks this list against the code
ENTITY_TYPES = {"faction", "size", "points", "destination", "origin", "target", "multiplier", "offset", "reference", "count"}


def all_templates():
    templates = []
    for name in TEMPLATE_LISTS:
        templates += getattr(g, name)
    for lists in g.MIXED.values():
        templates += lists
    return templates


def generate(out_dir):
    """Runs main() into out_dir with a fresh module (its RNG and flags as on a first run); the files' text."""
    fresh = importlib.reload(g)
    fresh.OUT_DIR = out_dir
    devnull = open(os.devnull, "w")
    stdout, sys.stdout = sys.stdout, devnull
    try:
        fresh.main()
    finally:
        sys.stdout = stdout
        devnull.close()
    files = {}
    for name in ("nlu.yml", "lookups.yml"):
        with open(os.path.join(out_dir, name), encoding="utf-8") as f:
            files[name] = f.read()
    return files


class Templates(unittest.TestCase):

    def test_every_slot_is_one_slot_knows(self):
        unknown = set()
        for template in all_templates():
            for name, _group in g.SLOT.findall(template):
                try:
                    g.slot(name, None)
                except KeyError:
                    unknown.add(name)
        self.assertEqual(set(), unknown, "{slots} that slot() doesn't know")

    def test_every_template_fills_without_slots_left(self):
        for template in all_templates():
            text = g.fill(template)
            self.assertIsNone(g.SLOT.search(text), "a slot left in %r -> %r" % (template, text))


class GeneratedData(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.TemporaryDirectory()
        cls.files = generate(cls.dir.name)
        cls.examples = nlu_data.read(os.path.join(cls.dir.name, "nlu.yml"))

    @classmethod
    def tearDownClass(cls):
        cls.dir.cleanup()

    def test_the_same_seed_gives_the_same_files(self):
        with tempfile.TemporaryDirectory() as again:
            self.assertEqual(self.files, generate(again), "a second run differs")

    def test_every_intent_has_examples(self):
        intents = {e.intent for e in self.examples}
        for intent in ("spawn_fleet", "trade_convoy", "request_help", "out_of_scope", "cancel"):
            self.assertIn(intent, intents)
        self.assertGreater(len(self.examples), 1000)

    def test_every_label_is_well_formed(self):
        for e in self.examples:
            self.assertNotRegex(e.text, r"\]\(|\]\{|\[[^\]]*\]", "label markup left in %r" % e.text)
            for start, end, label in e.spans:
                self.assertTrue(0 <= start < end <= len(e.text), "span %r outside %r" % ((start, end), e.text))
                entity, group = nlu_data.split_label(label)
                self.assertIn(entity, ENTITY_TYPES, "unknown entity type in %r" % e.text)
                self.assertRegex(group, r"^\d?$", "group %r in %r" % (group, e.text))
                self.assertEqual(e.text[start:end], e.text[start:end].strip(), "a span with spaces around it in %r" % e.text)

    def test_no_example_twice_in_an_intent(self):
        seen = set()
        for e in self.examples:
            key = (e.intent, e.text.lower())
            self.assertNotIn(key, seen, "twice: %r" % (key,))
            seen.add(key)

    def test_lookups_list_every_place(self):
        for place in g.PLACES:
            self.assertIn("    - %s\n" % place, self.files["lookups.yml"])


class Helpers(unittest.TestCase):

    def test_number_words(self):
        cases = {0: "zero", 7: "seven", 19: "nineteen", 20: "twenty", 21: "twenty-one", 99: "ninety-nine",
                 100: "one hundred", 105: "one hundred and five", 250: "two hundred and fifty",
                 999: "nine hundred and ninety-nine"}
        for n, words in cases.items():
            self.assertEqual(words, g.number_words(n), n)
        self.assertEqual(1000, len({g.number_words(n) for n in range(1000)}), "every number has its own words")

    def test_typo_leaves_short_words_and_phrases_alone(self):
        for word in ["TT", "path", "tri tachyon", "luddic path", "Kanta's Den"]:
            for _ in range(200):
                self.assertEqual(word, g.typo(word))

    def test_typo_changes_at_most_one_letter(self):
        changed = 0
        for _ in range(500):
            t = g.typo("hegemony")
            changed += t != "hegemony"
            self.assertLessEqual(abs(len(t) - len("hegemony")), 1, t)
            self.assertTrue(re.fullmatch(r"[a-z]+", t), t)
        self.assertGreater(changed, 0, "typo() never made one")

    def test_label(self):
        self.assertEqual("[small](size)", g.label("small", "size"))
        self.assertEqual('[small]{"entity": "size", "group": "2"}', g.label("small", "size", "2"))

    def test_dedupe_keeps_the_first_in_order_ignoring_case(self):
        self.assertEqual(["b", "A", "c"], g.dedupe(["b", "A", "a", "c", "B"]))


if __name__ == "__main__":
    unittest.main()
