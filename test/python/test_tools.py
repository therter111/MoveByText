"""Tests of the helpers in the evaluation scripts (nlp/rasa/cross_validate.py, nlp/rasa/test_handwritten.py).
Standard library only, but test_handwritten.py looks for the Starsector jars when it is loaded, as build.py does.
build.py runs these:  python -m unittest discover -s test/python
"""

import importlib.util
import os
import tempfile
import unittest

MOD = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def load(name, path):
    """A script by its path: nlp/rasa and nlp/spacy both have a cross_validate.py and a test_handwritten.py."""
    spec = importlib.util.spec_from_file_location(name, os.path.join(MOD, path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


cross_validate = load("rasa_cross_validate", os.path.join("nlp", "rasa", "cross_validate.py"))
test_handwritten = load("rasa_test_handwritten", os.path.join("nlp", "rasa", "test_handwritten.py"))


class CrossValidate(unittest.TestCase):

    def test_examples_read_and_written_back_unchanged(self):
        nlu = ('version: "3.1"\nnlu:\n- intent: spawn_fleet\n  examples: |\n    - [pirates](faction)\n'
               '    - a [big]{"entity": "size", "group": "1"} fleet\n- intent: cancel\n  examples: |\n    - never mind\n')
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "nlu.yml")
            with open(path, "w", encoding="utf-8") as f:
                f.write(nlu)
            pairs = cross_validate.read_examples(path)
            self.assertEqual([("spawn_fleet", "    - [pirates](faction)"),
                              ("spawn_fleet", '    - a [big]{"entity": "size", "group": "1"} fleet'),
                              ("cancel", "    - never mind")], pairs)
            again = os.path.join(d, "again.yml")
            cross_validate.write_examples(again, pairs, ["spawn_fleet", "cancel"])
            self.assertEqual(pairs, cross_validate.read_examples(again))

    def test_write_examples_leaves_out_intents_without_examples_and_keeps_the_order_given(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "nlu.yml")
            cross_validate.write_examples(path, [("cancel", "    - stop"), ("spawn_fleet", "    - go")],
                                          ["spawn_fleet", "trade_convoy", "cancel"])
            with open(path, encoding="utf-8") as f:
                text = f.read()
        self.assertNotIn("trade_convoy", text)
        self.assertLess(text.index("spawn_fleet"), text.index("cancel"))

    def test_rel_is_short_inside_the_rasa_folder_and_absolute_outside(self):
        inside = os.path.join(cross_validate.HERE, "results", "cv-1")
        self.assertEqual(os.path.join("results", "cv-1"), cross_validate.rel(inside))
        outside = os.path.abspath(os.path.join(cross_validate.HERE, "..", "..", "..", "elsewhere"))
        self.assertEqual(outside, cross_validate.rel(outside))


class HandwrittenHelpers(unittest.TestCase):

    def test_pct(self):
        self.assertEqual(50.0, test_handwritten.pct(1, 2))
        self.assertEqual(0.0, test_handwritten.pct(3, 0), "no cases: 0, not a division by zero")

    def test_spread(self):
        self.assertEqual(0.0, test_handwritten.spread([42.0]))
        self.assertAlmostEqual(1.0, test_handwritten.spread([1.0, 2.0, 3.0]))

    def test_parse_console_and_the_summary(self):
        console = "\n".join([
            "Writing the results to C:\\mod\\test\\logs\\run.log", "",
            "== place names ==", 'PASS   "spawn pirates" -> <SPAWN:pirates:100>', 'FAIL   "help Jangala"',
            "       1st:      <NONE>", "== advanced - stories ==", 'FLAKY  "a story"', "",
            "== summary ==", "1/3 passed", 'FAIL   "help Jangala"'])
        self.assertEqual([("basic", "place names", "PASS"), ("basic", "place names", "FAIL"),
                          ("advanced", "advanced - stories", "FLAKY")], test_handwritten.parse_console(console))
        self.assertEqual((1, 3), test_handwritten.summary_counts(console))
        self.assertEqual("C:\\mod\\test\\logs\\run.log", test_handwritten.report_path(console))
        self.assertIsNone(test_handwritten.summary_counts("no summary here"))
        self.assertIsNone(test_handwritten.report_path("nothing written"))


if __name__ == "__main__":
    unittest.main()
