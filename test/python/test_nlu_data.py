"""Tests of nlp/spacy/nlu_data.py, which turns Rasa's training markup into spaCy's text and spans. The functions
tested here need no spaCy; build.py runs them:  python -m unittest discover -s test/python
"""

import collections
import os
import sys
import tempfile
import unittest

MOD = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(MOD, "nlp", "spacy"))

import nlu_data  # noqa: E402

Example = nlu_data.Example


class ParseExample(unittest.TestCase):

    def test_plain_labels(self):
        self.assertEqual(("spawn a small pirate fleet", [(8, 13, "size"), (14, 20, "faction")]),
                         nlu_data.parse_example("spawn a [small](size) [pirate](faction) fleet"))

    def test_grouped_labels_get_the_group_after_a_mark(self):
        text, spans = nlu_data.parse_example(
            'a [big]{"entity": "size", "group": "1"} and a [tiny]{"entity": "size", "group": "2"} fleet')
        self.assertEqual("a big and a tiny fleet", text)
        self.assertEqual([(2, 5, "size#1"), (12, 16, "size#2")], spans)

    def test_no_labels(self):
        self.assertEqual(("how do I upgrade my ship?", []), nlu_data.parse_example("how do I upgrade my ship?"))

    def test_spans_cover_their_words(self):
        marked = "send [two](count) convoys from [Kanta's Den](origin) to [Port Tse](destination)"
        text, spans = nlu_data.parse_example(marked)
        self.assertEqual(["two", "Kanta's Den", "Port Tse"], [text[s:e] for s, e, _ in spans])


class Labels(unittest.TestCase):

    def test_split_label(self):
        self.assertEqual(("faction", "1"), nlu_data.split_label("faction#1"))
        self.assertEqual(("size", ""), nlu_data.split_label("size"))

    def test_is_request_like_the_java_side(self):
        for intent in ["spawn_fleet", "trade_convoy", "request_help", "spawn_fleet+trade_convoy"]:
            self.assertTrue(nlu_data.is_request(intent), intent)
        for intent in ["out_of_scope", "cancel", "nlu_fallback", ""]:
            self.assertFalse(nlu_data.is_request(intent), intent)


class StratifiedSplit(unittest.TestCase):

    def examples(self):
        out = []
        for intent, n in [("spawn_fleet", 50), ("trade_convoy", 20), ("cancel", 3), ("rare", 1)]:
            out += [Example("%s %d" % (intent, i), intent, []) for i in range(n)]
        return out

    def test_every_example_in_exactly_one_part(self):
        examples = self.examples()
        rest, held = nlu_data.stratified_split(examples, 0.2, seed=1)
        self.assertEqual(sorted(e.text for e in examples), sorted(e.text for e in rest + held))
        self.assertEqual(set(), {e.text for e in rest} & {e.text for e in held})

    def test_about_the_fraction_of_each_intent_is_held_out(self):
        rest, held = nlu_data.stratified_split(self.examples(), 0.2, seed=1)
        counts = collections.Counter(e.intent for e in held)
        self.assertEqual(10, counts["spawn_fleet"])
        self.assertEqual(4, counts["trade_convoy"])
        self.assertEqual(1, counts["cancel"], "at least one of an intent with several examples")
        self.assertEqual(0, counts["rare"], "an intent with one example keeps it for training")

    def test_the_same_seed_gives_the_same_split(self):
        a = nlu_data.stratified_split(self.examples(), 0.2, seed=7)
        b = nlu_data.stratified_split(self.examples(), 0.2, seed=7)
        self.assertEqual(a, b)


class Read(unittest.TestCase):

    def test_read_skips_lookups_and_keeps_order(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "nlu.yml")
            with open(path, "w", encoding="utf-8") as f:
                f.write('version: "3.1"\nnlu:\n- intent: spawn_fleet\n  examples: |\n    - [pirates](faction)\n'
                        "    - a [big](size) fleet\n- lookup: place\n  examples: |\n    - Jangala\n"
                        "- intent: cancel\n  examples: |\n    - never mind\n")
            examples = nlu_data.read(path)
        self.assertEqual([("pirates", "spawn_fleet"), ("a big fleet", "spawn_fleet"), ("never mind", "cancel")],
                         [(e.text, e.intent) for e in examples])


if __name__ == "__main__":
    unittest.main()
